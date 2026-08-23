package com.coparently.app.presentation.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * Everything the wizard has collected so far, plus which step is showing it.
 *
 * The parent's fields and the child's are held flat rather than as a `User` and a `ChildInfo`
 * because the wizard owns neither record wholesale: it edits five of the parent's fields and
 * four of the child's, and each is written onto a **freshly read** row at save time. Holding
 * the whole objects would invite the mistake `ProfileViewModel` documents at length — a stale
 * `User` carries `partnerId`, and writing it back resurrects a pairing the co-parent has since
 * ended.
 *
 * @property step Which step is currently showing
 * @property name The parent's own name — the one field that blocks progress
 * @property dateOfBirth The parent's own date of birth, or null while unanswered
 * @property phone The parent's own phone, free text as typed
 * @property allergies The parent's own allergies
 * @property medicalProfile The parent's own emergency medical profile
 * @property childName The child's name; blank means the child step was left unanswered
 * @property childDateOfBirth The child's date of birth, or null while unanswered
 * @property childAllergies The child's allergies
 * @property childMedicalProfile The child's emergency medical profile
 * @property relatives Emergency contacts, saved onto the child's record
 * @property isSaving True while [OnboardingViewModel.finish]'s write is in flight
 * @property isFinished True once onboarding is recorded as complete and the host may leave
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Intro,
    val name: String = "",
    val dateOfBirth: LocalDate? = null,
    val phone: String = "",
    val allergies: List<String> = emptyList(),
    val medicalProfile: MedicalProfile = MedicalProfile(),
    val childName: String = "",
    val childDateOfBirth: LocalDate? = null,
    val childAllergies: List<String> = emptyList(),
    val childMedicalProfile: MedicalProfile = MedicalProfile(),
    val relatives: List<EmergencyContact> = emptyList(),
    val isSaving: Boolean = false,
    val isFinished: Boolean = false
) {
    /**
     * Only a blank parent name blocks progress.
     *
     * The questionnaire asks for a great deal — a blood group, hereditary conditions, a phone
     * number for an aunt — and it is collected for the parent's own benefit in an emergency.
     * Data gathered for someone's benefit must not lock them out of their calendar, so the one
     * thing that blocks is the one thing the app cannot work without.
     */
    val canAdvance: Boolean
        get() = step != OnboardingStep.Profile || name.isNotBlank()

    /** Whether this step offers a Skip. */
    val canSkip: Boolean get() = step.isSkippable

    /**
     * Whether the relatives step can accept contacts yet.
     *
     * An [EmergencyContact] belongs to a child — that is the record both parents may write, and
     * the only place the rest of the app reads contacts from. With no named child there is
     * nowhere honest to put one, so the step says so rather than collecting contacts it would
     * then drop on the floor.
     */
    val canEditRelatives: Boolean get() = childName.isNotBlank()
}

/**
 * Drives the first-run questionnaire: which step is showing, what has been typed into it, and
 * when that reaches Room and Firestore.
 *
 * **Saving happens per step, on Next.** A parent who is interrupted after step 2 and force-stops
 * the app finds their answers already stored, and [prefill] puts them back into the form on the
 * next launch — the wizard resumes rather than restarting empty. [skip] deliberately does not
 * save: skipping means "I am not answering this", and writing a blank answer over a value the
 * account already had would turn a skip into a deletion.
 *
 * **Each write goes onto a freshly read row.** The parent's five fields are copied onto whatever
 * `users/{uid}` holds right now, and the child's four onto whatever `child_info` holds right now,
 * for the reason `ProfileViewModel.save` documents: a held snapshot carries `partnerId`,
 * `createdByFirebaseUid` and sync flags that belong to whoever last wrote them, not to this form.
 *
 * **The child is read once, not observed.** [prefill] takes the first emission of
 * [ChildInfoRepository.getAllChildInfo] and lets go. A screen-lifetime subscription to the whole
 * list is the exact shape of the `ChildInfoViewModel` defect CLAUDE.md records under "Known
 * issues", where a background sync tick re-emits the list and overwrites the form the user is
 * mid-edit on.
 *
 * @param userRepository Reads and writes the signed-in parent's own record
 * @param childInfoRepository Reads and writes the child record the wizard fills in
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val childInfoRepository: ChildInfoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * The child record this wizard is filling in, once one is known.
     *
     * Held outside [OnboardingUiState] because no part of the UI renders it: it exists so the
     * child step and the relatives step write to the **same** record rather than creating a
     * second one, and so an account that already has a child edits that child instead of
     * duplicating it.
     */
    private var childInfoId: String? = null

    init {
        prefill()
    }

    /**
     * Loads whatever this account already holds into the form.
     *
     * A Google sign-in arrives with a name; an interrupted first run arrives with everything it
     * got as far as saving. Asking either to retype it would be the wizard's worst moment.
     *
     * Every field is filled **only while it is still untouched**, and a blank stored value is
     * never applied. Both halves of that matter: this runs asynchronously against a Room read,
     * so a parent who starts typing on the intro's Next before it lands must not have their
     * answer replaced by the row it finds, and an account whose stored name is the empty string
     * — every email/password sign-up before the profile screen is opened — must not have that
     * emptiness written over what they just typed.
     */
    private fun prefill() {
        viewModelScope.launch {
            try {
                val user = userRepository.getCurrentUser()
                val child = childInfoRepository.getAllChildInfo().first().firstOrNull()
                // Never clears an id a step has already claimed: this read is asynchronous, and
                // "no child yet" arriving after a child was created would orphan that record and
                // make the next save create a second one.
                childInfoId = childInfoId ?: child?.id
                _uiState.update { state ->
                    state.copy(
                        name = state.name.orStored(user?.name),
                        dateOfBirth = state.dateOfBirth ?: user?.dateOfBirth,
                        phone = state.phone.orStored(user?.phone),
                        allergies = state.allergies.orStored(user?.allergies),
                        medicalProfile = state.medicalProfile.orStored(user?.medicalProfile),
                        childName = state.childName.orStored(child?.childName),
                        childDateOfBirth = state.childDateOfBirth ?: child?.dateOfBirth?.toLocalDate(),
                        childAllergies = state.childAllergies.orStored(child?.allergies),
                        childMedicalProfile = state.childMedicalProfile.orStored(child?.medicalProfile),
                        relatives = state.relatives.orStored(child?.emergencyContacts)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                // An empty form is a survivable outcome; a crashed wizard on first launch is not.
                Log.e(TAG, "Failed to prefill the wizard from the existing account", e)
            }
        }
    }

    /** Updates the parent's name. */
    fun updateName(name: String) = _uiState.update { it.copy(name = name) }

    /** Updates the parent's date of birth. */
    fun updateDateOfBirth(date: LocalDate?) = _uiState.update { it.copy(dateOfBirth = date) }

    /** Updates the parent's phone number, free text as typed. */
    fun updatePhone(phone: String) = _uiState.update { it.copy(phone = phone) }

    /** Updates the parent's allergies. */
    fun updateAllergies(allergies: List<String>) = _uiState.update { it.copy(allergies = allergies) }

    /** Updates the parent's medical profile. */
    fun updateMedicalProfile(profile: MedicalProfile) =
        _uiState.update { it.copy(medicalProfile = profile) }

    /** Updates the child's name. */
    fun updateChildName(name: String) = _uiState.update { it.copy(childName = name) }

    /** Updates the child's date of birth. */
    fun updateChildDateOfBirth(date: LocalDate?) =
        _uiState.update { it.copy(childDateOfBirth = date) }

    /** Updates the child's allergies. */
    fun updateChildAllergies(allergies: List<String>) =
        _uiState.update { it.copy(childAllergies = allergies) }

    /** Updates the child's medical profile. */
    fun updateChildMedicalProfile(profile: MedicalProfile) =
        _uiState.update { it.copy(childMedicalProfile = profile) }

    /** Replaces the list of emergency contacts. */
    fun updateRelatives(relatives: List<EmergencyContact>) =
        _uiState.update { it.copy(relatives = relatives) }

    /**
     * Saves this step's answers and moves to the next one.
     *
     * The step advances immediately and the write runs behind it: the write targets a fresh row
     * and a snapshot taken here, so nothing the parent types on the next step can reach it, and
     * making them watch a spinner between two questions would be the wrong trade. A failed write
     * is logged, not surfaced — the parent has no action to take about it, and the next step's
     * save (or the profile screen later) writes the same fields again.
     */
    fun next() {
        val state = _uiState.value
        if (!state.canAdvance) return

        when (state.step) {
            OnboardingStep.Profile -> persist { saveProfile(state) }
            OnboardingStep.Child -> persist { saveChild(state) }
            OnboardingStep.Relatives -> persist { saveChild(state) }
            else -> Unit
        }
        leaveStep(state.step)
    }

    /**
     * Moves on without saving.
     *
     * Skipping means "I am not answering this", so the step's fields are deliberately not
     * written: a blank answer written over a value the account already had would turn a skip
     * into a deletion.
     */
    fun skip() {
        val state = _uiState.value
        if (!state.canSkip) return
        leaveStep(state.step)
    }

    /** Steps back. A no-op on the first step, which has nowhere to go. */
    fun back() {
        _uiState.update { state ->
            val previous = OnboardingStep.entries.getOrNull(state.step.ordinal - 1)
            previous?.let { state.copy(step = it) } ?: state
        }
    }

    /**
     * Records that onboarding is done and signals the host to leave.
     *
     * Unlike [next] this one waits: [OnboardingUiState.isFinished] is what navigates away, and
     * navigating before the marker reaches Room would let the launch check re-read a null marker
     * and hand the parent the same questionnaire again. The marker is stamped even when the
     * write to Firestore fails — [UserRepository.updateUser] already swallows that half — and
     * even when the local write throws, because trapping someone in a wizard they have finished
     * is worse than asking again on the next launch.
     */
    fun finish() {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val fresh = userRepository.getCurrentUser()
                if (fresh != null) {
                    userRepository.updateUser(
                        fresh.copy(onboardingCompletedAt = LocalDateTime.now().toString())
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                Log.e(TAG, "Failed to record that onboarding finished", e)
            }
            _uiState.update { it.copy(isSaving = false, isFinished = true) }
        }
    }

    /**
     * Moves on from [step] — to the next one, or out of the wizard when there is no next one.
     *
     * Both Next and Skip come through here, because on the last step they mean the same thing.
     * Skipping the co-parent invitation is a supported outcome, not a dead end: it leaves the
     * parent unpaired on Home, where the app's own "connect your co-parent" prompt lives. An
     * earlier version advanced blindly and left Skip on that step doing nothing at all.
     */
    private fun leaveStep(step: OnboardingStep) {
        if (step.isLast) {
            finish()
            return
        }
        _uiState.update { state ->
            val following = OnboardingStep.entries.getOrNull(state.step.ordinal + 1)
            following?.let { state.copy(step = it) } ?: state
        }
    }

    /** Runs [block] off the UI, logging rather than surfacing a failure. See [next]. */
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                Log.e(TAG, "Failed to save a wizard step", e)
            }
        }
    }

    /**
     * Copies the five fields this wizard owns onto the parent's current row.
     *
     * `partnerId`, `fcmToken`, `role` and `colorCode` come from the fresh read, never from
     * [state] — see this class's doc, and `ProfileViewModel.save`, which learned it the hard way.
     */
    private suspend fun saveProfile(state: OnboardingUiState) {
        val fresh = userRepository.getCurrentUser() ?: return
        userRepository.updateUser(
            fresh.copy(
                name = state.name.trim(),
                dateOfBirth = state.dateOfBirth,
                phone = state.phone.ifBlank { null },
                allergies = state.allergies,
                medicalProfile = state.medicalProfile
            )
        )
    }

    /**
     * Writes the child's answers, and the relatives, onto one record.
     *
     * Both the child step and the relatives step call this, so the two never create separate
     * rows. Nothing is written for a blank child name: a nameless child is not creatable
     * anywhere else in the app — `AddEditChildInfoScreen` refuses to save one — and it would
     * appear in the child list as an empty row nobody could identify.
     */
    private suspend fun saveChild(state: OnboardingUiState) {
        val name = state.childName.trim()
        if (name.isBlank()) return

        val uid = userRepository.getCurrentUserId()
        val now = LocalDateTime.now()
        val existing = childInfoId?.let { childInfoRepository.getChildInfoById(it) }
        val id = existing?.id ?: childInfoId ?: UUID.randomUUID().toString()
        childInfoId = id

        val base = existing ?: ChildInfo(
            id = id,
            childName = name,
            dateOfBirth = state.childDateOfBirth?.atStartOfDay(),
            createdAt = now,
            updatedAt = now
        )
        childInfoRepository.upsertChildInfo(
            base.copy(
                childName = name,
                dateOfBirth = state.childDateOfBirth?.atStartOfDay(),
                allergies = state.childAllergies,
                medicalProfile = state.childMedicalProfile,
                emergencyContacts = state.relatives,
                updatedAt = now,
                createdByFirebaseUid = base.createdByFirebaseUid ?: uid,
                lastModifiedBy = uid,
                syncedToFirestore = false
            )
        )
    }

    private companion object {
        const val TAG = "OnboardingViewModel"

        /** This text unless it is blank, in which case [stored] — but never a blank [stored]. */
        fun String.orStored(stored: String?): String =
            takeIf { it.isNotBlank() } ?: stored?.takeIf { it.isNotBlank() } ?: this

        /** This list unless it is empty, in which case [stored]. */
        fun <T> List<T>.orStored(stored: List<T>?): List<T> =
            takeIf { it.isNotEmpty() } ?: stored.orEmpty()

        /** This profile unless nothing has been entered into it, in which case [stored]. */
        fun MedicalProfile.orStored(stored: MedicalProfile?): MedicalProfile =
            takeIf { it != MedicalProfile() } ?: stored ?: this
    }
}
