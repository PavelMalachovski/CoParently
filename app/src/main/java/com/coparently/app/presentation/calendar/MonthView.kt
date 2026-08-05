package com.coparently.app.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.coparently.app.R
import com.coparently.app.domain.holidays.Holiday
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
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

/** Full-hue edge marking the first day of a custody run. */
private val CUSTODY_EDGE_WIDTH = 2.dp

/**
 * Classic month grid: always starts at the 1st of the month, pages horizontally
 * between months with follow-the-finger physics (kizitonwose HorizontalCalendar).
 *
 * Day cells show custody coloring (Mom pink / Dad blue), public holidays and
 * parent-coloured event dots. School vacation is a month-level banner above the grid,
 * not a per-day marker.
 *
 * @param eventsByDay Events pre-bucketed per day by `eventsByDay` in `CalendarScreen`. Taking the
 *   index rather than the flat list is deliberate: `dayContent` runs for all 42 cells on every
 *   recomposition, so filtering the whole list per cell cost O(42·N) each time. The agenda card
 *   under the grid reads the same map, so the two cannot disagree.
 */
@Suppress("LongParameterList") // screen-level composable: callbacks are its API surface
@Composable
fun MonthView(
    selectedMonth: YearMonth,
    selectedDate: LocalDate? = null,
    eventsByDay: Map<LocalDate, List<Event>>,
    getCustody: (LocalDate) -> String?,
    onDayClick: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    holidays: Map<LocalDate, Holiday> = emptyMap()
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

    // Propagate to the ViewModel only once the pager has fully settled. Reacting to
    // mid-fling month flips fed selectedMonth back into the effect below and kicked
    // off a programmatic scroll on top of the in-flight fling — which is what made
    // swiping one way animate differently from the other.
    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
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

            HorizontalCalendar(
                state = calendarState,
                modifier = Modifier.fillMaxSize(),
                dayContent = { day ->
                    DayCell(
                        day = day,
                        cellHeight = cellHeight,
                        isSelected = selectedDate == day.date,
                        events = eventsByDay[day.date].orEmpty(),
                        getCustody = getCustody,
                        onDayClick = onDayClick,
                        holiday = holidays[day.date]
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
@Composable
private fun DayCell(
    day: CalendarDay,
    cellHeight: Dp,
    isSelected: Boolean,
    events: List<Event>,
    getCustody: (LocalDate) -> String?,
    onDayClick: (LocalDate) -> Unit,
    holiday: Holiday? = null
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

    val weekendColor = if (isDarkTheme) {
        CoPlanlyColors.WeekendBackgroundDark
    } else {
        CoPlanlyColors.WeekendBackgroundLight.copy(alpha = 0.5f)
    }

    val isPublicHoliday = holiday != null && !holiday.isSchoolVacation

    // Custody is the product's core signal — it wins over holiday/weekend tints.
    // School vacation is intentionally NOT a full-cell fill (it used to drown
    // custody colors); it renders as a thin strip at the bottom instead.
    val backgroundColor = when {
        !isCurrentMonth -> MaterialTheme.colorScheme.surface
        custody == "mom" -> CoPlanlyColors.MomPink.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        custody == "dad" -> CoPlanlyColors.DadBlue.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
        isPublicHoliday -> CoPlanlyColors.HolidayRed.copy(alpha = 0.10f)
        isWeekend -> weekendColor
        else -> MaterialTheme.colorScheme.surface
    }

    // All localized pieces are resolved in composable scope; buildString itself is not one.
    val todayLabel = stringResource(R.string.calendar_day_desc_today)
    val outsideMonthLabel = stringResource(R.string.calendar_day_desc_outside_month)
    val custodyLabel = when (custody) {
        "mom" -> stringResource(
            R.string.calendar_day_desc_with_parent,
            stringResource(R.string.calendar_parent_mom)
        )
        "dad" -> stringResource(
            R.string.calendar_day_desc_with_parent,
            stringResource(R.string.calendar_parent_dad)
        )
        else -> null
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
        eventsLabel?.let {
            append(", ")
            append(it)
            events.firstOrNull()?.let { event ->
                append(": ")
                append(event.title)
            }
        }
    }

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
                color = backgroundColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
            .clickable(onClick = { onDayClick(date) }, onClickLabel = clickLabel)
            .padding(dims.paddingSmall / 2),
        contentAlignment = Alignment.TopCenter
    ) {
        // First day of a custody run gets a full-hue edge, so the pattern is legible without
        // relying on a 14% fill alone — where a run starts is the thing parents actually scan for.
        if (isCurrentMonth && custody != null && custody != getCustody(date.minusDays(1))) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(CUSTODY_EDGE_WIDTH)
                    .background(
                        color = if (custody == "mom") {
                            CoPlanlyColors.MomPink
                        } else {
                            CoPlanlyColors.DadBlue
                        },
                        shape = RoundedCornerShape(1.dp)
                    )
            )
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

/** Most event dots a cell shows before collapsing the rest into a "+". */
private const val MAX_EVENT_DOTS = 3

/** Diameter of an event dot in a month cell. */
private val EVENT_DOT_SIZE = 6.dp

/** Dot opacity on leading/trailing days from the neighbouring months. */
private const val OUTSIDE_MONTH_DOT_ALPHA = 0.4f
