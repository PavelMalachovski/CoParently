package com.coparently.app.presentation.childinfo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.*
import com.coparently.app.presentation.childinfo.components.*
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.MedicalProfileEditor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Screen for adding or editing child information.
 * Provides comprehensive form for entering child details, medications, activities, etc.
 *
 * @param childInfoId ID of the child info to edit, or "new" for creating new
 * @param onNavigateBack Navigation callback
 * @param viewModel ViewModel for child info operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditChildInfoScreen(
    childInfoId: String?,
    onNavigateBack: () -> Unit,
    viewModel: ChildInfoViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current

    // State for form fields
    var childName by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf<LocalDateTime?>(null) }
    var medications by remember { mutableStateOf<List<Medication>>(emptyList()) }
    var activities by remember { mutableStateOf<List<Activity>>(emptyList()) }
    var allergies by remember { mutableStateOf<List<String>>(emptyList()) }
    var medicalNotes by remember { mutableStateOf("") }
    var emergencyContacts by remember { mutableStateOf<List<EmergencyContact>>(emptyList()) }
    var schoolInfo by remember { mutableStateOf<SchoolInfo?>(null) }
    var medicalProfile by remember { mutableStateOf(MedicalProfile()) }
    // Photographs already on the record, those picked here and not yet uploaded, and those the
    // user asked to remove. Three lists rather than one, because the three have different
    // consequences on save: a picked URI is uploaded, a removed URL has its object deleted
    // *before* the reference goes, and a kept URL is left alone.
    var storedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var removedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSaving by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The ViewModel says when the write landed. This used to be a `saveCompleted && !isSaving`
    // guard over two local flags, and nothing ever cleared `isSaving`, so it never fired: the
    // form stayed disabled behind a spinner while the record had already been written to Room.
    val saveFailed = stringResource(R.string.childinfo_save_failed)
    LaunchedEffect(Unit) {
        viewModel.saveOutcome.collect { outcome ->
            when (outcome) {
                ChildSaveOutcome.SAVED -> {
                    // Cleared before leaving: a second Save would otherwise re-upload the same
                    // content URIs, orphaning objects in the bucket and doubling the strip.
                    pickedPhotos = emptyList()
                    removedPhotos = emptyList()
                    onNavigateBack()
                }
                ChildSaveOutcome.FAILED -> {
                    isSaving = false
                    snackbarHostState.showSnackbar(saveFailed)
                }
            }
        }
    }

    // Load existing child info if editing
    LaunchedEffect(childInfoId) {
        if (childInfoId != null && childInfoId != "new") {
            viewModel.loadChildInfoById(childInfoId)
        }
    }

    // Observe current child info for editing
    val currentChildInfo by viewModel.currentChildInfo.collectAsState()

    // Update form when child info loads
    LaunchedEffect(currentChildInfo) {
        currentChildInfo?.let { info ->
            childName = info.childName
            dateOfBirth = info.dateOfBirth
            medications = info.medications
            activities = info.activities
            allergies = info.allergies
            medicalNotes = info.medicalNotes ?: ""
            emergencyContacts = info.emergencyContacts
            schoolInfo = info.schoolInfo
            medicalProfile = info.medicalProfile
            storedPhotos = info.medicalPhotos
        }
    }

    if (showDeleteConfirm) {
        val child = currentChildInfo
        ConfirmationDialog(
            title = stringResource(R.string.childinfo_delete_title, child?.childName.orEmpty()),
            message = stringResource(R.string.childinfo_delete_message),
            confirmText = stringResource(R.string.childinfo_delete_confirm),
            dismissText = stringResource(R.string.childinfo_delete_cancel),
            isDestructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                if (child != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deleteChildInfo(child)
                    onNavigateBack()
                }
            }
        )
    }

    // The same picker the receipt and event-photo flows use. Nothing is uploaded here: the URI
    // is held until save, so a parent who backs out leaves nothing behind in the bucket.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { pickedPhotos = pickedPhotos + it.toString() }
    }

    // A failed upload or delete has to be said out loud: both leave the record in a state the
    // user did not ask for, and one of them leaves the photograph still attached.
    val photoError by viewModel.photoError.collectAsState()
    val uploadFailed = stringResource(R.string.medical_photos_upload_failed)
    val deleteFailed = stringResource(R.string.medical_photos_delete_failed)
    LaunchedEffect(photoError) {
        val message = when (photoError) {
            MedicalPhotoError.UPLOAD_FAILED -> uploadFailed
            MedicalPhotoError.DELETE_FAILED -> deleteFailed
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearPhotoError()
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { date ->
                dateOfBirth = date
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            initialDate = dateOfBirth
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (childInfoId == "new") {
                            stringResource(R.string.childinfo_title_add)
                        } else {
                            stringResource(R.string.childinfo_title_edit)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.childinfo_back)
                        )
                    }
                },
                actions = {
                    // `deleteChildInfo` has existed on the ViewModel since the feature shipped
                    // and had no caller: with one child a mis-added record was tolerable, with
                    // several it is a row nobody can remove. Same anatomy as the pet editor's.
                    if (childInfoId != null && childInfoId != "new" && currentChildInfo != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(
                                    R.string.childinfo_delete_action
                                ),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Basic Information Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_basic),
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = childName,
                        onValueChange = { childName = it },
                        label = { Text(stringResource(R.string.childinfo_child_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                        singleLine = true
                    )

                    // Date of Birth Picker
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving
                    ) {
                        Text(
                            text = if (dateOfBirth != null) {
                                stringResource(
                                    R.string.childinfo_dob_value,
                                    dateOfBirth!!.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                                )
                            } else {
                                stringResource(R.string.childinfo_select_dob)
                            }
                        )
                    }
                }
            }

            // Medications Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_medications),
                        style = MaterialTheme.typography.titleMedium
                    )

                    MedicationEditor(
                        medications = medications,
                        onAdd = { medication ->
                            medications = medications + medication
                        },
                        onEdit = { index, medication ->
                            medications = medications.toMutableList().apply {
                                set(index, medication)
                            }
                        },
                        onRemove = { index ->
                            medications = medications.toMutableList().apply {
                                removeAt(index)
                            }
                        }
                    )
                }
            }

            // Activities Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_activities),
                        style = MaterialTheme.typography.titleMedium
                    )

                    ActivityEditor(
                        activities = activities,
                        onAdd = { activity ->
                            activities = activities + activity
                        },
                        onEdit = { index, activity ->
                            activities = activities.toMutableList().apply {
                                set(index, activity)
                            }
                        },
                        onRemove = { index ->
                            activities = activities.toMutableList().apply {
                                removeAt(index)
                            }
                        }
                    )
                }
            }

            // Allergies Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_allergies),
                        style = MaterialTheme.typography.titleMedium
                    )

                    AllergyEditor(
                        allergies = allergies,
                        onAdd = { allergy ->
                            allergies = allergies + allergy
                        },
                        onRemove = { index ->
                            allergies = allergies.toMutableList().apply {
                                removeAt(index)
                            }
                        }
                    )
                }
            }

            // Medical Profile Section (blood type, intolerances, hereditary conditions, vaccinations)
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.medical_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    MedicalProfileEditor(
                        profile = medicalProfile,
                        onChange = { medicalProfile = it },
                        enabled = !isSaving
                    )
                }
            }

            // Medical Notes Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_medical_notes),
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = medicalNotes,
                        onValueChange = { medicalNotes = it },
                        label = { Text(stringResource(R.string.childinfo_medical_notes_label)) },
                        placeholder = { Text(stringResource(R.string.childinfo_medical_notes_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                        minLines = 3,
                        maxLines = 5
                    )

                    MedicalPhotoStrip(
                        photos = storedPhotos.filterNot { it in removedPhotos } + pickedPhotos,
                        onAdd = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = { photo ->
                            // A picked photograph is nowhere yet, so forgetting it is the whole
                            // removal. A stored one is only marked here — the object is deleted
                            // on save, before its URL leaves the record.
                            if (photo in pickedPhotos) {
                                pickedPhotos = pickedPhotos - photo
                            } else {
                                removedPhotos = removedPhotos + photo
                            }
                        },
                        enabled = !isSaving
                    )
                }
            }

            // Emergency Contacts Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_emergency_contacts),
                        style = MaterialTheme.typography.titleMedium
                    )

                    EmergencyContactEditor(
                        contacts = emergencyContacts,
                        onAdd = { contact ->
                            emergencyContacts = emergencyContacts + contact
                        },
                        onEdit = { index, contact ->
                            emergencyContacts = emergencyContacts.toMutableList().apply {
                                set(index, contact)
                            }
                        },
                        onRemove = { index ->
                            emergencyContacts = emergencyContacts.toMutableList().apply {
                                removeAt(index)
                            }
                        }
                    )
                }
            }

            // School Information Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.childinfo_section_school),
                        style = MaterialTheme.typography.titleMedium
                    )

                    SchoolInfoEditor(
                        schoolInfo = schoolInfo,
                        onSave = { info ->
                            schoolInfo = info
                        }
                    )
                }
            }

            // Save Button
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (childName.isNotBlank()) {
                        isSaving = true
                        // Preserve sync/ownership fields of the loaded child when editing;
                        // rebuilding from defaults would wipe createdByFirebaseUid/medicalProfile
                        // (the same trap AddEditEventScreen avoids for Event via existingEvent).
                        val isNewChild = childInfoId == null || childInfoId == "new"
                        val base = currentChildInfo.takeIf { !isNewChild }
                        val now = LocalDateTime.now()
                        val resolvedId = base?.id ?: childInfoId?.takeIf { it != "new" }
                            ?: UUID.randomUUID().toString()
                        val childInfo = (
                            base ?: ChildInfo(
                                id = resolvedId,
                                childName = childName,
                                dateOfBirth = dateOfBirth,
                                createdAt = now,
                                updatedAt = now
                            )
                            ).copy(
                            childName = childName,
                            dateOfBirth = dateOfBirth,
                            medications = medications,
                            activities = activities,
                            allergies = allergies,
                            medicalNotes = medicalNotes.ifBlank { null },
                            emergencyContacts = emergencyContacts,
                            schoolInfo = schoolInfo,
                            medicalProfile = medicalProfile,
                            updatedAt = now
                        )
                        viewModel.upsertChildInfoWithPhotos(
                            childInfo = childInfo,
                            isNewChild = isNewChild,
                            newPhotoUris = pickedPhotos,
                            removedPhotoUrls = removedPhotos
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = childName.isNotBlank() && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (childInfoId == "new") {
                        stringResource(R.string.childinfo_add_child_button)
                    } else {
                        stringResource(R.string.childinfo_save_changes)
                    }
                )
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
