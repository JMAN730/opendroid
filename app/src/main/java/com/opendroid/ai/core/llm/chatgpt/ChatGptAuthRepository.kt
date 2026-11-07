package com.opendroid.ai.core.llm.chatgpt

import android.util.Log
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What Settings shows about the ChatGPT sign-in. Never carries a token. */
sealed interface ChatGptAccountStatus {
    data object SignedOut : ChatGptAccountStatus
    data class SignedIn(val planType: String?) : ChatGptAccountStatus
    /** The encrypted credential store cannot be read, so the sign-in state is unknown. */
    data object Unavailable : ChatGptAccountStatus
}

/** How a device login ended. Messages are written by this app, never copied from a response. */
sealed interface DeviceLoginOutcome {
    data object SignedIn : DeviceLoginOutcome
    data class Failed(val message: String) : DeviceLoginOutcome
}

/**
 * Owns the ChatGPT sign-in: completing a device login, keeping the session in the encrypted
 * credential store, and handing requests a fresh access token.
 *
 * Refresh tokens rotate, so two refreshes racing with the same token can invalidate the session.
 * Every refresh, sign-in, and sign-out therefore runs under one lock, and a refresh re-reads the
 * stored session first in case another request already refreshed it.
 */
@Singleton
class ChatGptAuthRepository internal constructor(
    private val store: ProviderCredentialStore,
    private val oauth: ChatGptOAuthClient,
    private val nowMillis: () -> Long,
    private val delayMillis: suspend (Long) -> Unit
) {
    @Inject
    constructor(store: ProviderCredentialStore, oauth: ChatGptOAuthClient) :
        this(store, oauth, System::currentTimeMillis, { delay(it) })

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionLock = Mutex()

    private val mutableStatus = MutableStateFlow<ChatGptAccountStatus>(ChatGptAccountStatus.SignedOut)
    val status: StateFlow<ChatGptAccountStatus> = mutableStatus.asStateFlow()

    /** Re-reads the stored sign-in, e.g. after the credential store was reset. */
    suspend fun reloadStatus(): ChatGptAccountStatus = withContext(Dispatchers.IO) {
        publish(readStored())
    }

    suspend fun startDeviceLogin(): DeviceLoginStart = oauth.startDeviceLogin()

    /**
     * Polls [start] at its interval until the user approves it, it fails, or it expires, and
     * saves the session on approval. Cancel the calling coroutine to abandon the login.
     */
    suspend fun completeDeviceLogin(start: DeviceLoginStart): DeviceLoginOutcome {
        while (true) {
            if (nowMillis() >= start.expiresAtMillis) return EXPIRED
            delayMillis(start.intervalMillis)
            when (val result = oauth.pollDeviceLogin(start)) {
                DevicePollResult.Pending -> Unit
                is DevicePollResult.Failed -> return DeviceLoginOutcome.Failed(result.message)
                is DevicePollResult.Approved -> {
                    val saved = sessionLock.withLock { write(result.session) }
                    reloadStatus()
                    return if (saved) {
                        DeviceLoginOutcome.SignedIn
                    } else {
                        DeviceLoginOutcome.Failed("Signed in, but the session could not be saved on this device. Try again.")
                    }
                }
            }
        }
    }

    /** Forgets the sign-in on this device. Returns false if the store could not be updated. */
    suspend fun signOut(): Boolean {
        val removed = sessionLock.withLock {
            withContext(Dispatchers.IO) {
                store.remove(ProviderCredentialId.ChatGptSession) is CredentialStoreResult.Success
            }
        }
        reloadStatus()
        return removed
    }

    /**
     * The stored session, refreshed first if it expires within a minute.
     *
     * @throws LLMException [LLMError.AuthMissing] when not signed in, or a typed failure when the
     *   refresh is rejected.
     */
    suspend fun validSession(): ChatGptSession {
        val session = readSessionOrThrow()
        return if (session.expiresWithin(ChatGptProtocol.REFRESH_MARGIN_MILLIS, nowMillis())) {
            refresh(rejected = session)
        } else {
            session
        }
    }

    /**
     * Refreshes after the backend rejected [rejected]'s access token. If another request
     * already replaced it, that newer session is returned without a second refresh.
     */
    suspend fun refresh(rejected: ChatGptSession): ChatGptSession = sessionLock.withLock {
        val stored = readSessionOrThrow()
        val alreadyRefreshed = stored.access != rejected.access &&
            !stored.expiresWithin(ChatGptProtocol.REFRESH_MARGIN_MILLIS, nowMillis())
        if (alreadyRefreshed) return@withLock stored

        val fresh = try {
            oauth.refresh(stored)
        } catch (e: ChatGptAuthException) {
            throw e.toLLMException()
        }
        if (!write(fresh)) {
            // The new tokens still serve this request; the next one may need a reconnect.
            Log.w(TAG, "Could not persist the refreshed ChatGPT session.")
        }
        publish(StoredSession.Present(fresh))
        fresh
    }

    private suspend fun readSessionOrThrow(): ChatGptSession {
        val read = withContext(Dispatchers.IO) { readStored() }
        if (read is StoredSession.Present) return read.session
        publish(read)
        throw LLMErrorMapper.authMissing(ProviderCatalog.CHATGPT, "")
    }

    private suspend fun write(session: ChatGptSession): Boolean = withContext(Dispatchers.IO) {
        store.write(
            ProviderCredentialId.ChatGptSession,
            json.encodeToString(ChatGptSession.serializer(), session)
        ) is CredentialStoreResult.Success
    }

    private fun readStored(): StoredSession =
        when (val result = store.read(ProviderCredentialId.ChatGptSession)) {
            is CredentialStoreResult.Success -> {
                val raw = result.value
                if (raw.isNullOrBlank()) {
                    StoredSession.Absent
                } else {
                    // A record this build cannot decode is treated as signed out; a new sign-in
                    // overwrites it.
                    runCatching { json.decodeFromString(ChatGptSession.serializer(), raw) }
                        .getOrNull()
                        ?.takeIf { it.access.isNotBlank() && it.refresh.isNotBlank() }
                        ?.let(StoredSession::Present)
                        ?: StoredSession.Absent
                }
            }
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> StoredSession.Unreadable
        }

    private fun publish(read: StoredSession): ChatGptAccountStatus {
        val status = when (read) {
            is StoredSession.Present -> ChatGptAccountStatus.SignedIn(read.session.planType)
            StoredSession.Absent -> ChatGptAccountStatus.SignedOut
            StoredSession.Unreadable -> ChatGptAccountStatus.Unavailable
        }
        mutableStatus.value = status
        return status
    }

    private sealed interface StoredSession {
        data class Present(val session: ChatGptSession) : StoredSession
        data object Absent : StoredSession
        data object Unreadable : StoredSession
    }

    private companion object {
        const val TAG = "ChatGptAuth"
        val EXPIRED = DeviceLoginOutcome.Failed("This login request expired. Start again.")

        /** A rejected refresh is a dead session; other failures may pass. */
        fun ChatGptAuthException.toLLMException(): Exception {
            val status = httpStatus ?: return this
            val error = when {
                status == 400 || status == 401 || status == 403 -> LLMError.AuthInvalid
                status == 429 -> LLMError.RateLimited
                status >= 500 -> LLMError.ServerError
                else -> LLMError.Unknown
            }
            return LLMException(
                error = error,
                provider = ProviderCatalog.CHATGPT,
                model = "",
                status = status,
                retryable = error == LLMError.RateLimited || error == LLMError.ServerError
            )
        }
    }
}
