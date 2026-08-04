# §11 item 8 — the calendar swipe asymmetry

**Date:** 2026-08-04
**Scope:** item 8 of `docs/TEST-PLAN-2026-08.md` §11, and nothing else.
**Base:** `feature/plan11-batch-1` (`f71efe77`). The fix rewrites plumbing that item 9 introduced,
so it cannot sit on `main` until batch 1 lands.
**Prior work:** the "Diagnosis — item 8" section of
`docs/superpowers/specs/2026-08-03-plan11-batch-1-design.md`. That round found the cause and
deliberately shipped no fix. This spec is the fix.

## What is already established

Measured on the Samsung SM-A176B, five scripted swipes per run, cold start each time:

| Run | Frames | Janky | p50 | p90 |
|---|---|---|---|---|
| Forward (next month) | 142 | 20.4% | 14 ms | 113 ms |
| Backward (previous month) | 30 | 70.0% | 121 ms | 250 ms |
| Backward, all per-cell work stripped | 26 | 69.2% | 200 ms | 250 ms |
| Backward, month propagation cut | 124 | 16.1% | 16 ms | 73 ms |

Row 3 killed hypotheses 2 and 3: per-cell event, custody and holiday lookups are not the cost.
Row 4 confirmed hypothesis 1: the cost is the state round-trip a settle triggers. Item 9 renamed
that round-trip but did not remove it — today it reads:

```
settle → MonthView.onMonthChange → CalendarViewModel.showMonth → displayedMonth
       → CalendarSelection.anchorDate → LaunchedEffect(viewMode, anchorDate)
       → EventViewModel.loadEventsForDateRange
```

## What this spec adds to the diagnosis

Two findings from reading the code on 4 August, neither of which is in the diagnosis.

**1. The diagnosis's own proposed fix does not work as written.** It suggests "not re-querying when
the new range is already covered — consecutive months overlap heavily". They overlap, but the new
range is never *nested* in the old one, because `queryRangeFor` centres the range on the month:

| Displayed month | Range `queryRangeFor` produces |
|---|---|
| August 2026 | 15 June – 18 October |
| September 2026 | 20 July – 15 November |

September's range ends a month later than August's. A containment test therefore fails on every
single-month step and skips nothing. The range moves with the month by construction, so the fix has
to change *what the range is anchored to*, not add a test in front of it.

**2. Every query leaks its collector.** `EventViewModel.loadEventsForDateRange` is

```kotlin
viewModelScope.launch {
    _uiState.value = EventUiState.Loading
    eventUseCases.getEvents.getByDateRange(start, end).collect { … }
}
```

and the file contains no `Job`, `cancel`, `flatMapLatest` or `collectLatest` anywhere. `init` already
starts a permanent collector over *all* events (`loadEvents()`), and every month settle adds another
over its own range. `CalendarScreen` takes its `EventViewModel` from `hiltViewModel()` inside the
NavHost, so the instance is scoped to the Calendar tab's `NavBackStackEntry` and lives for the whole
session: five swipes leave seven live collectors, and they stay until the process dies.

Each surviving collector re-runs `RecurrenceExpander.expandAll` on every write to the events table
and then writes `_events`, so one event insert costs N expansions and N whole-screen recompositions.

This does **not** explain the direction asymmetry — both measured runs began from a cold start and
therefore from the same collector count. It explains the absolute cost of the round-trip, and it is
a defect on its own terms: an unbounded leak plus O(N) work per database write.

## The change

### `EventViewModel` — one query, one collector

The three imperative loaders become one piece of state:

```kotlin
internal sealed interface EventQuery {
    data object All : EventQuery
    data class Range(val start: LocalDateTime, val end: LocalDateTime) : EventQuery
}

private val query = MutableStateFlow<EventQuery>(EventQuery.All)

val events: StateFlow<List<Event>> = query
    .flatMapLatest { … }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

`loadEvents()` and `loadEventsForDateRange(start, end)` keep their names and signatures — each
becomes an assignment to `query`. `init` no longer launches anything; the initial `All` value is the
same behaviour by another route.

`loadEventsForDate(date)` is **deleted** rather than converted. A grep over `app/src` finds no
caller anywhere, production or test; carrying it forward would mean a third `EventQuery` branch and
a third leak path that nothing exercises.

Two properties follow from the shape rather than from discipline: `flatMapLatest` cancels the
previous collection before starting the next, so the leak cannot recur; and `MutableStateFlow`
conflates equal values, so re-requesting the range already loaded emits nothing and costs nothing.
No `distinctUntilChanged` is needed for that — `EventQuery.Range` is a data class and the state
flow does the deduplication itself.

`EventListScreen` and `AddEditEventScreen` obtain their own instances and never set a range, so they
stay on `All` and behave exactly as today. `EventUiState` keeps its current meaning, including the
`Loading` flip on a query change — see Non-goals.

### The query window — anchored, with hysteresis

`MonthView` already keeps ±24 months of pager loaded and re-anchors only on a jump of ≥24 months
(`MonthView.kt:94`). The pager has hysteresis; the query does not. This gives the query the same
property at a smaller radius.

- `CalendarViewModel` gains `queryAnchorMonth: StateFlow<YearMonth>`, initialised to the current
  month. `showMonth` and `setSelectedDate` pass the new displayed month through a pure re-anchor
  rule: keep the anchor while `abs(ChronoUnit.MONTHS.between(anchor, displayed)) <= 2`, otherwise
  adopt the displayed month.
- `CalendarSelection.anchorDate` in MONTH mode returns `queryAnchorMonth.atDay(1)` instead of
  `displayedMonth.atDay(1)`. WEEK and DAY are untouched — they still need a concrete day.
- The MONTH branch of `queryRangeFor` widens from ±6 weeks to ±3 months, still week-aligned
  (back to Monday, forward to Sunday). Per the project rule, the arithmetic is extended in
  `queryRangeFor` and not inlined at the call sites.

`anchorDate` feeds the holiday map (`CalendarScreen.kt:258`), the event query
(`CalendarScreen.kt:268`) and pull-to-refresh (`CalendarScreen.kt:398`) — all three follow the
anchor automatically. `displayedMonth` keeps its other jobs: the date picker's initial value, the
vacation banner label and `MonthView`'s `selectedMonth`.

Effect: four consecutive swipes issue no query at all. The fifth re-anchors and issues one.

### The coverage invariant

A sticky anchor is only safe if the loaded window covers the grid of every month reachable without
re-anchoring. With a ±3-month window and a ±2-month threshold it does, with a month to spare:

- Worst case forward — displayed = anchor + 2. `OutDateStyle.EndOfGrid` makes the grid 42 cells from
  the Monday on or before the 1st, so it ends at most on the 5th of the following month, i.e. inside
  `anchor + 3`. The window ends at the Sunday on or after `(anchor + 3).atEndOfMonth()`. Covered.
- Worst case backward — displayed = anchor − 2. Its grid starts at most 6 days before the 1st, i.e.
  inside `anchor − 3`. The window starts at the Monday on or before `(anchor − 3).atDay(1)`.
  Covered.

This is the assertion the test suite exists to defend: change either constant and the test fails.

## Non-goals

- **`EventUiState` is not redesigned.** The `Loading` flip on a query change stays. `CalendarScreen`
  never renders `Loading` — the only `is EventUiState.Loading` branch in the app is in
  `EventListScreen` — so its cost here is one wasted recomposition, and with the window it happens
  once every few months instead of once per swipe. Reworking `uiState` would touch the
  `OperationSuccess` channel that create/update/delete share with it; separate change.
- **Why the slowdown landed on one direction only is not investigated.** The diagnosis left it
  unestablished and this spec does not guess. If the after-numbers match in both directions the
  question is moot; if the asymmetry survives on levelled numbers, it is a fresh investigation and
  the PR says so.
- **Pull-to-refresh becomes a genuine no-op for events.** It re-requests the same range, which the
  query state flow now conflates away. It already achieved nothing: the Room flow is live, so
  re-collecting the same query returns the same rows. The custody reload it also triggers is
  unaffected. No visible change, recorded so it is not mistaken later for a regression.
- No Room schema change, no `firestore.rules` change, no new dependency, no new user-facing string.

## Testing strategy

JVM unit tests only, as everywhere in this project — assertions go on pure functions, the
composable keeps only the wiring.

| What | Where |
|---|---|
| Re-anchor rule | `CalendarSelectionTest` (extend) |
| Window covers every reachable grid | new `CalendarQueryRangeTest` |
| A new range cancels the previous collection | `EventViewModelTest` (extend) |
| `queryAnchorMonth` moves only on re-anchor | `CalendarViewModelTest` (extend) |

Cases the re-anchor test must carry: distance 0, 1 and 2 hold the anchor; distance 3 adopts;
symmetric in both directions; **December → January**, which must be measured with
`ChronoUnit.MONTHS` and not month numbers; and a far jump (Today pill, date picker).

The cancellation test asserts behaviour, not internals: after a second range is set, an emission
from the first range's flow must not reach `events`.

## Acceptance

1. `./gradlew assembleDebug testDebugUnitTest lint detekt` clean. detekt is red on `main` — measure
   the baseline in a worktree and only own this branch's delta.
2. **`gfxinfo` on the Samsung**, the baseline's protocol unchanged: `dumpsys gfxinfo … reset`, five
   scripted `input swipe` gestures 250 ms long and 1.5 s apart, cold start per run, both directions.
   Before/after table in the PR body. The target is the row-4 result — backward paging around
   ~124 frames at ~16% janky, p50 ~16 ms, matching forward.
3. **`[H]` — the human half of test-plan 4.2.3**, still open since July: a thumb on the glass,
   reporting whether "first swipe back is sticky, the next is fine" is gone. Frame counters do not
   express inertia and both prior passes were scripted. This is the owner's to run; the item is not
   closed without it.
4. `docs/TEST-PLAN-2026-08.md` §11 updated with the result — item 8 moved out of "diagnosed" into
   whatever the numbers actually support.

## Risks

- **The fix is measured on the same instrument that produced the diagnosis, on a branch that also
  changed the calendar.** Mitigated by re-running the *forward* direction too: if forward moved as
  well, something other than this change is in play and the comparison is not clean.
- **A wider window makes each re-anchor heavier.** `RecurrenceExpander.expandAll` runs over the whole
  window, so a daily recurring event yields roughly 215 occurrences per expansion at ±3 months
  against roughly 110 today — the window is seven months wide where today's is about three and a
  half. That cost moves off the swipe and onto one settle in five. If a
  re-anchor becomes visible in the after-numbers, the window shrinks — it is one constant.
- **`events` changing from a `MutableStateFlow` to a derived `StateFlow` touches every consumer.**
  `EventListScreen` and `AddEditEventScreen` share the class but not the instance; the compiler
  catches the write sites, and `SharingStarted.Eagerly` keeps `events.value` meaningful for the
  existing `EventViewModelTest` assertions.

## Files

- `app/src/main/java/com/coparently/app/presentation/event/EventViewModel.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarViewModel.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarSelection.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt` (`queryRangeFor`)
- `app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt`
- `app/src/test/java/com/coparently/app/presentation/calendar/CalendarViewModelTest.kt`
- `app/src/test/java/com/coparently/app/presentation/event/EventViewModelTest.kt`
- new: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarQueryRangeTest.kt`
