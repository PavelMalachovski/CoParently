# Calendar and custody — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one parent offer the other a single day, drop the banner the colours already say, and make a handover visible in the grid and on the home screen.

**Architecture:** A `dayOverrides` map keyed by ISO date on the pair's existing `custody_models` document — one more field beside PR #47's `proposal`, so one listener and one rule block still cover everything. Every consumer resolves custody through one function that joins accepted overrides onto the pattern.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room, Firebase Firestore, JUnit 4 + MockK, `@firebase/rules-unit-testing`.

**Spec:** `docs/superpowers/specs/2026-08-23-calendar-and-custody-design.md`

**Depends on nothing unmerged.** PR #47 is on `main`. If B1 (#49) or B2 has landed first, the Room schema version below shifts — Task 3 resolves it by reading `CoPlanlyDatabase`, not by trusting a number written here.

## Global Constraints

- **Jetpack Compose only.** Never add an XML layout.
- **Stateless composables** — state lives in ViewModels as `StateFlow`.
- **Custody is resolved in one place.** CLAUDE.md: every view goes through the unified lookup in `CalendarScreen`; reading `CustodyScheduleEntity` directly makes model-based custody vanish.
- **Calendar query ranges come from `queryRangeFor()`** in `CalendarScreen.kt` — extend that function rather than inlining range maths.
- **Parent colours** go through `presentation/theme/ParentColors.kt`: `fill()` for backgrounds and marks, `text()` for foregrounds. Raw `MomPink`/`DadBlue` are fill-only. A custody day background is the hue at ~14% alpha; a chip or marker is full strength.
- **`DayCellFills` keeps its invariant:** the weekend is a `base`, never an `overlay` that competes with custody.
- **Never hardcode user-visible text.** Every new key in all five locales in the same commit; `MissingTranslation` is disabled and reports nothing.
- **Room schema changes** require entity change → version bump → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`); exported schemas in `app/schemas/`.
- **Dates cross the Firestore schema as ISO strings.**
- **Never debug `firestore.rules` by deploying to production.** `firestore-tests/` runs them offline; add the case there first. Needs a JDK 21+ on `PATH`.
- KDoc on every public class and function; code and comments in **English**.
- detekt `MaxLineLength` **120**; `TooGenericExceptionCaught` active and lists `Exception`; nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26. Conventional Commits.
- `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew …` — the machine's own `JAVA_HOME` is broken.
- detekt exits non-zero from pre-existing findings. Judge by whether **your** files appear in `app/build/reports/detekt/detekt.xml`.

---

### Task 1: `DayOverride` and its transitions

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/custody/DayOverride.kt`
- Create: `app/src/main/java/com/coparently/app/domain/custody/DayOverrideTransition.kt`
- Test: `app/src/test/java/com/coparently/app/domain/custody/DayOverrideTransitionTest.kt`

**Interfaces produced:** `data class DayOverride(toParent: String, requestedBy: String, requestedAt: String, status: DayOverrideStatus, decidedBy: String?, decidedAt: String?, note: String?)`; `enum class DayOverrideStatus { PENDING, ACCEPTED, DECLINED }`; `DayOverrideTransition.offer/accept/decline`, each returning `Result<Map<String, DayOverride>>` over the whole map keyed by ISO date.

Read `CustodyProposalTransition.kt` first — this is the same shape at a smaller scale, and matching it is the point.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.coparently.app.domain.custody

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules behind offering one day to the other parent.
 *
 * The property that matters most is that a parent cannot decide their own offer. Without it a
 * swap is not an agreement at all — either parent could grant themselves a day and the other
 * would merely be told, which is exactly the state PR #47 existed to end for whole patterns.
 */
class DayOverrideTransitionTest {

    private val mom = "uid-mom"
    private val dad = "uid-dad"
    private val date = "2026-09-05"
    private val now = "2026-08-23T10:00:00"

    @Test
    fun `offering records a pending override for that date`() {
        val next = DayOverrideTransition
            .offer(emptyMap(), date, toParent = "dad", byUid = mom, atIso = now).getOrThrow()

        assertEquals(DayOverrideStatus.PENDING, next.getValue(date).status)
        assertEquals("dad", next.getValue(date).toParent)
        assertEquals(mom, next.getValue(date).requestedBy)
    }

    @Test
    fun `a second offer for the same date replaces the first rather than queueing`() {
        val first = DayOverrideTransition
            .offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val second = DayOverrideTransition
            .offer(first, date, "mom", dad, "2026-08-23T11:00:00").getOrThrow()

        assertEquals(1, second.size)
        assertEquals(dad, second.getValue(date).requestedBy)
    }

    @Test
    fun `the parent who offered cannot accept their own offer`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()

        assertTrue(DayOverrideTransition.accept(offered, date, byUid = mom, atIso = now).isFailure)
    }

    @Test
    fun `the other parent can accept it`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val accepted = DayOverrideTransition.accept(offered, date, dad, now).getOrThrow()

        assertEquals(DayOverrideStatus.ACCEPTED, accepted.getValue(date).status)
        assertEquals(dad, accepted.getValue(date).decidedBy)
    }

    @Test
    fun `a decided override cannot be decided again`() {
        val offered = DayOverrideTransition.offer(emptyMap(), date, "dad", mom, now).getOrThrow()
        val accepted = DayOverrideTransition.accept(offered, date, dad, now).getOrThrow()

        assertTrue(DayOverrideTransition.decline(accepted, date, dad, now).isFailure)
    }

    @Test
    fun `deciding a date with no override fails rather than inventing one`() {
        assertTrue(DayOverrideTransition.accept(emptyMap(), date, dad, now).isFailure)
    }

    @Test
    fun `an offer for one date leaves every other date alone`() {
        val other = "2026-09-12"
        val first = DayOverrideTransition.offer(emptyMap(), other, "mom", dad, now).getOrThrow()
        val second = DayOverrideTransition.offer(first, date, "dad", mom, now).getOrThrow()

        assertEquals(setOf(other, date), second.keys)
        assertEquals(DayOverrideStatus.PENDING, second.getValue(other).status)
    }
}
```

- [ ] **Step 2: Run it; expect `Unresolved reference: DayOverrideTransition`.**
- [ ] **Step 3: Implement both files.** Transitions are pure: they take the current map and return a new one, never mutate. Failures are `Result.failure(IllegalStateException(...))` with a message naming the rule broken.
- [ ] **Step 4: Run it; expect 7 PASS.**
- [ ] **Step 5: Commit** — `feat(custody): a one-off day swap the other parent has to agree to`

---

### Task 2: `custodyFor` — the single lookup, with overrides joined in

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/custody/CustodyResolver.kt`
- Test: `app/src/test/java/com/coparently/app/domain/custody/CustodyResolverTest.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/custody/HandoverCalculator.kt`

**Interfaces produced:** `CustodyResolver.custodyFor(model: CustodyModel?, overrides: Map<String, DayOverride>, legacy: (LocalDate) -> String?, date: LocalDate): String?`; `HandoverCalculator.nextHandoverFrom` gains an `overrides` parameter.

**The ordering is the whole correctness of this package:** an **accepted** override wins; otherwise the active model; otherwise the legacy schedule; otherwise null. A `PENDING` or `DECLINED` override changes nothing.

**`HandoverCalculator` must consume this too.** It currently walks `model.getCustodyFor` directly. A swap creates a handover on the swapped day and removes the one it displaced, and a calculator that cannot see overrides tells the home screen a date that is simply wrong — silently, because nothing fails.

- [ ] **Step 1: Write the failing test**

Cover, at minimum:
- an accepted override wins over the pattern;
- a pending override does **not**;
- a declined override does **not**;
- with no model, the legacy fallback is still consulted;
- with neither, the answer is null rather than a guess;
- `nextHandoverFrom` finds a handover created by an accepted swap;
- `nextHandoverFrom` no longer reports a handover that a swap removed.

The last two are the ones that would otherwise ship broken.

- [ ] **Step 2: Run it; expect a compile failure.**
- [ ] **Step 3: Implement `CustodyResolver`, then thread `overrides` through `HandoverCalculator`.** Keep `nextHandoverFrom`'s two-cycle bound — a model that never switches must still terminate.
- [ ] **Step 4: Run it; expect all PASS, and the existing `HandoverCalculator` tests still green.**
- [ ] **Step 5: Commit** — `feat(custody): resolve a day through overrides, pattern and legacy in one place`

---

### Task 3: Storage and sync for `dayOverrides`

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/CustodyModelEntity.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/CustodyModelRepository.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/custody/SharedCustody.kt` / `SharedCustodyRead.kt`
- Test: `app/src/androidTest/.../CoPlanlyDatabaseMigrationTest.kt`

**Resolve the schema version by reading `CoPlanlyDatabase`, not from this document** — B1 and B2 may have landed first. One nullable `dayOverridesJson TEXT` column, additive.

Read `CustodyModelRepository` before touching it. Two of its properties are load-bearing and documented in CLAUDE.md:

- **`isNewer` compares naive local date-times and the winner is re-pushed over the loser.** Do not add `dayOverrides` to that comparison, and do not stamp `lastModifiedAt` when writing an override — an override is not a pattern change, and re-dating the document makes this device win every future comparison.
- The document is mirrored, not merged. Confirm how a partial write lands before adding a third mutable field beside `proposal` and `lastDecision`.

- [ ] **Step 1: Add the column and bump the version.**
- [ ] **Step 2: Write `MIGRATION_N_N+1`** — one `ALTER TABLE custody_models ADD COLUMN dayOverridesJson TEXT`. Register it in `ALL_MIGRATIONS`.
- [ ] **Step 3: Carry the map through the repository's read and write paths**, mirroring how `proposal` is carried. Absent in the document must read as an empty map, never null — every document written before this change has no such key.
- [ ] **Step 4: Add a migration test case** following the file's existing style, asserting an existing row survives and the new column starts null.
- [ ] **Step 5: Run `assembleDebug testDebugUnitTest`, and the instrumented migration test on the device.**
- [ ] **Step 6: Commit** — `feat(custody): store and sync one-off day swaps`

---

### Task 4: Rules for `dayOverrides`

**Files:**
- Modify: `firestore.rules`
- Modify: `firestore-tests/rules/custody-models.test.js`

The `custody_models` block already requires `lastModifiedBy == request.auth.uid` on write and uses `allow get` so no list query can be issued. `dayOverrides` needs one more guarantee: **a transition to `ACCEPTED` or `DECLINED` must be written by someone who is not the entry's `requestedBy`.** Without it either parent can grant themselves a day.

- [ ] **Step 1: Add the emulator cases first** — a participant may add a pending override; a participant may decide one they did **not** request; the requester **may not** decide their own; a non-participant may do neither. Read `harness.js` and the file's existing helpers; do not invent a new setup.
- [ ] **Step 2: Run `cd firestore-tests && npm test`; expect the new cases to fail.**
- [ ] **Step 3: Write the rule.**
- [ ] **Step 4: Run again; expect all pass, and every pre-existing case still green.**
- [ ] **Step 5: Commit** — `feat(rules): stop a parent granting themselves a swapped day`

---

### Task 5: Item 6 — remove the banner

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt`
- Delete: `app/src/main/java/com/coparently/app/presentation/calendar/components/CustodyRibbon.kt`

The colours already say whose day it is, and the handover countdown the ribbon also carried is on the home screen's `HandoverHero`. Two answers to one question is what the design refresh removed elsewhere.

- [ ] **Step 1: Remove the `CustodyRibbon` call from `CalendarScreen` and delete the file.**
- [ ] **Step 2: Confirm nothing else references it** — `grep -rn "CustodyRibbon" app/src`. Expected: no output.
- [ ] **Step 3: Check its strings.** `calendar_custody_today_with`, `calendar_custody_handover_tomorrow`, `calendar_custody_handover_in_days` and the two content descriptions may now be unreferenced. Delete only the keys nothing else uses, from all five locales, and say in your report which you deleted and which survive.
- [ ] **Step 4: `assembleDebug`; commit** — `feat(calendar): drop the banner the colours already say`

---

### Task 6: Items 10 and 11 — the diagonal and the arrows

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/DayCellFills.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/MonthView.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/DayWeekView.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/calendar/DayCellFillsTest.kt`

**A handover day is `custodyFor(date) != custodyFor(date - 1)`.** The cell is split on a diagonal: the previous day's parent top-left, the new day's parent bottom-right — reading the way time does.

Add it to `DayCellFills` as an overlay **shape**, not a fourth colour, so the weekend base still shows through and the file's stated invariant survives. Extend the existing `DayCellFillsTest` rather than starting a parallel one.

**Item 11 is a marker, not a colour.** A date with a `PENDING` override shows `→` over `←` in the cell. The cell's colour still means whose day it is *now* — a pending swap has not changed that, and saying otherwise on the grid would be a lie both parents act on.

- [ ] **Step 1: Extend `DayCellFillsTest`** — a handover day is detected; the day after a handover is not; a swap creates one and removes the one it displaced.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Implement the fill decision, then the drawing** in `MonthView` and `DayWeekView`. Use `ParentColors.fill()` at the custody alpha for both triangles; a `Brush` with a diagonal gradient stop, or two `drawPath` calls, both work — pick one and say why in your report.
- [ ] **Step 4: Add the arrows.** Content description must name the date and that a swap is pending; the arrows alone are meaningless to a screen reader.
- [ ] **Step 5: Run tests, `assembleDebug`; commit** — `feat(calendar): split a handover day, and mark a day being negotiated`

---

### Task 7: Item 7 — the long-press sheet and the inbox

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/calendar/components/DaySwapSheet.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/MonthView.kt` (long-press)
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/changerequests/ChangeRequestsScreen.kt` + its ViewModel
- Create: `app/src/main/res/values*/day_swap_strings.xml` (five locales)

**Only a paired account may offer a swap.** Unpaired, the long-press does nothing — there is nobody to accept, and a swap that applies itself is an edit the custody editor already does.

The inbox shows day swaps beside event change requests. Read `ChangeRequestsScreen`'s existing two-section layout and `ChangeRequestHighlight.indexInInbox` before adding a third section — that function computes a flat index across headers and sections and will need to know about it.

- [ ] **Step 1: Write the strings in all five locales**, matching each file's register (German informal "du"; Czech and Ukrainian polite plural).
- [ ] **Step 2: Build the sheet** — the date, who has it now, who would take it, an optional note, and one primary action. Both parents' names come from `rememberParentNames`; never the words "Mom" or "Dad".
- [ ] **Step 3: Wire the long-press** in `MonthView`, guarded on paired.
- [ ] **Step 4: Add the inbox section** with accept and decline, using `DayOverrideTransition`.
- [ ] **Step 5: Post a chat card on offer**, mirroring `RequestChangeViewModel.postChatMessage`. (Package **D** generalises this; if D has landed, use its mechanism instead of adding a second one.)
- [ ] **Step 6: Verify locales by grep; confirm no hardcoded text; `assembleDebug testDebugUnitTest`.**
- [ ] **Step 7: Commit** — `feat(calendar): offer the other parent a single day, by long-press`

---

### Task 8: Item 10's home reminder

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/home/HomeScreen.kt`

`HandoverHero` already renders the next handover. It must now consume the override-aware calculator from Task 2, and say **which day** the child changes hands and to whom.

**It must not claim an hour.** Spec §5: there is no handover time anywhere in the schema. A hero that says "at 18:00" would be inventing it, and a separated parent would plan around it.

- [ ] **Step 1: Feed overrides into the ViewModel's handover flow.**
- [ ] **Step 2: Confirm the hero's copy says a day, not a time.** If any existing string implies an hour, fix it in all five locales and report it.
- [ ] **Step 3: `assembleDebug testDebugUnitTest`; commit** — `feat(home): say when the child changes hands, including after a swap`

---

### Task 9: Full verification

- [ ] **Step 1:** `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — report totals, and whether detekt or lint names any file in `git diff --name-only main..HEAD`.
- [ ] **Step 2:** `cd firestore-tests && npm test && npm run lint`.
- [ ] **Step 3:** the instrumented migration test, on the device.
- [ ] **Step 4:** locale grep for every new key — five files each.
- [ ] **Step 5: The two-device run, which is the point of this package.**
  1. Offer a swap on phone A. Phone B shows it in the inbox and shows two arrows on that date.
  2. Accept on B. Both grids show the day in the other parent's colour, and the diagonal moves to the right dates.
  3. Both home screens agree about when the child next changes hands.
  4. Decline instead: nothing moves on either phone, and the arrows clear.
  5. Confirm the requester cannot accept their own offer from either device.

- [ ] **Step 6:** record the run in the spec's §8 and commit.

---

## Notes for the reviewer

**The most likely silent failure is `HandoverCalculator`.** If it is left reading the pattern directly, everything looks right — the grid paints correctly, swaps work — and only the home screen's countdown is wrong, on exactly the dates a swap touched. Task 2 exists to prevent that and its last two test cases are the guard.

**Item 10 is delivered without an hour**, deliberately — spec §5. The schema has no handover time and inventing one on a screen a separated parent plans around would be worse than omitting it.

**What this package does not do:** recurring swaps (that is a pattern change, which `CustodyProposal` already does), swaps of past days, or any change to `CustodyProposal` itself.
