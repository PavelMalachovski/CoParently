package com.coparently.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.UiError
import com.coparently.app.presentation.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * ViewModel for Settings screen.
 * Manages settings state and user preferences.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val fcmService: FcmService,
    private val userRepository: UserRepository,
    private val preferencesRepository: PreferencesRepository,
    private val analyticsManager: AnalyticsManager
) : ViewModel() {

    private val _settingsState = MutableStateFlow(SettingsUiState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val _operationState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val operationState: StateFlow<UiState<Unit>> = _operationState.asStateFlow()

    val darkThemeFlow: StateFlow<Boolean?> = preferencesRepository.getDarkThemeFlow()
        .let { flow ->
            val stateFlow = MutableStateFlow<Boolean?>(null)
            viewModelScope.launch {
                flow.collect { stateFlow.value = it }
            }
            stateFlow
        }

    /** App-wide default currency for new expenses. */
    val defaultCurrency: StateFlow<SupportedCurrency> =
        preferencesRepository.getDefaultCurrencyFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedCurrency.DEFAULT)

    /**
     * Stores a new app-wide default currency.
     *
     * @param currency Currency to use for expenses created from now on
     */
    fun setDefaultCurrency(currency: SupportedCurrency) {
        viewModelScope.launch { preferencesRepository.setDefaultCurrency(currency) }
    }

    init {
        loadSettings()
    }

    /**
     * Loads current settings state.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            _operationState.value = UiState.Loading("Loading settings...")
            try {
                // Get current user
                val currentUser = userRepository.getCurrentUser()

                // Get FCM token status
                val fcmToken = fcmService.getCurrentToken()

                _settingsState.value = _settingsState.value.copy(
                    notificationsEnabled = fcmToken != null,
                    userEmail = currentUser?.email,
                    userName = currentUser?.name,
                    partnerId = currentUser?.partnerId,
                    isLoading = false
                )
                _operationState.value = UiState.Success(Unit)
            } catch (e: Exception) {
                _settingsState.value = _settingsState.value.copy(isLoading = false)
                _operationState.value = UiState.Error(
                    UiError.fromException(e, retry = { loadSettings() })
                )
            }
        }
    }

    /**
     * Toggles push notifications on/off.
     */
    fun toggleNotifications(enabled: Boolean) {
        viewModelScope.launch {
            _operationState.value = UiState.Loading(
                message = if (enabled) "Enabling notifications..." else "Disabling notifications..."
            )

            try {
                if (enabled) {
                    // Get and register FCM token
                    val token = fcmService.getCurrentToken()
                        ?: throw IOException("Failed to get FCM token")

                    fcmService.updateUserToken(token).getOrThrow()
                } else {
                    // Optionally clear token or unsubscribe from topics
                    // For now, just update UI state
                }

                analyticsManager.logNotificationsToggled(enabled)
                _settingsState.value = _settingsState.value.copy(
                    notificationsEnabled = enabled,
                    successMessage = if (enabled) "Notifications enabled" else "Notifications disabled"
                )
                _operationState.value = UiState.Success(
                    data = Unit,
                    message = if (enabled) "Notifications enabled successfully" else "Notifications disabled"
                )
            } catch (e: IOException) {
                _operationState.value = UiState.Error(
                    UiError.network(
                        message = "Network error. Please check your connection and try again.",
                        retry = { toggleNotifications(enabled) }
                    )
                )
            } catch (e: Exception) {
                _operationState.value = UiState.Error(
                    UiError.fromException(
                        throwable = e,
                        retry = { toggleNotifications(enabled) }
                    )
                )
            }
        }
    }

    /**
     * Requests notification permission and registers FCM token.
     */
    fun requestNotificationPermission() {
        viewModelScope.launch {
            _operationState.value = UiState.Loading("Requesting permission...")

            try {
                val token = fcmService.getCurrentToken()
                    ?: throw IOException("Failed to get notification token")

                fcmService.updateUserToken(token).getOrThrow()

                _settingsState.value = _settingsState.value.copy(
                    notificationsEnabled = true,
                    successMessage = "Notifications enabled"
                )
                _operationState.value = UiState.Success(
                    data = Unit,
                    message = "Notifications enabled successfully"
                )
            } catch (e: Exception) {
                _operationState.value = UiState.Error(
                    UiError.fromException(
                        throwable = e,
                        retry = { requestNotificationPermission() }
                    )
                )
            }
        }
    }

    /**
     * Toggles dark theme on/off.
     *
     * @param isDarkTheme True to enable dark theme, false to enable light theme
     */
    fun toggleDarkTheme(isDarkTheme: Boolean) {
        viewModelScope.launch {
            try {
                preferencesRepository.setDarkTheme(isDarkTheme)
                analyticsManager.logThemeChanged(isDarkTheme)
            } catch (e: Exception) {
                _operationState.value = UiState.Error(
                    UiError.fromException(e)
                )
            }
        }
    }

    /**
     * Resets theme to system default.
     */
    fun resetThemeToSystemDefault() {
        viewModelScope.launch {
            try {
                preferencesRepository.clearDarkTheme()
            } catch (e: Exception) {
                _operationState.value = UiState.Error(
                    UiError.fromException(e)
                )
            }
        }
    }

    /**
     * Clears success/error messages.
     */
    fun clearMessages() {
        _settingsState.value = _settingsState.value.copy(
            successMessage = null,
            errorMessage = null
        )
        _operationState.value = UiState.Idle
    }
}

/**
 * UI state for Settings screen.
 *
 * @property notificationsEnabled Whether push notifications are enabled
 * @property userEmail Current user's email
 * @property userName Current user's name
 * @property partnerId Partner's Firebase UID if paired
 * @property isLoading Loading state
 * @property successMessage Success message to display
 * @property errorMessage Error message to display
 */
data class SettingsUiState(
    val notificationsEnabled: Boolean = false,
    val userEmail: String? = null,
    val userName: String? = null,
    val partnerId: String? = null,
    val isLoading: Boolean = true,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

