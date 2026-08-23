# Home screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An unpaired home screen that asks one thing, a paired one that leads with the child's week, phone numbers a tap from dialling, and no weekly summary.

**Architecture:** The home ViewModel gains an explicit unpaired state so the screen renders a CTA rather than hollow tiles. `Event` gains one `isImportant` boolean. Contacts reuse the child's `emergencyContacts`, which B1 already shares and both parents may edit.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room, Firebase Firestore, JUnit 4 + MockK.

**Spec:** `docs/superpowers/specs/2026-08-23-home-screen-design.md`

**Depends on PR #49 (B1)** for `emergencyContacts` being shared with the co-parent. Task 5 is the only task that needs it; the rest can proceed without.

**Task 6 overlaps package C.** Both touch `DayCellFills`. Whichever lands second builds on the other; the file must end with **one** decision function covering every case. Read C's plan before starting Task 6 if C is in flight.

## Global Constraints

- **Jetpack Compose only.** Stateless composables; state in ViewModels as `StateFlow`.
- **Bottom navigation is Home / Calendar / Chat / Expenses.** Settings is **not** a tab — it opens from a gear in each top-level screen's top bar. Tab switches go through `NavHostController.navigateToTab`.
- **The home screen's UI goes through `SectionGroup` / `SectionRow` / `GroupLabel`** from `presentation/common/DesignSystem.kt`. Do not reintroduce `Card { ListItem { … } }` per row.
- **Parent colours** through `presentation/theme/ParentColors.kt`: `fill()` for backgrounds and marks, `text()` for foregrounds.
- **Recurring occurrences share the master event's id** — never use the id alone as a list key.
- **Private events never leave the device** and must never appear in a co-parent's view.
- **The Firestore event schema is defined in `EventRepositoryImpl.toFirestoreMap()`, and `SyncService` keeps its own separate maps.** A field added to one and not the other is deleted on every sync — package B1 shipped exactly that defect.
- **Never hardcode user-visible text.** Every new key in all five locales in the same commit.
- **Room schema changes:** entity → version bump → migration registered via `ALL_MIGRATIONS`; exported schemas in `app/schemas/`.
- KDoc on every public class and function; code and comments in **English**.
- detekt `MaxLineLength` **120**; nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26. Conventional Commits.
- `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew …`

---

### Task 1: The unpaired home says one thing

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/home/HomeScreen.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/home/HomeViewModelTest.kt` (extend)

Today an unpaired account sees the handover hero, both stat tiles, the week and the recent-changes list — almost all empty, arranged around the small card that says the thing that would fill them.

**The gear and the bottom bar stay.** The item is about the page's content. Removing navigation would strand the user: the calendar and the child's details are worth having alone, and Settings is where the pairing screen lives.

`HomeViewModelTest` already pins that `paired` tracks `PairingRepository.observePairingState` and treats `Loading` as not paired — extend that file rather than starting a parallel one.

- [ ] **Step 1: Extend the test** — while unpaired, the state exposes the CTA and no handover, tiles, week or changes; while `Loading`, the same (a screen that flashes tiles and then removes them is worse than one that waits); while paired, all of it returns.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Implement.** Prefer one sealed `HomeUiState` over a scatter of booleans the composable has to recombine — the screen should be told what to draw, not work it out.
- [ ] **Step 4: Run tests; build; commit** — `feat(home): ask for a co-parent, and nothing else, until there is one`

---

### Task 2: `Event.isImportant`

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/model/Event.kt`, `data/local/entity/EventEntity.kt`
- Modify: `CoPlanlyDatabase.kt`, `DatabaseMigrations.kt`
- Modify: `data/repository/EventRepositoryImpl.kt`, `data/sync/SyncService.kt`
- Modify: `presentation/event/AddEditEventScreen.kt`
- Test: `app/src/androidTest/.../CoPlanlyDatabaseMigrationTest.kt`

One boolean, defaulting false, set on the event form. It means "the co-parent is expected" — a statement, not an obligation the app enforces. Nothing blocks saving.

**Resolve the schema version by reading `CoPlanlyDatabase`**, not from this document.

**Three map sites, and missing one is silent:** `toFirestoreMap()`, its reader, and `SyncService`'s own maps. Grep `SyncService` for the field name before committing.

**The event form keeps a snapshot and uses `copy()`.** Never rebuild an `Event` from scratch on save — that wiped `sharedWith`, `permissions` and `createdByFirebaseUid` once already.

- [ ] **Step 1: Add the field** to model and entity with KDoc and `@property` entries; bump the version; write the additive migration; register it.
- [ ] **Step 2: Carry it through every map site.** Report which you found.
- [ ] **Step 3: Add the switch to the event form**, with helper text saying what the flag means. New strings in all five locales.
- [ ] **Step 4: Add the migration test case; run it on the device.**
- [ ] **Step 5: Verify locales; build; commit** — `feat(events): mark an event important, meaning the co-parent is expected`

---

### Task 3: The child's week, leading the screen

**Files:**
- Modify: `presentation/home/HomeViewModel.kt`, `HomeScreen.kt`
- Test: `presentation/home/HomeViewModelTest.kt` (extend)

The next **seven days from today**, not Monday-to-Sunday: a parent opening the app on Friday wants the weekend, not two days and a wrap-up.

Each row: the event, the parent whose day it falls on, and an exclamation mark when `isImportant`. The mark's content description must say what it means — an unexplained glyph is worse than none for a screen reader.

- [ ] **Step 1: Extend the test** — the window is seven days from today; a recurring series contributes distinct occurrences with keys that do not collide (they share the master id); the co-parent's **private** event never appears.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Implement,** moving the week above the tiles and the spend tile to the bottom, per spec §3's order.
- [ ] **Step 4: Run tests; build; commit** — `feat(home): lead with the child's week, and mark what matters`

---

### Task 4: Delete the weekly summary

**Files:**
- Delete: `presentation/summary/WeeklySummaryScreen.kt`, `WeeklySummaryViewModel.kt`
- Modify: `presentation/home/HomeScreen.kt`, `presentation/navigation/NavGraph.kt`
- Modify: `res/values*/home_strings.xml` and any summary strings

The August refresh made the home button the summary's single entry point, deliberately. Removing the button removes the last way in, so the screen goes with it rather than becoming unreachable code.

- [ ] **Step 1: Remove the button, the route and the `Screen` entry.**
- [ ] **Step 2: Delete both files.**
- [ ] **Step 3: `grep -rn "WeeklySummary" app/src`** — expected: no output.
- [ ] **Step 4: Delete strings nothing else uses**, from all five locales; report which you deleted and which survive.
- [ ] **Step 5: Build; commit** — `feat(home): remove the weekly summary, and the screen behind it`

---

### Task 5: Contacts, one tap from dialling

**Files:**
- Create: `presentation/contacts/ContactsScreen.kt`, `ContactsViewModel.kt`
- Modify: `presentation/home/HomeScreen.kt`, `presentation/navigation/NavGraph.kt`
- Create: `res/values*/contacts_strings.xml`

**Depends on PR #49** — `emergencyContacts` must be shared with the co-parent, which B1 does.

**One list, widened in meaning, not a second model.** A doctor is an emergency contact by any reasonable reading, and two parallel lists would make a parent guess which one grandma went into. `relationship` is free text and already carries "grandmother" or "paediatrician".

**Dial with `Intent.ACTION_DIAL`, never `ACTION_CALL`.** `DIAL` opens the dialler pre-filled and needs no permission; `CALL` places the call immediately and requires `CALL_PHONE`. Asking a separated parent for permission to place calls, to save one tap, is not a trade this app should make.

Editing goes to B1's existing editor — one list, one editor.

- [ ] **Step 1: Write the strings in all five locales**, matching each file's register.
- [ ] **Step 2: Build the ViewModel** over `ChildInfoRepository`. An entry with a blank phone must not be dialable.
- [ ] **Step 3: Build the screen** with `SectionGroup` / `SectionRow`: name as title, relationship as supporting, one trailing call action.
- [ ] **Step 4: Add the home button and the route.** It is a detail screen — not in `BottomNavDestination.topLevelRoutes`.
- [ ] **Step 5: Verify locales; confirm no hardcoded text; build; commit** — `feat(home): put the numbers worth finding in a hurry one tap away`

---

### Task 6: An unconfirmed schedule is translucent

**Files:**
- Modify: `presentation/calendar/DayCellFills.kt`
- Modify: `presentation/calendar/MonthView.kt`, `DayWeekView.kt`
- Modify: `presentation/calendar/CalendarViewModel.kt`
- Test: `presentation/calendar/DayCellFillsTest.kt` (extend)

PR #47 already carries a `proposal` on the shared custody document that changes nobody's pattern until accepted. The data exists; the grid does not draw it.

A day belonging to a **pending proposal** is drawn in that parent's hue at a lower alpha than an agreed day. **Same colour, less of it** — a different colour would read as a different parent.

**The agreed pattern keeps full strength underneath.** A pending proposal is a preview; a grid showing only the proposal would tell a parent their days had changed when they had not.

**Read package C's plan first if C is in flight.** Both packages rewrite this file, and it must end with **one** decision function covering weekend base, custody, holiday, today, and — depending on what has landed — the handover diagonal, the pending-swap arrows and this alpha. Two functions each knowing some cases is how the weekend band became unreachable before.

- [ ] **Step 1: Extend `DayCellFillsTest`** — a day in a pending proposal renders at the proposal alpha; the same day with no proposal renders at full; an accepted proposal is indistinguishable from an ordinary agreed day.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Implement,** feeding the pending proposal into the ViewModel's cell-fill inputs.
- [ ] **Step 4: Run tests; build; commit** — `feat(calendar): draw a schedule nobody has agreed to yet as a preview`

---

### Task 7: Full verification

- [ ] **Step 1:** `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — totals, and whether any changed file is named.
- [ ] **Step 2:** the instrumented migration test on the device.
- [ ] **Step 3:** locale grep — five files per new key.
- [ ] **Step 4:** `grep -rn "WeeklySummary" app/src` — no output.
- [ ] **Step 5: Device checks.**
  1. Unpaired: the home screen shows one explanation and one button — no hero, no tiles, no week.
  2. Paired: the week leads, spend is at the bottom, and there is no weekly-summary button.
  3. Create an important event: the mark appears on home and in the calendar's day agenda.
  4. Tap a contact: the dialler opens pre-filled, and no permission is requested.
  5. **Two devices:** propose a custody change from A. On B — who has not agreed — those days draw translucent while the agreed pattern still shows at full strength. Accept on B; they go solid on both.

- [ ] **Step 6:** record the run in the spec's §6 and commit.

---

## Notes for the reviewer

**Task 6 is shared ground with package C.** If both are in flight, the second one in must fold its case into the other's decision function rather than adding a parallel one.

**"Important" is a statement, not an enforcement** — spec §3. Nothing blocks saving and nothing chases the co-parent. If the owner wants the stronger reading, that is package D's acceptance machinery pointed at a different question and belongs there.

**What this package does not do:** enforce attendance; add a contacts model of its own; remove the recent-changes feed (that belongs with D, which replaces its job).
