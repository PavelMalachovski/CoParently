package com.coparently.app.presentation.calendar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.coparently.app.R
import com.coparently.app.domain.holidays.Holiday
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.common.ParentNames
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Months scrollable to each side of the visible month in the pager. */
private const val MONTH_PAGER_RANGE = 24L

/** Days per week for the weekday header. */
private const val DAYS_PER_WEEK = 7L

/** Public-holiday tint strength, drawn over the cell's base fill. */
private const val HOLIDAY_TINT_ALPHA = 0.10f


/** Full-hue edge marking the first day of a custody run. */

/**
 * Classic month grid: always starts at the 1st of the month, pages horizontally
 * between months with follow-the-finger physics (kizitonwose HorizontalCalendar).
 *
 * Day cells show custody colouring (slot 1 pink / slot 2 blue), public holidays and
 * parent-coloured event dots. School vacation is a month-level banner above the grid,
 * not a per-day marker.
 *
 * @param parentNames Resolves a slot to that parent's name, for the cells' accessibility
 *   descriptions — the grid says the colour out loud for anyone not reading it.
 * @param eventsByDay Events pre-bucketed per day by `eventsByDay` in `CalendarScreen`. Taking the
 *   index rather than the flat list is deliberate: `dayContent` runs for all 42 cells on every
 *   recomposition, so filtering the whole list per cell cost O(42·N) each time.
 * @param getProposedCustody What a **pending** custody proposal would make of a day, or null when
 *   nothing is pending. Separate from [getCustody] for the same reason [pendingSwapDates] is: a
 *   proposal has changed nothing yet. The days it *would* move are washed in the proposed
 *   parent's hue at a lower alpha, over the agreed day, which keeps its full strength underneath.
 * @param pendingSwapDates Dates a one-off swap is being negotiated on. Deliberately separate from
 *   [getCustody]: a pending swap has changed nothing about whose day it is, and saying otherwise
 *   in the cell's colour would be a lie both parents act on.
 * @param onDayLongClick Offers that day to the co-parent. Null when there is nobody to offer it
 *   to — an unpaired account gets no long-press at all, because a swap that applies itself is
 *   just an edit and the custody editor already does that.
 */
// Callbacks are this screen-level composable's API surface; the body carries the pager's
// anchor/settle wiring beside the grid itself.
@Suppress("LongParameterList", "LongMethod")
@Composable
fun MonthView(
    selectedMonth: YearMonth,
    selectedDate: LocalDate? = null,
    eventsByDay: Map<LocalDate, List<Event>>,
    getCustody: (LocalDate) -> String?,
    getProposedCustody: (LocalDate) -> String? = { null },
    parentNames: ParentNames,
    onDayClick: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    holidays: Map<LocalDate, Holiday> = emptyMap(),
    pendingSwapDates: Set<LocalDate> = emptySet(),
    onDayLongClick: ((LocalDate) -> Unit)? = null
) {
    val firstDayOfWeek = remember { DayOfWeek.MONDAY }

    // The pager's loaded month range is anchored to a stable month and only
    // re-anchors on a far jump (Today button / date picker). Keeping it fixed while
    // paging is what makes swipes symmetric: previously the range was keyed on
    // selectedMonth, so every settle shifted it and one direction visibly jumped.
    var anchorMonth by remember { mutableStateOf(selectedMonth) }
    LaunchedEffect(selectedMonth) {
        val distance = ChronoUnit.MONTHS.between(anchorMonth, selectedMonth)
        if (distance <= -MONTH_PAGER_RANGE || distance >= MONTH_PAGER_RANGE) {
            anchorMonth = selectedMonth
        }
    }

    val calendarState = rememberCalendarState(
        startMonth = remember(anchorMonth) { anchorMonth.minusMonths(MONTH_PAGER_RANGE) },
        endMonth = remember(anchorMonth) { anchorMonth.plusMonths(MONTH_PAGER_RANGE) },
        firstVisibleMonth = selectedMonth,
        firstDayOfWeek = firstDayOfWeek,
        // Pad every month to a full 6-row grid. The library default (EndOfRow) gives months
        // 5 or 6 rows, so their heights differ and paging between a short and a tall month
        // looks uneven in one direction — a fixed 6-row grid makes left/right symmetric.
        outDateStyle = OutDateStyle.EndOfGrid
    )

    // Propagate to the ViewModel only once the pager has fully settled *on a month
    // boundary*. Reacting to mid-fling month flips fed selectedMonth back into the effect
    // below and kicked off a programmatic scroll on top of the in-flight fling — which is
    // what made swiping one way animate differently from the other. The boundary check
    // matters with the custom settle below: between the finger lifting and the settle
    // animation starting there is one idle frame, and reporting the half-scrolled first
    // month in that gap would move the header title mid-animation.
    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && calendarState.isSettledOnBoundary()) {
                    val visibleMonth = calendarState.firstVisibleMonth.yearMonth
                    if (visibleMonth != selectedMonth) {
                        onMonthChange(visibleMonth)
                    }
                }
            }
    }

    // External month change (Today button, date picker) -> animate the pager to it.
    // Never do this while the user is mid-swipe: a programmatic scroll fighting the
    // fling is exactly what made left/right swipes look different.
    LaunchedEffect(selectedMonth) {
        if (!calendarState.isScrollInProgress &&
            calendarState.firstVisibleMonth.yearMonth != selectedMonth
        ) {
            calendarState.animateScrollToMonth(selectedMonth)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        WeekdayHeader(firstDayOfWeek)

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val cellHeight = remember(maxHeight) {
                val calculated = maxHeight / 6
                if (calculated < 48.dp) 48.dp else calculated
            }

            // The settle animation is ours, not the library's. `calendarScrollPaged = true`
            // snaps with an internal spring whose feel cannot be configured and, in practice,
            // did not read the same in both directions. So paging is taken over: the drag
            // still follows the finger (a CLAUDE.md invariant), but the release velocity is
            // consumed in onPreFling and the page settles with one explicit tween — the same
            // duration and easing whichever way the user swiped.
            val settleScope = rememberCoroutineScope()
            val settleConnection = remember(calendarState) {
                object : NestedScrollConnection {
                    private var settleJob: Job? = null

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        settleJob?.cancel()
                        settleJob = settleScope.launch {
                            // The gesture's own (now zero-velocity) fling still holds the
                            // scroll mutex for a frame; starting our animation under it would
                            // get the animation cancelled at UserInput priority. Wait it out.
                            snapshotFlow { calendarState.isScrollInProgress }.first { !it }
                            calendarState.settleToNearestMonth(available.x)
                        }
                        return available
                    }
                }
            }

            HorizontalCalendar(
                state = calendarState,
                // Not the library's paged snapping — the nestedScroll connection above
                // settles instead, with a direction-independent animation.
                calendarScrollPaged = false,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(settleConnection),
                dayContent = { day ->
                    DayCell(
                        day = day,
                        cellHeight = cellHeight,
                        isSelected = selectedDate == day.date,
                        events = eventsByDay[day.date].orEmpty(),
                        getCustody = getCustody,
                        getProposedCustody = getProposedCustody,
                        parentNames = parentNames,
                        onDayClick = onDayClick,
                        holiday = holidays[day.date],
                        isSwapPending = day.date in pendingSwapDates,
                        onDayLongClick = onDayLongClick
                    )
                }
            )
        }
    }
}

/**
 * Weekday header row (Mon, Tue, Wed, etc.)
 */
@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek) {
    val dims = dimensions()
    // Resolved here: the semantics lambda is not a composable context.
    val headerDescription = stringResource(R.string.calendar_weekday_header_description)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dims.buttonHeight * 0.8f)
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                contentDescription = headerDescription
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val weekdays = remember(firstDayOfWeek) {
            (0L until DAYS_PER_WEEK).map { firstDayOfWeek.plus(it) }
        }

        weekdays.forEach { dayOfWeek ->
            val isToday = dayOfWeek == LocalDate.now().dayOfWeek

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(dims.paddingSmall / 8)
                    .background(
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(dims.cornerRadius / 2)
                    )
                    .padding(dims.paddingSmall / 2),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Normal,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Individual day cell: day number, custody/holiday backgrounds and event dots.
 */
// A day cell renders many orthogonal visual states (today/selected/custody/
// holiday/vacation/events) — the branching is inherent to the design.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: CalendarDay,
    cellHeight: Dp,
    isSelected: Boolean,
    events: List<Event>,
    getCustody: (LocalDate) -> String?,
    getProposedCustody: (LocalDate) -> String?,
    parentNames: ParentNames,
    onDayClick: (LocalDate) -> Unit,
    holiday: Holiday? = null,
    isSwapPending: Boolean = false,
    onDayLongClick: ((LocalDate) -> Unit)? = null
) {
    val dims = dimensions()
    val date = day.date
    val isCurrentMonth = day.position == DayPosition.MonthDate
    val isToday = CustodyHelper.isToday(date)
    val custody = getCustody(date)
    val isWeekend = CustodyHelper.isWeekend(date)
    // Use the actually-rendered theme (the app can force light while the system is
    // dark); isSystemInDarkTheme() would pick the dark weekend fill on a light grid.
    val isDarkTheme =
        MaterialTheme.colorScheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD

    val isPublicHoliday = holiday != null && !holiday.isSchoolVacation

    // Two layers, not one pick: see DayCellFills for why a single `when` made the weekend
    // unreachable on every account with a custody model. Custody is still the product's core
    // signal and still wins over the holiday tint. School vacation is intentionally NOT a
    // full-cell fill at all (it used to drown custody colors); it renders as a thin strip at
    // the bottom instead.
    val previousCustody = getCustody(date.minusDays(1))
    val proposedCustody = getProposedCustody(date)
    val fill = DayCellFills.monthCell(
        isWeekend = isWeekend,
        isCurrentMonth = isCurrentMonth,
        custody = custody,
        previousCustody = previousCustody,
        isPublicHoliday = isPublicHoliday,
        proposedCustody = proposedCustody
    )
    val baseColor = when (fill.base) {
        DayCellBase.WEEKEND ->
            if (isDarkTheme) {
                CoPlanlyColors.WeekendBackgroundDark
            } else {
                CoPlanlyColors.WeekendBackgroundLight
            }
        DayCellBase.SURFACE -> MaterialTheme.colorScheme.surface
    }
    val overlayColor = when (fill.overlay) {
        DayCellOverlay.CUSTODY_MOM ->
            CoPlanlyColors.MomPink.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        DayCellOverlay.CUSTODY_DAD ->
            CoPlanlyColors.DadBlue.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        DayCellOverlay.PUBLIC_HOLIDAY -> CoPlanlyColors.HolidayRed.copy(alpha = HOLIDAY_TINT_ALPHA)
        DayCellOverlay.TODAY, DayCellOverlay.NONE -> Color.Transparent
    }

    // The parent the child is coming *from* on a handover day, at the same custody alpha as the
    // overlay: the two triangles must read as one system, not as a tint and a competing block.
    val handoverColor = when (fill.handoverFrom) {
        DayCellOverlay.CUSTODY_MOM ->
            CoPlanlyColors.MomPink.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        DayCellOverlay.CUSTODY_DAD ->
            CoPlanlyColors.DadBlue.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        else -> null
    }

    // A pending proposal's preview: the proposed parent's hue at a lower alpha than an agreed
    // day, laid over the agreed fill rather than replacing it. Same colour, less of it — the
    // meaning is "this is what it would become", and a different colour would read as a
    // different parent. The two translucent hues blend into something that is neither, which is
    // the honest rendering of a day nobody has agreed on; the cell's description says so in
    // words.
    val proposalColor = when (fill.pendingProposalFor) {
        DayCellOverlay.CUSTODY_MOM -> CoPlanlyColors.MomPink.copy(alpha = CoPlanlyColors.PROPOSAL_TINT_ALPHA)
        DayCellOverlay.CUSTODY_DAD -> CoPlanlyColors.DadBlue.copy(alpha = CoPlanlyColors.PROPOSAL_TINT_ALPHA)
        else -> null
    }

    // All localized pieces are resolved in composable scope; buildString itself is not one.
    val todayLabel = stringResource(R.string.calendar_day_desc_today)
    val outsideMonthLabel = stringResource(R.string.calendar_day_desc_outside_month)
    val custodyLabel = custody?.let {
        stringResource(R.string.calendar_day_desc_with_parent, parentNames.labelFor(it))
    }
    val proposalLabel = fill.pendingProposalFor?.let {
        stringResource(
            R.string.calendar_day_desc_proposal_pending,
            parentNames.labelFor(proposedCustody.orEmpty())
        )
    }
    val swapLabel = if (isSwapPending) {
        stringResource(R.string.calendar_day_desc_swap_pending)
    } else {
        null
    }
    val handoverLabel = fill.handoverFrom?.let {
        stringResource(
            R.string.calendar_day_desc_handover,
            parentNames.labelFor(previousCustody.orEmpty()),
            parentNames.labelFor(custody.orEmpty())
        )
    }
    val eventsLabel = if (events.isNotEmpty()) {
        pluralStringResource(R.plurals.calendar_day_desc_events, events.size, events.size)
    } else {
        null
    }
    val semanticDescription = buildString {
        append(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())))
        if (isToday) {
            append(", ")
            append(todayLabel)
        }
        if (!isCurrentMonth) {
            append(", ")
            append(outsideMonthLabel)
        }
        holiday?.let {
            append(", ")
            append(if (Locale.getDefault().language == "cs") it.nameCs else it.nameEn)
        }
        custodyLabel?.let {
            append(", ")
            append(it)
        }
        handoverLabel?.let {
            append(", ")
            append(it)
        }
        proposalLabel?.let {
            append(", ")
            append(it)
        }
        swapLabel?.let {
            append(", ")
            append(it)
        }
        eventsLabel?.let {
            append(", ")
            append(it)
            events.firstOrNull()?.let { event ->
                append(": ")
                append(event.title)
            }
        }
    }

    val swapClickLabel = stringResource(R.string.calendar_day_long_click_label)
    val clickLabel = stringResource(
        R.string.calendar_day_click_label,
        date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cellHeight)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = semanticDescription
                role = Role.Button
            }
            .padding(dims.paddingSmall / 8)
            .background(
                color = baseColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
            .background(
                color = overlayColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
            // A handover day is split on a diagonal: the parent who had the child yesterday in
            // the top-left triangle, today's parent in the bottom-right, reading the way time
            // does. This replaces the full-hue left edge that used to mark the first day of a
            // custody run — both answered "the child changes hands here", and two answers to one
            // question is what the design refresh removed elsewhere. The diagonal answers more:
            // it names *both* parents, which the edge could not.
            //
            // Two `drawPath` calls rather than one diagonal gradient: a gradient would smear the
            // boundary across the cell, and the point is that the day belongs to one parent until
            // the handover and the other after it. The first repaints the triangle with the
            // opaque base so the overlay's tint underneath is replaced rather than blended with —
            // two translucent parent hues stacked would read as a muddy third colour — and the
            // second lays yesterday's parent over it at the same custody alpha. The weekend base
            // still shows through both halves, so this file's invariant survives.
            .clip(RoundedCornerShape(dims.cornerRadius / 2))
            .drawBehind {
                handoverColor?.let { color ->
                    val triangle = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(triangle, baseColor)
                    drawPath(triangle, color)
                }
                // After the diagonal, deliberately: the preview is about the whole day, so a
                // handover boundary drawn inside it must be previewed too rather than punching
                // a hole in it.
                proposalColor?.let { drawRect(it) }
            }
            // Long-press offers the day to the co-parent. A day from a neighbouring month is
            // excluded for the same reason it takes no overlay: it is shown for context, not to
            // be acted on, and its dimmed number already says so.
            .combinedClickable(
                onClick = { onDayClick(date) },
                onClickLabel = clickLabel,
                onLongClick = onDayLongClick
                    ?.takeIf { isCurrentMonth }
                    ?.let { offer -> { offer(date) } },
                onLongClickLabel = swapClickLabel
            )
            .padding(dims.paddingSmall / 2),
        contentAlignment = Alignment.TopCenter
    ) {
        // Two arrows on a date a swap is being negotiated on. Not a colour: the cell's colour
        // still means whose day it is *now*, and a pending swap has not changed that. The arrows
        // say "this is being discussed", which is a different fact and deserves its own channel.
        // They carry no content description of their own — the cell has one description and it
        // already names the pending swap; two would announce it twice.
        if (isSwapPending) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SWAP_ARROW_SIZE)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SWAP_ARROW_SIZE)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Day number: filled circle for today, outlined ring for the selected day
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
                    .background(
                        color = when {
                            isToday -> MaterialTheme.colorScheme.primary
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday || isPublicHoliday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                        !isCurrentMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        // Red 700 is only 3.44:1 on DarkSurface; Red 400 clears AA there.
                        isPublicHoliday -> if (isDarkTheme) {
                            CoPlanlyColors.HolidayRedDark
                        } else {
                            CoPlanlyColors.HolidayRed
                        }
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Parent-coloured dots, one per event up to MAX_EVENT_DOTS, then a "+" marker.
            //
            // These replace a single full-width event chip plus a "+N" counter. The chip's
            // label rendered at roughly 9sp inside a 48dp cell — below the legibility floor —
            // and it showed only the *first* event, so a day with three things on it looked
            // much like a day with one. Dots make the count the signal and hand the titles to
            // the agenda card under the grid, where there is room to read them.
            if (events.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    events.take(MAX_EVENT_DOTS).forEach { event ->
                        Box(
                            modifier = Modifier
                                .size(EVENT_DOT_SIZE)
                                .background(
                                    color = eventDotColor(event.parentOwner, isCurrentMonth),
                                    shape = CircleShape
                                )
                        )
                    }
                    if (events.size > MAX_EVENT_DOTS) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dot colour for an event, dimmed on days outside the shown month.
 *
 * Full-strength parent hue — a dot is a fill, not text, so the AA floor that forces the event
 * *chip* onto the darker `MomChipFill`/`DadChipFill` variants does not apply here.
 */
@Composable
private fun eventDotColor(parentOwner: String, isCurrentMonth: Boolean): Color {
    val base = when (parentOwner) {
        "mom" -> CoPlanlyColors.MomPink
        "dad" -> CoPlanlyColors.DadBlue
        else -> MaterialTheme.colorScheme.tertiary
    }
    return if (isCurrentMonth) base else base.copy(alpha = OUTSIDE_MONTH_DOT_ALPHA)
}

/**
 * Settles the pager onto a month boundary with one deliberate, direction-independent tween.
 *
 * The fling velocity decides *which* month wins — a real fling turns the page the way it was
 * thrown, a slow release goes to whichever month holds more of the screen — but never *how*
 * the page gets there: the animation is the same [MONTH_SETTLE_MS] tween either way, which is
 * the whole point of taking snapping away from the library.
 *
 * Offsets are LTR-only, which every shipped locale (en/cs/de/ru/uk) is.
 */
private suspend fun CalendarState.settleToNearestMonth(velocityX: Float) {
    val visible = layoutInfo.visibleMonthsInfo
    if (visible.isEmpty()) return
    val first = visible.first()
    val target = when {
        // Finger flung left -> the next month (negative velocity scrolls content forward).
        velocityX < -MONTH_SETTLE_FLING_THRESHOLD -> visible.getOrNull(1) ?: first
        // Finger flung right -> back to the month peeking in at the start edge.
        velocityX > MONTH_SETTLE_FLING_THRESHOLD -> first
        // No real fling: the month holding more than half the viewport wins.
        else -> if (first.offset < -first.size / 2) visible.getOrNull(1) ?: first else first
    }
    // target.offset is the signed distance from the viewport's start edge, so scrolling by
    // exactly it aligns the month flush — forward for a positive offset, back for a negative.
    animateScrollBy(
        value = target.offset.toFloat(),
        animationSpec = tween(durationMillis = MONTH_SETTLE_MS, easing = FastOutSlowInEasing)
    )
}

/** Whether the first visible month sits flush with the viewport's start edge. */
private fun CalendarState.isSettledOnBoundary(): Boolean {
    val first = layoutInfo.visibleMonthsInfo.firstOrNull() ?: return false
    return kotlin.math.abs(first.offset) <= 1
}

/** How long a month page takes to settle, in either direction — the calm, deliberate slide. */
private const val MONTH_SETTLE_MS = 500

/** Release velocity (px/s) below which a swipe settles to the nearest month, not the thrown one. */
private const val MONTH_SETTLE_FLING_THRESHOLD = 300f

/** Most event dots a cell shows before collapsing the rest into a "+". */
private const val MAX_EVENT_DOTS = 3

/** Diameter of an event dot in a month cell. */
private val EVENT_DOT_SIZE = 6.dp

/**
 * The pending-swap arrows. Small enough not to compete with the day number or the event dots —
 * a swap under discussion is a footnote on the cell, not its headline.
 */
private val SWAP_ARROW_SIZE = 10.dp

/** Dot opacity on leading/trailing days from the neighbouring months. */
private const val OUTSIDE_MONTH_DOT_ALPHA = 0.4f
