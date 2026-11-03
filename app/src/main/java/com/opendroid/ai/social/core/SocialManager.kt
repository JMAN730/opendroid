package com.opendroid.ai.social.core

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.opendroid.ai.core.security.SocialCredentialStore
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.adapter.*
import com.opendroid.ai.social.core.audit.SocialAuditLogger
import com.opendroid.ai.social.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocialManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socialRepository: SocialRepository,
    private val credentialStore: SocialCredentialStore,
    private val auditLogger: SocialAuditLogger,
    private val memoryRepository: MemoryRepository,
    telegramAdapter: TelegramAdapter,
    discordAdapter: DiscordAdapter,
    xAdapter: XAdapter,
    linkedInAdapter: LinkedInAdapter,
    youTubeAdapter: YouTubeAdapter,
    instagramAdapter: InstagramAdapter,
    facebookAdapter: FacebookAdapter
) {
    companion object {
        private const val TAG = "SocialManager"
        private const val PREFS_NAME = "opendroid_social_settings"
        private const val KEY_AUTOMATION_LEVEL = "automation_level"
        private const val INTERACTION_REPLIED = "REPLIED"
    }

    private val adapters: Map<SocialPlatform, SocialPlatformAdapter> = mapOf(
        SocialPlatform.TELEGRAM to telegramAdapter,
        SocialPlatform.DISCORD to discordAdapter,
        SocialPlatform.X to xAdapter,
        SocialPlatform.LINKEDIN to linkedInAdapter,
        SocialPlatform.YOUTUBE to youTubeAdapter,
        SocialPlatform.INSTAGRAM to instagramAdapter,
        SocialPlatform.FACEBOOK to facebookAdapter
    )

    // Sends running in this process. A PUBLISHING post or SENDING comment that isn't listed here
    // was left behind by process death, and only then can the user settle it.
    private val postsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val repliesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val inboxRepliesInFlight = ConcurrentHashMap.newKeySet<String>()

    // One inbox fetch/save/acknowledge at a time per account, so an acknowledgement can only
    // confirm the batch that the same sync just saved.
    private val inboxSyncLocks = ConcurrentHashMap<String, Mutex>()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _automationLevel = MutableStateFlow(
        runCatching {
            AutomationLevel.valueOf(prefs.getString(KEY_AUTOMATION_LEVEL, AutomationLevel.APPROVAL.name) ?: AutomationLevel.APPROVAL.name)
        }.getOrDefault(AutomationLevel.APPROVAL)
    )
    val automationLevel = _automationLevel.asStateFlow()

    fun getAdapter(platform: SocialPlatform): SocialPlatformAdapter =
        adapters[platform] ?: throw IllegalArgumentException("No adapter registered for ${platform.displayName}")

    fun getAvailablePlatforms(): List<SocialPlatform> = adapters.keys.toList()

    fun setAutomationLevel(level: AutomationLevel) {
        _automationLevel.value = level
        prefs.edit { putString(KEY_AUTOMATION_LEVEL, level.name) }
    }

    // ── ACCOUNTS ─────────────────────────────────────────────────────────────

    suspend fun connectAccount(
        platform: SocialPlatform,
        credentials: SocialCredentials,
        requestedPermissions: List<SocialPermission>
    ): Result<SocialAccount> = withContext(Dispatchers.IO) {
        val adapter = getAdapter(platform)
        try {
            val accountInfoResult = adapter.validateCredentials(credentials)
            if (accountInfoResult.isFailure) {
                val error = accountInfoResult.exceptionOrNull()?.message ?: "Validation failed"
                auditLogger.log(
                    actor = "USER",
                    action = "CONNECT_ACCOUNT",
                    platform = platform,
                    details = "Failed connection attempt: $error",
                    status = "FAILED"
                )
                return@withContext Result.failure(Exception(error))
            }

            val info = accountInfoResult.getOrThrow()
            val accountId = info.id.ifBlank { UUID.randomUUID().toString() }

            // Store credentials securely in Android Keystore
            val boundCredentials = credentials.copy(accountId = accountId, platform = platform)
            val savedSecurely = credentialStore.saveCredentials(boundCredentials)
            if (!savedSecurely) {
                return@withContext Result.failure(IllegalStateException("Failed to securely store credentials in Keystore"))
            }

            val account = SocialAccount(
                id = accountId,
                platform = platform,
                username = info.username,
                displayName = info.displayName,
                avatarUrl = info.avatarUrl,
                status = AccountStatus.CONNECTED,
                permissions = requestedPermissions.ifEmpty { adapter.supportedPermissions },
                connectedAt = System.currentTimeMillis(),
                tokenExpiresAt = credentials.tokenExpiresAt,
                lastSyncAt = System.currentTimeMillis()
            )

            socialRepository.saveAccount(account)

            auditLogger.log(
                actor = "USER",
                action = "CONNECTED_ACCOUNT",
                platform = platform,
                targetId = accountId,
                details = "Connected account @${info.username} with ${account.permissions.size} permissions",
                status = "SUCCESS"
            )

            // Initial sync
            syncAccount(accountId)

            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting account for $platform", e)
            Result.failure(e)
        }
    }

    suspend fun disconnectAccount(accountId: String, platform: SocialPlatform) = withContext(Dispatchers.IO) {
        credentialStore.removeCredentials(accountId, platform)
        socialRepository.updateAccountStatus(accountId, AccountStatus.DISCONNECTED)
        auditLogger.log(
            actor = "USER",
            action = "DISCONNECTED_ACCOUNT",
            platform = platform,
            targetId = accountId,
            details = "Disconnected account",
            status = "SUCCESS"
        )
    }

    suspend fun revokeAccess(accountId: String, platform: SocialPlatform) = withContext(Dispatchers.IO) {
        credentialStore.removeCredentials(accountId, platform)
        socialRepository.deleteAccount(accountId)
        auditLogger.log(
            actor = "USER",
            action = "REVOKED_ACCOUNT",
            platform = platform,
            targetId = accountId,
            details = "Revoked account access and purged encrypted tokens",
            status = "SUCCESS"
        )
    }

    // ── POSTING PIPELINE ─────────────────────────────────────────────────────

    suspend fun createDraft(
        accountId: String,
        platform: SocialPlatform,
        content: String,
        mediaUrls: List<String> = emptyList(),
        contentType: ContentType = ContentType.ANNOUNCEMENT,
        campaignId: String? = null,
        scheduledPublishTime: Long? = null
    ): SocialPost = withContext(Dispatchers.IO) {
        val requiresApproval = _automationLevel.value != AutomationLevel.AUTONOMOUS
        val status = if (scheduledPublishTime != null) {
            if (requiresApproval) PostStatus.DRAFT else PostStatus.SCHEDULED
        } else {
            PostStatus.DRAFT
        }

        val post = SocialPost(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            platform = platform,
            content = content,
            mediaUrls = mediaUrls,
            status = status,
            scheduledPublishTime = scheduledPublishTime,
            campaignId = campaignId,
            contentType = contentType,
            requiresApproval = requiresApproval,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        socialRepository.savePost(post)
        auditLogger.log(
            actor = "OPENDROID_AI",
            action = "DRAFTED_POST",
            platform = platform,
            targetId = post.id,
            details = "Created draft for @$platform (${contentType.name})",
            status = "SUCCESS"
        )

        post
    }

    suspend fun approvePost(postId: String, publishImmediately: Boolean = false): Result<SocialPost> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        if (post.status.isSentOrSending()) {
            return@withContext Result.failure(IllegalStateException("Post $postId is ${post.status} and can't be approved"))
        }

        val approvedPost = post.copy(
            status = if (post.scheduledPublishTime != null) PostStatus.SCHEDULED else PostStatus.DRAFT,
            requiresApproval = false,
            approvedBy = "USER",
            approvedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        socialRepository.savePost(approvedPost)
        auditLogger.log(
            actor = "USER",
            action = "APPROVED_POST",
            platform = post.platform,
            targetId = postId,
            details = "Approved post for publication (Immediate: $publishImmediately)",
            status = "SUCCESS"
        )

        if (publishImmediately) {
            val pubResult = publishPost(postId, "USER")
            if (pubResult.isFailure) {
                return@withContext Result.failure(pubResult.exceptionOrNull() ?: Exception("Publication failed"))
            }
            return@withContext Result.success(socialRepository.getPostById(postId) ?: approvedPost)
        }

        Result.success(approvedPost)
    }

    suspend fun schedulePost(postId: String, scheduledTime: Long): Result<SocialPost> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        if (post.status.isSentOrSending()) {
            return@withContext Result.failure(IllegalStateException("Post $postId is ${post.status} and can't be scheduled"))
        }

        val nextStatus = if (post.requiresApproval) PostStatus.DRAFT else PostStatus.SCHEDULED
        val updated = post.copy(
            scheduledPublishTime = scheduledTime,
            status = nextStatus,
            updatedAt = System.currentTimeMillis()
        )
        socialRepository.savePost(updated)

        auditLogger.log(
            actor = "USER",
            action = "SCHEDULED_POST",
            platform = post.platform,
            targetId = postId,
            details = "Scheduled post for epoch $scheduledTime",
            status = "SUCCESS"
        )

        Result.success(updated)
    }

    /** [actor] is whoever started this send: the user tapping Publish, or the scheduler. */
    suspend fun publishPost(postId: String, actor: String): Result<PublishPostResult> = withContext(Dispatchers.IO) {
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        val account = socialRepository.getAccountById(post.accountId)
            ?: socialRepository.getAccountByPlatform(post.platform)
            ?: return@withContext Result.failure(IllegalStateException("No connected account for ${post.platform.displayName}"))

        if (account.status != AccountStatus.CONNECTED) {
            return@withContext Result.failure(IllegalStateException("Account for ${post.platform.displayName} is ${account.status}"))
        }
        account.missingPermission(SocialPermission.PUBLISH_POSTS)?.let { return@withContext Result.failure(it) }

        val credentials = credentialStore.getCredentials(account.id, post.platform)
            ?: return@withContext Result.failure(IllegalStateException("No stored credentials found for ${post.platform.displayName}"))

        val adapter = getAdapter(post.platform)
        ensureActive()

        // The HTTP call can't be recalled once it starts, so from the claim onwards the caller
        // leaving must not stop the outcome being saved.
        withContext(NonCancellable) {
            sendPost(post, credentials, adapter, actor)
        }
    }

    private suspend fun sendPost(
        post: SocialPost,
        credentials: SocialCredentials,
        adapter: SocialPlatformAdapter,
        actor: String
    ): Result<PublishPostResult> {
        val postId = post.id
        if (!postsInFlight.add(postId)) {
            return Result.failure(IllegalStateException("Post $postId is already being published"))
        }
        try {
            if (!socialRepository.claimPostForPublishing(postId)) {
                return Result.failure(IllegalStateException("Post $postId can't be published from its current status"))
            }

            val result = try {
                adapter.publishPost(credentials, post)
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (result.isSuccess) {
                val publishResult = result.getOrThrow()
                socialRepository.updatePublishResult(
                    id = postId,
                    status = PostStatus.PUBLISHED,
                    publishedTime = System.currentTimeMillis(),
                    platformPostId = publishResult.platformPostId,
                    errorMessage = null
                )
                auditLogger.log(
                    actor = actor,
                    action = "PUBLISHED_POST",
                    platform = post.platform,
                    targetId = postId,
                    details = "Published post: ${publishResult.platformPostId ?: "OK"}",
                    status = "SUCCESS"
                )
                return Result.success(publishResult)
            }

            val error = result.exceptionOrNull()?.message ?: "Publication failed"
            socialRepository.updatePublishResult(
                id = postId,
                status = PostStatus.FAILED,
                publishedTime = null,
                platformPostId = null,
                errorMessage = error
            )
            auditLogger.log(
                actor = "SYSTEM",
                action = "PUBLISH_FAILED",
                platform = post.platform,
                targetId = postId,
                details = "Publication failed: $error",
                status = "FAILED"
            )
            return Result.failure(Exception(error))
        } finally {
            postsInFlight.remove(postId)
        }
    }

    /**
     * Settles a post left in PUBLISHING by process death, after the user has checked the platform.
     * Saying it isn't live moves it to FAILED, where Retry is the user's own decision.
     */
    suspend fun resolveInterruptedPublish(postId: String, wasPublished: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (postId in postsInFlight) {
            return@withContext Result.failure(IllegalStateException("This post is still being sent"))
        }
        val post = socialRepository.getPostById(postId)
            ?: return@withContext Result.failure(IllegalArgumentException("Post $postId not found"))

        val resolved = if (wasPublished) {
            socialRepository.resolveInterruptedPublish(postId, PostStatus.PUBLISHED, System.currentTimeMillis(), null)
        } else {
            socialRepository.resolveInterruptedPublish(postId, PostStatus.FAILED, null, "Publishing was interrupted and the post isn't live")
        }
        if (!resolved) {
            return@withContext Result.failure(IllegalStateException("Post $postId is not waiting to be checked"))
        }

        auditLogger.log(
            actor = "USER",
            action = "RESOLVED_INTERRUPTED_PUBLISH",
            platform = post.platform,
            targetId = postId,
            details = "User checked an interrupted publish (live: $wasPublished)",
            status = "SUCCESS"
        )
        Result.success(Unit)
    }

    private fun PostStatus.isSentOrSending(): Boolean =
        this == PostStatus.PUBLISHING || this == PostStatus.PUBLISHED

    // The user can untick send permissions when connecting; they hold for every send path.
    private fun SocialAccount.missingPermission(permission: SocialPermission): Exception? =
        if (permission in permissions) {
            null
        } else {
            IllegalStateException("@$username doesn't have the ${permission.title} permission. Reconnect the account to turn it on.")
        }

    // ── SYNCING & COMMENTS ───────────────────────────────────────────────────

    suspend fun syncAccount(accountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val account = socialRepository.getAccountById(accountId)
            ?: return@withContext Result.failure(IllegalArgumentException("Account not found"))

        val credentials = credentialStore.getCredentials(account.id, account.platform)
            ?: return@withContext Result.failure(IllegalStateException("Missing credentials for account $accountId"))

        val adapter = getAdapter(account.platform)

        // 1. Sync Inbox
        inboxSyncLocks.getOrPut(account.id) { Mutex() }.withLock {
            val inboxResult = adapter.fetchInbox(credentials, account.lastSyncAt)
            if (inboxResult.isSuccess) {
                val interactions = inboxResult.getOrThrow()
                if (interactions.isNotEmpty()) {
                    socialRepository.saveInteractions(interactions.map { it.copy(accountId = account.id) })
                }
                adapter.acknowledgeInbox(credentials)
            }
        }

        // 2. Sync Comments
        val commentsResult = adapter.fetchComments(credentials, null)
        if (commentsResult.isSuccess) {
            val comments = commentsResult.getOrThrow()
            if (comments.isNotEmpty()) {
                // Replies must go out through the account that received the comment.
                socialRepository.saveComments(comments.map { it.copy(accountId = account.id) })
            }
        }

        // 3. Sync Analytics Snapshot
        val analyticsResult = adapter.fetchAnalytics(credentials)
        if (analyticsResult.isSuccess) {
            val analytics = analyticsResult.getOrThrow()
            socialRepository.recordAnalyticsSnapshot(analytics)
        }

        socialRepository.updateLastSync(accountId, System.currentTimeMillis())
        Result.success(Unit)
    }

    suspend fun syncAllAccounts(): Result<Unit> = withContext(Dispatchers.IO) {
        val failed = socialRepository.getConnectedAccounts().filter { account ->
            val result = try {
                syncAccount(account.id)
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.exceptionOrNull()?.let { Log.w(TAG, "Sync failed for ${account.platform.displayName}", it) }
            result.isFailure
        }
        if (failed.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Couldn't sync ${failed.joinToString { "@${it.username}" }}"))
        }
    }

    suspend fun replyToComment(
        commentId: String,
        replyText: String,
        actor: String = "USER"
    ): Result<ReplyCommentResult> = withContext(Dispatchers.IO) {
        val comment = socialRepository.getAllCommentsFlow() // retrieve comment
        // Find comment
        val unanswered = socialRepository.getUnansweredComments()
        val target = unanswered.find { it.id == commentId || it.platformCommentId == commentId }
            ?: return@withContext Result.failure(IllegalArgumentException("Comment not found"))

        // A comment synced before accounts were stored on comments has no owner, so it falls back
        // to the newest connected account. One with an owner never moves to another account.
        val account = if (target.accountId != null) {
            socialRepository.getAccountById(target.accountId)
                ?.takeIf { it.status == AccountStatus.CONNECTED }
                ?: return@withContext Result.failure(IllegalStateException("The ${target.platform.displayName} account this comment came from is no longer connected"))
        } else {
            socialRepository.getAccountByPlatform(target.platform)
                ?: return@withContext Result.failure(IllegalStateException("No account connected for ${target.platform}"))
        }
        account.missingPermission(SocialPermission.REPLY_TO_COMMENTS)?.let { return@withContext Result.failure(it) }

        val credentials = credentialStore.getCredentials(account.id, target.platform)
            ?: return@withContext Result.failure(IllegalStateException("No credentials for ${target.platform}"))

        val adapter = getAdapter(target.platform)
        ensureActive()

        // Same as publishing: once the send starts, its outcome must be saved.
        withContext(NonCancellable) {
            sendReply(target, credentials, adapter, replyText, actor)
        }
    }

    /**
     * Replies to an Inbox item (a DM or mention). These live apart from comments, so they have
     * their own lookup, and go out through the account that received them.
     */
    suspend fun replyToInteraction(
        interactionId: String,
        replyText: String,
        actor: String = "USER"
    ): Result<ReplyCommentResult> = withContext(Dispatchers.IO) {
        val target = socialRepository.getInteractionById(interactionId)
            ?: return@withContext Result.failure(IllegalArgumentException("Message not found"))
        if (target.status == INTERACTION_REPLIED) {
            return@withContext Result.failure(IllegalStateException("This message has already been replied to"))
        }

        val account = socialRepository.getAccountById(target.accountId)
            ?.takeIf { it.status == AccountStatus.CONNECTED }
            ?: return@withContext Result.failure(IllegalStateException("The ${target.platform.displayName} account this message came to is no longer connected"))
        account.missingPermission(SocialPermission.REPLY_TO_COMMENTS)?.let { return@withContext Result.failure(it) }

        val credentials = credentialStore.getCredentials(account.id, target.platform)
            ?: return@withContext Result.failure(IllegalStateException("No credentials for ${target.platform}"))

        val adapter = getAdapter(target.platform)
        ensureActive()

        withContext(NonCancellable) {
            sendInboxReply(target, credentials, adapter, replyText, actor)
        }
    }

    private suspend fun sendInboxReply(
        target: SocialInteraction,
        credentials: SocialCredentials,
        adapter: SocialPlatformAdapter,
        replyText: String,
        actor: String
    ): Result<ReplyCommentResult> {
        if (!inboxRepliesInFlight.add(target.id)) {
            return Result.failure(IllegalStateException("A reply to this message is already being sent"))
        }
        try {
            val result = try {
                adapter.replyToInteraction(credentials, target, replyText)
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (result.isSuccess) {
                socialRepository.updateInteractionStatus(target.id, INTERACTION_REPLIED)
                auditLogger.log(
                    actor = actor,
                    action = "SENT_INBOX_REPLY",
                    platform = target.platform,
                    targetId = target.id,
                    details = "Replied: \"${replyText.take(50)}...\"",
                    status = "SUCCESS"
                )
                return result
            }

            val error = result.exceptionOrNull()?.message ?: "Failed to send reply"
            auditLogger.log(
                actor = actor,
                action = "INBOX_REPLY_FAILED",
                platform = target.platform,
                targetId = target.id,
                details = "Reply failed: $error",
                status = "FAILED"
            )
            return Result.failure(Exception(error))
        } finally {
            inboxRepliesInFlight.remove(target.id)
        }
    }

    private suspend fun sendReply(
        target: SocialComment,
        credentials: SocialCredentials,
        adapter: SocialPlatformAdapter,
        replyText: String,
        actor: String
    ): Result<ReplyCommentResult> {
        if (!repliesInFlight.add(target.id)) {
            return Result.failure(IllegalStateException("A reply to this comment is already being sent"))
        }
        try {
            if (!socialRepository.claimCommentForReplying(target.id, replyText)) {
                return Result.failure(IllegalStateException("This comment can't be replied to from its current status"))
            }

            val result = try {
                adapter.replyToComment(credentials, target.platformCommentId, replyText)
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (result.isSuccess) {
                socialRepository.markCommentReplied(target.id, replyText)
                auditLogger.log(
                    actor = actor,
                    action = "SENT_REPLY",
                    platform = target.platform,
                    targetId = target.id,
                    details = "Replied: \"${replyText.take(50)}...\"",
                    status = "SUCCESS"
                )
                return result
            }

            val error = result.exceptionOrNull()?.message ?: "Failed to send reply"
            socialRepository.releaseReplyClaim(target.id)
            auditLogger.log(
                actor = actor,
                action = "REPLY_FAILED",
                platform = target.platform,
                targetId = target.id,
                details = "Reply failed: $error",
                status = "FAILED"
            )
            return Result.failure(Exception(error))
        } finally {
            repliesInFlight.remove(target.id)
        }
    }

    /**
     * Settles a comment left in SENDING by process death, after the user has checked the platform.
     * Saying the reply didn't go out makes the comment answerable again.
     */
    suspend fun resolveInterruptedReply(commentId: String, wasSent: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (commentId in repliesInFlight) {
            return@withContext Result.failure(IllegalStateException("This reply is still being sent"))
        }
        val comment = socialRepository.getCommentById(commentId)
            ?: return@withContext Result.failure(IllegalArgumentException("Comment not found"))

        val resolved = if (wasSent) {
            socialRepository.confirmInterruptedReply(commentId)
        } else {
            socialRepository.releaseReplyClaim(commentId)
        }
        if (!resolved) {
            return@withContext Result.failure(IllegalStateException("This comment is not waiting to be checked"))
        }

        auditLogger.log(
            actor = "USER",
            action = "RESOLVED_INTERRUPTED_REPLY",
            platform = comment.platform,
            targetId = commentId,
            details = "User checked an interrupted reply (sent: $wasSent)",
            status = "SUCCESS"
        )
        Result.success(Unit)
    }
}
