package com.opendroid.ai.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.ui.NavigationTestApplication
import com.opendroid.ai.ui.viewmodel.AutoReplyViewModel
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #130: the screen's last edit has to reach the store even if the screen goes away first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = NavigationTestApplication::class)
class AutoReplySettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `clearing the canned reply and leaving at once still clears it in the store`() {
        val repository = repositoryWithCannedReply()
        val viewModel = viewModel(repository)
        var showScreen by mutableStateOf(true)
        composeRule.setContent {
            if (showScreen) {
                AutoReplySettingsScreen(viewModel = viewModel, onBack = {})
            }
        }
        clearCannedReplyField()

        composeRule.runOnIdle { showScreen = false }
        composeRule.waitForIdle()

        assertCannedReplyCleared(repository)
    }

    @Test
    fun `backgrounding the app with an edit pending still clears it in the store`() {
        val repository = repositoryWithCannedReply()
        val viewModel = viewModel(repository)
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        composeRule.runOnUiThread { lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                AutoReplySettingsScreen(viewModel = viewModel, onBack = {})
            }
        }
        clearCannedReplyField()

        composeRule.runOnIdle { lifecycleOwner.lifecycle.currentState = Lifecycle.State.CREATED }

        assertCannedReplyCleared(repository)
    }

    @Test
    fun `clearing the ViewModel of a popped screen writes the pending edit`() {
        val repository = repositoryWithCannedReply()
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    viewModel(repository) as T
            }
        )[AutoReplyViewModel::class.java]
        composeRule.waitUntil(WAIT_MILLIS) { !viewModel.isLoading }

        viewModel.saveConfig(viewModel.config.copy(customReplyText = null), debounce = true)
        store.clear()

        assertCannedReplyCleared(repository)
    }

    @Test
    fun `a debounced edit that is never flushed lands once the delay has passed`() {
        val repository = repositoryWithCannedReply()
        val viewModel = viewModel(repository, debounceMillis = 50)
        composeRule.waitUntil(WAIT_MILLIS) { !viewModel.isLoading }

        viewModel.saveConfig(viewModel.config.copy(customReplyText = "typing"), debounce = true)
        viewModel.saveConfig(viewModel.config.copy(customReplyText = null), debounce = true)

        assertCannedReplyCleared(repository)
    }

    @Test
    fun `a debounced edit stays unwritten until the delay or a flush`() {
        val repository = repositoryWithCannedReply()
        val viewModel = viewModel(repository)
        composeRule.waitUntil(WAIT_MILLIS) { !viewModel.isLoading }

        viewModel.saveConfig(viewModel.config.copy(customReplyText = null), debounce = true)
        // An immediate save of the same config would land well inside this window.
        Thread.sleep(250)

        assertEquals(
            CANNED_REPLY,
            runBlocking { repository.autoReplyConfig.first().customReplyText }
        )
        viewModel.flushPendingConfig()
        assertCannedReplyCleared(repository)
    }

    private fun clearCannedReplyField() {
        val cannedField = hasText(CANNED_REPLY) and hasSetTextAction()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(cannedField).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(cannedField).performTextClearance()
    }

    private fun assertCannedReplyCleared(repository: SettingsRepository) {
        assertNotNull(
            "the cleared canned reply never reached the store",
            runBlocking {
                withTimeoutOrNull(WAIT_MILLIS) {
                    repository.autoReplyConfig.first { it.customReplyText == null }
                }
            }
        )
    }

    // The default debounce outlasts every test, so only a flush can make these writes land in time.
    private fun viewModel(repository: SettingsRepository, debounceMillis: Long = 60_000) =
        AutoReplyViewModel(
            repository,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            debounceMillis
        )

    private fun repositoryWithCannedReply() = newRepository().also { repository ->
        runBlocking {
            repository.updateAutoReplyConfig(
                AutoReplyConfig(globalEnabled = true, customReplyText = CANNED_REPLY)
            )
        }
    }

    private fun newRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = {
                Files.createTempDirectory("opendroid-auto-reply-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = NoCredentials,
        runStartupMigration = false
    )

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

    private companion object {
        const val CANNED_REPLY = "I'm driving, call you later"
        const val WAIT_MILLIS = 5_000L
    }
}
