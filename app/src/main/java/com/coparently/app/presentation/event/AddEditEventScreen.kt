package com.coparently.app.presentation.event

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PriorityHigh
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.coparently.app.presentation.common.FullScreenImageDialog
import com.coparently.app.presentation.common.rememberParentNames
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
 * event start (null = no reminder), paired with the label resource id.
 * Stored as [Event.reminderMinutes].
 */
private val REMINDER_OPTIONS: List<Pair<Int?, Int>> = listOf(
    null to R.string.event_reminder_none,
    10 to R.string.event_reminder_10_min,
    30 to R.string.event_reminder_30_min,
    60 to R.string.event_reminder_1_hour,
    120 to R.string.event_reminder_2_hours,
    1440 to R.string.event_reminder_1_day
)

/**
 * Whether the parent-owner picker (the two animated cards) should render.
 *
 * A plain function, not inlined into the `if`, so the property that decides it can be pinned in
 * a JVM test — this repo has no Compose UI tests, so an expression that only lives inside the
 * composable is untestable.
 *
 * Gated on [parentsLoaded] first: before `ParentsSource`'s upstream has emitted, `Parents()`
 * reads exactly like an unpaired, unresolved account (`isPaired = false`, no owner), so without
 * this the picker would render on every first composition and vanish the instant the real answer
 * arrives.
 *
 * The second half is deliberately not `parentOwner == null`. [parentOwner] starts null and is
 * only filled by a `LaunchedEffect` that runs *after* the composition in which `parents` first
 * resolves — so for an unpaired account whose slot already resolved, `parentOwner == null` is
 * still true for that one composition, and the picker would flash open for a frame anyway.
 * [currentUserSlot] is the same value that effect is about to assign [parentOwner] to, so
 * falling back to it answers "is there really nobody to choose between yet" without waiting a
 * frame for the effect to catch up: [parentOwner] takes priority once it diverges from
 * [currentUserSlot] (a loaded event, a restored draft, a manual pick), and only when neither is
 * known does the picker stay up to give the user a way to fill it in themselves.
 */
internal fun showParentOwnerSelector(
    parentsLoaded: Boolean,
    isPaired: Boolean,
    parentOwner: String?,
    currentUserSlot: String?
): Boolean = parentsLoaded && (isPaired || (parentOwner ?: currentUserSlot) == null)

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
    viewModel: EventViewModel = hiltViewModel(),
    friendViewModel: com.coparently.app.presentation.friends.FriendViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    // The family's live calendar friends (item 16). Empty for most families, which is why the
    // participation toggle below is absent rather than disabled when there is nobody to name.
    val calendarFriends by friendViewModel.friends.collectAsState()
    val parentNames = rememberParentNames(viewModel.parents.collectAsState().value)
    // The signed-in parent - the one source of truth for "who am I" already threaded into this
    // screen (see ParentsSource). Nothing here learns the signed-in slot any other way.
    val currentUser = parentNames.parents.me
    // Whether there is someone to choose between at all. Deliberately not
    // "parentNames.parents.coParent != null": a legacy pair (paired before slot assignment
    // shipped) has a real co-parent but a null Parents.coParent, because their slot isn't known
    // yet - gating the selector on that nullness would hide it from exactly the pairs this
    // app's slot migration exists to serve.
    val isPaired = parentNames.parents.isPaired
    // Whether Parents() is a real answer yet. Parents() itself - the synthetic value every
    // stateIn seeds with - is indistinguishable from "an unpaired, unresolved account" without
    // this: both have isPaired = false and me = null. Gating the selector on it too is what
    // stops the two-card block from rendering for one frame and then disappearing on every
    // first composition.
    val parentsLoaded = parentNames.parents.loaded

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // Yours unless you say otherwise. Hardcoding "mom" here meant every event either parent
    // created was attributed to the same person. Seeded from the signed-in user's slot, which
    // is unknown (null) until ParentsSource resolves it - Save stays disabled until then
    // (see isFormValid) rather than silently falling back to "mom".
    var parentOwner by remember { mutableStateOf(currentUser?.slot) }
    var eventType by remember { mutableStateOf("general") }
    // Snapshot of the loaded event so saving preserves sync/sharing fields
    var existingEvent by remember { mutableStateOf<Event?>(null) }
    // MVP1 fields: recurrence, reminder, privacy
    var recurrencePattern by remember { mutableStateOf<String?>(null) }
    var recurrenceEndDate by remember { mutableStateOf<LocalDate?>(null) }
    var showRecurrenceEndPicker by remember { mutableStateOf(false) }
    var reminderMinutes by remember { mutableStateOf<Int?>(null) }
    var isPrivate by remember { mutableStateOf(false) }
    var isImportant by remember { mutableStateOf(false) }
    // Which calendar friend takes part, or null. Not an owner — see Event.friendParticipates.
    var friendParticipates by remember { mutableStateOf<String?>(null) }
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

    // Strings used inside non-composable lambdas/effects must be resolved here,
    // in composable scope (stringResource cannot be called in those lambdas).
    val endBeforeStartMessage = stringResource(R.string.event_form_end_before_start)
    val draftRestoredMessage = stringResource(R.string.event_form_draft_restored)
    val draftClearLabel = stringResource(R.string.event_form_clear)

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
            timeValidationMessage = endBeforeStartMessage
        } else {
            showTimeValidationError = false
            timeValidationMessage = ""
        }
    }

    // Validation
    val isTitleValid = title.isNotBlank() && titleError == null
    // parentOwner != null: nothing may quietly fall back to a hardcoded slot again. Not
    // unreachable in practice: UserRepositoryImpl deliberately creates no Room row for a user
    // it cannot name, so `me` (and therefore parentOwner) can stay null indefinitely, not just
    // for the usual first-frame beat. See the selector below and the message next to Save for
    // what the user sees while that's true.
    val isFormValid = isTitleValid && descriptionError == null && !showTimeValidationError && parentOwner != null

    // Load user-defined event types asynchronously (encrypted-prefs read off Main)
    LaunchedEffect(Unit) {
        customEventTypes = withContext(Dispatchers.IO) { viewModel.customEventTypes() }
    }

    // Seed the owner from the signed-in user's slot once ParentsSource resolves it, as long as
    // nothing has claimed the field yet. Not restricted to a brand new event: an existing event
    // whose load already set parentOwner (see the eventId effect below, which assigns
    // unconditionally) makes this a no-op forever after, since the field is then never null
    // again - so it cannot overwrite a loaded owner, a draft restore, or a manual pick,
    // regardless of the order these effects resolve in. Leaving it unrestricted is what lets an
    // existing event whose load *fails* (getEventById returns null, parentOwner never gets set)
    // still end up with an owner once the signed-in user's own slot resolves, instead of being
    // stuck at null forever.
    LaunchedEffect(currentUser) {
        if (parentOwner == null) {
            parentOwner = currentUser?.slot
        }
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
                    isImportant = it.isImportant
                    friendParticipates = it.friendParticipates
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
                message = draftRestoredMessage,
                actionLabel = draftClearLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                title = ""
                description = ""
                parentOwner = currentUser?.slot
                eventType = "general"
                startDate = initialDate ?: LocalDate.now()
                startTime = LocalTime.now()
                endTime = LocalTime.now().plusHours(1)
                recurrencePattern = null
                recurrenceEndDate = null
                reminderMinutes = null
                isPrivate = false
                isImportant = false
                friendParticipates = null
                viewModel.clearEventDraft()
            }
            draftRestored = false
        }
    }

    // Auto-save draft when fields change (debounced)
    // Issue 1.3: Draft saving functionality
    LaunchedEffect(title, description, parentOwner, eventType, startDate, startTime, endTime) {
        // parentOwner can still be null for a beat while ParentsSource resolves the signed-in
        // slot; a draft needs an owner to be worth anything, so skip the save rather than
        // stamp a placeholder.
        val ownerForDraft = parentOwner
        if (eventId == null && ownerForDraft != null) { // Only save draft for new events
            kotlinx.coroutines.delay(1000) // Debounce 1 second
            viewModel.saveEventDraft(
                title = title,
                description = description,
                parentOwner = ownerForDraft,
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
        // isFormValid already guarantees parentOwner != null, but it is a mutable Compose state
        // var and cannot be smart-cast from that boolean, so pin it to a local val here for the
        // Event construction below. The elvis branch is unreachable given the guard above.
        val ownerSlot = parentOwner ?: return@performSave
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
                        // Log the cause: this failure used to be swallowed silently, which left
                        // "Photo upload failed" as the only evidence anywhere in the system.
                        android.util.Log.e("CoPlanlyUpload", "Event image upload failed", e)
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
                        parentOwner = ownerSlot,
                        createdAt = LocalDateTime.now(),
                        updatedAt = LocalDateTime.now()
                    )
                    ).copy(
                    title = title,
                    description = description.ifEmpty { null },
                    startDateTime = LocalDateTime.of(startDate, startTime),
                    endDateTime = LocalDateTime.of(startDate, endTime),
                    eventType = eventType,
                    parentOwner = ownerSlot,
                    isRecurring = recurrencePattern != null,
                    recurrencePattern = recurrencePattern,
                    recurrenceEndDate = recurrenceEndDate,
                    reminderMinutes = reminderMinutes,
                    isPrivate = isPrivate,
                    isImportant = isImportant,
                    friendParticipates = friendParticipates,
                    imageUrl = finalImageUrl,
                    updatedAt = LocalDateTime.now()
                )

                if (eventId == null) {
                    viewModel.createEvent(event)
                } else {
                    viewModel.updateEvent(event)
                }

                // Clear draft after successful save
                if (eventId == null) {
                    viewModel.clearEventDraft()
                }

                // Leave immediately. `showSnackbar` suspends until the snackbar is dismissed or
                // times out, so confirming the save here used to hold the editor open for the
                // snackbar's full duration — about 4.5s normally and 14.5s when a photo upload
                // had also failed — long after the event itself was written. The saved event is
                // its own confirmation once the calendar is back.
                //
                // The upload warning still has to reach the user, so it goes out as a Toast,
                // which survives navigation. This matches AddExpenseScreen, which already
                // reports its receipt-upload warning the same way.
                if (imageUploadFailed) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.event_form_photo_upload_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
                onSave()
            } catch (e: Exception) {
                // Re-enable Save before the snackbar, not after: this suspends for the
                // snackbar's lifetime and used to leave the button dead in the meantime.
                isSaving = false
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.event_form_save_failed, e.message),
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (eventId == null) {
                            stringResource(R.string.event_form_title_new)
                        } else {
                            stringResource(R.string.event_form_title_edit)
                        },
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
                            contentDescription = stringResource(R.string.event_form_cancel)
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
                                contentDescription = stringResource(R.string.event_form_cd_request_change)
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
                                    contentDescription = stringResource(R.string.event_form_cd_delete_event),
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dims.paddingMedium, vertical = dims.paddingSmall)
                ) {
                    // parentOwner == null is not just a first-frame beat: an account whose
                    // profile UserRepositoryImpl declined to name (see isFormValid) can sit here
                    // indefinitely. Save being merely disabled would leave no clue why - this
                    // says what's missing, next to the selector that (per isPaired || parentOwner
                    // == null above) is showing precisely so there's something to do about it.
                    if (parentOwner == null) {
                        Text(
                            text = stringResource(R.string.event_form_owner_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = dims.paddingSmall)
                        )
                    }
                    Button(
                        onClick = performSave,
                        enabled = isFormValid && !isSaving && !isDeleting,
                        modifier = Modifier
                            .fillMaxWidth()
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
                label = { Text(stringResource(R.string.event_form_field_title_label)) },
                placeholder = { Text(stringResource(R.string.event_form_field_title_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Title,
                        contentDescription = stringResource(R.string.event_form_cd_title_icon)
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
                label = { Text(stringResource(R.string.event_form_field_description_label)) },
                placeholder = { Text(stringResource(R.string.event_form_field_description_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = stringResource(R.string.event_form_cd_description_icon)
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

            // Parent Owner Selection with animated cards. Shown whenever there is a real choice
            // to make (isPaired - a co-parent exists even if their slot isn't known yet, in
            // which case their card falls through to the "unknown" label via parentLabel) or
            // whenever there is otherwise no way to fill the owner in: an unpaired account whose
            // own slot has not resolved would else have no escape from a permanently disabled
            // Save. A family of one with a resolved slot has neither condition and correctly
            // sees no control - nobody else an event could belong to. See
            // showParentOwnerSelector's KDoc for why this is a named function rather than the
            // condition inline.
            if (showParentOwnerSelector(parentsLoaded, isPaired, parentOwner, currentUser?.slot)) {
                Text(
                    text = stringResource(R.string.event_form_assigned_to),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Slots have an order, so when a card resolves to nobody a caption of
                    // "first"/"second" (never "You"/"Co-parent" positionally - an unpaired
                    // account's slot may be either one) tells the two cards apart. parentLabel
                    // itself is untouched: it still answers "who is this" and "we do not know"
                    // is the right answer to that question.
                    val slotFirst = stringResource(R.string.parent_label_slot_first)
                    val slotSecond = stringResource(R.string.parent_label_slot_second)
                    listOf(
                        "mom" to slotFirst,
                        "dad" to slotSecond
                    ).forEach { (value, ordinalLabel) ->
                        val label = if (parentNames.isKnown(value)) {
                            parentNames.labelFor(value)
                        } else {
                            ordinalLabel
                        }
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
                                    contentDescription = context.getString(
                                        R.string.event_form_cd_assign_to,
                                        label,
                                        context.getString(
                                            if (isSelected) R.string.event_form_cd_selected
                                            else R.string.event_form_cd_not_selected
                                        )
                                    )
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
                                // One glyph for both slots. It used to be Face for slot 1 and
                                // Person for slot 2 - not a matched pair, and slot 2 was already
                                // indistinguishable from "unknown" because Person was the fallback
                                // too. A per-parent glyph is a third channel asserting exactly the
                                // distinction this app stopped asserting; the name and the colour
                                // carry the identity.
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = stringResource(R.string.event_form_cd_label_icon, label),
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
            }

            // Event Type Selection
            Text(
                text = stringResource(R.string.event_form_event_type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Default types plus user-defined types created in the calendar filter sheet
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allTypes = listOf(
                    "general" to stringResource(R.string.event_type_general),
                    "medical" to stringResource(R.string.event_type_medical),
                    "school" to stringResource(R.string.event_type_school),
                    "sports" to stringResource(R.string.event_type_sports),
                    "birthday" to stringResource(R.string.event_type_birthday)
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
                                    contentDescription = stringResource(R.string.event_form_cd_selected_icon),
                                    modifier = Modifier.size(dims.iconSize * 0.75f) // ~18dp for compact
                                )
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(
                                R.string.event_form_cd_event_type,
                                label,
                                context.getString(
                                    if (eventType == value) R.string.event_form_cd_selected
                                    else R.string.event_form_cd_not_selected
                                )
                            )
                        }
                    )
                }
            }

            // Date & Time Section
            Text(
                text = stringResource(R.string.event_form_date_time),
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
                        contentDescription = context.getString(
                            R.string.event_form_cd_select_date,
                            startDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))
                        )
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
                            contentDescription = stringResource(R.string.event_form_cd_calendar_icon),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.event_form_date),
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
                        contentDescription = stringResource(R.string.event_form_cd_navigate_date_picker),
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
                            contentDescription = stringResource(R.string.event_form_cd_error),
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
                            contentDescription = context.getString(
                                R.string.event_form_cd_select_start_time,
                                startTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                            )
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
                            contentDescription = stringResource(R.string.event_form_cd_time_picker_icon),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dims.iconSize)
                        )
                        Spacer(modifier = Modifier.height(dims.paddingSmall))
                        Text(
                            text = stringResource(R.string.event_form_start_time),
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
                            contentDescription = context.getString(
                                R.string.event_form_cd_select_end_time,
                                endTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                            )
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
                            contentDescription = stringResource(R.string.event_form_cd_time_picker_icon),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(dims.iconSize)
                        )
                        Spacer(modifier = Modifier.height(dims.paddingSmall))
                        Text(
                            text = stringResource(R.string.event_form_end_time),
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
                text = stringResource(R.string.event_form_repeat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to stringResource(R.string.event_repeat_none),
                    "daily" to stringResource(R.string.event_repeat_daily),
                    "weekly" to stringResource(R.string.event_repeat_weekly),
                    "biweekly" to stringResource(R.string.event_repeat_biweekly),
                    "monthly" to stringResource(R.string.event_repeat_monthly)
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
                                    contentDescription = stringResource(R.string.event_form_cd_selected_icon),
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
                                text = stringResource(R.string.event_form_repeat_until),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = recurrenceEndDate?.format(
                                    DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy")
                                ) ?: stringResource(R.string.event_form_repeat_forever),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (recurrenceEndDate != null) {
                            TextButton(onClick = { recurrenceEndDate = null }) {
                                Text(stringResource(R.string.event_form_clear))
                            }
                        }
                    }
                }
            }

            // Reminder Section
            Text(
                text = stringResource(R.string.event_form_reminder),
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
                REMINDER_OPTIONS.forEach { (value, labelRes) ->
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
                        label = { Text(stringResource(labelRes)) },
                        leadingIcon = {
                            if (reminderMinutes == value) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.event_form_cd_selected_icon),
                                    modifier = Modifier.size(dims.iconSize * 0.75f)
                                )
                            }
                        }
                    )
                }
            }

            // Important Section. Deliberately a statement and not an obligation: the switch
            // says the co-parent is expected, and nothing here blocks saving or chases them.
            // The stronger reading — the co-parent must confirm attendance — is the event
            // acceptance machinery pointed at a different question, and lives there.
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(dims.paddingSmall * 1.5f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PriorityHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.event_form_important_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.event_form_important_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isImportant,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isImportant = it
                            }
                        )
                    }
                    // Only once the switch is on: the helper says what the mark will mean, and
                    // printing it while the flag is off would explain a state the event is not in.
                    if (isImportant) {
                        Text(
                            text = stringResource(R.string.event_form_important_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Who else takes part (item 16). Only when the family has admitted a friend: a
            // toggle naming nobody would be a control for a relationship that does not exist.
            calendarFriends.firstOrNull()?.let { friend ->
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
                        Text(
                            text = stringResource(
                                R.string.friend_event_participates,
                                friend.name
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = friendParticipates == friend.friendUid,
                            onCheckedChange = { on ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                friendParticipates = if (on) friend.friendUid else null
                            }
                        )
                    }
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
                                text = stringResource(R.string.event_form_private_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.event_form_private_description),
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
                                    text = stringResource(R.string.event_form_pickup),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (confirmedBy != null) {
                                        val confirmerName = parentNames.labelFor(confirmedBy)
                                        stringResource(R.string.event_form_pickup_confirmed_by, confirmerName)
                                    } else {
                                        stringResource(R.string.event_form_pickup_not_confirmed)
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
                            Text(
                                stringResource(
                                    if (confirmedBy != null) R.string.event_form_pickup_undo
                                    else R.string.event_form_pickup_confirm
                                )
                            )
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
                    Text(stringResource(R.string.event_form_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecurrenceEndPicker = false }) {
                    Text(stringResource(R.string.event_form_cancel))
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
                    Text(stringResource(R.string.event_form_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.event_form_cancel))
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
                Text(stringResource(R.string.event_form_delete_dialog_title))
            },
            text = {
                Text(stringResource(R.string.event_form_delete_dialog_message))
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
                                        message = context.getString(R.string.event_form_event_deleted),
                                        duration = SnackbarDuration.Short
                                    )
                                    kotlinx.coroutines.delay(500)
                                    onSave() // Navigate back
                                } ?: run {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.event_form_not_found),
                                        duration = SnackbarDuration.Short
                                    )
                                    isDeleting = false
                                    showDeleteDialog = false
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(
                                        R.string.event_form_delete_failed,
                                        e.message ?: ""
                                    ),
                                    duration = SnackbarDuration.Long
                                )
                                isDeleting = false
                                showDeleteDialog = false
                            }
                        }
                    },
                    enabled = !isDeleting
                ) {
                    Text(stringResource(R.string.event_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    },
                    enabled = !isDeleting
                ) {
                    Text(stringResource(R.string.event_form_cancel))
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
                text = stringResource(R.string.event_form_photo),
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
                    Text(stringResource(R.string.event_form_attach_photo))
                }
            } else {
                var viewingPhoto by rememberSaveable { mutableStateOf(false) }
                if (viewingPhoto) {
                    FullScreenImageDialog(
                        model = displayImage,
                        contentDescription = stringResource(R.string.event_form_photo),
                        onDismiss = { viewingPhoto = false }
                    )
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = displayImage,
                        contentDescription = stringResource(R.string.image_viewer_open),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { viewingPhoto = true }
                    )
                    FilledTonalIconButton(
                        onClick = onRemovePhoto,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(dims.paddingSmall)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.event_form_cd_remove_photo)
                        )
                    }
                }
                TextButton(
                    onClick = onPickPhoto,
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.event_form_change_photo))
                }
            }
        }
    }
}
