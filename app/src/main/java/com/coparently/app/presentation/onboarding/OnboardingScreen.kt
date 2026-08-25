package com.coparently.app.presentation.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.FamilyKind
import com.coparently.app.domain.model.PetSpecies
import com.coparently.app.presentation.childinfo.components.AllergyEditor
import com.coparently.app.presentation.childinfo.components.DatePickerDialog
import com.coparently.app.presentation.childinfo.components.EmergencyContactEditor
import com.coparently.app.presentation.common.MedicalProfileEditor
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.labelRes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The first-run questionnaire: the parent's own details, their child's, the people who could
 * help in an emergency, the custody schedule and the co-parent's invitation.
 *
 * Two of the six steps render no form of their own. Custody and the co-parent invitation hand
 * off to `CustodySetupScreen` and `PairingScreen`, which already do those jobs properly and are
 * reachable from Settings anyway; duplicating them here would give the app two custody editors
 * to keep in step.
 *
 * Every data-collecting step carries the same one-line footnote, and the intro carries it in
 * full. It is what makes the questionnaire acceptable rather than intrusive: a parent asked for
 * their blood type without being told why would be right to close the app. The wording
 * deliberately does not claim the data is encrypted — it is not — and does not claim only they
 * can see it, because the co-parent can, by design.
 *
 * Stateless: everything lives in [OnboardingViewModel]; this renders and forwards.
 *
 * @param onFinished Leaves the wizard once onboarding has been recorded as complete
 * @param onOpenCustodySetup Opens the existing custody schedule editor
 * @param onOpenPairing Opens the existing co-parent invitation screen
 * @param viewModel Wizard state and mutations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenCustodySetup: () -> Unit,
    onOpenPairing: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) onFinished()
    }

    // The wizard is the start destination, so an unhandled back press would close the app from
    // step 4. Inside the wizard, back means "the previous question".
    BackHandler(enabled = uiState.step != OnboardingStep.Intro) { viewModel.back() }

    Scaffold(
        topBar = { OnboardingTopBar(state = uiState) },
        bottomBar = {
            OnboardingBottomBar(
                state = uiState,
                onBack = viewModel::back,
                onSkip = viewModel::skip,
                onNext = viewModel::next
            )
        }
    ) { padding ->
        OnboardingBody(
            state = uiState,
            viewModel = viewModel,
            onOpenCustodySetup = onOpenCustodySetup,
            onOpenPairing = onOpenPairing,
            padding = padding
        )
    }
}

/** Title and how far along the wizard is, so no step feels open-ended. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingTopBar(state: OnboardingUiState) {
    Column {
        TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
        // Counted over the steps this run will actually walk, not over the enum: a pets-only
        // family skips two steps, and "Step 3 of 8" over a six-step flow is a lie the progress
        // bar tells at the exact moment the parent is deciding whether to finish.
        LinearProgressIndicator(
            progress = { state.displayIndex.toFloat() / state.stepCount },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                R.string.onboarding_progress,
                state.displayIndex,
                state.stepCount
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** The scrolling body, switching on the current step. */
@Composable
private fun OnboardingBody(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onOpenCustodySetup: () -> Unit,
    onOpenPairing: () -> Unit,
    padding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        when (state.step) {
            OnboardingStep.Intro -> IntroStep()
            OnboardingStep.Family -> FamilyKindStep(state, viewModel)
            OnboardingStep.Pet -> PetStep(state, viewModel)
            OnboardingStep.Split -> SplitStep(state, viewModel)
            OnboardingStep.Profile -> ProfileStep(state, viewModel)
            OnboardingStep.Child -> ChildStep(state, viewModel)
            OnboardingStep.Relatives -> RelativesStep(state, viewModel)
            OnboardingStep.Custody -> HandOffStep(
                title = R.string.onboarding_custody_title,
                body = R.string.onboarding_custody_body,
                action = R.string.onboarding_custody_open,
                onOpen = onOpenCustodySetup
            )
            OnboardingStep.CoParent -> HandOffStep(
                title = R.string.onboarding_coparent_title,
                body = R.string.onboarding_coparent_body,
                action = R.string.onboarding_coparent_open,
                onOpen = onOpenPairing
            )
        }
    }
}

/**
 * The only screen that exists purely to explain, and it earns its place: a questionnaire that
 * asks a separated parent for a blood group without saying why reads as intrusive. It carries
 * the note in full, so the later steps need only the one-line footnote.
 */
@Composable
private fun IntroStep() {
    StepHeading(title = R.string.onboarding_intro_title)
    Text(
        text = stringResource(R.string.onboarding_intro_body),
        style = MaterialTheme.typography.bodyLarge
    )
}

/** The parent's own details — the only step with a field that blocks progress. */
@Composable
private fun ProfileStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(title = R.string.onboarding_profile_title)

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::updateName,
        label = { Text(stringResource(R.string.profile_name_label)) },
        // Explained, not flagged: a field nobody has touched yet must not be rendered in error
        // colours on the first screen a new parent sees. The disabled Next carries the rule.
        supportingText = { Text(stringResource(R.string.onboarding_profile_name_required)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    DateOfBirthField(
        date = state.dateOfBirth,
        onDateChange = viewModel::updateDateOfBirth
    )

    OutlinedTextField(
        value = state.phone,
        onValueChange = viewModel::updatePhone,
        label = { Text(stringResource(R.string.profile_phone_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth()
    )

    SectionHeading(title = R.string.profile_allergies_label)
    AllergyEditor(
        allergies = state.allergies,
        onAdd = { viewModel.updateAllergies(state.allergies + it) },
        onRemove = { index ->
            viewModel.updateAllergies(state.allergies.toMutableList().apply { removeAt(index) })
        }
    )

    MedicalProfileEditor(
        profile = state.medicalProfile,
        onChange = viewModel::updateMedicalProfile,
        enabled = true
    )

    Footnote()
}

/**
 * The child's details — name, date of birth, allergies and medical profile only.
 *
 * Deliberately not the whole `AddEditChildInfoScreen`: medications, activities and school are
 * not first-run questions, and a wizard that asks for a teacher's email before the calendar has
 * been seen once will be abandoned. They stay one tap away in Settings.
 */
@Composable
private fun ChildStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(title = R.string.onboarding_child_title, body = R.string.onboarding_child_body)

    OutlinedTextField(
        value = state.childName,
        onValueChange = viewModel::updateChildName,
        label = { Text(stringResource(R.string.childinfo_child_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    DateOfBirthField(
        date = state.childDateOfBirth,
        onDateChange = viewModel::updateChildDateOfBirth
    )

    SectionHeading(title = R.string.childinfo_section_allergies)
    AllergyEditor(
        allergies = state.childAllergies,
        onAdd = { viewModel.updateChildAllergies(state.childAllergies + it) },
        onRemove = { index ->
            viewModel.updateChildAllergies(
                state.childAllergies.toMutableList().apply { removeAt(index) }
            )
        }
    )

    MedicalProfileEditor(
        profile = state.childMedicalProfile,
        onChange = viewModel::updateChildMedicalProfile,
        enabled = true
    )

    Footnote()
}

/**
 * Children, pets, or both — the question that decides what the rest of the wizard asks.
 *
 * A multi-select rather than a choice of one: a separated family with a child and a dog has both,
 * and making them pick would hide a section they are already using. Nothing is hidden by silence
 * either — an account that never answered reads as "show everything", which is what every
 * upgrade is.
 *
 * Changeable afterwards from Settings → Family. Without that route, a family that gets a dog a
 * year later could never reach the pet records, which is design item 8 in reverse: not an
 * affordance promising a missing feature, but a built feature with no affordance.
 */
@Composable
private fun FamilyKindStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(title = R.string.onboarding_family_title, body = R.string.onboarding_family_body)

    SectionGroup {
        FamilyKind.entries.forEachIndexed { index, kind ->
            val selected = kind in state.caresFor
            SectionRow(
                icon = if (kind == FamilyKind.CHILDREN) Icons.Default.ChildCare else Icons.Default.Pets,
                title = stringResource(
                    if (kind == FamilyKind.CHILDREN) {
                        R.string.onboarding_family_children
                    } else {
                        R.string.onboarding_family_pets
                    }
                ),
                supporting = stringResource(
                    if (kind == FamilyKind.CHILDREN) {
                        R.string.onboarding_family_children_hint
                    } else {
                        R.string.onboarding_family_pets_hint
                    }
                ),
                onClick = {
                    viewModel.setCaresFor(
                        if (selected) state.caresFor - kind else state.caresFor + kind
                    )
                },
                trailing = {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { checked ->
                            viewModel.setCaresFor(
                                if (checked) state.caresFor + kind else state.caresFor - kind
                            )
                        }
                    )
                }
            )
            if (index != FamilyKind.entries.lastIndex) Divider()
        }
    }
}

/**
 * The pet's name and species — the pet equivalent of [ChildStep], and as short.
 *
 * Deliberately not the whole pet record: vaccinations, feeding notes and the vet's number are
 * collected on the pet screen afterwards, the same trade [ChildStep] makes by leaving out school
 * and activities. A first run that asks for everything is a first run people abandon.
 */
@Composable
private fun PetStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(title = R.string.onboarding_pet_title, body = R.string.onboarding_pet_body)

    OutlinedTextField(
        value = state.petName,
        onValueChange = viewModel::setPetName,
        label = { Text(stringResource(R.string.pet_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PetSpecies.entries.forEach { species ->
            FilterChip(
                selected = state.petSpecies == species,
                onClick = { viewModel.setPetSpecies(species) },
                label = { Text(stringResource(species.labelRes())) }
            )
        }
    }

    Footnote()
}

/**
 * How a shared expense divides between the two parents.
 *
 * Easier to agree now than after a month of expenses to re-argue, which is why it is here and
 * not only in Settings. Nobody has to confirm it at this point: pairing is the last step, so
 * there is no co-parent yet and the answer applies outright — from the moment there *is* one,
 * changing it becomes a proposal they have to accept.
 *
 * Skippable, like everything after the profile. Half each is what a family splits by until they
 * say otherwise, and that is what a skip leaves in place.
 */
@Composable
private fun SplitStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(title = R.string.onboarding_split_title, body = R.string.onboarding_split_body)

    // Named, because two bare numbers do not say which half is yours — and the answer is not
    // guessable: the stored share is slot 1's, and pairing decides which slot this device gets.
    // "You / Co-parent" is the only honest wording here, there being no co-parent to name yet.
    Text(
        text = stringResource(
            R.string.onboarding_split_value,
            state.splitMomPercent,
            SPLIT_WHOLE_PERCENT - state.splitMomPercent
        ),
        style = MaterialTheme.typography.headlineSmall
    )
    Slider(
        value = state.splitMomPercent.toFloat(),
        onValueChange = { viewModel.setSplitMomPercent(it.toInt()) },
        valueRange = 0f..SPLIT_WHOLE_PERCENT.toFloat(),
        steps = SPLIT_SLIDER_STEPS
    )

    Footnote()
}

/** A whole share, as a percent. */
private const val SPLIT_WHOLE_PERCENT = 100

/** Stops on the slider: every 5 %, which is nineteen stops between the two ends. */
private const val SPLIT_SLIDER_STEPS = 19

/**
 * The people who could collect the child or be called in an emergency.
 *
 * These are saved onto the **child's** record, not the parent's, because that is the document
 * both parents may write — so the co-parent can add to them. With no child named yet there is
 * nowhere honest to put a contact, so the step says so instead of collecting contacts it would
 * then drop.
 */
@Composable
private fun RelativesStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(
        title = R.string.onboarding_relatives_title,
        body = R.string.onboarding_relatives_body
    )

    if (state.canEditRelatives) {
        EmergencyContactEditor(
            contacts = state.relatives,
            onAdd = { viewModel.updateRelatives(state.relatives + it) },
            onEdit = { index, contact ->
                viewModel.updateRelatives(
                    state.relatives.toMutableList().apply { this[index] = contact }
                )
            },
            onRemove = { index ->
                viewModel.updateRelatives(
                    state.relatives.toMutableList().apply { removeAt(index) }
                )
            }
        )
        Footnote()
    } else {
        Text(
            text = stringResource(R.string.onboarding_relatives_needs_child),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A step that explains itself and then opens an existing screen — custody and the co-parent
 * invitation. No footnote: nothing is collected here, the screen it opens owns its own copy.
 */
@Composable
private fun HandOffStep(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes action: Int,
    onOpen: () -> Unit
) {
    StepHeading(title = title, body = body)
    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(action))
    }
}

/** A step's title, and the sentence under it where one exists. */
@Composable
private fun StepHeading(@StringRes title: Int, @StringRes body: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall
        )
        if (body != null) {
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A label over an editor that brings no label of its own. */
@Composable
private fun SectionHeading(@StringRes title: Int) {
    Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall)
}

/**
 * Item 4 of the brief, in one line, under every step that collects something.
 *
 * It must not say the data is encrypted (it is not) and must not say only the user can see it
 * (the co-parent can, by design). Either claim would be a promise the app does not keep.
 */
@Composable
private fun Footnote() {
    Text(
        text = stringResource(R.string.onboarding_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A date-of-birth button and its picker, shared by the parent step and the child step. */
@Composable
private fun DateOfBirthField(date: LocalDate?, onDateChange: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(date?.format(formatter) ?: stringResource(R.string.profile_dob_label))
    }
    if (showPicker) {
        DatePickerDialog(
            onDateSelected = {
                onDateChange(it.toLocalDate())
                showPicker = false
            },
            onDismiss = { showPicker = false },
            initialDate = date?.atStartOfDay()
        )
    }
}

/**
 * Back, Skip and Next.
 *
 * Skip is present on every step that may be left unanswered — which is every step except the
 * intro, which asks nothing, and the profile, whose name field the app cannot work without.
 */
@Composable
private fun OnboardingBottomBar(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.step != OnboardingStep.Intro) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.canSkip) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
            Button(
                onClick = onNext,
                enabled = state.canAdvance && !state.isSaving,
                modifier = Modifier.height(48.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        stringResource(
                            if (state.isLastStep) {
                                R.string.onboarding_finish
                            } else {
                                R.string.onboarding_next
                            }
                        )
                    )
                }
            }
        }
    }
}
