package com.opendroid.ai.core.llm.chatgpt

import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.di.AppModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A credential store held in memory. [failWrites] simulates unavailable storage. */
internal class InMemoryCredentialStore(
    initial: Map<ProviderCredentialId, String> = emptyMap()
) : ProviderCredentialStore {
    val values = initial.toMutableMap()
    var failWrites = false
    var unreadable = false

    override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
        MutableStateFlow(ProviderCredentialRecoveryState.Ready)

    override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
        if (unreadable) CredentialStoreResult.StorageUnavailable else CredentialStoreResult.Success(values[credential])

    override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
        CredentialStoreResult.Success(
            values.filterKeys { it is ProviderCredentialId.ApiKey }
                .mapKeys { (key, _) -> (key as ProviderCredentialId.ApiKey).providerName }
        )

    override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> {
        if (failWrites) return CredentialStoreResult.StorageUnavailable
        values[credential] = value
        return CredentialStoreResult.Success(Unit)
    }

    override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> {
        if (failWrites) return CredentialStoreResult.StorageUnavailable
        values.remove(credential)
        return CredentialStoreResult.Success(Unit)
    }

    override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> = CredentialStoreResult.Success(Unit)

    override fun resetForReentry(): CredentialStoreResult<Unit> {
        values.clear()
        return CredentialStoreResult.Success(Unit)
    }
}

/** A ChatGPT backend with no sign-in, for tests of other providers that never reach it. */
internal fun signedOutChatGptBackend(): ChatGptBackend {
    val client = AppModule.provideOkHttpClient()
    return ChatGptBackend(client, ChatGptAuthRepository(InMemoryCredentialStore(), ChatGptOAuthClient(client)))
}

/** An unsigned JWT carrying [payloadJson]; enough for claim parsing, never for verification. */
internal fun fakeJwt(payloadJson: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(StandardCharsets.UTF_8))
    val payload = encoder.encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
    return "$header.$payload.sig"
}
