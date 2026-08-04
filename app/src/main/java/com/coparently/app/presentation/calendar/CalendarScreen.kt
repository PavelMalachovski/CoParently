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
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.calendar.components.CalendarHeader
import com.coparently.app.presentation.calendar.components.ChangeRequestBanner
import com.coparently.app.presentation.calendar.components.CustodyRibbon
import com.coparently.app.presentation.calendar.components.DayAgendaCard
import com.coparently.app.presentation.calendar.components.EventTypeFilterSheet
import com.coparently.app.presentation.calendar.components.VacationBanner
import com.coparently.app.presentation.event.EventUiState
import com.coparently.app.presentation.event.EventViewModel
import com.coparently.app.presentation.theme.dimensions
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Months loaded either side of the query anchor in MONTH mode.
 *
 * Deliberately larger than [CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS]: the anchor is
 * sticky, so the window has to cover every grid the user can reach before it re-centres.
 * Widening this makes each re-anchor more expensive (`RecurrenceExpander` expands over the whole
 * window); narrowing it makes re-anchors more frequent.
 */
internal const val MONTH_WINDOW_RADIUS = 3L

/**
 * Computes the event query range for a view mode and anchor date.
 *
 * Single source of truth, with exactly two callers: the event query and the holiday map.
 * Pull-to-refresh used to be a third; it now calls `EventViewModel.refresh()`, which re-collects
 * the range already loaded rather than recomputing one.
 *
 * In MONTH mode the anchor is the sticky query anchor (see [CalendarSelection.reanchor]), not the
 * month on screen; DAY and WEEK anchor on a concrete day.
 */
internal fun queryRangeFor(
    viewMode: CalendarViewMode,
    anchorDate: LocalDate
): Pair<LocalDateTime, LocalDateTime> {
    return when (viewMode) {
        CalendarViewMode.DAY -> {
            anchorDate.atStartOfDay() to anchorDate.atTime(23, 59, 59)
        }
        CalendarViewMode.WEEK -> {
            val firstDay = anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
            firstDay.atStartOfDay() to firstDay.plusDays(6).atTime(23, 59, 59)
        }
        CalendarViewMode.MONTH -> {
            // The range follows the sticky query anchor, not the displayed month, so ordinary
            // month paging stays inside an already-loaded window. Week-aligned because the grid
            // renders whole weeks either side of the month.
            val anchor = YearMonth.from(anchorDate)
            var startDate = anchor.minusMonths(MONTH_WINDOW_RADIUS).atDay(1)
            while (startDate.dayOfWeek != java.time.DayOfWeek.MONDAY) {
                startDate = startDate.minusDays(1)
            }

            var endDate = anchor.plusMonths(MONTH_WINDOW_RADIUS).atEndOfMonth()
            while (endDate.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                endDate = endDate.plusDays(1)
            }

            startDate.atStartOfDay() to endDate.atTime(23, 59, 59)
        }
    }
}

/**
 * Events covering [date], including multi-day and overnight spans, in start order.
 *
 * The reference definition of "which day does this event belong to". The UI does not call this
 * per day any more — [eventsByDay] is the indexed form it uses — but this stays as the spec the
 * index is held to: `EventsByDayTest` asserts the two agree on every day they touch, so the
 * agenda card under the grid can never disagree with the dots above it.
 *
 * @param events Events already filtered by parent and type
 * @param date The day to collect
 */
internal fun eventsOn(events: List<Event>, date: LocalDate): List<Event> {
    val dayStart = date.atStartOfDay()
    val dayEnd = date.plusDays(1).atStartOfDay()
    return events
        .filter { event ->
            val end = event.endDateTime ?: event.startDateTime
            event.startDateTime < dayEnd && end >= dayStart
        }
        .sortedBy { it.startDateTime }
}

/**
 * [events] bucketed by every day each one covers, each bucket in start order.
 *
 * Built once per event list so the month grid can index it instead of scanning: `dayContent`
 * runs for all 42 cells on every recomposition — a day tap, a filter change, any repository
 * emission — and a per-cell filter+sort made that O(42·N) over a list the ±3-month query window
 * grew by roughly 1.7×.
 *
 * A multi-day or overnight event is bucketed under each day of its span, not only its start day:
 * matching by start date alone is a bug this project has already shipped once (see the
 * range/day-query note in CLAUDE.md). Equivalent to calling [eventsOn] per day, which is what
 * `EventsByDayTest` checks.
 *
 * @param events Events already filtered by parent and type
 */
internal fun eventsByDay(events: List<Event>): Map<LocalDate, List<Event>> {
    val buckets = mutableMapOf<LocalDate, MutableList<Event>>()
    for (event in events) {
        val firstDay = event.startDateTime.toLocalDate()
        // `eventsOn` keeps an event whose end lands exactly on a day's 00:00 (`end >= dayStart`),
        // so the last covered day is the end's own date, not the day before it.
        val lastDay = (event.endDateTime ?: event.startDateTime).toLocalDate()
        var day = firstDay
        // An end before the start covers nothing, and this loop yields nothing for it — same
        // answer `eventsOn` gives such an event on every date.
        while (!day.isAfter(lastDay)) {
            buckets.getOrPut(day) { mutableListOf() }.add(event)
            day = day.plusDays(1)
        }
    }
    // sortedBy is stable, so events sharing a start time keep the incoming order, exactly as the
    // filter-then-sort in eventsOn did.
    return buckets.mapValues { (_, dayEvents) -> dayEvents.sortedBy { it.startDateTime } }
}

/**
 * The month's school vacation as one label, or null when the month has none.
 *
 * Picks the vacation covering the most days of [month] — a month straddling two of them (late
 * August into September, say) gets the one it is mostly in rather than an arbitrary first.
 *
 * @param holidays Holidays for the visible range, keyed by date
 * @param month The month on screen
 */
@Composable
private fun rememberVacationLabel(
    holidays: Map<LocalDate, Holiday>,
    month: YearMonth
): String? {
    val czech = Locale.getDefault().language == "cs"
    return remember(holidays, month, czech) {
        holidays.values
            .filter { it.isSchoolVacation && YearMonth.from(it.date) == month }
            .groupingBy { if (czech) it.nameCs else it.nameEn }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }
}

/**
 * Main calendar screen showing calendar view with events.
 * Supports Month, Week and Day view modes with parent and event type filters,
 * Czech holidays and custody indication.
 *
 * Restructured by the August 2026 design review: the header is one row (its four actions and
 * the segmented view-mode bar under it are now a title menu, a Today pill and one Filters
 * chip), change requests and school vacation surface as labelled banners over the grid, and
 * the month cells carry event dots with the selected day's titles listed underneath.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
// Three view modes, filters, holidays and custody all key off the same date state; splitting the
// body would hand each half the other's state rather than removing any of the branching.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun CalendarScreen(
    onEventClick: (String) -> Unit = {},
    onAddEventClick: (LocalDate?, Int?) -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    onChangeRequestsClick: (() -> Unit)? = null,
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
    val displayedMonth by calendarViewModel.displayedMonth.collectAsState()
    val queryAnchorMonth by calendarViewModel.queryAnchorMonth.collectAsState()
    val today = remember { LocalDate.now() }

    // What the screen says it is showing: the header title, and the day DAY/WEEK render.
    val anchorDate = CalendarSelection.anchorDate(viewMode, displayedMonth, selectedDate, today)

    // What is loaded. In MONTH mode this lags the displayed month by up to
    // CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS, which is the entire point; in DAY and WEEK
    // the two are the same value.
    val queryAnchorDate = CalendarSelection.anchorDate(viewMode, queryAnchorMonth, selectedDate, today)
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
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = displayedMonth.atDay(1).atStartOfDay(ZoneId.systemDefault())
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

    // One pass over the filtered list, reused by all 42 month cells and by the agenda card
    // underneath. Built here rather than inside MonthView so the grid and the card read the
    // same buckets by construction.
    val eventsByDay = remember(filteredEvents) { eventsByDay(filteredEvents) }

    // Czech public holidays and school vacations for the visible range
    val holidays: Map<LocalDate, Holiday> = remember(viewMode, queryAnchorDate, showHolidays) {
        if (!showHolidays) {
            emptyMap()
        } else {
            val (start, end) = queryRangeFor(viewMode, queryAnchorDate)
            CzechHolidays.holidaysInRange(start.toLocalDate(), end.toLocalDate())
        }
    }

    // Load events based on view mode
    LaunchedEffect(viewMode, queryAnchorDate) {
        val (start, end) = queryRangeFor(viewMode, queryAnchorDate)
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
                selectedDate = anchorDate,
                viewMode = viewMode,
                onViewModeChange = { mode -> calendarViewModel.setViewMode(mode) },
                onNavigateToToday = { calendarViewModel.showMonth(YearMonth.now()) },
                onFiltersClick = { showTypeFilters = true },
                filtersActive = parentFilter != ParentFilter.BOTH ||
                    hiddenEventTypes.isNotEmpty() ||
                    !showHolidays,
                onSettingsClick = onSettingsClick
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
                    // Re-requesting the range already loaded is a no-op (query state conflates
                    // equal values), so a stuck/failed query would never recover that way.
                    // refresh() re-collects the current query from scratch instead.
                    //
                    // Custody is deliberately not refreshed here: CalendarViewModel derives it
                    // straight from the Room flow, which pushes every write on its own. The old
                    // loadCustodySchedules() call left a permanent extra collector behind on
                    // each pull.
                    eventViewModel.refresh()
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
                // School vacation, stated once for the month instead of a teal strip under
                // every single cell — in July and August that was all 31 of them.
                if (viewMode == CalendarViewMode.MONTH) {
                    val vacationLabel = rememberVacationLabel(holidays, displayedMonth)
                    if (vacationLabel != null) {
                        VacationBanner(
                            label = vacationLabel,
                            modifier = Modifier.padding(
                                horizontal = dims.paddingMedium,
                                vertical = dims.paddingSmall / 2
                            )
                        )
                    }
                }

                // Change requests as a labelled banner rather than a badged glyph in the bar.
                if (pendingChangeRequests > 0 && onChangeRequestsClick != null) {
                    ChangeRequestBanner(
                        pendingCount = pendingChangeRequests,
                        onReview = onChangeRequestsClick,
                        modifier = Modifier.padding(
                            horizontal = dims.paddingMedium,
                            vertical = dims.paddingSmall / 2
                        )
                    )
                }

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
                                    selectedDate = anchorDate,
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
                                    selectedMonth = displayedMonth,
                                    selectedDate = selectedDate,
                                    eventsByDay = eventsByDay,
                                    getCustody = getCustody,
                                    // Selects the day so the agenda card below fills in.
                                    // Tapping used to jump straight into Day view, which was
                                    // the only way to read a cell's events at all — now the
                                    // month view answers that itself, and Day is a deliberate
                                    // choice from the title menu.
                                    onDayClick = { clickedDate ->
                                        calendarViewModel.setSelectedDate(clickedDate)
                                    },
                                    // Paging is not choosing: the new month gets today if it
                                    // holds today, and no selection at all otherwise.
                                    onMonthChange = { newMonth ->
                                        calendarViewModel.showMonth(newMonth)
                                    },
                                    holidays = holidays
                                )
                            }
                        }
                    }
                }

                // The selected day's events, under the grid. This is the other half of
                // replacing per-cell event chips with dots: the dots give the count, this
                // gives the titles — and it replaces the legend, whose Mom/Dad/vacation
                // keys are now spelled out in words by this card and the vacation banner.
                if (viewMode == CalendarViewMode.MONTH) {
                    selectedDate?.let { chosenDay ->
                        // The same buckets the grid's dots come from: one index, so a title in
                        // the card and a dot in the cell can never describe different days.
                        val agendaEvents = eventsByDay[chosenDay].orEmpty()
                        DayAgendaCard(
                            date = chosenDay,
                            events = agendaEvents,
                            custody = getCustody(chosenDay),
                            onEventClick = { eventId -> previewEventId = eventId },
                            modifier = Modifier.padding(
                                start = dims.paddingMedium,
                                // Clears the FAB, which floats over the end side.
                                end = 72.dp,
                                bottom = dims.paddingMedium
                            )
                        )
                    }
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
