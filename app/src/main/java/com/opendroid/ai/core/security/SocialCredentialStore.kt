package com.opendroid.ai.core.security

import android.content.Context
import android.util.Log
import com.opendroid.ai.social.domain.model.SocialCredentials
import com.opendroid.ai.social.domain.model.SocialPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class StoredSocialCredentialPayload(
    val accountId: String,
    val platform: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val botToken: String? = null,
    val webhookUrl: String? = null,
    val channelOrTargetId: String? = null
)

interface SocialCredentialStore {
    fun getCredentials(accountId: String, platform: SocialPlatform): SocialCredentials?
    fun saveCredentials(credentials: SocialCredentials): Boolean
    fun removeCredentials(accountId: String, platform: SocialPlatform): Boolean
    fun hasCredentials(accountId: String, platform: SocialPlatform): Boolean
    fun clearAll(): Boolean
}

@Singleton
class AndroidSocialCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) : SocialCredentialStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val records: KeystoreSecretRecords

    init {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        records = KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(prefs),
            cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS)
        )
    }

    override fun getCredentials(accountId: String, platform: SocialPlatform): SocialCredentials? {
        val storageKey = makeStorageKey(accountId, platform)
        val aad = makeAad(accountId, platform)
        return when (val result = records.read(storageKey, aad)) {
            is SecretRecordResult.Success -> {
                val bytes = result.value ?: return null
                try {
                    val decodedStr = String(bytes, StandardCharsets.UTF_8)
                    val payload = json.decodeFromString<StoredSocialCredentialPayload>(decodedStr)
                    SocialCredentials(
                        accountId = payload.accountId,
                        platform = SocialPlatform.fromId(payload.platform),
                        accessToken = payload.accessToken,
                        refreshToken = payload.refreshToken,
                        tokenExpiresAt = payload.tokenExpiresAt,
                        apiKey = payload.apiKey,
                        apiSecret = payload.apiSecret,
                        botToken = payload.botToken,
                        webhookUrl = payload.webhookUrl,
                        channelOrTargetId = payload.channelOrTargetId
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse decrypted credentials for $accountId", e)
                    null
                }
            }
            is SecretRecordResult.Unrecoverable -> {
                Log.w(TAG, "Unrecoverable credential state for $accountId")
                null
            }
            is SecretRecordResult.StorageUnavailable -> {
                Log.e(TAG, "Storage unavailable for $accountId")
                null
            }
        }
    }

    override fun saveCredentials(credentials: SocialCredentials): Boolean {
        val storageKey = makeStorageKey(credentials.accountId, credentials.platform)
        val aad = makeAad(credentials.accountId, credentials.platform)
        val payload = StoredSocialCredentialPayload(
            accountId = credentials.accountId,
            platform = credentials.platform.id,
            accessToken = credentials.accessToken,
            refreshToken = credentials.refreshToken,
            tokenExpiresAt = credentials.tokenExpiresAt,
            apiKey = credentials.apiKey,
            apiSecret = credentials.apiSecret,
            botToken = credentials.botToken,
            webhookUrl = credentials.webhookUrl,
            channelOrTargetId = credentials.channelOrTargetId
        )
        val jsonBytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        return when (records.write(storageKey, aad, jsonBytes)) {
            is SecretRecordResult.Success -> true
            else -> {
                Log.e(TAG, "Failed to write encrypted credentials for ${credentials.accountId}")
                false
            }
        }
    }

    override fun removeCredentials(accountId: String, platform: SocialPlatform): Boolean {
        val storageKey = makeStorageKey(accountId, platform)
        return when (records.removeRecord(storageKey)) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    override fun hasCredentials(accountId: String, platform: SocialPlatform): Boolean {
        return getCredentials(accountId, platform) != null
    }

    override fun clearAll(): Boolean {
        return try {
            records.resetKeyMaterial()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset social credentials", e)
            false
        }
    }

    private fun makeStorageKey(accountId: String, platform: SocialPlatform): String =
        "cred_${platform.id}_${accountId}"

    private fun makeAad(accountId: String, platform: SocialPlatform): String =
        "social_cred_aad:${platform.id}:$accountId"

    companion object {
        private const val TAG = "SocialCredentialStore"
        const val PREFERENCES_NAME = "opendroid_social_credentials"
        const val KEY_ALIAS = "opendroid.social_credentials.aes_gcm.v1"
    }
}
