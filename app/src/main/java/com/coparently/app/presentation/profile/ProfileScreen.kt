package com.coparently.app.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.User
import com.coparently.app.presentation.childinfo.components.AllergyEditor
import com.coparently.app.presentation.childinfo.components.DatePickerDialog
import com.coparently.app.presentation.common.MedicalProfileEditor
import com.coparently.app.presentation.common.PillChip
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A parent's profile, used in two modes.
 *
 * `editable = true` shows the signed-in user's own record — name, date of birth, phone,
 * allergies and medical profile — with fields the user can change and a sticky Save button.
 * `editable = false` shows the co-parent's record read-only, with a note explaining why: the
 * co-parent's `users/{uid}` document cannot be written from this device (`firestore.rules`
 * allows only its owner to write it — pinned in `firestore-tests/rules/users-profile.test.js`),
 * so an editable rendering here would promise a write the server rejects.
 *
 * Stateless: all state lives in [ProfileViewModel]; this only renders and forwards events.
 *
 * @param editable Whether this is the signed-in user's own record (editable) or the
 *   co-parent's (read-only).
 * @param onNavigateUp Returns to the screen that opened this one.
 * @param viewModel Profile state and mutations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    editable: Boolean,
    onNavigateUp: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.profile_saved)

    // savedAt is an instant, not a draft field, so this fires once per save rather than once
    // per recomposition — the same reason it is epoch millis and not a LocalDateTime (see
    // CLAUDE.md's chat-sync entry on why that distinction matters even for a value that is
    // only ever displayed).
    LaunchedEffect(uiState.savedAt) {
        if (uiState.savedAt != null) {
            snackbarHostState.showSnackbar(savedMessage)
        }
    }

    val person = if (editable) uiState.me else uiState.coParent

    Scaffold(
        topBar = { ProfileTopBar(editable = editable, onNavigateUp = onNavigateUp) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (editable && person != null) {
                ProfileSaveBar(
                    isSaving = uiState.isSaving,
                    onSave = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.save()
                    }
                )
            }
        }
    ) { paddingValues ->
        ProfileContent(
            editable = editable,
            person = person,
            meUnavailable = uiState.meUnavailable,
            paddingValues = paddingValues,
            viewModel = viewModel
        )
    }
}

/** The top bar: a fixed title per mode, and a back arrow — no other actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTopBar(editable: Boolean, onNavigateUp: () -> Unit) {
    TopAppBar(
        title = {
            Text(stringResource(if (editable) R.string.profile_my_title else R.string.profile_coparent_title))
        },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
        }
    )
}

/** The scrollable body: the editable form, its loading beat, or the read-only co-parent view. */
@Composable
private fun ProfileContent(
    editable: Boolean,
    person: User?,
    meUnavailable: Boolean,
    paddingValues: PaddingValues,
    viewModel: ProfileViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (editable) {
            when {
                meUnavailable -> ProfileLoadFailed(onRetry = viewModel::retryLoadingMe)
                person == null -> ProfileLoadingIndicator()
                else -> MyProfileContent(
                    person = person,
                    onNameChange = viewModel::updateName,
                    onDateOfBirthChange = viewModel::updateDateOfBirth,
                    onPhoneChange = viewModel::updatePhone,
                    onAllergiesChange = viewModel::updateAllergies,
                    onMedicalProfileChange = viewModel::updateMedicalProfile
                )
            }
        } else {
            CoParentProfileContent(coParent = person)
        }
    }
}

/**
 * The sticky bottom Save button for the editable mode — same anatomy as the event form's
 * (see `AddEditEventScreen`): a full-width primary button on its own elevated surface, so it
 * stays reachable without scrolling back up.
 */
@Composable
private fun ProfileSaveBar(isSaving: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shadowElevation = 8.dp) {
        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(52.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.profile_save))
            }
        }
    }
}

/**
 * A centered spinner for the brief beat before [ProfileViewModel] resolves the signed-in
 * user's Room row on a cold start.
 */
@Composable
private fun ProfileLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Shown instead of [ProfileLoadingIndicator] once [ProfileViewModel] gives up waiting for the
 * signed-in user's Room row — either [com.coparently.app.domain.repository.UserRepository.ensureProfile]
 * is taking unusually long, or the name-less identity path never created a row at all. Either
 * way an endless spinner gave the user nothing to do; this gives them a retry.
 */
@Composable
private fun ProfileLoadFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.common_action_retry))
        }
    }
}

/** The signed-in user's own record: editable fields, allergies and the medical profile. */
@Composable
@Suppress("LongParameterList") // one form, one callback per editable field
private fun MyProfileContent(
    person: User,
    onNameChange: (String) -> Unit,
    onDateOfBirthChange: (LocalDate?) -> Unit,
    onPhoneChange: (String) -> Unit,
    onAllergiesChange: (List<String>) -> Unit,
    onMedicalProfileChange: (MedicalProfile) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    OutlinedTextField(
        value = person.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.profile_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(person.dateOfBirth?.format(dateFormatter) ?: stringResource(R.string.profile_dob_label))
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { dateTime ->
                onDateOfBirthChange(dateTime.toLocalDate())
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            initialDate = person.dateOfBirth?.atStartOfDay()
        )
    }

    OutlinedTextField(
        value = person.phone.orEmpty(),
        onValueChange = onPhoneChange,
        label = { Text(stringResource(R.string.profile_phone_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth()
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfileSectionLabel(stringResource(R.string.profile_allergies_label))
        AllergyEditor(
            allergies = person.allergies,
            onAdd = { entry -> onAllergiesChange(person.allergies + entry) },
            onRemove = { index ->
                onAllergiesChange(person.allergies.toMutableList().apply { removeAt(index) })
            }
        )
    }

    MedicalProfileEditor(
        profile = person.medicalProfile,
        onChange = onMedicalProfileChange,
        enabled = true
    )
}

/**
 * The co-parent's record, read-only.
 *
 * Three states, gated on what is actually known:
 * - [coParent] null: nobody is paired (or the pairing/profile read has not resolved yet) —
 *   [R.string.profile_not_paired].
 * - [coParent] known but carries none of the profile-specific fields yet (the co-parent has
 *   never opened their own profile screen) — [R.string.profile_coparent_empty]. The name is
 *   still shown: it comes from pairing itself, not from the profile questionnaire, so there is
 *   no reason to hide it behind the same empty state.
 * - [coParent] known and has filled in at least one field — the fields, allergies and medical
 *   profile, all rendered with no editing affordance ([MedicalProfileEditor]'s `enabled = false`).
 */
@Composable
private fun CoParentProfileContent(coParent: User?) {
    if (coParent == null) {
        ProfileEmptyState(stringResource(R.string.profile_not_paired))
        return
    }

    val coParentName = coParent.name.ifBlank { stringResource(R.string.profile_coparent_title) }

    SectionGroup {
        SectionRow(
            title = stringResource(R.string.profile_name_label),
            trailing = { ProfileValueText(coParentName) }
        )
    }

    Text(
        text = stringResource(R.string.profile_readonly_note, coParentName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (!coParent.hasProfileData()) {
        ProfileEmptyState(stringResource(R.string.profile_coparent_empty))
        return
    }

    CoParentDetailRows(coParent)
    CoParentAllergiesSection(coParent.allergies)
    MedicalProfileEditor(profile = coParent.medicalProfile, onChange = {}, enabled = false)
}

/**
 * The date-of-birth and phone rows, one per field the co-parent has actually filled in.
 *
 * There is no approved copy for "not set" in this screen's fixed sixteen-key string set, and a
 * co-parent can easily have filled in one field (say, a medical profile) without the other (a
 * phone number) — so a field with nothing to show gets no row at all, rather than a placeholder.
 */
@Composable
private fun CoParentDetailRows(coParent: User) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val detailRows = buildList {
        coParent.dateOfBirth?.format(dateFormatter)?.let { add(R.string.profile_dob_label to it) }
        coParent.phone?.takeIf { it.isNotBlank() }?.let { add(R.string.profile_phone_label to it) }
    }
    if (detailRows.isEmpty()) return

    SectionGroup {
        detailRows.forEachIndexed { index, (labelRes, value) ->
            SectionRow(title = stringResource(labelRes), trailing = { ProfileValueText(value) })
            if (index != detailRows.lastIndex) Divider()
        }
    }
}

/** The co-parent's allergies, as plain non-interactive chips, or the shared "nothing yet" text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoParentAllergiesSection(allergies: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfileSectionLabel(stringResource(R.string.profile_allergies_label))
        if (allergies.isEmpty()) {
            Text(
                text = stringResource(R.string.medical_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                allergies.forEach { allergy -> PillChip(label = allergy) }
            }
        }
    }
}

/** A field-group label above an editor or a read-only section, e.g. "Allergies". */
@Composable
private fun ProfileSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** The trailing value text for a read-only [SectionRow] field. */
@Composable
private fun ProfileValueText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** A centered, muted message filling the space where the profile fields would otherwise be. */
@Composable
private fun ProfileEmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    )
}

/**
 * Whether [this] carries any of the profile-specific fields a parent fills in on their own
 * profile screen — as opposed to the name, which arrives from pairing itself and is present
 * regardless. Used to tell "the co-parent has not filled this in yet" apart from "there is
 * nothing to show here at all".
 */
private fun User.hasProfileData(): Boolean =
    dateOfBirth != null ||
        !phone.isNullOrBlank() ||
        allergies.isNotEmpty() ||
        medicalProfile != MedicalProfile()
