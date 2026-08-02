package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.security.LegacyPreferenceMigration
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.UserProfile
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

/**
 * @param profileMustBeReentered the previous profile could not be decrypted; the screen explains
 *   why the fields are blank instead of silently losing the user's details.
 * @param storageError the last save did not durably commit, so the user can retry.
 */
data class OnboardingUiState(
    val isLoading: Boolean = true,
    val name: String = "",
    val dateOfBirth: String = "",
    val profileMustBeReentered: Boolean = false,
    val storageError: Boolean = false
)

/** Owns the onboarding profile so no Composable touches encrypted storage directly. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userProfileStore: UserProfileStore,
    private val appSettingsStore: AppSettingsStore,
    private val legacyPreferenceMigration: LegacyPreferenceMigration
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            // A failure here must still leave usable, blank fields rather than a stuck spinner.
            mutableUiState.value = runCatching {
                legacyPreferenceMigration.await()
                withContext(Dispatchers.IO) { loadProfile() }
            }.getOrElse { OnboardingUiState(isLoading = false, storageError = true) }
        }
    }

    fun onNameChange(value: String) {
        mutableUiState.value = mutableUiState.value.copy(name = value, storageError = false)
    }

    fun onDateOfBirthChange(value: String) {
        mutableUiState.value = mutableUiState.value.copy(dateOfBirth = value, storageError = false)
    }

    /** Invokes [onSaved] only after the profile is durably encrypted at rest. */
    fun saveProfile(onSaved: () -> Unit) {
        val state = mutableUiState.value
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    userProfileStore.write(
                        UserProfile(name = state.name.trim(), dateOfBirth = state.dateOfBirth.trim())
                    )
                }
            }.getOrDefault(ProfileStoreResult.StorageUnavailable)
            if (result is ProfileStoreResult.Success) {
                mutableUiState.value = mutableUiState.value.copy(
                    profileMustBeReentered = false,
                    storageError = false
                )
                onSaved()
            } else {
                mutableUiState.value = mutableUiState.value.copy(storageError = true)
            }
        }
    }

    /** Marks onboarding complete and invokes [onCompleted] regardless of the settings write. */
    fun completeOnboarding(onCompleted: () -> Unit) {
        viewModelScope.launch {
            // A failed flag write only means the user sees onboarding again; the profile they
            // just entered is already stored, so it is not worth blocking them here.
            runCatching { withContext(Dispatchers.IO) { appSettingsStore.setOnboardingCompleted(true) } }
            onCompleted()
        }
    }

    private fun loadProfile(): OnboardingUiState = when (val result = userProfileStore.read()) {
        is ProfileStoreResult.Success -> {
            val profile = result.value
            OnboardingUiState(
                isLoading = false,
                name = profile?.name.orEmpty(),
                dateOfBirth = profile?.dateOfBirth.orEmpty(),
                profileMustBeReentered = profile == null && appSettingsStore.isOnboardingCompleted()
            )
        }
        // Never fall back to a plaintext copy; ask for the details again instead.
        ProfileStoreResult.ProfileMustBeReentered -> {
            // Clear the unusable key material so the profile the user re-enters can be stored.
            userProfileStore.resetForReentry()
            OnboardingUiState(isLoading = false, profileMustBeReentered = true)
        }
        ProfileStoreResult.StorageUnavailable ->
            OnboardingUiState(isLoading = false, storageError = true)
    }

}
