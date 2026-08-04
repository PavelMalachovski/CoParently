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
- `CalendarSelection.anchorDate` is called **twice** by the screen, with two different months: once
  with `displayedMonth`, once with `queryAnchorMonth`. The function itself does not change. This
  matters more than it looks: `anchorDate` also feeds `CalendarHeader`, whose title *is* the
  Month/Week/Day picker and which derives its `YearMonth` from that value. Repurposing the single
  value would freeze the header title for up to two months of paging — you page to September and the
  title still reads August. What is displayed and what is loaded need two values, which is the same
  separation item 9 made between the displayed month and the chosen day.
- The MONTH branch of `queryRangeFor` widens from ±6 weeks to ±3 months, still week-aligned
  (back to Monday, forward to Sunday). Per the project rule, the arithmetic is extended in
  `queryRangeFor` and not inlined at the call sites.

The query-anchored value feeds the holiday map and the event query, and nothing else. (Pull-to-refresh
was a third call site while this was written; the Non-goals below took it out — it re-collects the
loaded range through `EventViewModel.refresh()` and never recomputes a range.) The
display-anchored value keeps `CalendarHeader` and the DAY/WEEK grid. `displayedMonth`
keeps its other jobs: the date picker's initial value, the vacation banner label and `MonthView`'s
`selectedMonth`.

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
- **Pull-to-refresh stops re-requesting a range and instead re-collects the current query.**
  Re-requesting the same range is conflated away by the query state flow, and on the happy path
  that cost nothing: the Room flow is live, so re-collecting the same query would have returned
  the same rows anyway. But `.catch` completes the *inner* flow on error, so a failed query stays
  failed - re-requesting it can never restart it. Pull-to-refresh is the only gesture positioned
  to recover from that, so `EventViewModel.refresh()` bumps a separate tick combined with the
  query (not a field on `EventQuery.Range`, which would break the range-equality conflation that
  makes ordinary paging free) to force a fresh subscription without changing what is being
  queried. The custody reload it also triggers is unaffected.
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

## Measured result — 4 August 2026 (Task 4)

Two cold-start runs per direction, Samsung SM-A176B, same protocol as the diagnosis
(`dumpsys gfxinfo … reset`, five `input swipe` gestures 250 ms long, 1.5 s apart, cold start per
run), with the Calendar tab's month grid confirmed on screen *before* each reset so app startup and
tab navigation are excluded from the counters:

| Run | Frames | Janky | p50 | p90 |
|---|---|---|---|---|
| Forward, run 1 | 121 | 27.3% | 17 ms | 200 ms |
| Forward, run 2 | 144 | 20.8% | 15 ms | 121 ms |
| Backward, run 1 | 26 | 69.2% | 200 ms | 300 ms |
| Backward, run 2 | 31 | 64.5% | 125 ms | 200 ms |

For comparison, the diagnosis's numbers reproduced from "What is already established" above:

| Run | Frames | Janky | p50 | p90 |
|---|---|---|---|---|
| Forward (baseline) | 142 | 20.4% | 14 ms | 113 ms |
| Backward (baseline, before any fix) | 30 | 70.0% | 121 ms | 250 ms |
| Backward, all per-cell work stripped | 26 | 69.2% | 200 ms | 250 ms |
| Backward, month propagation cut (the target this spec aimed at) | 124 | 16.1% | 16 ms | 73 ms |

**Forward control.** Run 2 (144 frames, 20.8% janky, p50 15 ms) matches the baseline closely; run 1
(121 frames, 27.3%, p50 17 ms) is worse but the same order of magnitude — read as run-to-run
variance on a shared device, not a regression in the forward path. The comparison below is clean.

**Backward, after the fix.** Both runs land at 26 and 31 frames, 64–69% janky, p50 125–200 ms —
statistically indistinguishable from the pre-fix baseline (30 frames, 70%, p50 121 ms), and much
closer to the diagnosis's "per-cell work stripped" dead end (26 frames, 69.2%, p50 200 ms) than to
the "month propagation cut" experiment (124 frames, 16.1%, p50 16 ms) this spec's fix was built to
reproduce.

**Conclusion: Tasks 1–3 (the sticky query anchor and the single `EventViewModel` collector) did not
close the gap on device.** Whatever produced the 124-frame result in the diagnosis's isolated
experiment is not fully present in what shipped, or something else on this branch still triggers the
same per-settle cost that experiment removed. Per this spec's own non-goal — "if the asymmetry
survives on levelled numbers, it is a fresh investigation and the PR says so" — that is exactly what
this is: a fresh investigation, not a partial win rounded up to a full one.

Event dots on Aug 3 and Aug 21 were confirmed intact on the month grid after five swipes forward and
five back, so the wider ±3-month query window is not silently dropping data — the coverage invariant
holds in practice as well as on paper. The window just is not delivering the frame-rate improvement
predicted.

**Acceptance status:**

1. `./gradlew assembleDebug testDebugUnitTest lint detekt` — done in Tasks 1–3; not re-run here, out
   of this task's scope.
2. `gfxinfo` before/after table — done, above. The target (backward ~124 frames, ~16% janky, p50
   ~16 ms, matching forward) was **not met**.
3. `[H]` — still outstanding, not run in this task (that is the owner's, by design). Given the
   numbers above it would not have changed the verdict, but the item is not closed without it
   regardless.
4. `docs/TEST-PLAN-2026-08.md` §11 — updated with an "Item 8 — measured after the attempted fix"
   subsection recording the same result and re-opening the item as a fresh investigation rather than
   moving it into "fixed".

## What the negative result narrows it to

The measurement is worth more than its verdict, because three experiments now bracket the cause:

| Experiment | What it removed | Backward frames |
|---|---|---|
| Diagnosis row 3 | every per-cell lookup (events, custody, holidays) | 26 — no change |
| Diagnosis row 4 | `onMonthChange` made a **complete** no-op | 124 — fixed |
| This branch | only the **event query** that `onMonthChange` triggered | 26–31 — no change |

Row 4 is the only experiment that helped, and it removed three things at once: the event query, the
ViewModel state write, and the whole-screen recomposition that state write causes. The diagnosis
attributed the entire gain to the query — "`onMonthChange` → … → a fresh event query through
`queryRangeFor`" — because its single experiment could not separate the three. This branch removed
the query and nothing else, and the number did not move. **That attribution is now falsified.**

What row 4 removed and this branch did not: `showMonth` still writes `_displayedMonth` and
`_selectedDate`, and `CalendarScreen` collects both near the top of its composable body
(`CalendarScreen.kt:188-191`). Every settle therefore still recomposes the entire screen — header,
banners, filters, the grid and the agenda card — even though no query is issued any more. That, not
the query, is what is left of row 4's delta.

**The next experiment is the mirror image of this one:** keep the query wiring exactly as it now is
and cut only the ViewModel state write on settle, then re-run the protocol. If backward paging
recovers, the fix is scoping the recomposition — hoisting the parts of `CalendarScreen` that do not
depend on the displayed month out of the recomposing scope, or deriving them so the month change
touches only the grid. If it does not recover either, then row 4's result came from something
outside the ViewModel round-trip altogether and the diagnosis needs restarting, not refining.

Why any of this lands on one direction only is still unestablished. It was unestablished in the
diagnosis, this round did not touch it, and it should not be guessed at now.

**What stays on the branch regardless.** Tasks 1–3 are not reverted, and their value does not depend
on this measurement: the collector leak was real and unbounded (one permanent collector per month
swipe, for the life of the Calendar tab, each re-expanding recurrences on every write to the events
table), and querying a fresh range on every settle was real waste. Both are fixed and unit-tested.
They are correctness and efficiency work that happens not to be the frame-rate fix — which is
precisely what the numbers say, and all they say.

## Files

- `app/src/main/java/com/coparently/app/presentation/event/EventViewModel.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarViewModel.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarSelection.kt`
- `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt` (`queryRangeFor`)
- `app/src/test/java/com/coparently/app/presentation/calendar/CalendarSelectionTest.kt`
- `app/src/test/java/com/coparently/app/presentation/calendar/CalendarViewModelTest.kt`
- `app/src/test/java/com/coparently/app/presentation/event/EventViewModelTest.kt`
- new: `app/src/test/java/com/coparently/app/presentation/calendar/CalendarQueryRangeTest.kt`
