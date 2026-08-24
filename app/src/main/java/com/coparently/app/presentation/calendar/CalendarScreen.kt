package com.coparently.app.presentation.calendar

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import com.coparently.app.domain.custody.CustodyResolver
import com.coparently.app.domain.custody.DaySwapInbox
import com.coparently.app.domain.holidays.CzechHolidays
import com.coparently.app.domain.holidays.Holiday
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.calendar.components.CalendarHeader
import com.coparently.app.presentation.calendar.components.ChangeRequestBanner
import com.coparently.app.presentation.calendar.components.CustodyChangedBanner
import com.coparently.app.presentation.calendar.components.DaySwapSheet
import com.coparently.app.presentation.calendar.components.EventTypeFilterSheet
import com.coparently.app.presentation.common.rememberParentNames
import com.coparently.app.presentation.event.EventUiState
import com.coparently.app.presentation.event.EventViewModel
import com.coparently.app.presentation.theme.dimensions
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
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
    val dayOverrides by calendarViewModel.dayOverrides.collectAsState()
    val swapError by calendarViewModel.swapError.collectAsState()
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
    val custodyChangeAnnouncement by calendarViewModel.custodyChangeAnnouncement.collectAsState()
    val pendingProposal by calendarViewModel.pendingProposal.collectAsState()
    val calendarFriends by calendarViewModel.calendarFriends.collectAsState()

    // Who the two parents are, resolved with the fallback strings once for the whole screen.
    // Every label below this line - ribbon, grid, agenda card, filters, preview sheet - reads
    // this one value, so no two of them can name the same slot differently.
    val parents by calendarViewModel.parents.collectAsState()
    val parentNames = rememberParentNames(parents)

    // Reduce animation duration on older devices for better performance
    val animationDuration = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 150 else 200
    }

    val now = remember { YearMonth.now() }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        // Material3's DatePickerState speaks UTC-midnight millis, so the conversion must go
        // through UTC — a system-zone start-of-day lands one day off west of Greenwich. Opens
        // on the selected day (or today), never on the 1st: "jump to a date" should start from
        // where the user is, and proposing the 1st is what read as "schedule from the 1st".
        initialSelectedDateMillis = (selectedDate ?: today)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
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

    // Day-swap sheet: a long-press on a day offers it to the co-parent.
    var swapDate by remember { mutableStateOf<LocalDate?>(null) }

    // Unified custody lookup: an accepted one-off swap, then the active CustodyModel (Custody
    // Setup), then the legacy CustodyScheduleEntity rows. Views must use this — reading only the
    // legacy schedules left model-based custody invisible, and the precedence between a swap and
    // the pattern lives in exactly one place, `CustodyResolver`, for the same reason.
    val getCustody: (LocalDate) -> String? =
        remember(custodyModel, dayOverrides, custodySchedules) {
            CustodyResolver.resolver(
                model = custodyModel,
                overrides = dayOverrides,
                legacy = { date -> CustodyHelper.getCustodyForDate(date, custodySchedules) }
            )
        }

    // What a pending proposal would make of a day, or null when nothing is pending. Deliberately
    // separate from `getCustody`, and for the same reason a pending swap is: a proposal has
    // changed nothing yet, and folding it into the lookup would move days both parents are still
    // arguing about. The grid draws it as a preview over the agreed day instead.
    val getProposedCustody: (LocalDate) -> String? = remember(pendingProposal) {
        val proposed = pendingProposal?.model
        if (proposed == null) {
            { _ -> null }
        } else {
            { date -> proposed.getCustodyFor(date) }
        }
    }

    // The dates a swap is being negotiated on. A pending swap has changed nothing about whose
    // day it is, so it is deliberately not part of `getCustody` — the grid marks it separately.
    val pendingSwapDates: Set<LocalDate> = remember(dayOverrides) {
        dayOverrides
            .filterValues { it.isPending }
            .keys
            .mapNotNull { iso -> runCatching { LocalDate.parse(iso) }.getOrNull() }
            .toSet()
    }

    // The dates an accepted swap decides. `getCustody` already answers whose day each one is;
    // this set only tells the grid the answer came from a swap, so the cell (and the one after
    // it) draws one solid fill instead of the handover diagonal.
    val swappedDates: Set<LocalDate> = remember(dayOverrides) {
        dayOverrides
            .filterValues { it.isAccepted }
            .keys
            .mapNotNull { iso -> runCatching { LocalDate.parse(iso) }.getOrNull() }
            .toSet()
    }

    // Events filtered by parent view and hidden event types
    val filteredEvents = remember(events, parentFilter, hiddenEventTypes) {
        events
            .filter { event ->
                when (parentFilter) {
                    ParentFilter.BOTH -> true
                    ParentFilter.MOM -> event.parentOwner == "mom"
                    ParentFilter.DAD -> event.parentOwner == "dad"
                    // Not an owner check: a friend never owns a day, so this asks the only
                    // question their presence raises — where are they expected?
                    ParentFilter.FRIEND -> !event.friendParticipates.isNullOrBlank()
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

    // Day swaps live on the custody document, not in `change_requests`, so the banner count
    // must add them explicitly — an incoming swap used to raise no banner at all, leaving the
    // co-parent no visible route to the inbox that answers it.
    val inboxUserId by changeRequestViewModel.currentUserId.collectAsState()
    val pendingSwapsAwaitingMe = remember(dayOverrides, inboxUserId) {
        if (inboxUserId.isEmpty()) {
            0
        } else {
            DaySwapInbox.visible(dayOverrides, LocalDate.now())
                .count { DaySwapInbox.awaitsAnswerFrom(it, inboxUserId) }
        }
    }
    val pendingInboxCount = pendingChangeRequests + pendingSwapsAwaitingMe

    // A custody-pattern proposal draws two different banners (item 7): the parent who must
    // answer gets a Review into the inbox; the one who proposed it gets a passive "waiting".
    // `pendingProposal` (CalendarViewModel) is either party's; `proposalAwaitingMe`
    // (ChangeRequestViewModel) is only the co-parent's, so the difference tells them apart.
    val proposalAwaitingMe by changeRequestViewModel.pendingProposal.collectAsState()
    val proposerWaiting = pendingProposal != null && proposalAwaitingMe == null

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

                // Regular "+" button. Pre-fills the day on screen: the selected day when there
                // is one, otherwise the anchor of the current view — a null date made the form
                // default to today even with another day highlighted.
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAddEventClick(selectedDate ?: anchorDate, null)
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
                // The school-vacation banner is deliberately not rendered.
                //
                // It appeared only in months that actually contain a vacation, so the grid
                // below it was a banner's height shorter in those months and taller in the
                // rest. Paging between them resized the calendar mid-swipe, which is what was
                // left of the long-running "the month swipe feels wrong" complaint once the
                // pager itself was measured and cleared (§11 item 8): the hands-on pass on
                // 9 August found the swipe itself even, and named this as the remaining
                // roughness — and reported week and day view as the smoothest precisely
                // because nothing there changes height between pages.
                //
                // Removed rather than hidden because that is what was asked for now. **This
                // loses the school-vacation signal entirely** — the July 2026 design replaced
                // a per-day teal strip with this banner, so there is no longer any other
                // marker for it. When it comes back, it must reserve its height in every
                // month, vacation or not, or it will reintroduce exactly this defect.
                // `VacationBanner` itself is left in `CalendarBanners.kt`; the label helper
                // that fed it is recoverable from this commit's parent.

                // A custody proposal the co-parent must answer: a Review banner into the inbox.
                proposalAwaitingMe?.let { proposal ->
                    if (onChangeRequestsClick != null) {
                        ChangeRequestBanner(
                            pendingCount = 1,
                            message = stringResource(
                                R.string.custody_proposal_review,
                                parentNames.labelForUid(proposal.proposedBy)
                            ),
                            onReview = onChangeRequestsClick,
                            modifier = Modifier.padding(
                                horizontal = dims.paddingMedium,
                                vertical = dims.paddingSmall / 2
                            )
                        )
                    }
                }

                // The proposer's own view: a passive note that the change is not live yet.
                if (proposerWaiting) {
                    Text(
                        text = stringResource(R.string.custody_proposal_waiting),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = dims.paddingMedium,
                            vertical = dims.paddingSmall
                        )
                    )
                }

                // Change requests as a labelled banner rather than a badged glyph in the bar.
                // The count folds in day swaps awaiting this parent — see pendingSwapsAwaitingMe.
                if (pendingInboxCount > 0 && onChangeRequestsClick != null) {
                    ChangeRequestBanner(
                        pendingCount = pendingInboxCount,
                        onReview = onChangeRequestsClick,
                        modifier = Modifier.padding(
                            horizontal = dims.paddingMedium,
                            vertical = dims.paddingSmall / 2
                        )
                    )
                }

                // Custody is last-write-wins with no consent step; this is what keeps a remote
                // change from landing silently. Never shown for this device's own write - see
                // CalendarViewModel.custodyChangeAnnouncement. Named via labelForUid, not a
                // slot lookup: a pair not yet migrated off a shared "mom" slot would otherwise
                // have the co-parent's write reported as the signed-in parent's own.
                custodyChangeAnnouncement?.let { announcement ->
                    CustodyChangedBanner(
                        byName = parentNames.labelForUid(announcement.lastModifiedBy),
                        onDismiss = {
                            calendarViewModel.dismissCustodyChange(announcement.lastModifiedAt)
                        },
                        modifier = Modifier.padding(
                            horizontal = dims.paddingMedium,
                            vertical = dims.paddingSmall / 2
                        )
                    )
                }

                // No "Today with X" ribbon here. The day cells already say whose day it is, in
                // the colour that says it everywhere else, and the handover countdown the ribbon
                // also carried lives on the home screen's hero. Two answers to one question is
                // what the design refresh removed elsewhere.

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
                                    getProposedCustody = getProposedCustody,
                                    parentNames = parentNames,
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
                                    getProposedCustody = getProposedCustody,
                                    parentNames = parentNames,
                                    pendingSwapDates = pendingSwapDates,
                                    swappedDates = swappedDates,
                                    // Only a paired account may offer a swap: unpaired there is
                                    // nobody to accept, and a swap that applies itself is just an
                                    // edit the custody editor already does. Null here removes the
                                    // long-press entirely rather than opening a sheet that would
                                    // have to apologise.
                                    onDayLongClick = parents.coParent?.let {
                                        { date: LocalDate -> swapDate = date }
                                    },
                                    // Selects the day and opens Day view, where an empty hour
                                    // slot creates an event — the owner's walkthrough found the
                                    // select-only tap a dead end: two redesign passes removed
                                    // first the jump, then the agenda card that replaced it,
                                    // leaving a tap with no visible outcome and no tap route to
                                    // creating an event on a chosen day.
                                    onDayClick = { clickedDate ->
                                        calendarViewModel.setSelectedDate(clickedDate)
                                        calendarViewModel.setViewMode(CalendarViewMode.DAY)
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

                // No day-agenda card under the grid any more: it moved to the home screen
                // (the "today" card), so the month grid fills its screen. A day's titles are
                // one tap away — selecting a day still works, and the preview sheet opens an
                // event from any view.
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
                            // LocalDate.ofInstant requires API 34; atZone works from minSdk 26.
                            // The millis are UTC midnight (DatePickerState's contract), so read
                            // them back in UTC — a system-zone read is a day early west of it.
                            val pickedDate = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC)
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
                parentNames = parentNames,
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

    // Day-swap sheet
    swapDate?.let { date ->
        DaySwapSheet(
            date = date,
            currentCustody = getCustody(date),
            parentNames = parentNames,
            onOffer = { toParent, note ->
                calendarViewModel.offerDaySwap(date, toParent, note)
                swapDate = null
            },
            onDismiss = { swapDate = null }
        )
    }

    // A refused swap has to be said out loud: the sheet closes optimistically, so without this a
    // rejected write would look exactly like a successful one.
    val swapRefusedMessage = stringResource(R.string.day_swap_error_refused)
    val swapNotReadyMessage = stringResource(R.string.day_swap_error_not_ready)
    LaunchedEffect(swapError) {
        swapError?.let { error ->
            snackbarHostState.showSnackbar(
                when (error) {
                    SwapError.NOT_READY -> swapNotReadyMessage
                    SwapError.REFUSED -> swapRefusedMessage
                }
            )
            calendarViewModel.clearSwapError()
        }
    }

    // Event type filter sheet
    if (showTypeFilters) {
        EventTypeFilterSheet(
            allEventTypes = CalendarViewModel.DEFAULT_EVENT_TYPES + customEventTypes,
            hiddenEventTypes = hiddenEventTypes,
            showHolidays = showHolidays,
            parentFilter = parentFilter,
            parentNames = parentNames,
            // Only when the family has actually admitted somebody: the chip is absent rather
            // than disabled, so a feature nobody uses costs nothing on screen.
            friendName = calendarFriends.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            onParentFilterChange = { calendarViewModel.setParentFilter(it) },
            onToggleType = { calendarViewModel.toggleEventTypeVisibility(it) },
            onAddCustomType = { calendarViewModel.addCustomEventType(it) },
            onShowHolidaysChange = { calendarViewModel.setShowHolidays(it) },
            onDismiss = { showTypeFilters = false },
            sheetState = typeFilterSheetState
        )
    }
}
