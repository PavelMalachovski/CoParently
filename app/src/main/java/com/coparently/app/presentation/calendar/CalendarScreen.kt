package com.coparently.app.presentation.calendar

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.holidays.CzechHolidays
import com.coparently.app.domain.holidays.Holiday
import com.coparently.app.presentation.calendar.components.CalendarHeader
import com.coparently.app.presentation.calendar.components.CalendarLegend
import com.coparently.app.presentation.calendar.components.CalendarViewModeBar
import com.coparently.app.presentation.calendar.components.CustodyRibbon
import com.coparently.app.presentation.calendar.components.EventTypeFilterSheet
import com.coparently.app.presentation.event.EventUiState
import com.coparently.app.presentation.event.EventViewModel
import com.coparently.app.presentation.theme.dimensions
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Computes the event query range for a view mode and selected date.
 * Single source of truth used by both the initial load and pull-to-refresh.
 */
internal fun queryRangeFor(
    viewMode: CalendarViewMode,
    selectedDate: LocalDate
): Pair<LocalDateTime, LocalDateTime> {
    return when (viewMode) {
        CalendarViewMode.DAY -> {
            selectedDate.atStartOfDay() to selectedDate.atTime(23, 59, 59)
        }
        CalendarViewMode.WEEK -> {
            val firstDay = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
            firstDay.atStartOfDay() to firstDay.plusDays(6).atTime(23, 59, 59)
        }
        CalendarViewMode.MONTH -> {
            // Extended range to cover the MonthView week buffer (6 weeks before/after)
            val visibleMonth = YearMonth.from(selectedDate)
            var startDate = visibleMonth.atDay(1)
            while (startDate.dayOfWeek != java.time.DayOfWeek.MONDAY) {
                startDate = startDate.minusDays(1)
            }
            startDate = startDate.minusWeeks(6)

            var endDate = visibleMonth.atEndOfMonth()
            while (endDate.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                endDate = endDate.plusDays(1)
            }
            endDate = endDate.plusWeeks(6)

            startDate.atStartOfDay() to endDate.atTime(23, 59, 59)
        }
    }
}

/**
 * Main calendar screen showing calendar view with events.
 * Supports Month, Week and Day view modes with parent and event type filters,
 * Czech holidays and custody indication.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    onEventClick: (String) -> Unit = {},
    onAddEventClick: (LocalDate?, Int?) -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    onChangeRequestsClick: (() -> Unit)? = null,
    onWeeklySummaryClick: (() -> Unit)? = null,
    eventViewModel: EventViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel(),
    changeRequestViewModel: com.coparently.app.presentation.changerequests.ChangeRequestViewModel = hiltViewModel()
) {
    val dims = dimensions()
    val haptic = LocalHapticFeedback.current
    val events by eventViewModel.events.collectAsState()
    val custodySchedules by calendarViewModel.custodySchedules.collectAsState()
    val custodyModel by calendarViewModel.custodyModel.collectAsState()
    val viewMode by calendarViewModel.viewMode.collectAsState()
    val selectedDate by calendarViewModel.selectedDate.collectAsState()
    val parentFilter by calendarViewModel.parentFilter.collectAsState()
    val hiddenEventTypes by calendarViewModel.hiddenEventTypes.collectAsState()
    val customEventTypes by calendarViewModel.customEventTypes.collectAsState()
    val showHolidays by calendarViewModel.showHolidays.collectAsState()
    val nextHandover by calendarViewModel.nextHandover.collectAsState()

    // Reduce animation duration on older devices for better performance
    val animationDuration = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 150 else 200
    }

    val now = remember { YearMonth.now() }

    var showDatePicker by remember { mutableStateOf(false) }
    val currentYearMonth = YearMonth.from(selectedDate)
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentYearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli(),
        yearRange = IntRange(now.year - 5, now.year + 5)
    )
    val scope = rememberCoroutineScope()

    // Pull-to-Refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Event type filter sheet state
    var showTypeFilters by remember { mutableStateOf(false) }
    val typeFilterSheetState = rememberModalBottomSheetState()

    // Snackbar state for undo functionality
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by eventViewModel.uiState.collectAsState()

    // Delete button state - show red cross when long pressing event
    var showDeleteButton by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<String?>(null) }
    var isDragOverDeleteButton by remember { mutableStateOf(false) }

    // Event preview sheet: a tap opens the read-only preview, Edit goes to the editor
    var previewEventId by remember { mutableStateOf<String?>(null) }

    // Unified custody lookup: prefers the active CustodyModel (Custody Setup),
    // falls back to legacy CustodyScheduleEntity rows. Views must use this —
    // reading only the legacy schedules left model-based custody invisible.
    val getCustody: (LocalDate) -> String? = remember(custodyModel, custodySchedules) {
        {
                date ->
            custodyModel?.getCustodyFor(date)
                ?: CustodyHelper.getCustodyForDate(date, custodySchedules)
        }
    }

    // Events filtered by parent view and hidden event types
    val filteredEvents = remember(events, parentFilter, hiddenEventTypes) {
        events
            .filter { event ->
                when (parentFilter) {
                    ParentFilter.BOTH -> true
                    ParentFilter.MOM -> event.parentOwner == "mom"
                    ParentFilter.DAD -> event.parentOwner == "dad"
                }
            }
            .filterNot { it.eventType in hiddenEventTypes }
    }

    // Czech public holidays and school vacations for the visible range
    val holidays: Map<LocalDate, Holiday> = remember(viewMode, selectedDate, showHolidays) {
        if (!showHolidays) {
            emptyMap()
        } else {
            val (start, end) = queryRangeFor(viewMode, selectedDate)
            CzechHolidays.holidaysInRange(start.toLocalDate(), end.toLocalDate())
        }
    }

    // Load events based on view mode
    LaunchedEffect(viewMode, selectedDate) {
        val (start, end) = queryRangeFor(viewMode, selectedDate)
        eventViewModel.loadEventsForDateRange(start, end)
    }

    // Show snackbar with undo when event is moved.
    // Resolved here: stringResource is composable and must not be called inside LaunchedEffect.
    val movedMessage = stringResource(R.string.calendar_event_moved)
    val undoMoveLabel = stringResource(R.string.calendar_undo)
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is EventUiState.OperationSuccess -> {
                if (state.message == "Event rescheduled" && eventViewModel.hasUndoAction()) {
                    val result = snackbarHostState.showSnackbar(
                        message = movedMessage,
                        actionLabel = undoMoveLabel,
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        eventViewModel.undoLastMove()
                    }
                }
            }
            else -> {}
        }
    }

    // Single delete path for the whole screen, so every way of destroying an event offers the
    // same protection. Deleting by id alone cannot be undone (the row is already gone), so the
    // full event is captured first and Undo re-creates it with the same id.
    val deletedMessage = stringResource(R.string.event_deleted_message)
    val undoLabel = stringResource(R.string.event_deleted_undo)
    val deleteEventWithUndo: (String) -> Unit = { eventId ->
        val deletedEvent = events.firstOrNull { it.id == eventId }
        if (deletedEvent == null) {
            // Already gone (deleted elsewhere or synced away) — nothing to capture or restore.
            eventViewModel.deleteEventById(eventId)
        } else {
            eventViewModel.deleteEvent(deletedEvent)
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = deletedMessage,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    eventViewModel.createEvent(deletedEvent)
                }
            }
        }
    }

    val pendingChangeRequests by changeRequestViewModel.pendingIncomingCount.collectAsState()

    Scaffold(
        topBar = {
            CalendarHeader(
                selectedDate = selectedDate,
                onNavigateToToday = { calendarViewModel.setSelectedDate(LocalDate.now()) },
                onSettingsClick = onSettingsClick,
                onChangeRequestsClick = onChangeRequestsClick,
                pendingChangeRequests = pendingChangeRequests,
                onWeeklySummaryClick = onWeeklySummaryClick
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        floatingActionButton = {
            Box {
                // Red delete button - appears above the "+" button when long pressing event or dragging
                if ((showDeleteButton && eventToDelete != null) || isDragOverDeleteButton) {
                    FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            eventToDelete?.let { eventId ->
                                deleteEventWithUndo(eventId)
                            }
                            showDeleteButton = false
                            eventToDelete = null
                        },
                        containerColor = if (isDragOverDeleteButton) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        contentColor = MaterialTheme.colorScheme.onError,
                        shape = RoundedCornerShape(dims.cornerRadius),
                        modifier = Modifier
                            .offset(y = (-64).dp)
                            .graphicsLayer {
                                scaleX = if (isDragOverDeleteButton) 1.2f else 1f
                                scaleY = if (isDragOverDeleteButton) 1.2f else 1f
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.calendar_delete_event),
                            modifier = Modifier.size(dims.iconSize)
                        )
                    }
                }

                // Regular "+" button
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAddEventClick(null, null)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(dims.cornerRadius)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.calendar_add_event),
                        modifier = Modifier.size(dims.iconSize)
                    )
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    val (start, end) = queryRangeFor(viewMode, selectedDate)
                    eventViewModel.loadEventsForDateRange(start, end)
                    calendarViewModel.loadCustodySchedules()
                    kotlinx.coroutines.delay(500)
                    isRefreshing = false
                }
            },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // View mode + the entry point to every filter (parent and event type).
                CalendarViewModeBar(
                    viewMode = viewMode,
                    onViewModeChange = { mode -> calendarViewModel.setViewMode(mode) },
                    onFiltersClick = { showTypeFilters = true },
                    filtersActive = parentFilter != ParentFilter.BOTH ||
                        hiddenEventTypes.isNotEmpty() ||
                        !showHolidays
                )

                // Today's custody ribbon. Shown in month and day view; week view carries its own
                // full-width custody band above the day headers instead.
                if (viewMode != CalendarViewMode.WEEK) {
                    val today = LocalDate.now()
                    val todayCustody = getCustody(today)
                    if (todayCustody != null) {
                        key(todayCustody) {
                            AnimatedContent(
                                targetState = todayCustody,
                                transitionSpec = {
                                    slideInVertically(
                                        animationSpec = tween(animationDuration),
                                        initialOffsetY = { -it }
                                    ) + fadeIn() togetherWith slideOutVertically(
                                        animationSpec = tween(animationDuration),
                                        targetOffsetY = { it }
                                    ) + fadeOut()
                                },
                                modifier = Modifier.padding(
                                    horizontal = dims.paddingMedium,
                                    vertical = dims.paddingSmall / 2
                                )
                            ) { custody ->
                                CustodyRibbon(custody = custody, handover = nextHandover)
                            }
                        }
                    }
                }

                // Calendar content based on view mode
                Crossfade(
                    targetState = viewMode,
                    animationSpec = tween(
                        durationMillis = animationDuration,
                        easing = FastOutSlowInEasing
                    ),
                    modifier = Modifier.weight(1f)
                ) { mode ->
                    key(mode) {
                        when (mode) {
                            CalendarViewMode.DAY, CalendarViewMode.WEEK -> {
                                DayWeekView(
                                    selectedDate = selectedDate,
                                    daysCount = if (mode == CalendarViewMode.DAY) 1 else 7,
                                    events = filteredEvents,
                                    getCustody = getCustody,
                                    onDateChange = { calendarViewModel.setSelectedDate(it) },
                                    onEventClick = { eventId -> previewEventId = eventId },
                                    onAddEventClick = { date, hour ->
                                        onAddEventClick(date, hour)
                                    },
                                    onEventDragDrop = { eventId, targetDate, targetHour ->
                                        eventViewModel.moveEvent(eventId, targetDate, targetHour)
                                    },
                                    onEventResize = { eventId: String, newStartTime: LocalDateTime?, newEndTime: LocalDateTime? ->
                                        eventViewModel.resizeEvent(eventId, newStartTime, newEndTime)
                                    },
                                    onEventDelete = { eventId ->
                                        deleteEventWithUndo(eventId)
                                    },
                                    onEventLongPressStart = { eventId ->
                                        showDeleteButton = true
                                        eventToDelete = eventId
                                    },
                                    onEventLongPressEnd = {
                                        showDeleteButton = false
                                        eventToDelete = null
                                    },
                                    onDragOverDeleteButton = { isOver ->
                                        isDragOverDeleteButton = isOver
                                    },
                                    holidays = holidays
                                )
                            }
                            CalendarViewMode.MONTH -> {
                                MonthView(
                                    selectedMonth = YearMonth.from(selectedDate),
                                    selectedDate = selectedDate,
                                    events = filteredEvents,
                                    getCustody = getCustody,
                                    onDayClick = { clickedDate ->
                                        calendarViewModel.setSelectedDate(clickedDate)
                                        calendarViewModel.setViewMode(CalendarViewMode.DAY)
                                    },
                                    onMonthChange = { newMonth ->
                                        calendarViewModel.setSelectedDate(newMonth.atDay(1))
                                    },
                                    holidays = holidays
                                )
                            }
                        }
                    }
                }

                // Legend shares its line with the FAB, which floats over the end side. Month
                // only: week and day have no vacation strip and no custody fill to explain.
                if (viewMode == CalendarViewMode.MONTH) {
                    CalendarLegend(
                        modifier = Modifier.padding(
                            start = dims.paddingMedium,
                            end = 72.dp,
                            bottom = dims.paddingMedium
                        )
                    )
                }
            }
        }
    }

    // Date picker dialog for selecting month and year
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            // LocalDate.ofInstant requires API 34; atZone works from minSdk 26
                            val pickedDate = java.time.Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()

                            calendarViewModel.setSelectedDate(pickedDate)
                            if (viewMode != CalendarViewMode.MONTH) {
                                calendarViewModel.setViewMode(CalendarViewMode.MONTH)
                            }
                            // MonthView follows selectedMonth on its own
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.calendar_dialog_ok))
                }
            },
            dismissButton = {
                Button(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.calendar_dialog_cancel))
                }
            },
            colors = DatePickerDefaults.colors()
        ) {
            androidx.compose.material3.DatePicker(
                state = datePickerState,
                title = null,
                headline = null,
                showModeToggle = true
            )
        }
    }

    // Event preview bottom sheet
    previewEventId?.let { eventId ->
        val previewEvent = events.firstOrNull { it.id == eventId }
        if (previewEvent != null) {
            com.coparently.app.presentation.event.EventPreviewSheet(
                event = previewEvent,
                onEdit = {
                    previewEventId = null
                    onEventClick(eventId)
                },
                onDelete = {
                    previewEventId = null
                    deleteEventWithUndo(eventId)
                },
                onDismiss = { previewEventId = null }
            )
        } else {
            // Event disappeared (deleted/synced away) — close the sheet
            previewEventId = null
        }
    }

    // Event type filter sheet
    if (showTypeFilters) {
        EventTypeFilterSheet(
            allEventTypes = CalendarViewModel.DEFAULT_EVENT_TYPES + customEventTypes,
            hiddenEventTypes = hiddenEventTypes,
            showHolidays = showHolidays,
            parentFilter = parentFilter,
            onParentFilterChange = { calendarViewModel.setParentFilter(it) },
            onToggleType = { calendarViewModel.toggleEventTypeVisibility(it) },
            onAddCustomType = { calendarViewModel.addCustomEventType(it) },
            onShowHolidaysChange = { calendarViewModel.setShowHolidays(it) },
            onDismiss = { showTypeFilters = false },
            sheetState = typeFilterSheetState
        )
    }
}
