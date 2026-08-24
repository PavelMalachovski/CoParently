package com.coparently.app.presentation.childinfo

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Activity
import com.coparently.app.domain.guests.GuestGrant
import com.coparently.app.domain.guests.GuestGrantPolicy
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.EmergencyContact
import com.coparently.app.domain.model.MedicalProfile
import com.coparently.app.domain.model.Medication
import com.coparently.app.domain.model.SchoolInfo
import com.coparently.app.domain.model.Vaccination
import com.coparently.app.presentation.childinfo.components.GuestInviteSheet
import com.coparently.app.presentation.childinfo.components.MedicalPhotoStrip
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.GroupLabel
import com.coparently.app.presentation.common.SectionGroup
import com.coparently.app.presentation.common.SectionGroupScope
import com.coparently.app.presentation.common.SectionRow
import com.coparently.app.presentation.common.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Screen for displaying and managing child information.
 * Shows medications, activities, allergies, and other important data.
 *
 * @param onNavigateBack Callback for navigation back
 * @param onEditClick Callback for edit action
 * @param viewModel ViewModel for managing child info
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildInfoScreen(
    onNavigateBack: () -> Unit,
    onEditClick: (String) -> Unit,
    viewModel: ChildInfoViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val guestInvite by viewModel.guestInvite.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // A failed revoke has to be said out loud. The row vanishes from the list either way once
    // Room is written, so silence here would leave the parent believing access is gone.
    val revokeFailed by viewModel.guestRevokeFailed.collectAsState()
    val revokeFailedMessage = stringResource(R.string.guest_revoke_failed)
    LaunchedEffect(revokeFailed) {
        if (revokeFailed) {
            snackbarHostState.showSnackbar(revokeFailedMessage)
            viewModel.clearGuestRevokeFailed()
        }
    }

    GuestInviteSheet(
        state = guestInvite,
        onChooseDuration = viewModel::chooseGuestDuration,
        onCreate = viewModel::createGuestInvite,
        onShare = { link -> context.startActivity(guestShareIntent(context, link)) },
        onDismiss = viewModel::dismissGuestInvite
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.childinfo_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.childinfo_back)
                        )
                    }
                },
                actions = {
                    // The only manual sync trigger on this screen. Editing now happens by
                    // tapping a section below, not through a top-bar pencil.
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.syncChildInfo()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.childinfo_sync)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is ChildInfoUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ChildInfoUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.loadChildInfo()
                        }) {
                            Text(stringResource(R.string.childinfo_retry))
                        }
                    }
                }
                is ChildInfoUiState.Success -> {
                    if (state.childInfoList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.childinfo_empty_state),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEditClick("new")
                            }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.childinfo_title_add))
                            }
                        }
                    } else {
                        // Read from the list this screen already holds, rather than from
                        // `currentChildInfo`: that state belongs to the editor and is set by the
                        // child it was opened on. Feeding it from the head of the list is what
                        // let an edit of one child be saved over another.
                        state.childInfoList.firstOrNull()?.let { childInfo ->
                            ChildInfoContent(
                                childInfo = childInfo,
                                onEditClick = onEditClick,
                                onInviteGuest = { viewModel.openGuestInvite(childInfo.id) },
                                onRevokeGuest = { uid -> viewModel.revokeGuest(childInfo, uid) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content displaying detailed child information as tappable, grouped sections.
 *
 * Every row opens the single editor for [childInfo] via [onEditClick] — this screen is a
 * read-only summary, the pencil that used to hide editing in the top bar is gone. Each group
 * mirrors one of the old `Card` + `SectionHeader` pairs, rebuilt on [GroupLabel] and
 * [SectionGroup]/[SectionRow] from the shared design system.
 */
@Composable
private fun ChildInfoContent(
    childInfo: ChildInfo,
    onEditClick: (String) -> Unit,
    onInviteGuest: () -> Unit,
    onRevokeGuest: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val onRowClick: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onEditClick(childInfo.id)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { BasicInfoGroup(childInfo = childInfo, onClick = onRowClick) }

        if (childInfo.medications.isNotEmpty()) {
            item { MedicationsGroup(medications = childInfo.medications, onClick = onRowClick) }
        }
        if (childInfo.activities.isNotEmpty()) {
            item { ActivitiesGroup(activities = childInfo.activities, onClick = onRowClick) }
        }
        if (childInfo.allergies.isNotEmpty()) {
            item { AllergiesGroup(allergies = childInfo.allergies, onClick = onRowClick) }
        }
        childInfo.medicalNotes?.let { notes ->
            item { MedicalNotesGroup(notes = notes, onClick = onRowClick) }
        }
        if (childInfo.medicalPhotos.isNotEmpty()) {
            // Beside the notes, because that is what they are: what the doctor said, in the form
            // a parent could actually capture it in. Read-only here — the strip becomes an editor
            // only when the same composable is given add and remove callbacks.
            item { MedicalPhotoStrip(photos = childInfo.medicalPhotos) }
        }
        if (childInfo.emergencyContacts.isNotEmpty()) {
            item {
                EmergencyContactsGroup(contacts = childInfo.emergencyContacts, onClick = onRowClick)
            }
        }
        childInfo.schoolInfo?.let { school ->
            item { SchoolGroup(schoolInfo = school, onClick = onRowClick) }
        }
        item { MedicalDetailsGroup(profile = childInfo.medicalProfile, onClick = onRowClick) }
        item {
            GuestAccessGroup(
                guests = childInfo.guests,
                onInviteGuest = onInviteGuest,
                onRevokeGuest = onRevokeGuest
            )
        }
    }
}

/**
 * Letting somebody outside the pair read this record.
 *
 * Last, below the medical details, because it is about the record rather than part of it —
 * and because the parent should have seen what they are about to share before they are
 * offered the button that shares it.
 */
@Composable
private fun GuestAccessGroup(
    guests: Map<String, GuestGrant>,
    onInviteGuest: () -> Unit,
    onRevokeGuest: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // Only the grants that are still good. An expired one is not somebody to revoke — the
    // rule already refuses them and the sweep removes them — and offering a destructive
    // action that does nothing is worse than not listing them at all.
    val active = remember(guests) {
        GuestGrantPolicy.active(guests.values.toList(), Instant.now())
    }
    var pendingRevoke by remember { mutableStateOf<GuestGrant?>(null) }

    Column {
        GroupLabel(stringResource(R.string.guest_section_label))
        SectionGroup {
            active.forEach { grant ->
                // Red, and it confirms: the sign-out anatomy, because the row's whole action
                // is destructive and a row is easier to hit by accident than a button.
                SectionRow(
                    icon = Icons.Default.PersonRemove,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = grant.name,
                    titleColor = MaterialTheme.colorScheme.error,
                    supporting = stringResource(
                        R.string.guest_access_until,
                        localDate(grant.expiresAtMillis)
                    ),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pendingRevoke = grant
                    }
                )
                Divider()
            }
            SectionRow(
                icon = Icons.Default.PersonAdd,
                title = stringResource(R.string.guest_invite_row_title),
                supporting = stringResource(R.string.guest_invite_row_supporting),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onInviteGuest()
                }
            )
        }
    }

    pendingRevoke?.let { grant ->
        ConfirmationDialog(
            title = stringResource(R.string.guest_revoke_title, grant.name),
            message = stringResource(R.string.guest_revoke_message),
            confirmText = stringResource(R.string.guest_revoke_confirm),
            dismissText = stringResource(R.string.guest_revoke_cancel),
            isDestructive = true,
            onDismiss = { pendingRevoke = null },
            onConfirm = {
                pendingRevoke = null
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevokeGuest(grant.uid)
            }
        )
    }
}

/** An epoch-millis instant as a date in the reader's own zone. */
private fun localDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** Builds the share-sheet intent for a guest link. */
private fun guestShareIntent(context: Context, link: String): Intent {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.guest_invite_share_message, link))
    }
    return Intent.createChooser(sendIntent, context.getString(R.string.guest_invite_share))
}

/** The child's identity: name, and date of birth when recorded. */
@Composable
private fun BasicInfoGroup(childInfo: ChildInfo, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_basic))
        SectionGroup {
            SectionRow(
                icon = Icons.Default.ChildCare,
                title = childInfo.childName,
                supporting = childInfo.dateOfBirth?.let { dob ->
                    stringResource(R.string.childinfo_dob_value, dob.toLocalDate().toString())
                },
                onClick = onClick
            )
        }
    }
}

/** One row per medication: its name as the title, dosage and frequency as the summary line. */
@Composable
private fun MedicationsGroup(medications: List<Medication>, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_medications))
        SectionGroup {
            medications.forEachIndexed { index, medication ->
                SectionRow(
                    icon = Icons.Default.Favorite,
                    title = medication.name,
                    supporting = stringResource(
                        R.string.childinfo_medication_summary,
                        medication.dosage,
                        medication.frequency
                    ),
                    onClick = onClick
                )
                if (index != medications.lastIndex) Divider()
            }
        }
    }
}

/** One row per activity: its name as the title, its schedule as the summary line. */
@Composable
private fun ActivitiesGroup(activities: List<Activity>, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_activities))
        SectionGroup {
            activities.forEachIndexed { index, activity ->
                SectionRow(
                    icon = Icons.Default.Star,
                    title = activity.name,
                    supporting = activity.schedule,
                    onClick = onClick
                )
                if (index != activities.lastIndex) Divider()
            }
        }
    }
}

/** One row per known allergy. */
@Composable
private fun AllergiesGroup(allergies: List<String>, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_allergies))
        SectionGroup {
            allergies.forEachIndexed { index, allergy ->
                SectionRow(icon = Icons.Default.Warning, title = allergy, onClick = onClick)
                if (index != allergies.lastIndex) Divider()
            }
        }
    }
}

/** The free-text medical notes field, as a single row. */
@Composable
private fun MedicalNotesGroup(notes: String, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_medical_notes))
        SectionGroup {
            SectionRow(icon = Icons.Default.Info, title = notes, onClick = onClick)
        }
    }
}

/** One row per emergency contact: their name as the title, phone number as the summary line. */
@Composable
private fun EmergencyContactsGroup(contacts: List<EmergencyContact>, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_emergency_contacts))
        SectionGroup {
            contacts.forEachIndexed { index, contact ->
                SectionRow(
                    icon = Icons.Default.Phone,
                    title = contact.name,
                    supporting = stringResource(R.string.childinfo_contact_phone_marker, contact.phone),
                    onClick = onClick
                )
                if (index != contacts.lastIndex) Divider()
            }
        }
    }
}

/** The school: its name as the title, address (falling back to grade) as the summary line. */
@Composable
private fun SchoolGroup(schoolInfo: SchoolInfo, onClick: () -> Unit) {
    Column {
        GroupLabel(stringResource(R.string.childinfo_section_school))
        SectionGroup {
            SectionRow(
                icon = Icons.Default.Place,
                title = schoolInfo.name,
                supporting = schoolInfo.address ?: schoolInfo.grade,
                onClick = onClick
            )
        }
    }
}

/**
 * The medical profile: blood type (always shown, falling back to "not recorded") plus
 * intolerances, hereditary conditions and vaccinations, each skipped entirely when its list is
 * empty. Never renders `BloodType.name` — the notation is locale-dependent, which is why the
 * blood type goes through [labelRes] instead.
 */
@Composable
private fun MedicalDetailsGroup(profile: MedicalProfile, onClick: () -> Unit) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val bloodTypeText = profile.bloodType?.let { stringResource(it.labelRes()) }
        ?: stringResource(R.string.medical_blood_type_not_set)

    Column {
        GroupLabel(stringResource(R.string.medical_section_title))
        SectionGroup {
            SectionRow(
                icon = Icons.Default.Bloodtype,
                title = stringResource(R.string.medical_blood_type_label),
                onClick = onClick,
                trailing = {
                    Text(
                        text = bloodTypeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            if (profile.intolerances.isNotEmpty()) {
                Divider()
                StringListRows(items = profile.intolerances, icon = Icons.Default.NoFood, onClick = onClick)
            }
            if (profile.hereditaryConditions.isNotEmpty()) {
                Divider()
                StringListRows(
                    items = profile.hereditaryConditions,
                    icon = Icons.Default.FamilyRestroom,
                    onClick = onClick
                )
            }
            if (profile.vaccinations.isNotEmpty()) {
                Divider()
                VaccinationRows(vaccinations = profile.vaccinations, dateFormatter = dateFormatter, onClick = onClick)
            }
        }
    }
}

/**
 * One row per plain string entry (intolerances, hereditary conditions), split out of
 * [MedicalDetailsGroup] to keep it under the project's method-length limit.
 */
@Composable
private fun SectionGroupScope.StringListRows(items: List<String>, icon: ImageVector, onClick: () -> Unit) {
    items.forEachIndexed { index, item ->
        SectionRow(icon = icon, title = item, onClick = onClick)
        if (index != items.lastIndex) Divider()
    }
}

/** One row per vaccination: its name as the title, its date (or "not recorded") as the summary line. */
@Composable
private fun SectionGroupScope.VaccinationRows(
    vaccinations: List<Vaccination>,
    dateFormatter: DateTimeFormatter,
    onClick: () -> Unit
) {
    vaccinations.forEachIndexed { index, vaccination ->
        SectionRow(
            icon = Icons.Default.Vaccines,
            title = vaccination.name,
            supporting = vaccination.date?.format(dateFormatter)
                ?: stringResource(R.string.medical_vaccination_no_date),
            onClick = onClick
        )
        if (index != vaccinations.lastIndex) Divider()
    }
}
