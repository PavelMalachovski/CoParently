# SDD ledger — plan: docs/superpowers/plans/2026-08-23-home-screen.md

Branch: `claude/start-e-schemas-docs-3kn5c6`, cut from `main` @ `2e04828` — which already carries
B2, C (PR #53) and D (PR #54), so Room schema starts at **18** and `DayCellFills` already holds
C's handover diagonal.
Tasks: 7. Tasks 1–6 implemented; Task 7 partly run.

## What was and was not verified here

Same environment constraint as packages C and D: **no Android SDK, and no route to Google's Maven
host** (`dl.google.com`, and `maven.google.com` by redirect, are refused by the proxy with 403).
So `assembleDebug`, `testDebugUnitTest`, `lint`, `detekt` and `connectedDebugAndroidTest` were
**not** run, and no Compose or Android-dependent file has been through a compiler.

Really run:

- **The Firestore rules, on the emulator** — full suite **237 passing, 0 failing**; eslint clean.
  No rule change was needed and none was made: the `events` block validates by *presence*
  (`keys().hasAll([...])`), not by an exact key set, so Task 2's new `isImportant` field is
  accepted without touching it. The suite was run anyway, as a regression check on a package that
  changes an event's wire format.
- **Every pure-Kotlin test that compiles without the Android classpath** — 32 test classes
  compiled with a standalone `kotlinc` 2.1 (fetched from the JetBrains GitHub release; the
  Maven Central `kotlin-compiler` jar alone is not runnable) against 76 pure main sources, run
  under JUnit: **293 passing**. That includes this package's three suites — `HomeWeekTest` (8),
  `ContactDirectoryTest` (7) and `DayCellFillsTest` (16 → **24**) — alongside every suite B2, C
  and D added.
- **The nested-`combine`-with-destructuring shape** `HomeViewModel.dashboard` uses, type-checked
  in isolation against real `kotlinx.coroutines` — that one is pure Kotlin even though the file
  around it is not, and getting the arity or the destructuring wrong is a compile error nothing
  else here would have caught.
- **Locale completeness**, by grep: all **15** new keys present in exactly five files each, no
  duplicates. Both deleted keys gone from all five, and unreferenced.
- **`grep -rn "WeeklySummary" app/src`** — no output.
- **`MaxLineLength` 120** over every file this package touches — the four >120 lines in
  `DayWeekView`, `NavGraph` and `CalendarScreen` are pre-existing and unmoved.

Tests written but **not runnable here** (they need the Android/Firebase classpath, and run on the
first local build): 3 new cases in `EventRepositoryImplTest`, the rewritten `HomeViewModelTest`,
and 1 instrumented migration case.

---

## Ledger

Task 1 (the unpaired home): `d58e5ab`. `HomeViewModel` now exposes one sealed `HomeUiState`
instead of eight flows the composable recombined — which is *why* the old screen was hollow
rather than merely untidy: "unpaired" decided whether one card appeared, and nothing had told the
hero, the tiles, the week or the changes feed not to render.
  - DECISION. The seven content flows stay as private `StateFlow`s with their own initial values
    rather than becoming bare flows. `combine` emits nothing until every source has, and what a
    paired user would be looking at meanwhile is `AskForCoParent` — a paired parent being asked to
    pair. Seeding each source keeps the first frame honest.
  - `PairingState.Loading` still reads as "not paired", per the plan. The existing doc's reasoning
    got stronger, not weaker, once it gated the page, and the KDoc now says so.
  - Deleted `home_recent_empty_unpaired` from all five locales: the unpaired page renders no feed,
    so there is no feed there to be empty.

Task 2 (`Event.isImportant`): `0440534`. Room **18 → 19**, one column,
`INTEGER NOT NULL DEFAULT 0`.
  - **Six map sites, found by grepping `acceptance` rather than trusting the plan's list of
    three:** `EventRepositoryImpl.toFirestoreMap` / `toDomain` / `toEntity`, and `SyncService`'s
    upload map, its `UseLocal` conflict map and `toEventEntity`. The `UseLocal` map is the one a
    list would miss; it is a partial `update()` on the remote document, so a field absent from it
    is left stale rather than deleted — a quieter failure than the one B1 shipped, not a smaller
    one.
  - The Firestore reader defaults an absent `isImportant` to false, matching the migration.
  - The form switch's helper text appears only once the switch is on: printing it while the flag
    is off would explain a state the event is not in.

Task 3 (the child's week): `c80f959`. The window, the private-event filter and the
collision-free occurrence keys are pure, in `domain/home/HomeWeek.kt`, so they are among the
things that *were* run here.
  - DECISION worth a second opinion. Each row names the **custody** parent for that date, not
    `Event.parentOwner`, on the reading that "the parent whose day it falls on" (spec §3) is who
    has the child. The dot takes the same parent, so the colour and the words never name two
    different people in one row. A date nothing answers for falls back to the event's owner for
    the dot and drops the "'s day" clause from the text — the part that would be a guess.
  - DEVIATION. Custody here resolves through `CustodyResolver` with **no legacy fallback**
    (`legacy = { null }`). This ViewModel has never had one — the handover hero above it already
    resolves from the model and the swaps alone — and injecting a DAO to answer for accounts that
    never migrated off `custody_schedules` would put a second custody lookup on one screen. On
    such an account the week's rows say the time and not whose day it is; the grid is unaffected.
  - The list is no longer capped at three. "This week" ending on Tuesday, with nothing saying so,
    is what the cap did on a busy Monday.
  - The whole `StatTiles` row moves to the bottom, not just the spend tile. The two are one row,
    and the Chat tab already carries its own unread badge, so nothing is lost.
  - `nextHandover` and the week now share one `custody` subscription instead of opening
    `getActiveModel()` and `observeDayOverrides()` twice.

Task 4 (delete the weekly summary): `340af30`. Button, `Screen.WeeklySummary`, the route, both
files and `home_weekly_summary` in five locales. It was also the last screen still printing
hardcoded English titles, which is a second reason it should not have survived as dead code.

Task 5 (contacts): `e0b8d60`. `ContactDirectory` is pure and tested; the screen and ViewModel are
not compiled here.
  - LIMITATION, deliberate. One row per person, not per number. Every recorded number is *shown*
    in the supporting line, but the tap dials the first one — `SectionRow`'s one-trailing-control
    rule is the August refresh's, and two call buttons on a row is exactly what it removed.
  - A record whose primary `phone` is blank still dials its `alternatePhone`. The distinction
    between the two fields is which was typed first, and a parent looking for a number in a hurry
    does not care.
  - `ACTION_DIAL` with a failure path: a device with no dialler at all gets a snackbar. A silent
    no-op there looks exactly like a broken button, which on this screen is the worst outcome
    short of the wrong number.

Task 6 (the translucent proposal): `11e49ad`. Folded into `DayCellFills`' single decision function
alongside C's handover diagonal, per the shared-ground note in both plans.
  - Only the days a pending proposal would **change** are marked. A proposal is a whole pattern,
    so marking every day it covers would wash the month and say nothing.
  - DECISION. The preview is drawn to **both** parents, not only to the one who has to answer.
    The proposer needs to see what they asked for as much as the recipient needs to see what is
    being asked, and neither reading moves a day — `getCustodyForDate` still does not consult the
    proposal.
  - DEVIATION from C's precedent, deliberately. C kept the handover diagonal and the swap arrows
    to the month grid, because the week already shows a handover as the boundary between two runs
    of its custody band. A pending proposal has **no** other form in the week's layout, so leaving
    it out would make the week the one view that shows a schedule as settled while the month says
    it is not. It is in both.
  - In the month grid the preview is drawn *after* the diagonal, so a handover boundary inside a
    previewed day is previewed too rather than punching a hole in it.
  - The two translucent hues blend into a colour that is neither parent's. That is the intended
    reading — the day is being argued about — and the cell's spoken description says so in words,
    which is what actually disambiguates it. `PROPOSAL_TINT_ALPHA` (0.09f) lives in
    `CoPlanlyColors` beside `CUSTODY_TINT_ALPHA` (0.14f), for the reason that one is shared.

Task 7 (verification): rules, pure-Kotlin, greps and line length done (above). Everything
Gradle-shaped, and the device runs that are the point of Tasks 1, 5 and 6, still outstanding.

---

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — nothing Compose-shaped in
      this package has compiled.
- [ ] **Commit the generated `app/schemas/…/15.json` through `19.json`.** B2's, C's, D's and now
      E's are all missing; without them the instrumented migration tests have no schema to
      validate against. They appear on the first local `assembleDebug`.
- [ ] `connectedDebugAndroidTest --tests …CoPlanlyDatabaseMigrationTest` (through 18→19).
- [ ] Device checks, plan Task 7 step 5, all five.
- [ ] Record the run in the spec's §6.

## The checks that prove this package

1. **Unpaired: one explanation and one button.** No hero, no tiles, no week, no changes feed —
   and the gear and the bottom bar still there. The failure mode to watch for is the *opposite*
   one now: a genuinely paired account seeing the invitation for more than a frame means
   `pairingState` is not resolving, not that the gate is wrong.
2. **Two devices, a pending proposal.** Propose a custody change from A. On B — who has not
   agreed — those days draw translucent while the agreed pattern still shows at full strength,
   in **both** the month grid and the week view. Accept on B; they go solid on both phones and
   nothing distinguishes them from an ordinary agreed day. This is the check that cannot be done
   any other way: `DayCellFillsTest` pins the decision, not the paint.
3. **An important event's mark, on home and in the day agenda**, and the flag surviving a round
   trip to the co-parent's phone. Six map sites carry it and missing one is silent; the two unit
   tests cover the repository's, not `SyncService`'s.

## One thing to watch alongside them

**The week's row parent.** Task 3 changed what that line means — from the event's owner to whose
custody day it falls on. On an account still on the legacy `custody_schedules` table the week will
say the time and no parent at all, while the calendar grid beside it still colours the day. That
is the documented deviation above, not a bug, but it is the one place this package can look
broken while behaving exactly as designed.
