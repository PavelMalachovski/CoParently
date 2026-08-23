package com.coparently.app.presentation.pets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.Medication
import com.coparently.app.domain.model.Pet
import com.coparently.app.domain.model.PetSpecies
import com.coparently.app.domain.model.Vaccination
import com.coparently.app.presentation.childinfo.components.DatePickerDialog
import com.coparently.app.presentation.childinfo.components.MedicationEditor
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.common.PhotoStrip
import com.coparently.app.presentation.common.PhotoStripStrings
import com.coparently.app.presentation.common.VaccinationListEditor
import com.coparently.app.presentation.common.labelRes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Screen for adding or editing a pet.
 *
 * Mirrors `AddEditChildInfoScreen`: local per-field state hydrated in a
 * `LaunchedEffect(currentPet)`, photographs held until save, and a copy-based save that
 * preserves sync/ownership stamps rather than rebuilding the record from defaults.
 *
 * @param petId ID of the pet to edit, or "new" for creating one
 * @param onNavigateBack Navigation callback
 * @param viewModel ViewModel for pet operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPetScreen(
    petId: String?,
    onNavigateBack: () -> Unit,
    viewModel: PetsViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val isNewPet = petId == null || petId == "new"

    var name by remember { mutableStateOf("") }
    var species by remember { mutableStateOf(PetSpecies.OTHER) }
    var breed by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf<LocalDateTime?>(null) }
    var medications by remember { mutableStateOf<List<Medication>>(emptyList()) }
    var vaccinations by remember { mutableStateOf<List<Vaccination>>(emptyList()) }
    var specialNeeds by remember { mutableStateOf("") }
    var feedingNotes by remember { mutableStateOf("") }
    var vetName by remember { mutableStateOf("") }
    var vetPhone by remember { mutableStateOf("") }
    var storedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var removedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveCompleted by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(petId) {
        if (petId != null && petId != "new") {
            viewModel.loadPetById(petId)
        }
    }

    val currentPet by viewModel.currentPet.collectAsState()

    LaunchedEffect(currentPet) {
        currentPet?.let { pet ->
            name = pet.name
            species = pet.species
            breed = pet.breed ?: ""
            dateOfBirth = pet.dateOfBirth
            medications = pet.medications
            vaccinations = pet.vaccinations
            specialNeeds = pet.specialNeeds ?: ""
            feedingNotes = pet.feedingNotes ?: ""
            vetName = pet.vetName ?: ""
            vetPhone = pet.vetPhone ?: ""
            storedPhotos = pet.photos
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { pickedPhotos = pickedPhotos + it.toString() }
    }

    val photoError by viewModel.photoError.collectAsState()
    val uploadFailed = stringResource(R.string.pet_photos_upload_failed)
    val deleteFailed = stringResource(R.string.pet_photos_delete_failed)
    LaunchedEffect(photoError) {
        val message = when (photoError) {
            PetPhotoError.UPLOAD_FAILED -> uploadFailed
            PetPhotoError.DELETE_FAILED -> deleteFailed
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearPhotoError()
        }
    }

    LaunchedEffect(saveCompleted, isSaving) {
        if (saveCompleted && !isSaving) {
            onNavigateBack()
        }
    }

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

    if (showDeleteConfirm) {
        val pet = currentPet
        ConfirmationDialog(
            title = stringResource(R.string.pet_delete_title, name),
            message = stringResource(R.string.pet_delete_message),
            confirmText = stringResource(R.string.pet_delete_confirm),
            dismissText = stringResource(R.string.pet_delete_cancel),
            isDestructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                if (pet != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.deletePet(pet)
                    saveCompleted = true
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNewPet) stringResource(R.string.pet_title_add)
                        else stringResource(R.string.pet_title_edit)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pets_back)
                        )
                    }
                },
                actions = {
                    if (!isNewPet && currentPet != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.pet_delete_confirm),
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
            BasicSection(
                name = name,
                onName = { name = it },
                species = species,
                onSpecies = { species = it },
                breed = breed,
                onBreed = { breed = it },
                dateOfBirth = dateOfBirth,
                onPickDate = { showDatePicker = true },
                enabled = !isSaving
            )

            SectionCard(title = stringResource(R.string.pet_section_medications)) {
                MedicationEditor(
                    medications = medications,
                    onAdd = { medications = medications + it },
                    onEdit = { index, m ->
                        medications = medications.toMutableList().apply { set(index, m) }
                    },
                    onRemove = { index ->
                        medications = medications.toMutableList().apply { removeAt(index) }
                    }
                )
            }

            SectionCard(title = stringResource(R.string.pet_section_vaccinations)) {
                VaccinationListEditor(
                    vaccinations = vaccinations,
                    onAdd = { vaccinations = vaccinations + it },
                    onRemove = { index ->
                        vaccinations = vaccinations.toMutableList().apply { removeAt(index) }
                    },
                    enabled = !isSaving
                )
            }

            SectionCard(title = stringResource(R.string.pet_section_special_needs)) {
                OutlinedTextField(
                    value = specialNeeds,
                    onValueChange = { specialNeeds = it },
                    label = { Text(stringResource(R.string.pet_special_needs_label)) },
                    placeholder = { Text(stringResource(R.string.pet_special_needs_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    minLines = 2,
                    maxLines = 4
                )
            }

            SectionCard(title = stringResource(R.string.pet_section_feeding)) {
                OutlinedTextField(
                    value = feedingNotes,
                    onValueChange = { feedingNotes = it },
                    label = { Text(stringResource(R.string.pet_feeding_label)) },
                    placeholder = { Text(stringResource(R.string.pet_feeding_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    minLines = 2,
                    maxLines = 4
                )
            }

            SectionCard(title = stringResource(R.string.pet_section_vet)) {
                OutlinedTextField(
                    value = vetName,
                    onValueChange = { vetName = it },
                    label = { Text(stringResource(R.string.pet_vet_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    singleLine = true
                )
                OutlinedTextField(
                    value = vetPhone,
                    onValueChange = { vetPhone = it },
                    label = { Text(stringResource(R.string.pet_vet_phone_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    singleLine = true
                )
            }

            SectionCard(title = stringResource(R.string.pet_section_photos)) {
                PhotoStrip(
                    photos = storedPhotos.filterNot { it in removedPhotos } + pickedPhotos,
                    strings = petPhotoStripStrings(),
                    onAdd = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemove = { photo ->
                        if (photo in pickedPhotos) {
                            pickedPhotos = pickedPhotos - photo
                        } else {
                            removedPhotos = removedPhotos + photo
                        }
                    },
                    enabled = !isSaving
                )
            }

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (name.isNotBlank()) {
                        isSaving = true
                        val base = currentPet.takeIf { !isNewPet }
                        val now = LocalDateTime.now()
                        val resolvedId = base?.id ?: petId?.takeIf { it != "new" }
                            ?: UUID.randomUUID().toString()
                        val pet = (
                            base ?: Pet(id = resolvedId, name = name, createdAt = now, updatedAt = now)
                            ).copy(
                            name = name,
                            species = species,
                            breed = breed.ifBlank { null },
                            dateOfBirth = dateOfBirth,
                            medications = medications,
                            vaccinations = vaccinations,
                            specialNeeds = specialNeeds.ifBlank { null },
                            feedingNotes = feedingNotes.ifBlank { null },
                            vetName = vetName.ifBlank { null },
                            vetPhone = vetPhone.ifBlank { null },
                            updatedAt = now
                        )
                        viewModel.upsertPetWithPhotos(
                            pet = pet,
                            isNewPet = isNewPet,
                            newPhotoUris = pickedPhotos,
                            removedPhotoUrls = removedPhotos
                        )
                        saveCompleted = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && !isSaving
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
                    if (isNewPet) stringResource(R.string.pet_add_button)
                    else stringResource(R.string.pet_save_changes)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Name, species, breed and date of birth. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasicSection(
    name: String,
    onName: (String) -> Unit,
    species: PetSpecies,
    onSpecies: (PetSpecies) -> Unit,
    breed: String,
    onBreed: (String) -> Unit,
    dateOfBirth: LocalDateTime?,
    onPickDate: () -> Unit,
    enabled: Boolean
) {
    SectionCard(title = stringResource(R.string.pet_section_basic)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.pet_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true
        )

        SpeciesDropdown(species = species, onSpecies = onSpecies, enabled = enabled)

        OutlinedTextField(
            value = breed,
            onValueChange = onBreed,
            label = { Text(stringResource(R.string.pet_breed_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true
        )

        OutlinedButton(
            onClick = onPickDate,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            Text(
                text = dateOfBirth?.let {
                    stringResource(
                        R.string.pet_dob_value,
                        it.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    )
                } ?: stringResource(R.string.pet_select_dob)
            )
        }
    }
}

/** Species picker over all [PetSpecies], each rendered through its localized label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeciesDropdown(
    species: PetSpecies,
    onSpecies: (PetSpecies) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = stringResource(species.labelRes()),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.pet_species_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PetSpecies.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        onSpecies(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** A titled card wrapping one section of the form — mirrors the child editor's cards. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The pet-photo string set for the shared [PhotoStrip]. */
@Composable
private fun petPhotoStripStrings() = PhotoStripStrings(
    title = R.string.pet_photos_title,
    empty = R.string.pet_photos_none,
    add = R.string.pet_photos_add,
    hint = R.string.pet_photos_hint,
    thumbnailDescription = R.string.pet_photos_thumbnail_description,
    open = R.string.pet_photos_open,
    remove = R.string.pet_photos_remove,
    close = R.string.pet_photos_close
)
