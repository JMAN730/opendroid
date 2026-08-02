package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.security.LegacyPreferenceMigration
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.settings.AppSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app goes after the splash screen. */
enum class StartDestination(val route: String) {
    ONBOARDING("onboarding"),
    MAIN("main")
}

/**
 * Resolves the post-splash destination off the main thread, so the decision can decrypt the
 * stored profile rather than guess from a plaintext flag.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val appSettingsStore: AppSettingsStore,
    private val userProfileStore: UserProfileStore,
    private val legacyPreferenceMigration: LegacyPreferenceMigration
) : ViewModel() {

    private val mutableStartDestination = MutableStateFlow<StartDestination?>(null)

    /** Null until resolved; the splash screen waits for it rather than defaulting. */
    val startDestination: StateFlow<StartDestination?> = mutableStartDestination.asStateFlow()

    init {
        viewModelScope.launch {
            // The splash screen only routes once this is non-null, so an unexpected failure must
            // still produce a destination rather than stranding the user on the splash screen.
            mutableStartDestination.value = runCatching {
                legacyPreferenceMigration.await()
                withContext(Dispatchers.IO) { resolveDestination() }
            }.getOrDefault(StartDestination.MAIN)
        }
    }

    private fun resolveDestination(): StartDestination {
        if (!appSettingsStore.isOnboardingCompleted()) return StartDestination.ONBOARDING

        // A profile that can no longer be decrypted is never replaced by a plaintext guess. The
        // recoverable path is to ask for the details again, which onboarding already does. An
        // absent profile is left alone: the onboarding flag decides, exactly as it did before.
        return when (userProfileStore.read()) {
            is ProfileStoreResult.Success -> StartDestination.MAIN
            ProfileStoreResult.ProfileMustBeReentered -> StartDestination.ONBOARDING
            // A transient storage failure is not a reason to re-onboard a returning user.
            ProfileStoreResult.StorageUnavailable -> StartDestination.MAIN
        }
    }
}
