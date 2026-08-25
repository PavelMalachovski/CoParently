package com.coparently.app.presentation.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.repository.FamilySettingsRepository
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.expenses.WHOLE_PERCENT
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.presentation.theme.ParentColorChoice
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.model.PetSpecies
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import com.coparently.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * What the family step opens pre-answered with.
 *
 * Children rather than nothing, so the step always has something to move on with and the very
 * first screen of the app is not a gate. A parent who has pets instead simply changes it.
 *
 * One definition, shared with the Settings dialog's seed: both are "what an unanswered parent is
 * offered", and the two drifting apart is how one of them ends up offering the co-parent's answer.
 */
private val DEFAULT_CARES_FOR = FamilyKind.DEFAULT

/** Half each, which is what a family splits by until they agree otherwise. */
private const val EVEN_SPLIT_PERCENT = 50

/**
 * One child the wizard is setting up.
 *
 * A **list** of these rather than the flat `childName`/`childDateOfBirth`/… fields this state
 * used to hold, because a family with two children could not say so: the wizard wrote one record
 * and the child list was the only place a second could be added, after the questionnaire had
 * already asked how the family works. It also silently mis-filed the emergency contacts — see
 * [relatives].
 *
 * **How many children a family has is never stored as an answer.** The wizard asks for names and
 * the count falls out of them. A stored "one or several" would be a fact that goes stale the
 * moment a second child arrives, and would then need a settings toggle to correct; a derived
 * count cannot disagree with the records. It is the same reasoning `FamilyKind` documents for
 * treating an unanswered account as "show everything".
 *
 * @property id The id the [ChildInfo] record will have, generated when the draft is created.
 *   Fixed up front rather than at save time so nothing has to be written back into a form the
 *   parent may be typing into, and so the child step and the relatives step address one record.
 * @property name The child's name; blank means this draft is never written
 * @property dateOfBirth The child's date of birth, or null while unanswered
 * @property allergies The child's allergies
 * @property medicalProfile The child's emergency medical profile
 * @property relatives Emergency contacts for **this** child. They live on the draft rather than
 *   beside it because that is where they live in the data model — `ChildInfo.emergencyContacts`
 *   — and a single flat list landed every contact on whichever child happened to be first.
 */
data class ChildDraft(
    val id: String,
    val name: String = "",
    val dateOfBirth: LocalDate? = null,
    val allergies: List<String> = emptyList(),
    val medicalProfile: MedicalProfile = MedicalProfile(),
    val relatives: List<EmergencyContact> = emptyList()
) {
    /** True while nothing has been entered, which is what makes a draft safe to replace. */
    val isBlank: Boolean
        get() = name.isBlank() && dateOfBirth == null && allergies.isEmpty() &&
            medicalProfile == MedicalProfile() && relatives.isEmpty()
}

/**
 * One pet the wizard is setting up, on the same terms as [ChildDraft].
 *
 * The pets screen has always been a genuine list — this is the wizard catching up with it.
 *
 * @property id The id the [Pet] record will have, generated when the draft is created
 * @property name The pet's name; blank means this draft is never written
 * @property species What kind of animal this is
 */
data class PetDraft(
    val id: String,
    val name: String = "",
    val species: PetSpecies = PetSpecies.DOG
) {
    /** True while nothing has been entered. See [ChildDraft.isBlank]. */
    val isBlank: Boolean get() = name.isBlank() && species == PetSpecies.DOG
}

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
 * @property children The children being set up, one [ChildDraft] each. Never empty: the step
 *   always has one form to render, and a draft nobody names is never written.
 * @property pets The pets, on the same terms as [children]
 * @property relativesForId Which child the relatives step is collecting contacts for, or null
 *   to let [relativesChild] fall back to the first named one
 * @property isSaving True while [OnboardingViewModel.finish]'s write is in flight
 * @property isFinished True once onboarding is recorded as complete and the host may leave
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Intro,
    val name: String = "",
    val dateOfBirth: LocalDate? = null,
    val phone: String = "",
    /**
     * The colour this parent wants to be drawn in, or null until they touch the swatches.
     *
     * Null rather than a pre-selected default so the wizard does not claim an answer nobody
     * gave: an untouched profile keeps whatever colour its slot has always drawn in, and
     * [OnboardingViewModel.saveProfile] leaves the stored value alone.
     */
    val parentColor: ParentColorChoice? = null,
    val allergies: List<String> = emptyList(),
    val medicalProfile: MedicalProfile = MedicalProfile(),
    val children: List<ChildDraft> = emptyList(),
    val pets: List<PetDraft> = emptyList(),
    val relativesForId: String? = null,
    val caresFor: Set<FamilyKind> = DEFAULT_CARES_FOR,
    /** Slot 1's share of a shared expense, as a whole percent. Half each until changed. */
    val splitMomPercent: Int = EVEN_SPLIT_PERCENT,
    val isSaving: Boolean = false,
    val isFinished: Boolean = false
) {
    /**
     * The steps this run will walk, given the family answer.
     *
     * Derived rather than stored: the answer can change on the [OnboardingStep.Family] step
     * itself, and a list captured at construction would keep asking about a child the parent has
     * just said they do not have.
     */
    val steps: List<OnboardingStep> get() = OnboardingStep.stepsFor(caresFor)

    /** This step's 1-based position in [steps], for the progress indicator. */
    val displayIndex: Int get() = steps.indexOf(step).coerceAtLeast(0) + 1

    /** How many steps this run has. Never `OnboardingStep.entries.size` — most runs are shorter. */
    val stepCount: Int get() = steps.size

    /** True on the step that ends the wizard; leaving it, by any button, finishes onboarding. */
    val isLastStep: Boolean get() = step == steps.lastOrNull()

    /**
     * Only a blank parent name blocks progress.
     *
     * The questionnaire asks for a great deal — a blood group, hereditary conditions, a phone
     * number for an aunt — and it is collected for the parent's own benefit in an emergency.
     * Data gathered for someone's benefit must not lock them out of their calendar, so the one
     * thing that blocks is the one thing the app cannot work without.
     */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.Profile -> name.isNotBlank()
            // At least one kind, or the wizard cannot decide what to ask next.
            OnboardingStep.Family -> caresFor.isNotEmpty()
            else -> true
        }

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
    val canEditRelatives: Boolean get() = namedChildren.isNotEmpty()

    /** The children that have been named, which are the only ones anything is written for. */
    val namedChildren: List<ChildDraft> get() = children.filter { it.name.isNotBlank() }

    /**
     * The child the relatives step is collecting contacts for.
     *
     * Resolved rather than read straight off [relativesForId] so the selection heals itself: a
     * child whose name is cleared, or who is removed from the wizard, must not leave the step
     * pointing at a draft that no longer takes contacts.
     */
    val relativesChild: ChildDraft?
        get() = namedChildren.firstOrNull { it.id == relativesForId } ?: namedChildren.firstOrNull()
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
 * **The children are read once, not observed.** [prefill] takes the first emission of
 * [ChildInfoRepository.getAllChildInfo] and lets go. A screen-lifetime subscription to the whole
 * list is the exact shape of the `ChildInfoViewModel` defect CLAUDE.md records under "Known
 * issues", where a background sync tick re-emits the list and overwrites the form the user is
 * mid-edit on.
 *
 * **Children and pets are lists, and how many there are is never asked.** The steps collect
 * names and the count falls out of them — see [ChildDraft] for why a stored "one or several"
 * would be the wrong shape of answer.
 *
 * @param userRepository Reads and writes the signed-in parent's own record
 * @param childInfoRepository Reads and writes the child records the wizard fills in
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val childInfoRepository: ChildInfoRepository,
    private val petRepository: PetRepository,
    private val familySettingsRepository: FamilySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        // One blank draft of each kind, so both steps open with a form rather than an empty
        // list and an Add button. Neither is written unless it is named.
        OnboardingUiState(
            children = listOf(ChildDraft(id = newDraftId())),
            pets = listOf(PetDraft(id = newDraftId()))
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Serializes every write this wizard makes, so a read-modify-write cannot interleave with
     * another.
     *
     * All of them follow the same shape — read the current row, copy this form's fields onto it,
     * write it back — and two of them touch the same row: the profile step's save and [finish]'s
     * marker. Without this, a parent moving quickly from the profile step to the end could have
     * both read the pre-marker row, and whichever wrote second would silently drop the other's
     * field. The marker is the one that matters: losing it means the wizard reappears on the
     * next launch over data the parent has already entered.
     */
    private val writeLock = Mutex()

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
                val storedChildren = childInfoRepository.getAllChildInfo().first()
                val storedPets = petRepository.getAllPets().first()
                _uiState.update { state ->
                    state.copy(
                        name = state.name.orStored(user?.name),
                        dateOfBirth = state.dateOfBirth ?: user?.dateOfBirth,
                        phone = state.phone.orStored(user?.phone),
                        allergies = state.allergies.orStored(user?.allergies),
                        medicalProfile = state.medicalProfile.orStored(user?.medicalProfile),
                        // A stored answer wins over the default, but never over one the parent
                        // has already changed on the step itself.
                        caresFor = state.caresFor.takeIf { it != DEFAULT_CARES_FOR }
                            ?: user?.caresFor?.takeIf { it.isNotEmpty() }
                            ?: DEFAULT_CARES_FOR,
                        splitMomPercent = state.splitMomPercent.takeIf { it != EVEN_SPLIT_PERCENT }
                            ?: familySettingsRepository.agreedRatioOrDefault().momPercent,
                        // Whole-list, not field-by-field: with several drafts there is no
                        // honest way to merge a stored record into a form the parent may have
                        // started, so a touched list is left alone entirely. The race is
                        // theoretical — this runs at construction, while the intro step, which
                        // collects nothing, is what the parent is looking at.
                        children = state.children.orStoredChildren(storedChildren),
                        pets = state.pets.orStoredPets(storedPets)
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

    /** Records the colour picked on the profile step. */
    fun updateParentColor(choice: ParentColorChoice) =
        _uiState.update { it.copy(parentColor = choice) }

    /** Updates the parent's allergies. */
    fun updateAllergies(allergies: List<String>) = _uiState.update { it.copy(allergies = allergies) }

    /** Updates the parent's medical profile. */
    fun updateMedicalProfile(profile: MedicalProfile) =
        _uiState.update { it.copy(medicalProfile = profile) }

    /** Updates one child's name. */
    fun updateChildName(id: String, name: String) = updateChild(id) { it.copy(name = name) }

    /** Updates one child's date of birth. */
    fun updateChildDateOfBirth(id: String, date: LocalDate?) =
        updateChild(id) { it.copy(dateOfBirth = date) }

    /** Updates one child's allergies. */
    fun updateChildAllergies(id: String, allergies: List<String>) =
        updateChild(id) { it.copy(allergies = allergies) }

    /** Updates one child's medical profile. */
    fun updateChildMedicalProfile(id: String, profile: MedicalProfile) =
        updateChild(id) { it.copy(medicalProfile = profile) }

    /** Replaces one child's emergency contacts. */
    fun updateRelatives(id: String, relatives: List<EmergencyContact>) =
        updateChild(id) { it.copy(relatives = relatives) }

    /** Appends a blank child draft. Nothing is written for it until it is named. */
    fun addChild() = _uiState.update { it.copy(children = it.children + ChildDraft(id = newDraftId())) }

    /**
     * Takes a child out of the wizard, and deletes its record if one was already written.
     *
     * Dropping the draft alone would leave a child the parent has explicitly removed sitting in
     * the child list — the step saves on Next, so a draft removed after one is a record. The
     * delete is a no-op for a draft that never reached Room.
     *
     * It goes through [ChildInfoRepository.deleteChildInfo], which removes the Firestore document
     * outright instead of writing a tombstone. That is the defect CLAUDE.md records under "Known
     * issues" for the child editor's own Delete action, and this is the same call, not a new one:
     * fixing it there fixes it here. It is also the least harmful place for it — the record is
     * seconds old and the co-parent has almost certainly never seen it.
     */
    fun removeChild(id: String) {
        val removed = _uiState.value.children.firstOrNull { it.id == id } ?: return
        _uiState.update { state ->
            val remaining = state.children.filterNot { it.id == id }
            // Never leave the step with nothing to render.
            state.copy(children = remaining.ifEmpty { listOf(ChildDraft(id = newDraftId())) })
        }
        if (!removed.isBlank) {
            persist { childInfoRepository.getChildInfoById(id)?.let { childInfoRepository.deleteChildInfo(it) } }
        }
    }

    /** Which child the relatives step is collecting contacts for. */
    fun selectRelativesChild(id: String) = _uiState.update { it.copy(relativesForId = id) }

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
            OnboardingStep.Family -> persist { saveCaresFor(state) }
            OnboardingStep.Profile -> persist { saveProfile(state) }
            OnboardingStep.Child -> persist { saveChildren(state) }
            OnboardingStep.Relatives -> persist { saveChildren(state) }
            OnboardingStep.Pet -> persist { savePets(state) }
            OnboardingStep.Split -> persist {
                familySettingsRepository.submitRatio(
                    SplitRatio.ofMomPercent(state.splitMomPercent)
                )
            }
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
            val steps = state.steps
            val previous = steps.getOrNull(steps.indexOf(state.step) - 1)
            previous?.let { state.copy(step = it) } ?: state
        }
    }

    /**
     * Records whether this family is co-parenting children, pets or both.
     *
     * Written on Next like every other step rather than on tap, so backing out of the wizard
     * leaves nothing behind — and so the answer reaches the co-parent's device through the same
     * profile write as the rest.
     */
    fun setCaresFor(kinds: Set<FamilyKind>) {
        _uiState.update { it.copy(caresFor = kinds) }
    }

    /** One pet's name. */
    fun setPetName(id: String, value: String) = updatePet(id) { it.copy(name = value) }

    /** One pet's species. */
    fun setPetSpecies(id: String, value: PetSpecies) = updatePet(id) { it.copy(species = value) }

    /** Appends a blank pet draft. Nothing is written for it until it is named. */
    fun addPet() = _uiState.update { it.copy(pets = it.pets + PetDraft(id = newDraftId())) }

    /** Takes a pet out of the wizard, deleting its record if one was written. See [removeChild]. */
    fun removePet(id: String) {
        val removed = _uiState.value.pets.firstOrNull { it.id == id } ?: return
        _uiState.update { state ->
            val remaining = state.pets.filterNot { it.id == id }
            state.copy(pets = remaining.ifEmpty { listOf(PetDraft(id = newDraftId())) })
        }
        if (!removed.isBlank) {
            persist { petRepository.getPetById(id)?.let { petRepository.deletePet(it) } }
        }
    }

    /** Applies [transform] to the one child with [id], leaving the rest of the list alone. */
    private fun updateChild(id: String, transform: (ChildDraft) -> ChildDraft) =
        _uiState.update { state ->
            state.copy(children = state.children.map { if (it.id == id) transform(it) else it })
        }

    /** Applies [transform] to the one pet with [id]. See [updateChild]. */
    private fun updatePet(id: String, transform: (PetDraft) -> PetDraft) =
        _uiState.update { state ->
            state.copy(pets = state.pets.map { if (it.id == id) transform(it) else it })
        }

    /** The split step's share for slot 1, as a whole percent. */
    fun setSplitMomPercent(value: Int) {
        _uiState.update { it.copy(splitMomPercent = value.coerceIn(0, WHOLE_PERCENT)) }
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
                writeLock.withLock {
                    val fresh = userRepository.getCurrentUser()
                    if (fresh != null) {
                        userRepository.updateUser(
                            fresh.copy(onboardingCompletedAt = LocalDateTime.now().toString())
                        )
                    }
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
        if (_uiState.value.isLastStep) {
            finish()
            return
        }
        _uiState.update { state ->
            val steps = state.steps
            val following = steps.getOrNull(steps.indexOf(state.step) + 1)
            following?.let { state.copy(step = it) } ?: state
        }
    }

    /** Runs [block] off the UI, logging rather than surfacing a failure. See [next]. */
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                writeLock.withLock { block() }
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
     * Writes what the family co-parents onto the parent's own record.
     *
     * Its own write rather than folding it into [saveProfile], because the family step comes
     * *before* the profile step: the answer decides which steps follow, and waiting for the
     * profile save would leave it unstored for anyone who backs out in between.
     */
    private suspend fun saveCaresFor(state: OnboardingUiState) {
        val fresh = userRepository.getCurrentUser() ?: return
        userRepository.updateUser(fresh.copy(caresFor = state.caresFor))
    }

    /** Writes every named pet. See [saveChildren]. */
    private suspend fun savePets(state: OnboardingUiState) {
        state.pets.forEach { savePetDraft(it) }
    }

    /**
     * Writes one pet's record.
     *
     * Same shape as [saveChildDraft]: the draft's id **is** the record's id, so a second Next
     * updates rather than duplicating. Nothing is written for a blank name — a nameless pet is
     * not creatable anywhere else in the app, and would show as an unidentifiable row.
     */
    private suspend fun savePetDraft(draft: PetDraft) {
        val name = draft.name.trim()
        if (name.isBlank()) return

        val uid = userRepository.getCurrentUserId()
        val now = LocalDateTime.now()
        val existing = petRepository.getPetById(draft.id)

        // `copy()` onto whatever is stored, never a fresh object: the same field-preserving rule
        // the event and child editors follow, so ownership and sync stamps survive.
        val pet = (existing ?: Pet(id = draft.id, name = name, createdAt = now, updatedAt = now)).copy(
            name = name,
            species = draft.species,
            createdByFirebaseUid = existing?.createdByFirebaseUid ?: uid,
            lastModifiedBy = uid,
            syncedToFirestore = false,
            updatedAt = now
        )
        petRepository.upsertPet(pet)
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
                medicalProfile = state.medicalProfile,
                // Only when they actually chose. An untouched swatch strip must not overwrite a
                // colour the parent set in Settings on a previous run through this wizard.
                colorCode = state.parentColor?.storedCode ?: fresh.colorCode
            )
        )
    }

    /**
     * Writes every named child, each onto its own record.
     *
     * Both the child step and the relatives step call this, so the two never create separate
     * rows: the contacts a parent enters on the relatives step belong to a [ChildDraft] and are
     * written by the same pass that writes that child's name.
     */
    private suspend fun saveChildren(state: OnboardingUiState) {
        state.children.forEach { saveChildDraft(it) }
    }

    /**
     * Writes one child's record.
     *
     * Nothing is written for a blank name: a nameless child is not creatable anywhere else in
     * the app — `AddEditChildInfoScreen` refuses to save one — and it would appear in the child
     * list as an empty row nobody could identify.
     */
    private suspend fun saveChildDraft(draft: ChildDraft) {
        val name = draft.name.trim()
        if (name.isBlank()) return

        val uid = userRepository.getCurrentUserId()
        val now = LocalDateTime.now()
        val existing = childInfoRepository.getChildInfoById(draft.id)

        val base = existing ?: ChildInfo(
            id = draft.id,
            childName = name,
            dateOfBirth = draft.dateOfBirth?.atStartOfDay(),
            createdAt = now,
            updatedAt = now
        )
        childInfoRepository.upsertChildInfo(
            base.copy(
                childName = name,
                dateOfBirth = draft.dateOfBirth?.atStartOfDay(),
                allergies = draft.allergies,
                medicalProfile = draft.medicalProfile,
                emergencyContacts = draft.relatives,
                updatedAt = now,
                createdByFirebaseUid = base.createdByFirebaseUid ?: uid,
                lastModifiedBy = uid,
                syncedToFirestore = false
            )
        )
    }

    private companion object {
        const val TAG = "OnboardingViewModel"

        /** A fresh record id for a draft the parent has just opened. */
        fun newDraftId(): String = UUID.randomUUID().toString()

        /**
         * The drafts already on screen, unless none has been touched — then the stored records.
         *
         * Whole-list rather than field-by-field: with several drafts there is no honest way to
         * merge a stored record into a form the parent may have started typing into, so a list
         * with anything in it is left entirely alone.
         */
        fun List<ChildDraft>.orStoredChildren(stored: List<ChildInfo>): List<ChildDraft> =
            if (stored.isEmpty() || any { !it.isBlank }) {
                this
            } else {
                stored.map { child ->
                    ChildDraft(
                        id = child.id,
                        name = child.childName,
                        dateOfBirth = child.dateOfBirth?.toLocalDate(),
                        allergies = child.allergies,
                        medicalProfile = child.medicalProfile,
                        relatives = child.emergencyContacts
                    )
                }
            }

        /** The pet drafts on screen, unless none has been touched. See [orStoredChildren]. */
        fun List<PetDraft>.orStoredPets(stored: List<Pet>): List<PetDraft> =
            if (stored.isEmpty() || any { !it.isBlank }) {
                this
            } else {
                stored.map { PetDraft(id = it.id, name = it.name, species = it.species) }
            }

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
