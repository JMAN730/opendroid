package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #160: asking to stop auto-replying has to stop it, or say that it didn't. */
class AutoReplyToggleActionTest {

    private val context: Context = ContextWrapper(null)

    private val allOn = AutoReplyConfig(
        globalEnabled = true,
        whatsappEnabled = true,
        telegramEnabled = true,
        discordEnabled = true,
        smsEnabled = true,
        emailEnabled = true
    )

    @Test
    fun `off with no app turns off the global switch only`() = runBlocking {
        val repository = repositoryWith(allOn)

        val result = dispatch(repository, mapOf("state" to "off"))

        assertTrue(result.success)
        assertEquals(allOn.copy(globalEnabled = false), repository.autoReplyConfig.first())
    }

    @Test
    fun `every name for all apps turns off the global switch only`() = runBlocking {
        listOf("all", "everything", "global", "", "   ", " ALL ").forEach { app ->
            val repository = repositoryWith(allOn)

            val result = dispatch(repository, mapOf("state" to "off", "app" to app))

            assertTrue("app='$app'", result.success)
            assertEquals("app='$app'", allOn.copy(globalEnabled = false), repository.autoReplyConfig.first())
        }
    }

    @Test
    fun `off for one app turns off that app only`() = runBlocking {
        val expected = mapOf(
            "whatsapp" to allOn.copy(whatsappEnabled = false),
            "telegram" to allOn.copy(telegramEnabled = false),
            "discord" to allOn.copy(discordEnabled = false),
            "sms" to allOn.copy(smsEnabled = false),
            "email" to allOn.copy(emailEnabled = false)
        )
        expected.forEach { (app, config) ->
            val repository = repositoryWith(allOn)

            val result = dispatch(repository, mapOf("state" to "off", "app" to app))

            assertTrue("app='$app'", result.success)
            assertEquals("app='$app'", config, repository.autoReplyConfig.first())
        }
    }

    @Test
    fun `app names are matched after trimming and ignoring case`() = runBlocking {
        val repository = repositoryWith(allOn)

        dispatch(repository, mapOf("state" to "off", "app" to " Telegram "))

        assertEquals(allOn.copy(telegramEnabled = false), repository.autoReplyConfig.first())
    }

    @Test
    fun `on for one app turns on that app only`() = runBlocking {
        val repository = repositoryWith(AutoReplyConfig(globalEnabled = true))

        dispatch(repository, mapOf("state" to "on", "app" to "discord"))

        assertEquals(
            AutoReplyConfig(globalEnabled = true, discordEnabled = true),
            repository.autoReplyConfig.first()
        )
    }

    @Test
    fun `an app auto-reply does not cover fails and changes nothing`() = runBlocking {
        val repository = repositoryWith(allOn)

        val result = dispatch(repository, mapOf("state" to "off", "app" to "signal"))

        assertFalse(result.success)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("signal"))
        assertEquals(allOn, repository.autoReplyConfig.first())
    }

    @Test
    fun `status names the app that changed and lists the apps still enabled`() = runBlocking {
        val repository = repositoryWith(allOn)

        val result = dispatch(repository, mapOf("state" to "off", "app" to "telegram"))

        assertEquals(
            "Auto-reply for Telegram is now OFF. " +
                "Enabled for WhatsApp, Discord, SMS, Email. Reply delay: 15 minutes.",
            result.data
        )
    }

    @Test
    fun `status lists all five apps when the global switch comes on`() = runBlocking {
        val repository = repositoryWith(allOn.copy(globalEnabled = false))

        val result = dispatch(repository, mapOf("state" to "on"))

        assertEquals(
            "Auto-reply is now ON. Enabled for WhatsApp, Telegram, Discord, SMS, Email. Reply delay: 15 minutes.",
            result.data
        )
    }

    @Test
    fun `status says so when the global switch is on but no app is`() = runBlocking {
        val repository = repositoryWith(AutoReplyConfig())

        val result = dispatch(repository, mapOf("state" to "on"))

        assertEquals("Auto-reply is now ON. No apps are enabled, so no replies will go out.", result.data)
    }

    @Test
    fun `status says nothing goes out when an app comes on under a global off`() = runBlocking {
        val repository = repositoryWith(AutoReplyConfig())

        val result = dispatch(repository, mapOf("state" to "on", "app" to "telegram"))

        assertEquals(
            "Auto-reply for Telegram is now ON. Auto-reply is OFF overall, so no replies will go out.",
            result.data
        )
    }

    @Test
    fun `status for the global switch going off is plain`() = runBlocking {
        val repository = repositoryWith(allOn)

        val result = dispatch(repository, mapOf("state" to "off", "app" to "all"))

        assertEquals("Auto-reply is now OFF.", result.data)
    }

    @Test
    fun `schema tells the planner about every app and the global switch`() {
        val app = ActionSchema.getAction("AUTO_REPLY_TOGGLE")!!.params.single { it.name == "app" }

        listOf("whatsapp", "telegram", "discord", "sms", "email", "all").forEach { name ->
            assertTrue(name, app.description.contains(name))
        }
    }

    /** What [ActionDispatcher] does before it calls a handler: the schema's defaults, then the action. */
    private suspend fun dispatch(
        repository: SettingsRepository,
        params: Map<String, String>
    ): ActionResult {
        val (validation, enriched) = ActionSchema.validateParams("AUTO_REPLY_TOGGLE", params)
        assertEquals(ActionSchema.ValidationResult.Valid, validation)
        return AutoReplyToggleAction(repository)
            .execute(enriched.mapValues { it.value.toString() }, context)
    }

    private suspend fun repositoryWith(config: AutoReplyConfig): SettingsRepository {
        val repository = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    Files.createTempDirectory("opendroid-auto-reply-toggle-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = NoCredentials,
            runStartupMigration = false
        )
        repository.updateAutoReplyConfig(config)
        return repository
    }

    private object NoCredentials : ProviderCredentialStore {
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
