# Calendar Swipe Symmetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make paging the calendar backwards cost the same as paging forwards, by anchoring the event query to a sticky month window instead of the displayed month, and by making a single live collector structurally impossible to duplicate.

**Architecture:** Two independent changes that compound. (1) `EventViewModel` stops launching a coroutine per load and instead holds the query as state, collected once through `flatMapLatest` — the previous collection is cancelled by construction. (2) The MONTH query range stops following the displayed month: `CalendarViewModel` keeps a sticky `queryAnchorMonth` that only moves when the displayed month drifts more than 2 months away, and `queryRangeFor` widens its MONTH window from ±6 weeks to ±3 months so every reachable grid stays covered. Four consecutive swipes then issue no query at all.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Hilt, Room, kotlinx-coroutines Flow, JUnit 4 + MockK + coroutines-test.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-04-calendar-swipe-symmetry-design.md`. The implementation is not free to revisit its decisions.
- **Branch:** `fix/calendar-swipe-symmetry`, based on `feature/plan11-batch-1` (`f71efe77`). Do not rebase onto `main` — the code this changes was introduced by batch 1 and is not on `main` yet.
- **Gradle needs an explicit JDK on this machine.** System `JAVA_HOME` points at a broken JBR. Prefix every Gradle command in PowerShell with `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr";` (OpenJDK 21, verified present on 4 August 2026).
- **JVM unit tests only.** No instrumentation tests, no Compose UI tests exist in this project. Logic that needs asserting gets extracted into a pure function; the composable keeps only the wiring.
- **No Room schema change, no `firestore.rules` change, no new dependency, no new user-facing string.** If a step seems to need one, stop and re-read the spec.
- **KDoc on public declarations; code and comments in English** (project rule).
- **Conventional Commits.** Every commit message body explains *why*, not *what*.
- **detekt is red on `main`.** Measure the baseline in a worktree before claiming this branch introduced a finding; only fix this branch's delta.
- **Do not touch `EventUiState`'s shape or its `OperationSuccess` channel.** Explicit non-goal.

---

## File Structure

| File | Responsibility after this plan |
|---|---|
| `presentation/calendar/CalendarSelection.kt` | Pure rules tying displayed month, selection and **query anchor** together. Gains `reanchor` and the tolerance constant. |
| `presentation/calendar/CalendarScreen.kt` | `queryRangeFor` — the single source of range arithmetic. Its MONTH branch widens to ±3 months. The composable reads `queryAnchorMonth` instead of `displayedMonth` for the range. |
| `presentation/calendar/CalendarViewModel.kt` | Owns `queryAnchorMonth` state and applies `CalendarSelection.reanchor` on every month move. |
| `presentation/event/EventViewModel.kt` | Holds the event query as state; exactly one collection is live at a time. |
| `presentation/calendar/CalendarSelectionTest.kt` | Re-anchor rule, including the December→January trap. |
| `presentation/calendar/CalendarQueryRangeTest.kt` *(new)* | The coverage invariant: every grid reachable without a re-anchor lies inside the window. |
| `presentation/calendar/CalendarViewModelTest.kt` | `queryAnchorMonth` moves only on a re-anchor. |
| `presentation/event/EventViewModelTest.kt` | A new range cancels the previous collection. |

---

### Task 1: The window and the re-anchor rule

Pure functions first, with no wiring. After this task the app loads a wider range per month but still re-queries on every settle — correct, just not yet fast. Nothing user-visible changes.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarSelection.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt` (`queryRangeFor`, lines 81–111)
- Modify: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt`
- Create: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarQueryRangeTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS: Long` (value `2`)
  - `CalendarSelection.reanchor(current: YearMonth, displayed: YearMonth): YearMonth`
  - `MONTH_WINDOW_RADIUS: Long` — `internal const` in `CalendarScreen.kt` (value `3`)
  - `queryRangeFor(viewMode, selectedDate)` keeps its existing signature; only the MONTH branch's arithmetic changes.

- [ ] **Step 1: Write the failing re-anchor tests**

Append to `app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt`, inside the existing class:

```kotlin
    @Test
    fun `the anchor holds while the displayed month stays within tolerance`() {
        val anchor = YearMonth.of(2026, 8)
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 8)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 9)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 10)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 7)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2026, 6)))
    }

    @Test
    fun `the anchor moves to the displayed month once tolerance is exceeded`() {
        val anchor = YearMonth.of(2026, 8)
        assertEquals(YearMonth.of(2026, 11), CalendarSelection.reanchor(anchor, YearMonth.of(2026, 11)))
        assertEquals(YearMonth.of(2026, 5), CalendarSelection.reanchor(anchor, YearMonth.of(2026, 5)))
    }

    @Test
    fun `distance is measured in months and not in month numbers`() {
        // December 2026 -> January 2027 is one month apart. Subtracting month numbers
        // makes it eleven, which would re-anchor on every year boundary.
        val anchor = YearMonth.of(2026, 12)
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2027, 1)))
        assertEquals(anchor, CalendarSelection.reanchor(anchor, YearMonth.of(2027, 2)))
        assertEquals(YearMonth.of(2027, 3), CalendarSelection.reanchor(anchor, YearMonth.of(2027, 3)))
    }

    @Test
    fun `a far jump such as the Today pill re-anchors`() {
        assertEquals(
            YearMonth.of(2026, 8),
            CalendarSelection.reanchor(YearMonth.of(2019, 4), YearMonth.of(2026, 8))
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.CalendarSelectionTest"
```

Expected: compilation failure — `Unresolved reference: reanchor`.

- [ ] **Step 3: Implement `reanchor`**

In `CalendarSelection.kt`, add the imports `java.time.temporal.ChronoUnit` and `kotlin.math.abs`, then add to the object:

```kotlin
    /**
     * Months the event query's anchor may drift from the displayed month before the loaded
     * window is re-centred. Paired with the window radius in [queryRangeFor]: the radius must
     * stay strictly larger, or a month reachable without a re-anchor would fall outside the
     * loaded range. `CalendarQueryRangeTest` is what defends that relationship.
     */
    const val QUERY_ANCHOR_TOLERANCE_MONTHS = 2L

    /**
     * The query anchor after the grid moved to [displayed].
     *
     * Keeps [current] while the two are within [QUERY_ANCHOR_TOLERANCE_MONTHS] of each other,
     * so ordinary month-to-month paging issues no query at all; adopts [displayed] otherwise.
     * This mirrors the hysteresis the month pager itself already has (`MonthView.anchorMonth`),
     * which the query never had — every settle re-queried, and that round-trip is what made
     * backward paging drop frames.
     */
    fun reanchor(current: YearMonth, displayed: YearMonth): YearMonth =
        if (abs(ChronoUnit.MONTHS.between(current, displayed)) > QUERY_ANCHOR_TOLERANCE_MONTHS) {
            displayed
        } else {
            current
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.CalendarSelectionTest"
```

Expected: PASS, all tests in the class.

- [ ] **Step 5: Write the failing coverage-invariant test**

Create `app/src/test/java/com/coparently/app/presentation/calendar/CalendarQueryRangeTest.kt`:

```kotlin
package com.coparently.app.presentation.calendar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The MONTH query window is anchored and sticky, so it must cover the grid of every month the
 * user can page to without re-anchoring. If it does not, paging shows a month whose events were
 * never loaded — an empty grid that looks like data loss.
 */
class CalendarQueryRangeTest {

    /**
     * The 42 cells kizitonwose renders for [month] under `OutDateStyle.EndOfGrid`: six rows
     * starting from the Monday on or before the 1st.
     */
    private fun gridBounds(month: YearMonth): Pair<LocalDate, LocalDate> {
        var start = month.atDay(1)
        while (start.dayOfWeek != DayOfWeek.MONDAY) {
            start = start.minusDays(1)
        }
        return start to start.plusDays(41)
    }

    private fun windowFor(anchor: YearMonth): Pair<LocalDate, LocalDate> {
        val (start, end) = queryRangeFor(CalendarViewMode.MONTH, anchor.atDay(1))
        return start.toLocalDate() to end.toLocalDate()
    }

    @Test
    fun `the window covers every grid reachable without a re-anchor`() {
        val anchors = listOf(
            YearMonth.of(2026, 1),
            YearMonth.of(2026, 2),   // short month
            YearMonth.of(2026, 8),
            YearMonth.of(2026, 11),  // window crosses the year boundary
            YearMonth.of(2027, 1),
            YearMonth.of(2028, 2)    // leap February
        )
        val tolerance = CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS

        for (anchor in anchors) {
            val (windowStart, windowEnd) = windowFor(anchor)
            for (offset in -tolerance..tolerance) {
                val displayed = anchor.plusMonths(offset)
                val (gridStart, gridEnd) = gridBounds(displayed)
                assertTrue(
                    "grid of $displayed starts before the window anchored on $anchor",
                    !gridStart.isBefore(windowStart)
                )
                assertTrue(
                    "grid of $displayed ends after the window anchored on $anchor",
                    !gridEnd.isAfter(windowEnd)
                )
            }
        }
    }

    @Test
    fun `the window radius leaves room beyond the re-anchor tolerance`() {
        assertTrue(
            "the window must reach further than the anchor is allowed to drift",
            MONTH_WINDOW_RADIUS > CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS
        )
    }

    @Test
    fun `the window is week-aligned so no partial row is unloaded`() {
        val (start, end) = queryRangeFor(CalendarViewMode.MONTH, YearMonth.of(2026, 8).atDay(1))
        assertTrue("window starts on a Monday", start.dayOfWeek == DayOfWeek.MONDAY)
        assertTrue("window ends on a Sunday", end.dayOfWeek == DayOfWeek.SUNDAY)
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.CalendarQueryRangeTest"
```

Expected: compilation failure — `Unresolved reference: MONTH_WINDOW_RADIUS`. (The coverage test would also fail on the current ±6-week window: August's window ends 18 October, and the grid of October 2026 runs to 8 November.)

- [ ] **Step 7: Widen the MONTH window**

In `CalendarScreen.kt`, add the constant above `queryRangeFor`:

```kotlin
/**
 * Months loaded either side of the query anchor in MONTH mode.
 *
 * Deliberately larger than [CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS]: the anchor is
 * sticky, so the window has to cover every grid the user can reach before it re-centres.
 * Widening this makes each re-anchor more expensive (`RecurrenceExpander` expands over the whole
 * window); narrowing it makes re-anchors more frequent.
 */
internal const val MONTH_WINDOW_RADIUS = 3L
```

Then replace the MONTH branch of `queryRangeFor` (currently lines 93–109) with:

```kotlin
        CalendarViewMode.MONTH -> {
            // The range follows the sticky query anchor, not the displayed month, so ordinary
            // month paging stays inside an already-loaded window. Week-aligned because the grid
            // renders whole weeks either side of the month.
            val anchor = YearMonth.from(selectedDate)
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
```

Rename the function's parameter from `selectedDate` to `anchorDate` throughout its body (the DAY
and WEEK branches use it too) and replace its KDoc, so it stops promising a range built from the
selection:

```kotlin
/**
 * Computes the event query range for a view mode and anchor date.
 *
 * Single source of truth used by the initial load, the holiday map and pull-to-refresh. In MONTH
 * mode the anchor is the sticky query anchor (see [CalendarSelection.reanchor]), not the month on
 * screen; DAY and WEEK anchor on a concrete day.
 */
```

Every call site passes the argument positionally, so no call site changes in this step.

- [ ] **Step 8: Run both test classes to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.*"
```

Expected: PASS.

- [ ] **Step 9: Build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/calendar/CalendarSelection.kt app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt app/src/test/java/com/coparently/app/presentation/calendar/CalendarQueryRangeTest.kt
git commit
```

Message:

```
feat(calendar): widen the month query window and give it a re-anchor rule

The range was centred on the displayed month, so a one-month step always
extended it a month further out and no containment test could ever skip a
query. Anchoring it instead needs two things: a window wide enough to cover
every grid reachable before it re-centres, and a rule for when to re-centre.

Both land here as pure functions with the coverage relationship between them
asserted, so changing either constant fails a test rather than quietly
un-loading a month. Nothing is wired to them yet — the screen still re-queries
on every settle, it just loads more per query.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 2: Anchor the calendar's query

Wires Task 1's rule into the ViewModel and the screen. After this task the round-trip is gone for ordinary paging.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarSelection.kt` (`anchorDate` KDoc + parameter name)
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt` — lines 189–191, plus the four range sites at 258, 262, 268–269 and 398
- Modify: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarViewModelTest.kt`
- Modify: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt` (named arguments only)

**Interfaces:**
- Consumes: `CalendarSelection.reanchor(current, displayed)` and `CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS` from Task 1.
- Produces: `CalendarViewModel.queryAnchorMonth: StateFlow<YearMonth>`.

- [ ] **Step 1: Write the failing ViewModel tests**

Append to `app/src/test/java/com/coparently/app/presentation/calendar/CalendarViewModelTest.kt`, inside the existing class:

```kotlin
    @Test
    fun `the query anchor starts on the current month`() = runTest {
        assertEquals(YearMonth.now(), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `paging within tolerance leaves the query anchor alone`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.plusMonths(1))
        viewModel.showMonth(start.plusMonths(2))
        assertEquals(start, viewModel.queryAnchorMonth.value)
        assertEquals(start.plusMonths(2), viewModel.displayedMonth.value)
    }

    @Test
    fun `paging past tolerance re-anchors the query`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.plusMonths(3))
        assertEquals(start.plusMonths(3), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `paging backwards past tolerance re-anchors the query`() = runTest {
        val start = YearMonth.now()
        viewModel.showMonth(start.minusMonths(3))
        assertEquals(start.minusMonths(3), viewModel.queryAnchorMonth.value)
    }

    @Test
    fun `tapping a day in a distant month re-anchors the query`() = runTest {
        val start = YearMonth.now()
        val distant = start.plusMonths(7).atDay(14)
        viewModel.setSelectedDate(distant)
        assertEquals(YearMonth.from(distant), viewModel.queryAnchorMonth.value)
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.CalendarViewModelTest"
```

Expected: compilation failure — `Unresolved reference: queryAnchorMonth`.

- [ ] **Step 3: Add the anchor state to `CalendarViewModel`**

After the `displayedMonth` declaration (around line 60), add:

```kotlin
    private val _queryAnchorMonth = MutableStateFlow(YearMonth.now())

    /**
     * Month the event query window is centred on. Distinct from [displayedMonth]: it stays put
     * while the user pages within [CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS] of it, so a
     * settle no longer triggers a fresh query. Chasing the displayed month is what made backward
     * paging drop frames — see the item 8 diagnosis.
     */
    val queryAnchorMonth: StateFlow<YearMonth> = _queryAnchorMonth.asStateFlow()
```

Then route both month moves through the rule. `setSelectedDate` becomes:

```kotlin
    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
        moveTo(YearMonth.from(date))
    }
```

`showMonth` becomes:

```kotlin
    fun showMonth(month: YearMonth) {
        moveTo(month)
        _selectedDate.value = CalendarSelection.forMonth(month, LocalDate.now())
    }
```

and add the shared private helper below them:

```kotlin
    /** Shows [month] and re-centres the query window only if it has drifted too far. */
    private fun moveTo(month: YearMonth) {
        _displayedMonth.value = month
        _queryAnchorMonth.value = CalendarSelection.reanchor(_queryAnchorMonth.value, month)
    }
```

- [ ] **Step 4: Run the ViewModel tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.CalendarViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Give the screen a second anchor, for the range only**

**Do not repurpose `anchorDate`.** It feeds `CalendarHeader` (line 325), whose title *is* the
Month/Week/Day picker and which derives its `YearMonth` from that value
(`CalendarHeader.kt:110`). Point it at the sticky anchor and the header stops following the swipe:
you page to September and the title still says August until the next re-anchor. The range and the
title need two different values.

In `CalendarScreen.kt`, replace lines 189–191:

```kotlin
    val displayedMonth by calendarViewModel.displayedMonth.collectAsState()
    val today = remember { LocalDate.now() }
    val anchorDate = CalendarSelection.anchorDate(viewMode, displayedMonth, selectedDate, today)
```

with:

```kotlin
    val displayedMonth by calendarViewModel.displayedMonth.collectAsState()
    val queryAnchorMonth by calendarViewModel.queryAnchorMonth.collectAsState()
    val today = remember { LocalDate.now() }

    // What the screen says it is showing: the header title, and the day DAY/WEEK render.
    val anchorDate = CalendarSelection.anchorDate(viewMode, displayedMonth, selectedDate, today)

    // What is loaded. In MONTH mode this lags the displayed month by up to
    // CalendarSelection.QUERY_ANCHOR_TOLERANCE_MONTHS, which is the entire point; in DAY and WEEK
    // the two are the same value.
    val queryAnchorDate = CalendarSelection.anchorDate(viewMode, queryAnchorMonth, selectedDate, today)
```

Then switch the three range computations — and only those three — from `anchorDate` to
`queryAnchorDate`:

| Line | Site | Change |
|---|---|---|
| 258 | `remember(viewMode, anchorDate, showHolidays)` — the holiday map's key | `remember(viewMode, queryAnchorDate, showHolidays)` |
| 262 | `queryRangeFor(viewMode, anchorDate)` inside that `remember` | `queryRangeFor(viewMode, queryAnchorDate)` |
| 268–269 | `LaunchedEffect(viewMode, anchorDate)` and the `queryRangeFor` call inside it | `LaunchedEffect(viewMode, queryAnchorDate)`, `queryRangeFor(viewMode, queryAnchorDate)` |
| 398 | `queryRangeFor(viewMode, anchorDate)` in `onRefresh` | `queryRangeFor(viewMode, queryAnchorDate)` |

Leave `anchorDate` at line 325 (`CalendarHeader`) and line 482 (`DayWeekView`) exactly as they are.

Every other use of `displayedMonth` stays as it is — the date picker's initial value (line 207), the
vacation banner label (line 416) and `MonthView`'s `selectedMonth` (line 516) all describe what is on
screen, not what is loaded.

- [ ] **Step 6: Rename `anchorDate`'s month parameter and update its KDoc**

`CalendarSelection.anchorDate` is now called twice with two different months, so its parameter can no
longer be named after either one. In `CalendarSelection.kt`, rename `displayedMonth: YearMonth` to
`month: YearMonth`, update the MONTH branch to `month.atDay(1)`, and replace its KDoc with:

```kotlin
    /**
     * The date [month] resolves to for a view mode.
     *
     * Called twice by the calendar with two different months: the displayed month, for the header
     * title and the DAY/WEEK grid, and the sticky query anchor, for the event range. In MONTH mode
     * the month is the whole answer; DAY and WEEK need a concrete day and fall back to today when
     * nothing is selected, which makes the two calls identical in those modes.
     */
```

In `CalendarSelectionTest.kt`, rename the named argument `displayedMonth =` to `month =` in the three
tests that use it. No test name and no assertion changes — the function's behaviour is unchanged.

- [ ] **Step 7: Run the whole calendar test package and build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.*" assembleDebug
```

Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/calendar/ app/src/test/java/com/coparently/app/presentation/calendar/
git commit
```

Message:

```
fix(calendar): stop every month settle from re-querying events

The pager already had hysteresis - it keeps 24 months loaded and re-anchors
only on a far jump - but the query had none, so each settle recomputed a range
centred on the new month and started a fresh load. Two device experiments in
the item 8 diagnosis pinned the backward-paging jank on exactly that
round-trip, not on the pager and not on the cells.

The query now follows a sticky anchor with the same shape of rule. Four swipes
in a row issue no query; the fifth re-centres the window and issues one. What
is on screen and what is loaded are now separate ideas, which is why
displayedMonth keeps its other jobs untouched.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 3: One query, one collector

Independent of Tasks 1 and 2 in mechanism, and the other half of the cost. Fixes an unbounded collector leak on the way.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/event/EventViewModel.kt:53-115`
- Modify: `app/src/test/java/com/coparently/app/presentation/event/EventViewModelTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EventViewModel.events: StateFlow<List<Event>>` (same name and type as today, now derived); `loadEvents()` and `loadEventsForDateRange(start: LocalDateTime, end: LocalDateTime)` keep their signatures. `loadEventsForDate(date: LocalDateTime)` is removed.

- [ ] **Step 1: Confirm `loadEventsForDate` really is dead before deleting it**

```bash
git grep -n "loadEventsForDate(" -- app/src
```

Expected: exactly one hit, the declaration itself at `EventViewModel.kt:84`. If anything else appears, stop — the spec's decision to delete it was made on this grep and needs revisiting.

- [ ] **Step 2: Write the failing cancellation test**

Append to `app/src/test/java/com/coparently/app/presentation/event/EventViewModelTest.kt`, inside the existing class:

```kotlin
    @Test
    fun `setting a new range cancels the previous collection`() = runTest {
        val firstStart = LocalDateTime.of(2026, 8, 1, 0, 0)
        val firstEnd = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val secondStart = LocalDateTime.of(2026, 11, 1, 0, 0)
        val secondEnd = LocalDateTime.of(2026, 11, 30, 23, 59, 59)

        val firstRange = MutableSharedFlow<List<Event>>()
        val laterEvent = sampleEvent.copy(id = "e2", title = "Dentist")
        every { getEvents.getByDateRange(firstStart, firstEnd) } returns firstRange
        every { getEvents.getByDateRange(secondStart, secondEnd) } returns flowOf(listOf(laterEvent))

        viewModel.loadEventsForDateRange(firstStart, firstEnd)
        advanceUntilIdle()
        firstRange.emit(listOf(sampleEvent))
        advanceUntilIdle()
        assertEquals(listOf(sampleEvent), viewModel.events.value)

        viewModel.loadEventsForDateRange(secondStart, secondEnd)
        advanceUntilIdle()

        assertEquals(0, firstRange.subscriptionCount.value)
        assertEquals(listOf(laterEvent), viewModel.events.value)

        // A late emission from the abandoned range must not reach the UI.
        firstRange.emit(listOf(sampleEvent))
        advanceUntilIdle()
        assertEquals(listOf(laterEvent), viewModel.events.value)
    }

    @Test
    fun `re-requesting the same range does not restart the collection`() = runTest {
        val start = LocalDateTime.of(2026, 8, 1, 0, 0)
        val end = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
        val range = MutableSharedFlow<List<Event>>()
        every { getEvents.getByDateRange(start, end) } returns range

        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()
        viewModel.loadEventsForDateRange(start, end)
        advanceUntilIdle()

        assertEquals(1, range.subscriptionCount.value)
    }
```

Add the imports `kotlinx.coroutines.flow.MutableSharedFlow` to the same file.

- [ ] **Step 3: Run them to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.event.EventViewModelTest"
```

Expected: FAIL. `setting a new range cancels the previous collection` fails on `assertEquals(0, firstRange.subscriptionCount.value)` — today the first collector is still subscribed. `re-requesting the same range does not restart the collection` fails with a subscription count of 2.

- [ ] **Step 4: Replace the three loaders with one query state**

In `EventViewModel.kt`, add these imports:

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
```

Add the query type above the `EventViewModel` class declaration:

```kotlin
/**
 * What the events list is currently showing.
 *
 * Modelling this as state rather than as a call is what guarantees a single live collection:
 * `flatMapLatest` cancels the previous one. Before August 2026 each load launched its own
 * coroutine and cancelled nothing, so the Calendar tab accumulated one permanent collector per
 * month swipe for the life of the process.
 */
internal sealed interface EventQuery {
    data object All : EventQuery
    data class Range(val start: LocalDateTime, val end: LocalDateTime) : EventQuery
}
```

Replace the `_events` / `events` declarations and the `init` block (lines 56–61) with:

```kotlin
    // Re-requesting the range already loaded costs nothing: MutableStateFlow conflates equal
    // values, and EventQuery.Range is a data class, so setting it again emits nothing at all.
    private val query = MutableStateFlow<EventQuery>(EventQuery.All)

    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<Event>> = query
        .flatMapLatest { current ->
            val source = when (current) {
                is EventQuery.All -> eventUseCases.getEvents()
                is EventQuery.Range -> eventUseCases.getEvents.getByDateRange(current.start, current.end)
            }
            source
                .onStart { _uiState.value = EventUiState.Loading }
                .onEach { _uiState.value = EventUiState.Success(it) }
                // Caught per query, not on the outer chain: a failure of one range must not
                // end the flatMapLatest and leave every later query silently unserved.
                .catch { e -> _uiState.value = EventUiState.Error(errorHandler.handleError(e).userMessage) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

Replace `loadEvents`, `loadEventsForDate` and `loadEventsForDateRange` (lines 63–115) with:

```kotlin
    /**
     * Shows every event. The initial query, and what the event list screen stays on.
     */
    fun loadEvents() {
        query.value = EventQuery.All
    }

    /**
     * Shows the events between [start] and [end]. Re-requesting the range currently loaded is a
     * no-op, which is why the calendar can call this freely as the user pages.
     */
    fun loadEventsForDateRange(start: LocalDateTime, end: LocalDateTime) {
        query.value = EventQuery.Range(start, end)
    }
```

- [ ] **Step 5: Run the event tests to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.event.EventViewModelTest"
```

Expected: PASS, including the pre-existing `loadEvents populates events state`.

- [ ] **Step 6: Run the whole unit suite and build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest assembleDebug
```

Expected: PASS, BUILD SUCCESSFUL. If `EventListScreen` or `AddEditEventScreen` fail to compile, the cause is a write to `_events` that this step removed — those screens read `events` only, so investigate rather than reinstating the field.

- [ ] **Step 7: Run static analysis**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew lint detekt
```

detekt is red on `main`. Compare against a `main` worktree before treating any finding as this branch's, and fix only the delta.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/event/EventViewModel.kt app/src/test/java/com/coparently/app/presentation/event/EventViewModelTest.kt
git commit
```

Message:

```
fix(events): collect one event query at a time

Nothing in EventViewModel cancelled anything: no Job, no flatMapLatest, no
collectLatest. init left a collector over all events and every call to
loadEventsForDateRange added another over its own range. CalendarScreen takes
its instance from hiltViewModel() inside the NavHost, so the instance lives as
long as the Calendar tab - five month swipes left seven live collectors, and
every write to the events table then cost seven recurrence expansions and seven
whole-screen recompositions.

Holding the query as state and collecting it through flatMapLatest makes a
second live collection unrepresentable rather than merely absent. The catch
sits inside flatMapLatest on purpose: on the outer chain one failed range would
end the pipeline and every later query would be silently unserved.

loadEventsForDate is deleted rather than ported - it had no caller anywhere in
app/src, production or test.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 4: Measure it on the device and record the result

The only honest instrument this item has. No code changes unless the numbers say so.

**Files:**
- Modify: `docs/TEST-PLAN-2026-08.md` (§11, the "Fixed in batch 1" area — add an item 8 result section)
- Modify: `docs/superpowers/specs/2026-08-04-calendar-swipe-symmetry-design.md` (append the after-numbers)

**Interfaces:**
- Consumes: the built branch from Tasks 1–3.
- Produces: the before/after table for the PR body.

- [ ] **Step 1: Install the branch build on the Samsung**

Confirm the device is attached and that CoPlanly is the focused app before any scripted input — a stray `input swipe` into another app is how a scripted run lies.

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys window | grep mCurrentFocus
```

Expected: one device listed, `Success`, and `mCurrentFocus` naming `com.coparently.app`.

- [ ] **Step 2: Run the forward-direction control**

Same protocol as the July baseline, unchanged so the numbers are comparable: cold start, reset the counters, five swipes 250 ms long, 1.5 s apart.

```bash
adb shell am force-stop com.coparently.app
adb shell am start -n com.coparently.app/.presentation.MainActivity
adb shell dumpsys gfxinfo com.coparently.app reset
for i in 1 2 3 4 5; do adb shell input swipe 900 1200 200 1200 250; sleep 1.5; done
adb shell dumpsys gfxinfo com.coparently.app | head -30
```

Record frames rendered, janky percentage, p50 and p90. **This is the control**: forward paging was already fine (142 frames, 20.4% janky, p50 14 ms). If it moved substantially, something other than this change is in play and the backward comparison is not clean — say so rather than reporting the backward number alone.

- [ ] **Step 3: Run the backward direction**

```bash
adb shell am force-stop com.coparently.app
adb shell am start -n com.coparently.app/.presentation.MainActivity
adb shell dumpsys gfxinfo com.coparently.app reset
for i in 1 2 3 4 5; do adb shell input swipe 200 1200 900 1200 250; sleep 1.5; done
adb shell dumpsys gfxinfo com.coparently.app | head -30
```

Target, from the diagnosis's row 4: around 124 frames at ~16% janky, p50 ~16 ms — matching forward. Before this branch it was 30 frames at 70%, p50 121 ms.

- [ ] **Step 4: Confirm the calendar still shows events after paging**

The window is the one thing that could break silently. Page five months forward and five back, and confirm event dots appear on the days that have events rather than an empty grid — a re-anchor that loads the wrong window looks exactly like a month with nothing in it.

- [ ] **Step 5: Hand the `[H]` half to the owner**

Ask the owner to page the calendar back and forth by thumb and report whether the 4.2.3 characterisation — "first swipe back is sticky, the next is fine" — is gone. Frame counters do not express inertia; both prior passes were scripted, and this question has been open since July. **The item is not closed without this answer.** Do not mark it closed on the numbers alone.

- [ ] **Step 6: Write the result into both documents**

Append an after-table to the spec's diagnosis section, and add an item 8 result to `docs/TEST-PLAN-2026-08.md` §11 stating what the numbers actually support. If backward paging still lags forward on levelled numbers, record that plainly and open it as a fresh investigation — the spec's non-goal says the direction question was never answered, and a partial win reported as a full one is worse than a partial win.

- [ ] **Step 7: Commit and open the PR**

```bash
git add docs/TEST-PLAN-2026-08.md docs/superpowers/specs/2026-08-04-calendar-swipe-symmetry-design.md
git commit -m "docs: record the item 8 before/after numbers from the device"
git push -u origin fix/calendar-swipe-symmetry
```

The PR body carries the before/after table, names `feature/plan11-batch-1` as the base, and states the `[H]` answer from Step 5.

---

## Self-Review

**Spec coverage.** Every section maps to a task: the `EventViewModel` rewrite and the `loadEventsForDate` deletion to Task 3; the anchored window, the `queryRangeFor` widening and `anchorDate`'s change to Tasks 1 and 2; the coverage invariant to Task 1 Step 5; the four testing-strategy rows to Tasks 1–3; all four acceptance items to Task 4 plus the build steps in Tasks 1–3. The non-goals are constraints, not work — they appear in Global Constraints and in the commit messages.

**Type consistency.** `reanchor(current, displayed): YearMonth`, `QUERY_ANCHOR_TOLERANCE_MONTHS: Long` and `MONTH_WINDOW_RADIUS: Long` are defined in Task 1 and used under those exact names in Tasks 1 and 2. `queryAnchorMonth: StateFlow<YearMonth>` is defined in Task 2 Step 3 and consumed in Step 5 of the same task. `EventQuery.All` / `EventQuery.Range(start, end)` appear only inside Task 3.

**Known intermediate state.** After Task 1 the app loads a ±3-month window on every settle — more data per query, same number of queries. That is correct but not yet fast, and it is the only point in the plan where the numbers would look worse than before. Do not measure between tasks.
