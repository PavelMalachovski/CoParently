package com.coparently.app.presentation.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.Event
import com.coparently.app.presentation.theme.CoPlanlyColors
import com.coparently.app.presentation.theme.dimensions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** Virtual center page of the day/week pager (allows ~3 years of swiping each way). */
private const val PAGER_BASE_PAGE = 1200

/** Height of the custody band drawn above the week's day headers. */
private val CUSTODY_BAND_HEIGHT = 16.dp

/** Full-hue bar on an event block's start edge; the only identity carrier in week view. */
private val EVENT_ACCENT_BAR_WIDTH = 3.dp

/** Opacity of the hour-grid outline. Enough to read on DarkSurface without becoming a cage. */
private const val GRIDLINE_ALPHA = 0.55f

/**
 * Hourly view for day/week calendar views.
 * Horizontal swiping between days/weeks uses a [HorizontalPager], so the content
 * follows the finger with fling physics instead of a fixed swipe threshold.
 */
@Suppress("LongParameterList", "LongMethod") // screen-level composable: callbacks are its API surface
@Composable
fun DayWeekView(
    selectedDate: LocalDate,
    daysCount: Int,
    events: List<Event>,
    getCustody: (LocalDate) -> String?,
    onDateChange: (LocalDate) -> Unit,
    onEventClick: (String) -> Unit,
    onAddEventClick: (LocalDate, Int) -> Unit = { _, _ -> },
    onEventDragDrop: ((String, LocalDate, Int) -> Unit)? = null,
    onEventResize: ((String, LocalDateTime?, LocalDateTime?) -> Unit)? = null,
    onEventDelete: ((String) -> Unit)? = null,
    onEventLongPressStart: ((String) -> Unit)? = null,
    onEventLongPressEnd: (() -> Unit)? = null,
    onDragOverDeleteButton: ((Boolean) -> Unit)? = null,
    holidays: Map<LocalDate, com.coparently.app.domain.holidays.Holiday> = emptyMap()
) {
    // The pager is anchored at a fixed date; each page offsets it by daysCount.
    // External date changes (Today button, month picker) re-anchor the pager.
    var anchorDate by remember { mutableStateOf(selectedDate) }
    var lastPagerDate by remember { mutableStateOf(selectedDate) }
    val pagerState = rememberPagerState(initialPage = PAGER_BASE_PAGE) { PAGER_BASE_PAGE * 2 + 1 }

    LaunchedEffect(selectedDate, daysCount) {
        if (selectedDate != lastPagerDate) {
            anchorDate = selectedDate
            lastPagerDate = selectedDate
            pagerState.scrollToPage(PAGER_BASE_PAGE)
        }
    }

    LaunchedEffect(pagerState, daysCount) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val newDate = anchorDate.plusDays((page - PAGER_BASE_PAGE).toLong() * daysCount)
            if (newDate != lastPagerDate) {
                lastPagerDate = newDate
                onDateChange(newDate)
            }
        }
    }

    // Hour scroll position shared between pages so swiping keeps the time window
    var savedHourIndex by remember {
        mutableIntStateOf((java.time.LocalTime.now().hour - 1).coerceIn(0, 23))
    }
    var savedHourOffset by remember { mutableIntStateOf(0) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val pageDate = remember(page, anchorDate, daysCount) {
            anchorDate.plusDays((page - PAGER_BASE_PAGE).toLong() * daysCount)
        }
        val scrollState = rememberLazyListState(savedHourIndex, savedHourOffset)
        if (page == pagerState.settledPage) {
            LaunchedEffect(scrollState) {
                snapshotFlow {
                    scrollState.firstVisibleItemIndex to scrollState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    savedHourIndex = index
                    savedHourOffset = offset
                }
            }
        }
        DayWeekPage(
            selectedDate = pageDate,
            daysCount = daysCount,
            events = events,
            getCustody = getCustody,
            scrollState = scrollState,
            onEventClick = onEventClick,
            onAddEventClick = onAddEventClick,
            onEventDragDrop = onEventDragDrop,
            onEventResize = onEventResize,
            onEventDelete = onEventDelete,
            onEventLongPressStart = onEventLongPressStart,
            onEventLongPressEnd = onEventLongPressEnd,
            onDragOverDeleteButton = onDragOverDeleteButton,
            holidays = holidays
        )
    }
}

/**
 * One pager page: the fixed hour grid plus day columns and event overlay for
 * [selectedDate] (day view) or the week containing it (week view).
 */
// Pre-existing hour-grid body moved out of DayWeekView unchanged; splitting it
// further is tracked separately (was baselined under the old function name).
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun DayWeekPage(
    selectedDate: LocalDate,
    daysCount: Int,
    events: List<Event>,
    getCustody: (LocalDate) -> String?,
    scrollState: LazyListState,
    onEventClick: (String) -> Unit,
    onAddEventClick: (LocalDate, Int) -> Unit = { _, _ -> },
    onEventDragDrop: ((String, LocalDate, Int) -> Unit)? = null,
    onEventResize: ((String, LocalDateTime?, LocalDateTime?) -> Unit)? = null,
    onEventDelete: ((String) -> Unit)? = null,
    onEventLongPressStart: ((String) -> Unit)? = null,
    onEventLongPressEnd: (() -> Unit)? = null,
    onDragOverDeleteButton: ((Boolean) -> Unit)? = null,
    holidays: Map<LocalDate, com.coparently.app.domain.holidays.Holiday> = emptyMap()
) {
    val dims = dimensions()
    val hours = (0..23).toList()
    val density = LocalDensity.current
    // Match the actually-rendered theme, not the system one (the app can force light while
    // the system is dark). Hoisted here because both the day headers and the hour-grid
    // columns below need it to pick the readable member of a colour pair.
    val isDarkTheme =
        MaterialTheme.colorScheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD
    val hourCellHeight = dims.buttonHeight * 1.07f
    val hourCellHeightPx = remember(hourCellHeight, density) {
        with(density) { hourCellHeight.toPx() }
    }

    // The AnimatedContent wrappers below no longer animate (the pager provides the
    // motion); a static direction keeps their transitionSpec inert.
    val swipeDirection = 0

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Fixed header row with modern design - 1.5x larger
        // Animated header with optimized animation (200ms slide + 150ms fade)
        AnimatedContent(
            targetState = selectedDate,
            transitionSpec = {
                val direction = swipeDirection
                (
                    slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = 200,
                            easing = FastOutSlowInEasing
                        ),
                        initialOffsetX = { fullWidth -> fullWidth * direction }
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = 150,
                            easing = LinearEasing
                        )
                    )
                    ) togetherWith
                    (
                        slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing
                            ),
                            targetOffsetX = { fullWidth -> -fullWidth * direction }
                        ) + fadeOut(
                            animationSpec = tween(
                                durationMillis = 150,
                                easing = LinearEasing
                            )
                        )
                        )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(dims.buttonHeight * 1.6f) // ~90dp for compact
        ) { currentDate ->
            val currentDates = DateRangeHelper.rememberDateRange(currentDate, daysCount)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.buttonHeight * 1.6f) // ~90dp for compact
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = dims.paddingSmall)
            ) {
                // Custody band across the visible week. The column tint alone answers "whose day
                // is this?" only once the eye has settled on a column; the band answers it for
                // the whole week at a glance, and shows where the handover falls.
                if (daysCount > 1) {
                    CustodyWeekBand(
                        dates = currentDates,
                        getCustody = getCustody,
                        gutterWidth = dims.iconSize * 2.17f,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }

                // Week number for 3 days and week views (absolutely positioned, doesn't affect layout)
                if (daysCount >= 3) {
                    val weekFields = WeekFields.ISO // Always use Monday-first week
                    val firstDate = currentDates.firstOrNull() ?: currentDate
                    val weekNumber = firstDate.get(weekFields.weekOfWeekBasedYear())

                    Box(
                        modifier = Modifier
                            .width(dims.iconSize * 1.33f) // ~32dp for compact
                            .fillMaxHeight()
                            .align(Alignment.CenterStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = weekNumber.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                // Main row with time column and dates - exactly matches content structure
                // This row is identical to content row, ensuring perfect alignment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Time column space - fixed width for consistency (matches content layout)
                    Box(
                        modifier = Modifier
                            .width(dims.iconSize * 2.17f) // ~52dp for compact
                            .fillMaxHeight()
                    )

                    currentDates.forEach { date ->
                        val isToday = date == LocalDate.now()
                        val holiday = holidays[date]
                        val isPublicHoliday = holiday != null && !holiday.isSchoolVacation

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = date.format(DateTimeFormatter.ofPattern("EEE")),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Normal,
                                    color = if (isToday) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        isPublicHoliday -> if (isDarkTheme) {
                                            CoPlanlyColors.HolidayRedDark
                                        } else {
                                            CoPlanlyColors.HolidayRed
                                        }
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                // Holiday name shown in single-day view where there is room
                                if (holiday != null && daysCount == 1) {
                                    val holidayName = if (Locale.getDefault().language == "cs") {
                                        holiday.nameCs
                                    } else {
                                        holiday.nameEn
                                    }
                                    Text(
                                        text = holidayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        // Holiday/vacation names are text, so each needs the
                                        // member of its pair that clears AA on this theme.
                                        color = when {
                                            isPublicHoliday && isDarkTheme -> CoPlanlyColors.HolidayRedDark
                                            isPublicHoliday -> CoPlanlyColors.HolidayRed
                                            isDarkTheme -> CoPlanlyColors.VacationTint
                                            else -> CoPlanlyColors.VacationTintLight
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main container: Box to overlay events on top of scrollable grid
        Box(
            modifier = Modifier.weight(1f)
        ) {
            // Scrollable content - time stays in place, only days animate
            // Use single scrollState shared across all dates to preserve scroll position
            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(hours.size) { hourIndex ->
                    val hour = hours[hourIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Hour label - static, outside AnimatedContent
                        // Fixed width to ensure consistent layout and single-line time display
                        Box(
                            modifier = Modifier
                                .width(dims.iconSize * 2.17f) // ~52dp for compact
                                .height(hourCellHeight) // ~60dp for compact
                                .padding(top = dims.paddingSmall / 2),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = String.format("%02d:00", hour),
                                // labelSmall (11sp) rather than bodyMedium: the hour gutter is
                                // narrow, and this keeps the rendered size while still scaling
                                // with the user's font-size setting.
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }

                        // Day columns - animated, optimized animation (200ms slide + 150ms fade)
                        AnimatedContent(
                            targetState = selectedDate,
                            transitionSpec = {
                                val direction = swipeDirection
                                (
                                    slideInHorizontally(
                                        animationSpec = tween(
                                            durationMillis = 200,
                                            easing = FastOutSlowInEasing
                                        ),
                                        initialOffsetX = { fullWidth -> fullWidth * direction }
                                    ) + fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 150,
                                            easing = LinearEasing
                                        )
                                    )
                                    ) togetherWith
                                    (
                                        slideOutHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 200,
                                                easing = FastOutSlowInEasing
                                            ),
                                            targetOffsetX = { fullWidth -> -fullWidth * direction }
                                        ) + fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 150,
                                                easing = LinearEasing
                                            )
                                        )
                                        )
                            },
                            modifier = Modifier.weight(1f)
                        ) { currentDate ->
                            // Day columns for this hour - use optimized DateRangeHelper
                            val currentDates = DateRangeHelper.rememberDateRange(currentDate, daysCount)

                            // Background cells only (no events)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                currentDates.forEachIndexed { dayIndex, date ->
                                    val isToday = date == LocalDate.now()
                                    val custody = getCustody(date)
                                    val isWeekend = CustodyHelper.isWeekend(date)
                                    val weekendColor = if (isDarkTheme) {
                                        CoPlanlyColors.WeekendBackgroundDark.copy(alpha = 0.5f)
                                    } else {
                                        CoPlanlyColors.WeekendBackgroundLight.copy(alpha = 0.3f)
                                    }
                                    // Custody wins over the today tint, matching MonthView:
                                    // it is the product's core signal, and today already
                                    // reads as today from its coloured header above. The old
                                    // order hid custody on the one column parents check first.
                                    val backgroundColor = when {
                                        custody == "mom" ->
                                            CoPlanlyColors.MomPink.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
                                        custody == "dad" ->
                                            CoPlanlyColors.DadBlue.copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
                                        isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
                                        isWeekend -> weekendColor
                                        else -> MaterialTheme.colorScheme.surface
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(hourCellHeight)
                                            .background(
                                                color = backgroundColor,
                                                shape = RoundedCornerShape(dims.paddingSmall)
                                            )
                                            // Hour cells had no outline at all, so on a dark
                                            // surface the grid read as one flat block and the
                                            // hour boundaries were invisible.
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = GRIDLINE_ALPHA),
                                                shape = RoundedCornerShape(dims.paddingSmall)
                                            )
                                            .clickable {
                                                onAddEventClick(date, hour)
                                            }
                                            .semantics {
                                                contentDescription = "Time slot at ${String.format("%02d:00", hour)} on ${date.format(DateTimeFormatter.ofPattern("MMM dd"))}. Tap to add event."
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Events overlay layer - positioned absolutely above the grid
            val currentDates = DateRangeHelper.rememberDateRange(selectedDate, daysCount)
            val scrollOffset = scrollState.firstVisibleItemScrollOffset.toFloat()
            val firstVisibleHour = scrollState.firstVisibleItemIndex

            // Calculate layout dimensions
            val hourLabelWidth = dims.iconSize * 2.17f
            val horizontalPadding = 8.dp
            val daySpacing = 4.dp
            val headerHeight = dims.buttonHeight * 1.6f // Match header height

            Box(
                modifier = Modifier
                    .matchParentSize() // Match parent Box size (same as LazyColumn)
                    .padding(
                        start = horizontalPadding + hourLabelWidth,
                        end = horizontalPadding
                        // top = headerHeight  <- REMOVED: This was causing the time offset issue!
                    )
                    .clipToBounds() // Prevent events from drawing over the header
            ) {
                // Track container width for column calculations
                var containerWidthPx by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            containerWidthPx = coordinates.size.width.toFloat()
                        }
                ) {
                    if (containerWidthPx > 0f) {
                        val spacingPx = with(density) { daySpacing.toPx() }
                        val totalSpacingPx = spacingPx * (currentDates.size - 1)
                        val columnWidth = (containerWidthPx - totalSpacingPx) / currentDates.size
                        val totalScroll = firstVisibleHour * hourCellHeightPx + scrollOffset
                        val firstHour = hours.first()

                        // Vertical pixel offset (from the top of the visible grid) for a time
                        fun yOffsetFor(time: LocalDateTime): Float {
                            val minutesFromTop = (time.hour - firstHour) * 60f + time.minute + time.second / 60f
                            return minutesFromTop / 60f * hourCellHeightPx - totalScroll
                        }

                        currentDates.forEachIndexed { dayIndex, date ->
                            val dayColumnX = dayIndex * (columnWidth + spacingPx)

                            // Multi-day/overnight events are clamped to this day and laid out
                            // in side-by-side lanes when they overlap in time.
                            layoutDayEvents(events, date).forEach { seg ->
                                val laneWidth = columnWidth / seg.laneCount
                                val x = dayColumnX + seg.lane * laneWidth
                                val y = yOffsetFor(seg.segStart)

                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = with(density) { x.toDp() },
                                            y = with(density) { y.toDp() }
                                        )
                                        .width(with(density) { laneWidth.toDp() })
                                        .padding(horizontal = 1.dp)
                                ) {
                                    EventChip(
                                        event = seg.event,
                                        onClick = { onEventClick(seg.event.id) },
                                        columnWidthPx = laneWidth,
                                        hourHeightPx = hourCellHeightPx,
                                        baseDate = date,
                                        baseHour = seg.segStart.hour,
                                        onDragDrop = onEventDragDrop,
                                        onResize = onEventResize,
                                        onDelete = onEventDelete,
                                        onLongPressStart = onEventLongPressStart,
                                        onLongPressEnd = onEventLongPressEnd,
                                        onDragOverDeleteButton = onDragOverDeleteButton,
                                        displayStart = seg.segStart,
                                        displayEnd = seg.segEnd,
                                        resizable = !seg.clamped,
                                        draggable = !seg.clamped,
                                        showLabels = daysCount == 1
                                    )
                                }
                            }

                            // Current-time indicator (red line + dot) on today's column
                            if (date == LocalDate.now()) {
                                val nowY = yOffsetFor(LocalDateTime.now())
                                Row(
                                    modifier = Modifier
                                        .offset(
                                            x = with(density) { dayColumnX.toDp() },
                                            y = with(density) { nowY.toDp() } - 4.dp
                                        )
                                        .width(with(density) { columnWidth.toDp() }),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(NowIndicatorColor, CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(2.dp)
                                            .background(NowIndicatorColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventChip(
    event: Event,
    onClick: () -> Unit,
    columnWidthPx: Float,
    hourHeightPx: Float,
    baseDate: LocalDate,
    baseHour: Int,
    onDragDrop: ((String, LocalDate, Int) -> Unit)?,
    onResize: ((String, LocalDateTime?, LocalDateTime?) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onLongPressStart: ((String) -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
    onDragOverDeleteButton: ((Boolean) -> Unit)? = null,
    // Visible segment of the event within the current day (differs from the real event
    // times for multi-day / overnight events, which are clamped to each day).
    displayStart: LocalDateTime = event.startDateTime,
    displayEnd: LocalDateTime = event.endDateTime ?: event.startDateTime.plusHours(1),
    // Continuation segments of multi-day events are not resizable/movable (ambiguous).
    resizable: Boolean = true,
    draggable: Boolean = true,
    // Week columns are ~40dp wide, where a title renders about two characters before
    // ellipsising. Day view has the room; week view drops the text entirely and lets the
    // parent bar carry identity. The block keeps its contentDescription either way, so a
    // screen reader still announces the full title and time.
    showLabels: Boolean = true
) {
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current

    // Sizing/temp times follow the visible segment; edits still act on the real event.
    val eventStart = displayStart
    val eventEnd = displayEnd

    // Calculate total event duration in minutes
    val totalDuration = java.time.Duration.between(eventStart, eventEnd)
    val totalMinutes = totalDuration.toMinutes().coerceAtLeast(15) // Minimum 15 minutes
    val eventHeightDp = with(density) { (hourHeightPx * totalMinutes / 60f).toDp() }

    // Transparent background colors (more transparent)
    val backgroundColor = when (event.parentOwner) {
        "mom" -> CoPlanlyColors.MomPink.copy(alpha = 0.3f)
        "dad" -> CoPlanlyColors.DadBlue.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
    }

    val borderColor = when (event.parentOwner) {
        "mom" -> CoPlanlyColors.MomPink.copy(alpha = 0.8f)
        "dad" -> CoPlanlyColors.DadBlue.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
    }

    // Full-hue bar on the start edge. This is what carries parent identity in week view, where
    // the block has no room for a label at all.
    val accentColor = when (event.parentOwner) {
        "mom" -> CoPlanlyColors.MomPink
        "dad" -> CoPlanlyColors.DadBlue
        else -> MaterialTheme.colorScheme.tertiary
    }

    // The title is NOT tinted with the parent colour: pink text on a pink fill (and blue on
    // blue) was the same hue at three alphas and unreadable. Identity is carried by the
    // border above; the label just has to be legible.
    val textColor = MaterialTheme.colorScheme.onSurface

    // Drag states
    var isDraggingEvent by remember { mutableStateOf(false) }
    var isResizingStart by remember { mutableStateOf(false) }
    var isResizingEnd by remember { mutableStateOf(false) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }
    var resizeDragStart by remember { mutableStateOf(Offset.Zero) }
    var resizeDragAmountStart by remember { mutableStateOf(0f) }
    var resizeDragAmountEnd by remember { mutableStateOf(0f) }

    // Track if event is over delete button
    var isOverDeleteButton by remember { mutableStateOf(false) }

    // Long press state - track when user is holding the event
    var isLongPressing by remember { mutableStateOf(false) }
    // Calculate temporary times for display during resize, snapped to a 15-minute grid
    val tempStartTime = remember(isResizingStart, resizeDragAmountStart, eventStart) {
        if (isResizingStart) {
            resizedTime(eventStart, resizeDragAmountStart, hourHeightPx)
        } else {
            eventStart
        }
    }

    val tempEndTime = remember(isResizingEnd, resizeDragAmountEnd, eventEnd) {
        if (isResizingEnd) {
            resizedTime(eventEnd, resizeDragAmountEnd, hourHeightPx)
        } else {
            eventEnd
        }
    }

    // Calculate dynamic height based on resize state
    val dynamicHeightDp = if (isResizingStart || isResizingEnd) {
        val heightAdjustment = with(density) {
            when {
                isResizingStart -> -resizeDragAmountStart.toDp()
                isResizingEnd -> resizeDragAmountEnd.toDp()
                else -> 0.dp
            }
        }
        (eventHeightDp + heightAdjustment).coerceAtLeast(24.dp)
    } else {
        eventHeightDp.coerceAtLeast(24.dp)
    }

    // Calculate vertical offset to keep bottom edge fixed when resizing from top
    val verticalOffsetDp = if (isResizingStart) {
        with(density) { resizeDragAmountStart.toDp() }
    } else {
        0.dp
    }

    // Track global position for delete button detection
    var eventGlobalPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dynamicHeightDp)
            .offset(y = verticalOffsetDp)
            .onGloballyPositioned { coordinates ->
                // Store global position of event for delete button detection
                // localToWindow converts local coordinates to window coordinates
                eventGlobalPosition = coordinates.localToWindow(Offset.Zero)
            }
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isDraggingEvent && isOverDeleteButton) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                } else {
                    backgroundColor
                }
            )
            .drawBehind {
                drawRect(
                    color = accentColor,
                    size = Size(EVENT_ACCENT_BAR_WIDTH.toPx(), size.height)
                )
            }
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp)
            )
            .pointerInput(event.id, onDelete, onLongPressStart, onLongPressEnd) {
                if (onDelete != null && onLongPressStart != null && onLongPressEnd != null) {
                    detectTapGestures(
                        onTap = {
                            if (!isLongPressing) {
                                // Normal click - open event
                                onClick()
                            }
                        },
                        onLongPress = {
                            isLongPressing = true
                            onLongPressStart(event.id)
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                } else {
                    // If no delete handler, use normal clickable
                    detectTapGestures(
                        onTap = { onClick() }
                    )
                }
            }
            // Track pointer release when long pressing - restart when isLongPressing changes
            .pointerInput(event.id, isLongPressing) {
                if (isLongPressing && onLongPressEnd != null) {
                    awaitPointerEventScope {
                        while (isLongPressing) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val isPressed = event.changes.firstOrNull()?.pressed == true
                            if (!isPressed) {
                                // Released - hide delete button
                                isLongPressing = false
                                onLongPressEnd()
                                break
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                if (isDraggingEvent) {
                    shadowElevation = 8.dp.toPx()
                    translationX = totalDrag.x
                    translationY = totalDrag.y
                    // Make event more transparent and red-tinted when over delete button
                    alpha = if (isOverDeleteButton) 0.5f else 0.8f
                    if (isOverDeleteButton) {
                        // Add red tint when over delete button
                        // This is handled by changing the background color in the Box
                    }
                }
            }
            .semantics {
                contentDescription = "${event.title} event. ${
                    when {
                        isDraggingEvent -> "Dragging"
                        isResizingStart -> "Resizing start time to ${tempStartTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
                        isResizingEnd -> "Resizing end time to ${tempEndTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
                        isLongPressing -> "Long pressed, delete button shown"
                        else -> "Tap to view, long press to show delete, drag corners to resize"
                    }
                }"
            }
    ) {
        // Event content - center area for drag & drop
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(start = 12.dp, end = 12.dp) // Padding to avoid resize handles
                .pointerInput(
                    columnWidthPx,
                    hourHeightPx,
                    onDragDrop,
                    onDelete,
                    onDragOverDeleteButton,
                    configuration,
                    eventGlobalPosition,
                    draggable
                ) {
                    // Center drag for moving event
                    if (onDragDrop != null && draggable && columnWidthPx > 0f && hourHeightPx > 0f) {
                        val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
                        val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }

                        // Store initial touch position in window coordinates
                        var startPositionInWindow = Offset.Zero

                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                isDraggingEvent = true
                                totalDrag = Offset.Zero
                                // Calculate initial position in window: event position + offset from event top-left
                                startPositionInWindow = eventGlobalPosition + startOffset
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragCancel = {
                                isDraggingEvent = false
                                totalDrag = Offset.Zero
                                isOverDeleteButton = false
                                onDragOverDeleteButton?.invoke(false)
                            },
                            onDragEnd = {
                                if (isDraggingEvent) {
                                    // Check if dropped over delete button area using the last known position
                                    // The position is already tracked in the drag handler
                                    if (isOverDeleteButton && onDelete != null) {
                                        // Delete event
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDelete(event.id)
                                        isOverDeleteButton = false
                                        onDragOverDeleteButton?.invoke(false)
                                    } else {
                                        // Normal drag & drop: snap the vertical move to a 15-minute grid
                                        val dayOffset = (totalDrag.x / columnWidthPx).roundToInt()
                                        val rawMinuteShift = (totalDrag.y / hourHeightPx * 60f).roundToInt()
                                        val minuteShift = (rawMinuteShift / RESIZE_SNAP_MINUTES.toFloat()).roundToInt() * RESIZE_SNAP_MINUTES
                                        if (dayOffset != 0 || minuteShift != 0) {
                                            val newStart = resizedTime(
                                                event.startDateTime.plusDays(dayOffset.toLong()),
                                                minuteShift.toFloat() / 60f * hourHeightPx,
                                                hourHeightPx
                                            )
                                            val targetMinuteOfDay = newStart.hour * 60 + newStart.minute
                                            onDragDrop(event.id, newStart.toLocalDate(), targetMinuteOfDay)
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                }
                                isDraggingEvent = false
                                totalDrag = Offset.Zero
                                isOverDeleteButton = false
                                onDragOverDeleteButton?.invoke(false)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount

                            // Calculate absolute position of pointer in window
                            // startPositionInWindow is the initial touch position in window coordinates
                            // totalDrag is the accumulated drag offset
                            // So current position = startPositionInWindow + totalDrag
                            val currentPositionInWindow = startPositionInWindow + totalDrag

                            // Delete button is in the right-bottom corner (FloatingActionButton)
                            // Check if pointer is in the right-bottom area (last 25% width, last 25% height)
                            val deleteAreaWidth = screenWidth * 0.25f
                            val deleteAreaHeight = screenHeight * 0.25f
                            val isInDeleteArea = currentPositionInWindow.x >= (screenWidth - deleteAreaWidth) &&
                                currentPositionInWindow.y >= (screenHeight - deleteAreaHeight)

                            if (isInDeleteArea != isOverDeleteButton) {
                                isOverDeleteButton = isInDeleteArea
                                onDragOverDeleteButton?.invoke(isInDeleteArea)
                                if (isInDeleteArea) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.Center
        ) {
            if (showLabels) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (event.isPrivate) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Private event",
                            tint = textColor,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    if (event.pickupConfirmedBy != null) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Pickup confirmed by ${event.pickupConfirmedBy}",
                            tint = textColor,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        maxLines = 1,
                        // Never wrap character-by-character in narrow week columns —
                        // a clipped-but-horizontal title beats an unreadable vertical one
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // Always show time if enough space, or if resizing (to give feedback).
            // Resize feedback still shows in week view, where the label is otherwise hidden:
            // the user is actively dragging and needs to see the time they are setting.
            if (showLabels && totalMinutes >= 45 || isResizingStart || isResizingEnd) {
                Text(
                    text = "${tempStartTime.format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    )} - ${tempEndTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = textColor.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Time Tracker Badge - Floating overlay for precise feedback during resize
        if (isResizingStart || isResizingEnd) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = "${tempStartTime.format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    )} - ${tempEndTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Top resize handle (start time) - centered pill, drag up to start earlier, down to start later
        if (onResize != null && resizable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(40.dp)
                    .height(14.dp)
                    .background(color = borderColor, shape = RoundedCornerShape(7.dp))
                    .semantics { contentDescription = "Resize start time. Drag up or down (15-minute steps)." }
                    .pointerInput(hourHeightPx, onResize) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isResizingStart = true
                                resizeDragStart = startOffset
                                resizeDragAmountStart = 0f
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                if (isResizingStart && onResize != null) {
                                    val newStartTime = resizedTime(eventStart, resizeDragAmountStart, hourHeightPx)
                                    // Keep at least one 15-minute slot before the end
                                    if (newStartTime.isBefore(eventEnd)) {
                                        onResize(event.id, newStartTime, null)
                                    }
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                isResizingStart = false
                                resizeDragAmountStart = 0f
                            },
                            onDragCancel = {
                                isResizingStart = false
                                resizeDragAmountStart = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            resizeDragAmountStart += dragAmount.y
                        }
                    }
            )
        }

        // Bottom resize handle (end time) - centered pill, drag down to end later, up to end earlier
        if (onResize != null && resizable) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(40.dp)
                    .height(14.dp)
                    .background(color = borderColor, shape = RoundedCornerShape(7.dp))
                    .semantics { contentDescription = "Resize end time. Drag up or down (15-minute steps)." }
                    .pointerInput(hourHeightPx, onResize) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isResizingEnd = true
                                resizeDragStart = startOffset
                                resizeDragAmountEnd = 0f
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                if (isResizingEnd && onResize != null) {
                                    val newEndTime = resizedTime(eventEnd, resizeDragAmountEnd, hourHeightPx)
                                    // Keep at least one 15-minute slot after the start
                                    if (newEndTime.isAfter(eventStart)) {
                                        onResize(event.id, null, newEndTime)
                                    }
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                isResizingEnd = false
                                resizeDragAmountEnd = 0f
                            },
                            onDragCancel = {
                                isResizingEnd = false
                                resizeDragAmountEnd = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            resizeDragAmountEnd += dragAmount.y
                        }
                    }
            )
        }
    }
}

/** Minutes the resize handles snap to. */
private const val RESIZE_SNAP_MINUTES = 15

/** Google-Calendar-style red "now" indicator. */
private val NowIndicatorColor = Color(0xFFEA4335)

/**
 * A single event's visible slice within one day, with its lane assignment for
 * side-by-side layout of overlapping events.
 *
 * @property segStart start of the visible slice (clamped to the day)
 * @property segEnd end of the visible slice (clamped to the day)
 * @property clamped true when the event extends beyond this day (multi-day/overnight)
 * @property lane 0-based column index within its overlap cluster
 * @property laneCount number of columns in that cluster
 */
private data class EventSegment(
    val event: Event,
    val segStart: LocalDateTime,
    val segEnd: LocalDateTime,
    val clamped: Boolean,
    val lane: Int,
    val laneCount: Int
)

/**
 * Computes the drawable event slices for [date]: events that overlap the day are
 * clamped to it (so multi-day/overnight events appear on every day they cover), then
 * grouped into overlap clusters and assigned side-by-side lanes (greedy first-fit,
 * optimal for intervals) so concurrent events don't stack on top of each other.
 */
private fun layoutDayEvents(events: List<Event>, date: LocalDate): List<EventSegment> {
    val dayStart = date.atStartOfDay()
    val dayEnd = date.plusDays(1).atStartOfDay()

    data class Slice(val event: Event, val start: LocalDateTime, val end: LocalDateTime, val clamped: Boolean)

    val slices = events.mapNotNull { e ->
        val s = e.startDateTime
        val en = e.endDateTime ?: e.startDateTime.plusHours(1)
        if (s.isBefore(dayEnd) && en.isAfter(dayStart)) {
            val cs = if (s.isBefore(dayStart)) dayStart else s
            val ce = if (en.isAfter(dayEnd)) dayEnd else en
            Slice(e, cs, ce, s.isBefore(dayStart) || en.isAfter(dayEnd))
        } else {
            null
        }
    }.sortedWith(compareBy({ it.start }, { it.end }))

    val result = mutableListOf<EventSegment>()
    val cluster = mutableListOf<Slice>()
    var clusterEnd: LocalDateTime? = null

    fun flushCluster() {
        if (cluster.isEmpty()) return
        val laneEnds = mutableListOf<LocalDateTime>()
        val lanes = IntArray(cluster.size)
        cluster.forEachIndexed { i, slice ->
            var lane = laneEnds.indexOfFirst { !slice.start.isBefore(it) }
            if (lane == -1) {
                laneEnds.add(slice.end)
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = slice.end
            }
            lanes[i] = lane
        }
        val laneCount = laneEnds.size
        cluster.forEachIndexed { i, slice ->
            result.add(EventSegment(slice.event, slice.start, slice.end, slice.clamped, lanes[i], laneCount))
        }
        cluster.clear()
        clusterEnd = null
    }

    for (slice in slices) {
        val currentEnd = clusterEnd
        if (currentEnd == null || slice.start.isBefore(currentEnd)) {
            cluster.add(slice)
            clusterEnd = if (currentEnd == null || slice.end.isAfter(currentEnd)) slice.end else currentEnd
        } else {
            flushCluster()
            cluster.add(slice)
            clusterEnd = slice.end
        }
    }
    flushCluster()
    return result
}

/**
 * Applies a vertical drag (in pixels) to a base time and snaps the result to the
 * nearest [RESIZE_SNAP_MINUTES] grid, so resizing moves in clean 15-minute steps.
 */
private fun resizedTime(
    base: LocalDateTime,
    dragPx: Float,
    hourHeightPx: Float
): LocalDateTime {
    val deltaMinutes = (dragPx / hourHeightPx * 60f).roundToInt()
    val moved = base.plusMinutes(deltaMinutes.toLong()).withSecond(0).withNano(0)
    val minutesOfDay = moved.hour * 60 + moved.minute
    val snapped = ((minutesOfDay + RESIZE_SNAP_MINUTES / 2) / RESIZE_SNAP_MINUTES) * RESIZE_SNAP_MINUTES
    val clamped = snapped.coerceIn(0, 24 * 60 - RESIZE_SNAP_MINUTES)
    return moved.toLocalDate().atStartOfDay().plusMinutes(clamped.toLong())
}

/**
 * Solid band above the week's day headers, split into runs of consecutive same-custody days.
 *
 * Each run is drawn at full hue and labelled when it is wide enough to hold a word; a one-day
 * run gets no label, because a clipped "M" is worse than a plain coloured block. Column geometry
 * (gutter width, weights, 4dp gaps) mirrors the header and content rows so the band lines up
 * with the days it describes.
 *
 * @param dates Visible dates, in order
 * @param getCustody Unified custody lookup (model first, legacy schedules as fallback)
 * @param gutterWidth Width of the hour-label gutter the band must skip
 * @param modifier Modifier for the band row
 */
@Composable
private fun CustodyWeekBand(
    dates: List<LocalDate>,
    getCustody: (LocalDate) -> String?,
    gutterWidth: Dp,
    modifier: Modifier = Modifier
) {
    // Collapse the week into runs so a Mon–Wed stretch is one block, not three.
    val runs: List<Pair<String?, Int>> = remember(dates, getCustody) {
        buildList {
            dates.forEach { date ->
                val custody = getCustody(date)
                val last = lastOrNull()
                if (last != null && last.first == custody) {
                    set(lastIndex, custody to last.second + 1)
                } else {
                    add(custody to 1)
                }
            }
        }
    }
    if (runs.all { it.first == null }) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CUSTODY_BAND_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.width(gutterWidth))

        runs.forEach { (custody, days) ->
            val color = when (custody) {
                "mom" -> CoPlanlyColors.MomPink
                "dad" -> CoPlanlyColors.DadBlue
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .weight(days.toFloat())
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (custody != null && days >= 2) {
                    Text(
                        text = if (custody == "mom") {
                            stringResource(R.string.calendar_parent_mom)
                        } else {
                            stringResource(R.string.calendar_parent_dad)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
