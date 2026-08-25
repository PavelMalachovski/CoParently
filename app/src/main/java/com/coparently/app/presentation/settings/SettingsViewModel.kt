package com.coparently.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.repository.FamilySettingsRepository
import com.coparently.app.data.repository.RatioSubmission
import com.coparently.app.data.session.AccountDeletionService
import com.coparently.app.data.session.SignedInAccountSource
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.model.AccountSummary
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.presentation.theme.ParentColorChoice
import com.coparently.app.domain.money.SupportedCurrency
import com.coparently.app.domain.repository.PreferencesRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.presentation.common.FamilyKindSource
import com.coparently.app.presentation.common.Parents
import com.coparently.app.data.family.FamilyOption
import com.coparently.app.data.family.SelectedFamilySource
import com.coparently.app.presentation.common.ParentsSource
import com.coparently.app.presentation.common.UiError
import com.coparently.app.presentation.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val accountDeletionService: AccountDeletionService,
    private val userRepository: UserRepository,
    private val preferencesRepository: PreferencesRepository,
    private val analyticsManager: AnalyticsManager,
    signedInAccountSource: SignedInAccountSource,
    private val familyKindSource: FamilyKindSource,
    parentsSource: ParentsSource,
    private val selectedFamilySource: SelectedFamilySource,
    private val familySettingsRepository: FamilySettingsRepository
) : ViewModel() {

    /**
     * The account the app itself is signed in as — distinct from the Google account used
     * for calendar sync, which `SyncViewModel` reports separately further up this screen.
     *
     * Null while signed out. Unlike [settingsState], this keeps following the profile, so
     * the name and avatar fill in as soon as `ensureProfile` writes them rather than
     * staying at whatever a one-shot read saw at startup.
     */
    val account: StateFlow<AccountSummary?> = signedInAccountSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS), null)

    /**
     * Whether this family's app offers child records, pet records, or both.
     *
     * The union of the two parents' answers, from [FamilyKindSource]; an account that has never
     * answered — every one that predates the question — reads as both, so an upgrade hides
     * nothing somebody was already using.
     */
    val caresFor: StateFlow<Set<FamilyKind>> = familyKindSource.observe()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS),
            FamilyKind.ALL
        )

    /**
     * Signed-in parent and paired co-parent, for naming the two halves of the split.
     *
     * Two bare numbers cannot say which half is yours, and the answer is not guessable: the
     * stored share is slot 1's and pairing decides which slot this device holds. A parent
     * dragging that slider without names can propose the opposite of what they meant.
     */
    val parents: StateFlow<Parents> = parentsSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS), Parents())

    /**
     * This parent's **own** answer, which is what the Settings dialog edits.
     *
     * Not [caresFor]. That is the union of both parents' answers and decides which rows the app
     * draws; [setCaresFor] writes to this parent's row alone. Seeding the dialog with the union
     * meant a box ticked only by the co-parent looked like yours: saving without touching
     * anything copied their answer onto your record, and unticking one put it straight back.
     */
    val myCaresFor: StateFlow<Set<FamilyKind>> = familyKindSource.observeMine()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS),
            FamilyKind.ALL
        )

    /**
     * What happened to a family answer, for the one case each where saying nothing would be a lie.
     */
    enum class CaresForOutcome {
        /**
         * Saved, but a kind this parent removed stays on screen because the co-parent keeps it.
         * The union is what the app shows; unticking here changes only this parent's record.
         */
        KEPT_BY_CO_PARENT,

        /**
         * Saved, and a kind this parent removed is now hidden — along with every route to the
         * records under it, which keep existing, keep syncing, and keep holding guest grants.
         * The switch is reversible; saying nothing would make it look like a deletion.
         */
        RECORDS_KEPT,

        /** Not saved at all: this account has no local profile row to write onto. */
        NOT_SAVED
    }

    /**
     * A **conflated `Channel`**, not a `MutableSharedFlow`: a shared flow with `replay = 0`
     * discards a `tryEmit` outright when nothing is collecting — and returns `true` while doing
     * it — so a signal raised a moment after the parent left Settings would vanish. The channel
     * holds the last one until a collector attaches.
     */
    private val _caresForOutcome = Channel<CaresForOutcome>(Channel.CONFLATED)

    /** Raised when a family answer needs a word said about it. See [CaresForOutcome]. */
    val caresForOutcome: Flow<CaresForOutcome> = _caresForOutcome.receiveAsFlow()

    /**
     * Records a new answer onto this parent's own record.
     *
     * Refuses an empty set: with neither kind selected the app would have nothing to offer, and
     * a family that co-parents nothing is not a state this product has.
     *
     * Both non-obvious outcomes are announced rather than swallowed. A missing profile row made
     * this a silent no-op that read as "the setting resets itself"; and unticking a kind the
     * co-parent still holds changes this row but nothing on screen, because what the app draws is
     * the union — a control that appears to do nothing is what CLAUDE.md's design item 8 forbids.
     */
    /**
     * Records the colour this parent wants to be drawn in.
     *
     * Their own choice and nobody else's: the co-parent's colour is on the co-parent's profile,
     * and the two are reconciled at draw time by [ParentPalette.of] when they happen to collide.
     * That is why this writes and returns without checking what the other parent holds — a
     * "that one is taken" refusal would need the two phones to agree on an order they have no
     * way to establish, and would make one parent's setting depend on the other's.
     */
    /**
     * The families this parent is in, each with the co-parent named, and which is on screen.
     *
     * Refreshed on demand rather than observed: it costs one Firestore read per relationship
     * (see [SelectedFamilySource.namedFamilies]) and the set changes only on a pairing
     * transition, so a listener would pay that price on every unrelated row this screen
     * redraws.
     */
    private val _families = MutableStateFlow<List<FamilyOption>>(emptyList())
    val families: StateFlow<List<FamilyOption>> = _families.asStateFlow()

    private val _selectedFamilyId = MutableStateFlow<String?>(null)
    val selectedFamilyId: StateFlow<String?> = _selectedFamilyId.asStateFlow()

    /** Reloads the family list. Called when Settings opens and after a switch. */
    fun refreshFamilies() {
        viewModelScope.launch {
            _families.value = selectedFamilySource.namedFamilies()
            _selectedFamilyId.value = selectedFamilySource.selected()?.familyId
        }
    }

    /**
     * Points this device at another family.
     *
     * Everything downstream — the calendar's audience, the chat thread, the custody pair, the
     * expense query — follows from the one row `SelectedFamilySource` re-points, so there is
     * nothing else to notify here.
     */
    fun selectFamily(familyId: String) {
        viewModelScope.launch {
            selectedFamilySource.select(familyId)
            _selectedFamilyId.value = selectedFamilySource.selected()?.familyId
        }
    }

    fun setParentColor(choice: ParentColorChoice) {
        viewModelScope.launch {
            val fresh = userRepository.getCurrentUser() ?: return@launch
            if (fresh.colorCode == choice.storedCode) return@launch
            userRepository.updateUser(fresh.copy(colorCode = choice.storedCode))
        }
    }

    fun setCaresFor(kinds: Set<FamilyKind>) {
        if (kinds.isEmpty()) return
        viewModelScope.launch {
            val fresh = userRepository.getCurrentUser()
            if (fresh == null) {
                _caresForOutcome.trySend(CaresForOutcome.NOT_SAVED)
                return@launch
            }
            val dropped = fresh.caresFor - kinds
            userRepository.updateUser(fresh.copy(caresFor = kinds))
            // Read at save time, off the shared upstream, rather than from a `WhileSubscribed`
            // `.value` — invariant 17. `first()` is one emission of a flow that already has a
            // subscriber on this screen, so it costs no listener.
            val theirs = familyKindSource.observeTheirs().first()
            // Both branches judge what the screen actually *draws* — the union — rather than
            // this parent's row alone. Judging by the row got both answers wrong: `dropped` is
            // empty for every account that never answered, which is the population that upgrades
            // into this question, so the one save that really did hide a section said nothing;
            // and `theirs - kinds` is non-empty whenever the co-parent holds a kind this parent
            // does not, so an unchanged save told them they had removed one.
            val hidden = FamilyKind.effective(fresh.caresFor, theirs) -
                FamilyKind.effective(kinds, theirs)
            when {
                // Given up here and still drawn because the co-parent keeps it. Conditioned on a
                // real removal, so it can never claim one that did not happen.
                (dropped intersect theirs).isNotEmpty() ->
                    _caresForOutcome.trySend(CaresForOutcome.KEPT_BY_CO_PARENT)
                hidden.isNotEmpty() -> _caresForOutcome.trySend(CaresForOutcome.RECORDS_KEPT)
                else -> Unit
            }
        }
    }

    /**
     * The agreed split of a shared expense.
     *
     * Half each until the family agrees otherwise. Cached locally as it changes, because the
     * expense save path reads it and cannot wait on a document.
     */
    val agreedRatio: StateFlow<SplitRatio> = familySettingsRepository.observeSettings()
        .map { settings ->
            settings?.ratio?.also(familySettingsRepository::cacheAgreedRatio)
                ?: familySettingsRepository.agreedRatioOrDefault()
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS),
            SplitRatio.EVEN
        )

    /**
     * How the last submitted ratio landed, for the screen to report once.
     *
     * Conflated channel for the reason [caresForOutcome] gives: a `MutableSharedFlow` with
     * `replay = 0` drops an emission when nothing is collecting, and the round trip to Firestore
     * easily outlives a parent closing Settings.
     */
    private val _ratioSubmission = Channel<RatioSubmission?>(Channel.CONFLATED)
    val ratioSubmission: Flow<RatioSubmission?> = _ratioSubmission.receiveAsFlow()

    /**
     * Puts a new split to the co-parent, or applies it when there is nobody to ask.
     *
     * Emits null on a refusal — the transition refuses proposing over the co-parent's pending
     * one, and refuses a "change" to the ratio already agreed. Either way the screen has to say
     * something: a control that looks like it worked is worse than one that says it did not.
     */
    fun submitRatio(ratio: SplitRatio) {
        viewModelScope.launch {
            _ratioSubmission.send(familySettingsRepository.submitRatio(ratio).getOrNull())
        }
    }

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

    /**
     * Erases the account and everything it holds, then wipes this device.
     *
     * Irreversible, and the screen says so before calling this — see the confirmation in
     * `SettingsScreen`. [onDeleted] runs only on success and is where the caller leaves the
     * screen; on failure the account still exists and the user can try again, which is why
     * the server deletes the Auth user last.
     *
     * @param onDeleted Invoked once the account is gone and local data is cleared.
     */
    fun deleteAccount(onDeleted: () -> Unit) {
        if (_settingsState.value.isDeletingAccount) return
        _settingsState.value = _settingsState.value.copy(
            isDeletingAccount = true,
            errorMessage = null
        )
        viewModelScope.launch {
            accountDeletionService.deleteAccount().fold(
                onSuccess = {
                    _settingsState.value = _settingsState.value.copy(isDeletingAccount = false)
                    onDeleted()
                },
                onFailure = { error ->
                    _settingsState.value = _settingsState.value.copy(
                        isDeletingAccount = false,
                        errorMessage = error.message
                    )
                }
            )
        }
    }

    private companion object {
        /** Keeps the account subscription alive across a configuration change. */
        const val ACCOUNT_STOP_TIMEOUT_MS = 5_000L
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
    val errorMessage: String? = null,
    /**
     * True while [SettingsViewModel.deleteAccount] is in flight.
     *
     * Separate from [isLoading], which describes the screen's initial load: deletion has to
     * disable its own control and show progress without making the rest of Settings look
     * unloaded, and it is the one action here that cannot be repeated by accident.
     */
    val isDeletingAccount: Boolean = false
)

