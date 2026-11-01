package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.repository.SettingsRepository
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * An [OkHttpClient] whose application interceptor answers every call from a
 * queue of canned responses, so a provider with a hard-coded host (Claude,
 * Cohere) can be exercised end to end without a socket. The provider's own
 * request building, header handling and body parsing all run unchanged.
 */
internal class CannedTransport {

    private val queue = ArrayDeque<(Request) -> Response>()

    /** Every request the provider issued, in order, for assertions on headers and bodies. */
    val recordedRequests = mutableListOf<Request>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val answer = queue.removeFirstOrNull() ?: error("No canned response enqueued")
            val request = chain.request()
            recordedRequests += request
            // Short-circuit: the request never leaves the process.
            answer(request)
        }
        .build()

    fun enqueue(body: String, code: Int = 200) {
        queue.addLast { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
    }

    /** The next call fails at the transport layer with [failure]. */
    fun enqueueFailure(failure: IOException) {
        queue.addLast { _ -> throw failure }
    }

    companion object {
        fun newSettingsRepository(): SettingsRepository = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    Files.createTempDirectory("opendroid-canned-transport-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = EmptyProviderCredentialStore(),
            runStartupMigration = false
        )
    }

    /** The request under test carries its own credential, so nothing is stored. */
    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }
}
