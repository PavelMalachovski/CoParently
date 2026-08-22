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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * State of a profile screen — the signed-in parent's own, and the co-parent's.
 *
 * @property me The signed-in user, or null before the first load
 * @property coParent The co-parent's profile, or null when unpaired or not yet loaded
 * @property isSaving Whether a save is in flight
 * @property savedAt Epoch millis of the last successful save, for a transient confirmation
 */
data class ProfileUiState(
    val me: User? = null,
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
 *   sign-out or account switch reloads it. It is deliberately **not** re-read on every Room
 *   emission of that row — only on an identity change — so a save this ViewModel itself just
 *   made, or an unrelated background sync, can never clobber an in-progress edit. That is the
 *   exact shape of the `ChildInfoViewModel` bug CLAUDE.md's "Known issues" records: a
 *   screen-lifetime subscription overwriting a draft the user is mid-edit on.
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
     * account switch) — not on every Room emission of that row, which would also fire on this
     * ViewModel's own [save] and clobber whatever the user is mid-edit on.
     */
    private fun observeMe() {
        viewModelScope.launch {
            userRepository.observeCurrentUserId().collectLatest { uid ->
                val me = uid?.let { userRepository.getUserById(it) }
                _uiState.update { it.copy(me = me) }
            }
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
     * Persists the current draft of [ProfileUiState.me] — to Room and, best-effort, to
     * Firestore (see [UserRepository.updateUser]). A failure is logged and swallowed rather
     * than surfaced: the local write already succeeded, and there is nothing destructive left
     * to undo.
     */
    fun save() {
        val me = _uiState.value.me ?: return
        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                userRepository.updateUser(me)
                _uiState.update { it.copy(isSaving = false, savedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                Log.e(TAG, "Failed to save the profile for ${me.id}", e)
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ProfileViewModel"
    }
}
