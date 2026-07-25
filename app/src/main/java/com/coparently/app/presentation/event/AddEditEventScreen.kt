package com.coparently.app.presentation.event

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.components.TimePickerDialog
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import com.coparently.app.utils.ValidationResult
import com.coparently.app.utils.ValidationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Reminder lead-time options offered in the event form, in minutes before the
 * event start (null = no reminder). Stored as [Event.reminderMinutes].
 */
private val REMINDER_OPTIONS: List<Pair<Int?, String>> = listOf(
    null to "None",
    10 to "10 min",
    30 to "30 min",
    60 to "1 hour",
    120 to "2 hours",
    1440 to "1 day"
)

/**
 * Screen for adding or editing an event.
 * Modernized with Material 3 design, date/time pickers, and validation.
 * Based on Design Roadmap Day 3: Forms & Input.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddEditEventScreen(
    eventId: String?,
    initialDate: LocalDate? = null,
    initialHour: Int? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRequestChange: ((String) -> Unit)? = null,
    viewModel: EventViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var parentOwner by remember { mutableStateOf("mom") }
    var eventType by remember { mutableStateOf("general") }
    // Snapshot of the loaded event so saving preserves sync/sharing fields
    var existingEvent by remember { mutableStateOf<Event?>(null) }
    // MVP1 fields: recurrence, reminder, privacy
    var recurrencePattern by remember { mutableStateOf<String?>(null) }
    var recurrenceEndDate by remember { mutableStateOf<LocalDate?>(null) }
    var showRecurrenceEndPicker by remember { mutableStateOf(false) }
    var reminderMinutes by remember { mutableStateOf<Int?>(null) }
    var isPrivate by remember { mutableStateOf(false) }
    // Event photo: URL of an already-attached image, and a freshly picked local image
    // (not yet uploaded). Picking a new one supersedes the existing image on save.
    var existingImageUrl by remember { mutableStateOf<String?>(null) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    // Custom event types are read from encrypted prefs; load them off the main thread
    // so opening the screen isn't blocked by the (synchronous) decrypt on first composition.
    var customEventTypes by remember { mutableStateOf<List<String>>(emptyList()) }
    var startDate by remember { mutableStateOf(initialDate ?: LocalDate.now()) }
    var startTime by remember(initialHour, initialDate) {
        mutableStateOf(
            if (initialHour != null) {
                LocalTime.of(initialHour, 0)
            } else {
                LocalTime.now()
            }
        )
    }
    var endTime by remember(initialHour) {
        mutableStateOf(
            if (initialHour != null) {
                LocalTime.of(initialHour, 0).plusHours(1)
            } else {
                LocalTime.now().plusHours(1)
            }
        )
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val dims = dimensions()
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickedImageUri = uri }
    val snackbarHostState = remember { SnackbarHostState() }
    var isSaving by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    // Set when a saved draft was auto-restored into a blank "New Event", so the user
    // gets a subtle, dismissible hint (with a Clear action) instead of a silent prefill.
    var draftRestored by remember { mutableStateOf(false) }

    // Validation states
    var titleError by remember { mutableStateOf<String?>(null) }
    var descriptionError by remember { mutableStateOf<String?>(null) }
    var showTimeValidationError by remember { mutableStateOf(false) }
    var timeValidationMessage by remember { mutableStateOf("") }

    // Validate title
    fun validateTitle(): Boolean {
        val result = ValidationUtils.validateEventTitle(title)
        titleError = if (result is ValidationResult.Error) result.message else null
        return result is ValidationResult.Success
    }

    // Validate description
    fun validateDescription(): Boolean {
        val result = ValidationUtils.validateDescription(description)
        descriptionError = if (result is ValidationResult.Error) result.message else null
        return result is ValidationResult.Success
    }

    // Time validation effect
    LaunchedEffect(startTime, endTime, startDate) {
        val startDateTime = LocalDateTime.of(startDate, startTime)
        val endDateTime = LocalDateTime.of(startDate, endTime)

        if (endDateTime.isBefore(startDateTime) || endDateTime.isEqual(startDateTime)) {
            showTimeValidationError = true
            timeValidationMessage = "End time must be after start time"
        } else {
            showTimeValidationError = false
            timeValidationMessage = ""
        }
    }

    // Validation
    val isTitleValid = title.isNotBlank() && titleError == null
    val isFormValid = isTitleValid && descriptionError == null && !showTimeValidationError

    // Load user-defined event types asynchronously (encrypted-prefs read off Main)
    LaunchedEffect(Unit) {
        customEventTypes = withContext(Dispatchers.IO) { viewModel.customEventTypes() }
    }

    // Load event if editing, or load draft if creating new event
    // Issue 1.3: Draft saving functionality
    LaunchedEffect(eventId) {
        if (eventId != null) {
            scope.launch {
                val event = viewModel.getEventById(eventId)
                event?.let {
                    existingEvent = it
                    title = it.title
                    description = it.description ?: ""
                    parentOwner = it.parentOwner
                    eventType = it.eventType
                    startDate = it.startDateTime.toLocalDate()
                    startTime = it.startDateTime.toLocalTime()
                    endTime = it.endDateTime?.toLocalTime() ?: startTime.plusHours(1)
                    recurrencePattern = if (it.isRecurring) it.recurrencePattern else null
                    recurrenceEndDate = it.recurrenceEndDate
                    reminderMinutes = it.reminderMinutes
                    isPrivate = it.isPrivate
                    existingImageUrl = it.imageUrl
                }
            }
        } else if (initialDate == null && initialHour == null) {
            // Restore a saved draft only for a blank new event (opened via the "+" button).
            // When the user taps a specific time slot, that slot must be respected — the
            // draft's date/time must not override it (this was the "tap 15:00 -> 09:00" bug).
            scope.launch {
                val draft = withContext(Dispatchers.IO) { viewModel.loadEventDraft() }
                draft?.let {
                    title = it.title
                    description = it.description
                    parentOwner = it.parentOwner
                    eventType = it.eventType
                    startDate = LocalDate.parse(it.startDate)
                    startTime = LocalTime.parse(it.startTime)
                    endTime = LocalTime.parse(it.endTime)
                    // Only surface the hint when the draft actually carried content the
                    // user would recognise, not for an empty auto-saved shell.
                    if (it.title.isNotBlank() || it.description.isNotBlank()) {
                        draftRestored = true
                    }
                }
            }
        }
    }

    // Notify the user that an unsaved draft was restored, and offer to discard it.
    LaunchedEffect(draftRestored) {
        if (draftRestored) {
            val result = snackbarHostState.showSnackbar(
                message = "Draft restored",
                actionLabel = "Clear",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                title = ""
                description = ""
                parentOwner = "mom"
                eventType = "general"
                startDate = initialDate ?: LocalDate.now()
                startTime = LocalTime.now()
                endTime = LocalTime.now().plusHours(1)
                recurrencePattern = null
                recurrenceEndDate = null
                reminderMinutes = null
                isPrivate = false
                viewModel.clearEventDraft()
            }
            draftRestored = false
        }
    }

    // Auto-save draft when fields change (debounced)
    // Issue 1.3: Draft saving functionality
    LaunchedEffect(title, description, parentOwner, eventType, startDate, startTime, endTime) {
        if (eventId == null) { // Only save draft for new events
            kotlinx.coroutines.delay(1000) // Debounce 1 second
            viewModel.saveEventDraft(
                title = title,
                description = description,
                parentOwner = parentOwner,
                eventType = eventType,
                startDate = startDate,
                startTime = startTime,
                endTime = endTime
            )
        }
    }

    // Shared save routine for the top-bar check and the sticky bottom Save button
    val performSave: () -> Unit = performSave@{
        // Run both validations (no short-circuit) so each field shows its error
        val isTitleValidated = validateTitle()
        val isDescriptionValidated = validateDescription()
        val isValidated = isTitleValidated && isDescriptionValidated
        if (!isFormValid || !isValidated || isSaving) {
            return@performSave
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        isSaving = true
        scope.launch {
            try {
                // Preserve sync/sharing fields of the loaded event when editing;
                // rebuilding from defaults would wipe sharedWith/permissions
                val base = existingEvent
                // Resolve the id up front so a newly picked image can be uploaded under
                // the same event id before the event is persisted.
                val resolvedId = base?.id ?: eventId ?: UUID.randomUUID().toString()

                // Resolve the final image URL: a freshly picked image is uploaded (best
                // effort — a failure keeps the previous image and still saves the event);
                // clearing the image removes it from storage.
                val originalImageUrl = base?.imageUrl
                var imageUploadFailed = false
                val finalImageUrl = if (pickedImageUri != null) {
                    try {
                        viewModel.uploadEventImage(resolvedId, pickedImageUri.toString())
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        imageUploadFailed = true
                        existingImageUrl
                    }
                } else {
                    existingImageUrl
                }
                if (originalImageUrl != null && finalImageUrl == null) {
                    viewModel.deleteEventImage(resolvedId)
                }

                val event = (
                    base ?: Event(
                        id = resolvedId,
                        title = "",
                        startDateTime = LocalDateTime.now(),
                        eventType = "general",
                        parentOwner = "mom",
                        createdAt = LocalDateTime.now(),
                        updatedAt = LocalDateTime.now()
                    )
                    ).copy(
                    title = title,
                    description = description.ifEmpty { null },
                    startDateTime = LocalDateTime.of(startDate, startTime),
                    endDateTime = LocalDateTime.of(startDate, endTime),
                    eventType = eventType,
                    parentOwner = parentOwner,
                    isRecurring = recurrencePattern != null,
                    recurrencePattern = recurrencePattern,
                    recurrenceEndDate = recurrenceEndDate,
                    reminderMinutes = reminderMinutes,
                    isPrivate = isPrivate,
                    imageUrl = finalImageUrl,
                    updatedAt = LocalDateTime.now()
                )

                if (eventId == null) {
                    viewModel.createEvent(event)
                } else {
                    viewModel.updateEvent(event)
                }

                if (imageUploadFailed) {
                    snackbarHostState.showSnackbar(
                        message = "Photo upload failed — event saved without the new photo",
                        duration = SnackbarDuration.Long
                    )
                }

                // Clear draft after successful save
                if (eventId == null) {
                    viewModel.clearEventDraft()
                }

                snackbarHostState.showSnackbar(
                    message = if (eventId == null) "Event created" else "Event updated",
                    duration = SnackbarDuration.Short
                )

                // Navigate after a short delay
                kotlinx.coroutines.delay(500)
                onSave()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    message = "Failed to save event: ${e.message}",
                    duration = SnackbarDuration.Long
                )
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (eventId == null) "New Event" else "Edit Event",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCancel()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel"
                        )
                    }
                },
                actions = {
                    // Propose a time change to the co-parent (only for existing shared events)
                    if (eventId != null && onRequestChange != null && existingEvent?.isPrivate == false) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRequestChange(eventId)
                            },
                            enabled = !isDeleting && !isSaving
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = "Request change"
                            )
                        }
                    }

                    // Delete button (only when editing existing event)
                    if (eventId != null) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showDeleteDialog = true
                            },
                            enabled = !isDeleting && !isSaving
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.error,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete event",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Saving is done via the sticky "Save event" button at the bottom;
                    // a duplicate check-mark here was two controls for one action.
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            // Sticky Save: the primary action stays reachable without scrolling
            // back to the top-bar check icon on this long form
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = performSave,
                    enabled = isFormValid && !isSaving && !isDeleting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dims.paddingMedium, vertical = dims.paddingSmall)
                        .height(52.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.event_form_save))
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(dims.paddingMedium),
            verticalArrangement = Arrangement.spacedBy(dims.paddingMedium + dims.paddingSmall / 2)
        ) {
            // Title Section
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (it.isNotEmpty()) {
                        validateTitle()
                    } else {
                        titleError = null
                    }
                },
                label = { Text("Event Title") },
                placeholder = { Text("e.g., Soccer Practice") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Title,
                        contentDescription = "Event title icon"
                    )
                },
                isError = titleError != null,
                supportingText = if (titleError != null) {
                    {
                        Text(
                            text = titleError ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            // Description Section
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    validateDescription()
                },
                label = { Text("Description (Optional)") },
                placeholder = { Text("Add details about the event...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Event description icon"
                    )
                },
                isError = descriptionError != null,
                supportingText = if (descriptionError != null) {
                    {
                        Text(
                            text = descriptionError ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.buttonHeight * 2.14f), // ~120dp for compact
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus() // Clear focus when Done is pressed
                    }
                )
            )

            // Parent Owner Selection with animated cards
            Text(
                text = "Assigned To",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf("mom" to "Mom", "dad" to "Dad").forEach { (value, label) ->
                    val isSelected = parentOwner == value
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.05f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy
                        ),
                        label = "parentOwnerScale"
                    )

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(dims.buttonHeight * 1.43f) // ~80dp for compact
                            .semantics {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = "Assign to $label, ${if (isSelected) "selected" else "not selected"}"
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            parentOwner = value
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                when (value) {
                                    "mom" -> CoPlanlyColors.MomPink.copy(alpha = 0.2f)
                                    "dad" -> CoPlanlyColors.DadBlue.copy(alpha = 0.2f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        // Unified selected state for both parents: a thin 1.5dp accent
                        // outline over a tonal fill, no drop shadow. Previously the
                        // selected chip used a 2dp border + 4dp elevation, which — with
                        // the more saturated DadBlue — read as a heavy, un-Material box
                        // that didn't match the MomPink chip.
                        border = if (isSelected) {
                            BorderStroke(
                                1.5.dp,
                                when (value) {
                                    "mom" -> CoPlanlyColors.MomPink
                                    "dad" -> CoPlanlyColors.DadBlue
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        } else {
                            null
                        },
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (value) {
                                    "mom" -> Icons.Default.Face
                                    "dad" -> Icons.Default.Person
                                    else -> Icons.Default.Person
                                },
                                contentDescription = "$label icon",
                                tint = when (value) {
                                    "mom" -> CoPlanlyColors.MomPink
                                    "dad" -> CoPlanlyColors.DadBlue
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(dims.iconSize * 1.17f) // ~28dp for compact
                            )
                            Spacer(modifier = Modifier.height(dims.paddingSmall / 2))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Event Type Selection
            Text(
                text = "Event Type",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Default types plus user-defined types created in the calendar filter sheet
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allTypes = listOf(
                    "general" to "General",
                    "medical" to "Medical",
                    "school" to "School",
                    "sports" to "Sports",
                    "birthday" to "Birthday"
                ) + customEventTypes.map { it to it.replaceFirstChar { c -> c.uppercase() } }

                allTypes.forEach { (value, label) ->
                    FilterChip(
                        selected = eventType == value,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            eventType = value
                        },
                        label = { Text(label) },
                        leadingIcon = {
                            if (eventType == value) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(dims.iconSize * 0.75f) // ~18dp for compact
                                )
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "$label event type, ${if (eventType == value) "selected" else "not selected"}"
                        }
                    )
                }
            }

            // Date & Time Section
            Text(
                text = "Date & Time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Date Picker Button
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp) // Ensure minimum touch target
                    .semantics {
                        role = Role.Button
                        contentDescription = "Select date, currently ${startDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))}"
                    },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showDatePicker = true
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dims.paddingMedium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall * 1.5f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Calendar icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Date",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = startDate.format(
                                    DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy")
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Navigate to date picker",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Time validation error display
            if (showTimeValidationError) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.paddingMedium),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = timeValidationMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Time Pickers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Start Time
                OutlinedCard(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp) // Ensure minimum touch target
                        .semantics {
                            role = Role.Button
                            contentDescription = "Select start time, currently ${startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showStartTimePicker = true
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.paddingMedium),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Time picker icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dims.iconSize)
                        )
                        Spacer(modifier = Modifier.height(dims.paddingSmall))
                        Text(
                            text = "Start Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                // End Time
                OutlinedCard(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp) // Ensure minimum touch target
                        .semantics {
                            role = Role.Button
                            contentDescription = "Select end time, currently ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showEndTimePicker = true
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.paddingMedium),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Time picker icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dims.iconSize)
                        )
                        Spacer(modifier = Modifier.height(dims.paddingSmall))
                        Text(
                            text = "End Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            // Repeat Section
            Text(
                text = "Repeat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to "None",
                    "daily" to "Daily",
                    "weekly" to "Weekly",
                    "biweekly" to "Every 2 weeks",
                    "monthly" to "Monthly"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = recurrencePattern == value,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            recurrencePattern = value
                            if (value == null) recurrenceEndDate = null
                        },
                        label = { Text(label) },
                        leadingIcon = {
                            if (recurrencePattern == value) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(dims.iconSize * 0.75f)
                                )
                            }
                        }
                    )
                }
            }

            // Recurrence end date (optional, only for recurring events)
            if (recurrencePattern != null) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showRecurrenceEndPicker = true
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.paddingMedium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Repeat until",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = recurrenceEndDate?.format(
                                    DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy")
                                ) ?: "Forever",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (recurrenceEndDate != null) {
                            TextButton(onClick = { recurrenceEndDate = null }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Reminder Section
            Text(
                text = "Reminder",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Picking a reminder is the moment notifications become relevant —
            // request POST_NOTIFICATIONS here (not on app start)
            val reminderPermissionRequester =
                com.coparently.app.presentation.common.rememberNotificationPermissionRequester()
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                REMINDER_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = reminderMinutes == value,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (value == null) {
                                reminderMinutes = null
                            } else {
                                reminderPermissionRequester.request { reminderMinutes = value }
                            }
                        },
                        label = { Text(label) },
                        leadingIcon = {
                            if (reminderMinutes == value) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(dims.iconSize * 0.75f)
                                )
                            }
                        }
                    )
                }
            }

            // Privacy Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(dims.paddingMedium),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall * 1.5f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Private event",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Only you can see this event",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isPrivate,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isPrivate = it
                        }
                    )
                }
            }

            // Event Photo Section
            EventPhotoSection(
                displayImage = pickedImageUri ?: existingImageUrl,
                enabled = !isSaving && !isDeleting,
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemovePhoto = {
                    pickedImageUri = null
                    existingImageUrl = null
                }
            )

            // Pickup Confirmation Section (existing events only)
            if (eventId != null) {
                val confirmedBy = existingEvent?.pickupConfirmedBy
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (confirmedBy != null) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.paddingMedium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall * 1.5f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (confirmedBy != null) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Column {
                                Text(
                                    text = "Pickup",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (confirmedBy != null) {
                                        "Confirmed by ${confirmedBy.replaceFirstChar { it.uppercase() }}"
                                    } else {
                                        "Not confirmed yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (confirmedBy != null) {
                                    viewModel.undoPickupConfirmation(eventId)
                                    existingEvent = existingEvent?.copy(
                                        pickupConfirmedBy = null,
                                        pickupConfirmedAt = null
                                    )
                                } else {
                                    viewModel.confirmPickup(eventId)
                                    existingEvent = existingEvent?.copy(
                                        pickupConfirmedBy = parentOwner,
                                        pickupConfirmedAt = LocalDateTime.now()
                                    )
                                }
                            }
                        ) {
                            Text(if (confirmedBy != null) "Undo" else "Confirm pickup")
                        }
                    }
                }
            }
        }
    }

    // Recurrence end date picker
    if (showRecurrenceEndPicker) {
        val recurrenceEndPickerState = rememberDatePickerState(
            initialSelectedDateMillis = (recurrenceEndDate ?: startDate.plusMonths(3))
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )

        DatePickerDialog(
            onDismissRequest = { showRecurrenceEndPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        recurrenceEndPickerState.selectedDateMillis?.let { millis ->
                            // LocalDate.ofInstant requires API 34; atZone works from minSdk 26
                            recurrenceEndDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showRecurrenceEndPicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecurrenceEndPicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = recurrenceEndPickerState)
        }
    }

    // Date Picker Dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            // LocalDate.ofInstant requires API 34; atZone works from minSdk 26
                            startDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Start Time Picker
    if (showStartTimePicker) {
        TimePickerDialog(
            initialTime = startTime,
            onTimeSelected = { selectedTime ->
                startTime = selectedTime
                // Auto-adjust end time if it's before start time
                if (endTime.isBefore(startTime)) {
                    endTime = startTime.plusHours(1)
                }
            },
            onDismiss = { showStartTimePicker = false }
        )
    }

    // End Time Picker
    if (showEndTimePicker) {
        TimePickerDialog(
            initialTime = endTime,
            onTimeSelected = { selectedTime ->
                endTime = selectedTime
            },
            onDismiss = { showEndTimePicker = false }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && eventId != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    showDeleteDialog = false
                }
            },
            title = {
                Text("Delete Event")
            },
            text = {
                Text("Are you sure you want to delete this event? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleting = true
                        scope.launch {
                            try {
                                val event = viewModel.getEventById(eventId)
                                event?.let {
                                    viewModel.deleteEvent(it)
                                    snackbarHostState.showSnackbar(
                                        message = "Event deleted",
                                        duration = SnackbarDuration.Short
                                    )
                                    kotlinx.coroutines.delay(500)
                                    onSave() // Navigate back
                                } ?: run {
                                    snackbarHostState.showSnackbar(
                                        message = "Event not found",
                                        duration = SnackbarDuration.Short
                                    )
                                    isDeleting = false
                                    showDeleteDialog = false
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(
                                    message = "Failed to delete event: ${e.message}",
                                    duration = SnackbarDuration.Long
                                )
                                isDeleting = false
                                showDeleteDialog = false
                            }
                        }
                    },
                    enabled = !isDeleting
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Event photo picker/preview. [displayImage] may be a [Uri] (freshly picked) or a
 * String download URL (already attached) — Coil renders both. Shows an attach button
 * when empty, or a preview with change/remove controls when a photo is present.
 */
@Composable
private fun EventPhotoSection(
    displayImage: Any?,
    enabled: Boolean,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    val dims = dimensions()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.paddingMedium),
            verticalArrangement = Arrangement.spacedBy(dims.paddingSmall)
        ) {
            Text(
                text = "Event photo",
                style = MaterialTheme.typography.titleSmall
            )
            if (displayImage == null) {
                OutlinedButton(
                    onClick = onPickPhoto,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null)
                    Spacer(modifier = Modifier.size(dims.paddingSmall))
                    Text("Attach photo")
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = displayImage,
                        contentDescription = "Event photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                    FilledTonalIconButton(
                        onClick = onRemovePhoto,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(dims.paddingSmall)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove photo")
                    }
                }
                TextButton(
                    onClick = onPickPhoto,
                    enabled = enabled
                ) {
                    Text("Change photo")
                }
            }
        }
    }
}
