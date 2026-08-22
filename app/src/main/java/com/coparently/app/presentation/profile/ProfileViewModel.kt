package com.coparently.app.presentation.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject

/**
 * State of a profile screen — the signed-in parent's own, and the co-parent's.
 *
 * @property me The signed-in user, or null before the first load
 * @property meUnavailable True once waiting for [me]'s Room row has timed out — either the
 *   [com.coparently.app.domain.repository.UserRepository.ensureProfile] write is taking
 *   unusually long, or (the name-less identity path) it is never going to arrive at all. The
 *   screen should offer a retry instead of spinning forever. Always false while [me] is set,
 *   and reset to false as soon as a new load attempt starts.
 * @property coParent The co-parent's profile, or null when unpaired or not yet loaded
 * @property isSaving Whether a save is in flight
 * @property savedAt Epoch millis of the last successful save, for a transient confirmation
 */
data class ProfileUiState(
    val me: User? = null,
    val meUnavailable: Boolean = false,
    val coParent: User? = null,
    val isSaving: Boolean = false,
    val savedAt: Long? = null
)

/**
 * Backs [ProfileScreen][com.coparently.app.presentation.profile.ProfileScreen] in both of its
 * modes: editing the signed-in user's own record, and displaying the co-parent's read-only.
 *
 * [me] and [coParent] are resolved differently because the app stores them differently — the
 * same split [com.coparently.app.presentation.common.ParentsSource] documents for its own
 * `Parents` type:
 * - **[me]** comes from Room, keyed by [UserRepository.observeCurrentUserId] so a sign-in,
 *   sign-out or account switch reloads it. For each identity, [observeMe] waits on
 *   [UserRepository.observeUserById] only until its **first** non-null row, then stops
 *   collecting — it does not stay subscribed for the rest of the screen's lifetime. That one
 *   difference matters both ways: waiting (rather than a single [UserRepository.getUserById]
 *   read) is what lets a screen opened in the window between sign-in and
 *   [UserRepository.ensureProfile]'s asynchronous write still receive the row once it lands,
 *   instead of leaving [me] null forever; stopping after that first row is what stops a save
 *   this ViewModel itself just made, or an unrelated background sync, from clobbering an
 *   in-progress edit — the exact shape of the `ChildInfoViewModel` bug CLAUDE.md's "Known
 *   issues" records, a screen-lifetime subscription overwriting a draft the user is mid-edit
 *   on. A row that still has not appeared after [ME_LOAD_TIMEOUT_MILLIS] sets
 *   [ProfileUiState.meUnavailable] instead of waiting forever, for the one case waiting can
 *   never resolve on its own: the name-less identity path in `ensureProfile` creates no local
 *   row at all.
 * - **[coParent]** comes from [UserRepository.getRemoteUserProfile], a direct
 *   `users/{uid}` read, once per co-parent uid reported by [PairingRepository.observePairingState] —
 *   never from [UserRepository.getAllUsers], which only ever holds a row for the signed-in
 *   user and, on a device where more than one account has signed in over time, may hold rows
 *   for accounts paired with nobody. Not extended onto
 *   [PartnerSummary][com.coparently.app.domain.model.PartnerSummary]: that model exists to
 *   name a person in a chat header, and hanging seven medical fields on it would load them on
 *   every screen that only wants a name.
 *
 * @param userRepository Loads and saves the signed-in user's own record, and reads the
 *   co-parent's remote one.
 * @param pairingRepository Reports which uid, if any, is the co-parent.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeMe()
        observeCoParent()
    }

    /**
     * Loads the signed-in user's own record on every identity change (sign-in, sign-out,
     * account switch). See this class's doc for why waiting on the first row and then letting
     * go of the subscription — rather than either a single read or a lifetime-long one — is
     * the shape that fixes both the cold-start race and the mid-edit clobber at once.
     */
    private fun observeMe() {
        viewModelScope.launch {
            userRepository.observeCurrentUserId().collectLatest { uid ->
                if (uid == null) {
                    _uiState.update { it.copy(me = null, meUnavailable = false) }
                    return@collectLatest
                }
                loadMe(uid)
            }
        }
    }

    /**
     * Waits for [uid]'s Room row to exist, bounded by [ME_LOAD_TIMEOUT_MILLIS], and publishes
     * the outcome either way. Shared by [observeMe] and [retryLoadingMe] so a retry after a
     * timeout goes through the exact same wait, not a copy of it.
     */
    private suspend fun loadMe(uid: String) {
        _uiState.update { it.copy(meUnavailable = false) }
        val me = withTimeoutOrNull(ME_LOAD_TIMEOUT_MILLIS) {
            userRepository.observeUserById(uid).first { it != null }
        }
        _uiState.update { it.copy(me = me, meUnavailable = me == null) }
    }

    /**
     * Retries after [ProfileUiState.meUnavailable]. Re-runs [UserRepository.ensureProfile] —
     * best-effort and idempotent, the same call [com.coparently.app.data.session.SessionProfileSynchronizer]
     * already makes on every sign-in — in case the write that never finished is exactly what
     * this is waiting on, then waits again for the row.
     */
    fun retryLoadingMe() {
        viewModelScope.launch {
            val uid = userRepository.getCurrentUserId() ?: return@launch
            userRepository.ensureProfile()
            loadMe(uid)
        }
    }

    /**
     * Loads the co-parent's remote profile whenever the pairing state reports a (possibly new)
     * co-parent uid, and clears it on unpair. One-shot per uid change, not a live listener —
     * the same tradeoff [PairingRepository]'s own partner-summary read makes.
     */
    private fun observeCoParent() {
        viewModelScope.launch {
            pairingRepository.observePairingState()
                .map { state -> (state as? PairingState.Paired)?.partner?.id }
                .distinctUntilChanged()
                .collectLatest { coParentUid ->
                    val coParent = coParentUid?.let { uid ->
                        userRepository.getRemoteUserProfile(uid)
                    }
                    _uiState.update { it.copy(coParent = coParent) }
                }
        }
    }

    /** Updates the draft's name. No-op before [me] has loaded. */
    fun updateName(name: String) = updateMe { it.copy(name = name) }

    /** Updates the draft's date of birth. No-op before [me] has loaded. */
    fun updateDateOfBirth(date: LocalDate?) = updateMe { it.copy(dateOfBirth = date) }

    /**
     * Updates the draft's phone number. Blank normalizes to null — [User.phone] means "not
     * recorded" for null, not for empty text. No-op before [me] has loaded.
     */
    fun updatePhone(phone: String) = updateMe { it.copy(phone = phone.ifBlank { null }) }

    /** Updates the draft's allergies. No-op before [me] has loaded. */
    fun updateAllergies(allergies: List<String>) = updateMe { it.copy(allergies = allergies) }

    /** Updates the draft's medical profile. No-op before [me] has loaded. */
    fun updateMedicalProfile(profile: MedicalProfile) = updateMe { it.copy(medicalProfile = profile) }

    /** Applies [transform] to the current draft, if one has loaded. */
    private fun updateMe(transform: (User) -> User) {
        _uiState.update { state -> state.me?.let { state.copy(me = transform(it)) } ?: state }
    }

    /**
     * Persists the fields this screen owns — name, date of birth, phone, allergies, medical
     * profile — to Room and, best-effort, to Firestore (see [UserRepository.updateUser]). A
     * failure is logged and swallowed rather than surfaced: the local write already succeeded,
     * and there is nothing destructive left to undo.
     *
     * Deliberately does **not** send [ProfileUiState.me] itself: that draft was loaded once,
     * on the last identity change, and is as stale as the time the user spent on this form.
     * `User` also carries `partnerId` and `fcmToken`, and [UserRepository.updateUser] writes
     * both straight through — so a stale draft resurrects whatever `partnerId` this screen
     * happened to load with. Concretely: A opens their profile while paired with B; B unpairs;
     * the server clears both sides and `SyncWorker` writes `partnerId = null` into A's Room row;
     * A, still on the open screen, edits a field and saves. Sending the stale draft would put
     * `partnerId: "bob"` straight back into Room and Firestore, handing B back read access to
     * A's phone, date of birth, medical profile, expenses and budgets — undoing an unpair the
     * user never asked to reverse.
     *
     * So this re-reads the user's row immediately before saving and copies only the fields the
     * form actually edited onto that fresh copy; everything else — `partnerId`, `fcmToken`,
     * `role`, `colorCode`, and so on — comes from whatever is current right now, the same way
     * every other writer in this codebase touches one field via a fresh Room read rather than
     * a held snapshot (see `updateFcmToken`, or the `role` comment in `updateUser` itself).
     */
    fun save() {
        val draft = _uiState.value.me ?: return
        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val fresh = userRepository.getUserById(draft.id) ?: draft
                val toSave = fresh.copy(
                    name = draft.name,
                    dateOfBirth = draft.dateOfBirth,
                    phone = draft.phone,
                    allergies = draft.allergies,
                    medicalProfile = draft.medicalProfile
                )
                userRepository.updateUser(toSave)
                _uiState.update {
                    it.copy(me = toSave, isSaving = false, savedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                Log.e(TAG, "Failed to save the profile for ${draft.id}", e)
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ProfileViewModel"

        /** Same value [ChatViewModel]'s own pairing-resolve wait uses. */
        const val ME_LOAD_TIMEOUT_MILLIS = 5000L
    }
}
