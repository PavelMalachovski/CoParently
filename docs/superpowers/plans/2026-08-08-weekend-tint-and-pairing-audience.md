# Weekend tint and pairing audience — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Saturday and Sunday read as a continuous grey band in every calendar grid row, and deliver each parent's pre-pairing events to their co-parent.

**Architecture:** The weekend stops competing for the day cell's single background and becomes the layer underneath it — a pure, Compose-free decision function returns a `base` and an `overlay`, and each view maps those to colours and draws them as two chained `Modifier.background` calls. The sync fix is a one-shot, per-partner audience backfill that clears `syncedToFirestore` on this user's own events so the existing upload half recomputes `sharedWith`.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room 2.7.2, Hilt 2.56.2, MockK + kotlinx-coroutines-test for JVM unit tests.

## Global Constraints

- Jetpack Compose only — never add XML layouts.
- Colours come from `MaterialTheme.colorScheme` where a Material role exists; weekend/holiday/vacation fills stay named fill-only tokens in `CoPlanlyColors`.
- Theme detection is `MaterialTheme.colorScheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD` — never `isSystemInDarkTheme()`.
- Custody tint alpha stays `CoPlanlyColors.CUSTODY_TINT_ALPHA` (0.14f). Do not change it.
- KDoc on public classes and functions; code and comments in English.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).
- No new user-facing strings in this plan. If one becomes necessary, it goes into all five locales (`values`, `values-cs`, `values-de`, `values-ru`, `values-uk`) in the same commit.
- Build with `./gradlew assembleDebug`; unit tests with `./gradlew testDebugUnitTest`.
- `detekt` is already red on `main` with pre-existing issues. Only this branch's delta is in scope.
- Branch: `fix/weekend-tint-and-pairing-audience`, based on `main` @ `8cde4179`.

---

## File structure

| File | Responsibility |
|---|---|
| `presentation/calendar/DayCellFills.kt` (new) | The pure base/overlay decision for a month cell and a week hour cell. No Compose types, so it is unit-testable on plain JVM. |
| `presentation/theme/Color.kt` (modify) | The two weekend tokens become neutral greys. |
| `presentation/calendar/MonthView.kt` (modify) | Maps `DayCellFills.monthCell` to colours; draws base then overlay. |
| `presentation/calendar/DayWeekView.kt` (modify) | Same, via `DayCellFills.weekHourCell`. |
| `data/local/dao/EventDao.kt` (modify) | `markOwnEventsUnsynced` — the backfill statement. |
| `data/local/preferences/PreferenceKeys.kt` (modify) | The per-user backfill marker key prefix. |
| `data/sync/SyncService.kt` (modify) | Runs the backfill once per partner, before the upload half reads unsynced rows. |

`DayCellFills` is deliberately colour-free: no unit test in this repo constructs an `androidx.compose.ui.graphics.Color`, and a decision that returns colours could only be tested from a composition. Returning an enum pair keeps the branching — which is the part that was wrong — under a plain JVM test.

---

### Task 1: The day-cell fill decision

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/calendar/DayCellFills.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/calendar/DayCellFillsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class DayCellBase { SURFACE, WEEKEND }`
  - `enum class DayCellOverlay { NONE, CUSTODY_MOM, CUSTODY_DAD, PUBLIC_HOLIDAY, TODAY }`
  - `data class DayCellFill(val base: DayCellBase, val overlay: DayCellOverlay)`
  - `DayCellFills.monthCell(isWeekend: Boolean, isCurrentMonth: Boolean, custody: String?, isPublicHoliday: Boolean): DayCellFill`
  - `DayCellFills.weekHourCell(isWeekend: Boolean, isToday: Boolean, custody: String?): DayCellFill`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/calendar/DayCellFillsTest.kt`:

```kotlin
package com.coparently.app.presentation.calendar

import org.junit.Test
import kotlin.test.assertEquals

/**
 * The branching that decides a day cell's fill.
 *
 * Before this existed, the month cell picked exactly one background with custody ahead of the
 * weekend, and `CustodyModel.getCustodyFor` never returns null — so on any account with an
 * active custody model the weekend branch was unreachable and Saturday/Sunday were tinted only
 * in the grid rows that reach into a neighbouring month.
 */
class DayCellFillsTest {

    @Test
    fun `a weekend under a custody wash keeps its grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.CUSTODY_MOM),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = true,
                custody = "mom",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `a weekend in a neighbouring month is grey with no overlay`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = false,
                custody = "dad",
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a weekday in a neighbouring month is plain surface`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = false,
                custody = "mom",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `custody still beats a public holiday`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.CUSTODY_DAD),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = "dad",
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a public holiday on a weekend tints over the grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.PUBLIC_HOLIDAY),
            DayCellFills.monthCell(
                isWeekend = true,
                isCurrentMonth = true,
                custody = null,
                isPublicHoliday = true
            )
        )
    }

    @Test
    fun `a plain weekday has no overlay`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = null,
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `an unknown custody slot is not treated as a parent`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.NONE),
            DayCellFills.monthCell(
                isWeekend = false,
                isCurrentMonth = true,
                custody = "grandma",
                isPublicHoliday = false
            )
        )
    }

    @Test
    fun `a week hour cell on a weekend keeps its grey base under custody`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.CUSTODY_MOM),
            DayCellFills.weekHourCell(isWeekend = true, isToday = false, custody = "mom")
        )
    }

    @Test
    fun `custody still beats today in the week view`() {
        assertEquals(
            DayCellFill(DayCellBase.SURFACE, DayCellOverlay.CUSTODY_DAD),
            DayCellFills.weekHourCell(isWeekend = false, isToday = true, custody = "dad")
        )
    }

    @Test
    fun `today tints a weekend hour cell over the grey base`() {
        assertEquals(
            DayCellFill(DayCellBase.WEEKEND, DayCellOverlay.TODAY),
            DayCellFills.weekHourCell(isWeekend = true, isToday = true, custody = null)
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.DayCellFillsTest"
```

Expected: compilation failure — `Unresolved reference: DayCellFills`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/coparently/app/presentation/calendar/DayCellFills.kt`:

```kotlin
package com.coparently.app.presentation.calendar

/** The fill a day cell starts from, before anything is drawn over it. */
enum class DayCellBase {
    /** The screen's own surface. */
    SURFACE,

    /** Saturday or Sunday — a neutral grey, applied in every grid row. */
    WEEKEND
}

/** What is drawn over [DayCellBase], at its own alpha, or nothing. */
enum class DayCellOverlay {
    NONE,
    CUSTODY_MOM,
    CUSTODY_DAD,
    PUBLIC_HOLIDAY,

    /** Week view only; the month grid marks today on the day number instead. */
    TODAY
}

/**
 * A day cell's two fills: the [base] it starts from and the [overlay] drawn over it.
 *
 * @property base Always decided by the weekday alone, so the weekend band is continuous.
 * @property overlay The cell's meaning — custody, holiday or today — or [DayCellOverlay.NONE].
 */
data class DayCellFill(val base: DayCellBase, val overlay: DayCellOverlay)

/**
 * Decides what a calendar day cell is filled with, kept out of Compose so the branching can be
 * unit tested without a composition.
 *
 * **The weekend is a base, not a competitor.** The month cell used to pick exactly one
 * background, with custody ahead of the weekend; because
 * [com.coparently.app.domain.model.CustodyModel.getCustodyFor] reduces any date into the pattern
 * cycle and always answers `"mom"` or `"dad"`, every in-month cell matched a custody branch and
 * the weekend branch could not be reached at all. The only weekends that kept a tint were the
 * ones belonging to a neighbouring month, which is why the band appeared in some grid rows and
 * not others with no rule a reader could infer.
 *
 * Weekend deliberately does **not** win over custody: weekends are the days a separated parent
 * checks first, and replacing the parent hue there with grey would remove the answer from exactly
 * the cells the screen exists to give it in.
 */
object DayCellFills {

    /**
     * The fill for a cell in the month grid.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isCurrentMonth False for the leading and trailing days borrowed from the
     *   neighbouring months. They take the base but never an overlay, matching their already
     *   dimmed day numbers.
     * @param custody `"mom"`, `"dad"`, or null when no custody model or legacy schedule applies.
     *   Any other value is treated as no custody rather than guessed at.
     * @param isPublicHoliday A public holiday, not a school vacation — school vacation is a
     *   month-level banner and never a cell fill.
     */
    fun monthCell(
        isWeekend: Boolean,
        isCurrentMonth: Boolean,
        custody: String?,
        isPublicHoliday: Boolean
    ): DayCellFill = DayCellFill(
        base = baseFor(isWeekend),
        overlay = if (!isCurrentMonth) {
            DayCellOverlay.NONE
        } else {
            custodyOverlay(custody)
                ?: if (isPublicHoliday) DayCellOverlay.PUBLIC_HOLIDAY else DayCellOverlay.NONE
        }
    )

    /**
     * The fill for one hour cell in the week or day view.
     *
     * Custody beats today for the same reason it does in the month grid: today already reads as
     * today from its coloured column header, and the old order hid custody on the one column
     * parents check first.
     *
     * @param isWeekend Saturday or Sunday.
     * @param isToday Whether this column is today.
     * @param custody `"mom"`, `"dad"`, or null.
     */
    fun weekHourCell(isWeekend: Boolean, isToday: Boolean, custody: String?): DayCellFill =
        DayCellFill(
            base = baseFor(isWeekend),
            overlay = custodyOverlay(custody)
                ?: if (isToday) DayCellOverlay.TODAY else DayCellOverlay.NONE
        )

    private fun baseFor(isWeekend: Boolean) =
        if (isWeekend) DayCellBase.WEEKEND else DayCellBase.SURFACE

    private fun custodyOverlay(custody: String?): DayCellOverlay? = when (custody) {
        "mom" -> DayCellOverlay.CUSTODY_MOM
        "dad" -> DayCellOverlay.CUSTODY_DAD
        else -> null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.calendar.DayCellFillsTest"
```

Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/calendar/DayCellFills.kt app/src/test/java/com/coparently/app/presentation/calendar/DayCellFillsTest.kt
git commit -m "feat(calendar): make the weekend a base layer instead of a competing fill"
```

---

### Task 2: Neutral grey tokens, and the month grid drawn in two layers

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/theme/Color.kt:98-100`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/MonthView.kt:265-289` and `:344-348`

**Interfaces:**
- Consumes: `DayCellFills.monthCell`, `DayCellBase`, `DayCellOverlay` from Task 1.
- Produces: nothing new. `CoPlanlyColors.WeekendBackgroundLight` / `WeekendBackgroundDark` keep their names and change value.

- [ ] **Step 1: Recolour the two tokens**

In `Color.kt`, replace lines 98–100:

```kotlin
    // Weekend background colors - subtle distinction for Saturday/Sunday. Fill-only.
    val WeekendBackgroundLight = Color(0xFFFFF8E1) // Warm cream/amber tint
    val WeekendBackgroundDark = Color(0xFF2D2D1E) // Dark warm tone
```

with:

```kotlin
    // Weekend background colors - Saturday/Sunday, applied to every cell in the grid as the
    // base a custody, holiday or today tint is then drawn over. Fill-only: never used as text.
    // Neutral rather than the warm cream/olive they used to be, and one value per theme rather
    // than a per-call-site alpha, so the month and week grids read as one system.
    val WeekendBackgroundLight = Color(0xFFECECEF) // Neutral light grey, one step off white
    val WeekendBackgroundDark = Color(0xFF2A2A31) // Neutral dark grey, one step off DarkSurface
```

- [ ] **Step 2: Replace the month cell's single background with a base and an overlay**

In `MonthView.kt`, replace the block currently at lines 265–289 (from `val isWeekend =` through the closing `}` of `val backgroundColor = when { … }`) with:

```kotlin
    val isWeekend = CustodyHelper.isWeekend(date)
    // Use the actually-rendered theme (the app can force light while the system is
    // dark); isSystemInDarkTheme() would pick the dark weekend fill on a light grid.
    val isDarkTheme =
        MaterialTheme.colorScheme.surface.luminance() < CoPlanlyColors.DARK_LUMINANCE_THRESHOLD

    val isPublicHoliday = holiday != null && !holiday.isSchoolVacation

    // Two layers, not one pick: see DayCellFills for why a single `when` made the weekend
    // unreachable on every account with a custody model. School vacation is still not a cell
    // fill at all — it is a month-level banner.
    val fill = DayCellFills.monthCell(
        isWeekend = isWeekend,
        isCurrentMonth = isCurrentMonth,
        custody = custody,
        isPublicHoliday = isPublicHoliday
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
```

- [ ] **Step 3: Draw both layers**

In the same file, in `DayCell`'s `Box` modifier chain, replace:

```kotlin
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
```

with:

```kotlin
            .background(
                color = baseColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
            .background(
                color = overlayColor,
                shape = RoundedCornerShape(dims.cornerRadius / 2)
            )
```

- [ ] **Step 4: Add the holiday alpha constant**

`HOLIDAY_TINT_ALPHA` replaces the literal `0.10f` the old `when` used. Add it next to the other file-level constants at the top of `MonthView.kt` (below the existing `private const val` declarations, or create the group if none exists):

```kotlin
/** Public-holiday tint strength, drawn over the cell's base fill. */
private const val HOLIDAY_TINT_ALPHA = 0.10f
```

- [ ] **Step 5: Build**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If `Color` or `DayCellFills` are unresolved, add the imports — `androidx.compose.ui.graphics.Color` is already imported (`Color.Transparent` is used in the weekday header), and `DayCellFills`/`DayCellBase`/`DayCellOverlay` are in the same package as `MonthView`, so no import is needed for them.

- [ ] **Step 6: Run the unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS. No existing test asserts on the weekend colours.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/theme/Color.kt app/src/main/java/com/coparently/app/presentation/calendar/MonthView.kt
git commit -m "fix(calendar): tint every weekend grey, in all six grid rows"
```

---

### Task 3: The same two layers in the week and day views

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/DayWeekView.kt:521-541` and its `Box` background at `:554-557`

**Interfaces:**
- Consumes: `DayCellFills.weekHourCell`, `DayCellBase`, `DayCellOverlay` from Task 1; the recoloured tokens from Task 2.
- Produces: nothing.

- [ ] **Step 1: Replace the hour cell's single background**

In `DayWeekView.kt`, replace the block currently at lines 521–541 (from `val isToday =` through the closing `}` of `val backgroundColor = when { … }`) with:

```kotlin
                                    val isToday = date == LocalDate.now()
                                    val custody = getCustody(date)
                                    val isWeekend = CustodyHelper.isWeekend(date)
                                    // Custody wins over the today tint, matching MonthView:
                                    // it is the product's core signal, and today already
                                    // reads as today from its coloured header above. The old
                                    // order hid custody on the one column parents check first.
                                    // The weekend is no longer part of that race — it is the
                                    // base both are drawn over. See DayCellFills.
                                    val fill = DayCellFills.weekHourCell(
                                        isWeekend = isWeekend,
                                        isToday = isToday,
                                        custody = custody
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
                                            CoPlanlyColors.MomPink
                                                .copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
                                        DayCellOverlay.CUSTODY_DAD ->
                                            CoPlanlyColors.DadBlue
                                                .copy(alpha = CoPlanlyColors.CUSTODY_TINT_ALPHA)
                                        DayCellOverlay.TODAY ->
                                            MaterialTheme.colorScheme.primaryContainer
                                                .copy(alpha = TODAY_TINT_ALPHA)
                                        DayCellOverlay.PUBLIC_HOLIDAY,
                                        DayCellOverlay.NONE -> Color.Transparent
                                    }
```

- [ ] **Step 2: Draw both layers**

Replace:

```kotlin
                                            .background(
                                                color = backgroundColor,
                                                shape = RoundedCornerShape(dims.paddingSmall)
                                            )
```

with:

```kotlin
                                            .background(
                                                color = baseColor,
                                                shape = RoundedCornerShape(dims.paddingSmall)
                                            )
                                            .background(
                                                color = overlayColor,
                                                shape = RoundedCornerShape(dims.paddingSmall)
                                            )
```

- [ ] **Step 3: Add the today alpha constant**

`TODAY_TINT_ALPHA` replaces the literal `0.05f`. Add it alongside the existing file-level constants in `DayWeekView.kt` (the file already declares `GRIDLINE_ALPHA` in that group):

```kotlin
/** Today's tint strength in the week grid, drawn over the cell's base fill. */
private const val TODAY_TINT_ALPHA = 0.05f
```

- [ ] **Step 4: Build**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If `Color` is unresolved, add `import androidx.compose.ui.graphics.Color` to the import block.

- [ ] **Step 5: Run the unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/calendar/DayWeekView.kt
git commit -m "fix(calendar): give the week grid the same weekend base as the month grid"
```

---

### Task 4: Deliver each parent's pre-pairing events to their co-parent

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt` (add a query at the end of the interface, after `reslotPickup`)
- Modify: `app/src/main/java/com/coparently/app/data/local/preferences/PreferenceKeys.kt`
- Modify: `app/src/main/java/com/coparently/app/data/sync/SyncService.kt:33-44` (constructor), `:86-91` (`syncEvents` head)
- Test: `app/src/test/java/com/coparently/app/data/sync/SyncServiceTest.kt`

**Interfaces:**
- Consumes: `EncryptedPreferences.getString(key, default)` / `putString(key, value)`; `SyncServiceTest`'s existing private helpers `pairWith(partnerId: String?)`, `eventEntity(createdByFirebaseUid: String, sharedWith: List<String>)` and `declaredSql(method: String)`.
- Produces:
  - `EventDao.markOwnEventsUnsynced(myUid: String): Int`
  - `PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX: String`
  - `SyncService`'s constructor gains a trailing `encryptedPreferences: EncryptedPreferences` parameter.

- [ ] **Step 1: Write the failing tests**

Add these to `SyncServiceTest`, above the `private fun applyReslot` helper. They rely on a new `encryptedPreferences` field added in Step 4.

```kotlin
    @Test
    fun `the audience backfill re-queues this user's events the first time a partner appears`() =
        runTest {
            // The defect this closes: `sharedWith` is computed at upload time and never again,
            // and only the *accepter's* rows are re-flagged (as a side effect of the slot
            // re-stamp). The inviter keeps their slot, so every event they created before
            // pairing stayed marked synced with an audience of one and never reached anybody.
            pairWith(partnerId = BOB)

            syncService.performFullSync()

            coVerify(exactly = 1) { eventDao.markOwnEventsUnsynced(ALICE) }
        }

    @Test
    fun `the audience backfill does not run twice for the same partner`() = runTest {
        pairWith(partnerId = BOB)
        every {
            encryptedPreferences.getString(
                "${PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX}$ALICE"
            )
        } returns BOB

        syncService.performFullSync()

        coVerify(exactly = 0) { eventDao.markOwnEventsUnsynced(any()) }
    }

    @Test
    fun `the audience backfill runs again for a different partner`() = runTest {
        // Alice unpaired from Bob and re-paired with Carol: Carol has never received any of
        // Alice's history, so the marker must re-arm rather than read as "already done".
        pairWith(partnerId = CAROL)
        every {
            encryptedPreferences.getString(
                "${PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX}$ALICE"
            )
        } returns BOB

        syncService.performFullSync()

        coVerify(exactly = 1) { eventDao.markOwnEventsUnsynced(ALICE) }
        verify {
            encryptedPreferences.putString(
                "${PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX}$ALICE",
                CAROL
            )
        }
    }

    @Test
    fun `the audience backfill does not run while unpaired`() = runTest {
        pairWith(partnerId = null)

        syncService.performFullSync()

        coVerify(exactly = 0) { eventDao.markOwnEventsUnsynced(any()) }
    }

    @Test
    fun `the backfill statement never re-queues a private event`() {
        // Private events must not leave the device, so they are excluded in the statement
        // rather than downstream — a row flagged unsynced is a row queued for upload.
        val sql = declaredSql("markOwnEventsUnsynced").filterNot { it.isWhitespace() }

        assert(sql.contains("isPrivate=0")) { "markOwnEventsUnsynced must exclude private events" }
        assert(sql.contains("createdByFirebaseUid=:myUid")) {
            "markOwnEventsUnsynced must be scoped to this user's own rows"
        }
    }
```

Add the two imports `SyncServiceTest` does not yet have, to its import block:

```kotlin
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.local.preferences.PreferenceKeys
import io.mockk.verify
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.sync.SyncServiceTest"
```

Expected: compilation failure — `Unresolved reference: markOwnEventsUnsynced`, `EVENT_AUDIENCE_BACKFILL_PREFIX` and `encryptedPreferences`.

- [ ] **Step 3: Add the DAO statement**

In `EventDao.kt`, after `reslotPickup`, before the interface's closing brace:

```kotlin
    /**
     * Re-queues every non-private event this user created for upload, by clearing the flag
     * `SyncService.syncEvents` selects on.
     *
     * The upload half recomputes `sharedWith` from live state for every row it uploads, so
     * clearing the flag is what republishes an event under the audience the account has *now*.
     * Without it, an event created while unpaired keeps the one-uid audience it was uploaded
     * with forever: `sharedWith` is never recomputed for a row already marked synced.
     *
     * Only [reslotOwner] used to have this effect, and only as a side effect, so it reached
     * only the parent whose slot moved — the accepter. The inviter keeps their slot
     * (`PairingViewModel.withSlotReslot`), so their whole pre-pairing history, Google imports
     * included, stayed invisible to the co-parent.
     *
     * `isPrivate = 0` is part of the statement rather than a filter applied to its result: a
     * row with the flag cleared is a row queued for upload, and a private event must never be
     * queued at all, not even for one pass that later drops it.
     *
     * Rows with a null `createdByFirebaseUid` — old enough to predate the column being stamped
     * — are not matched by `= :myUid` and are deliberately left alone: nothing distinguishes
     * this user's un-stamped event from anybody else's, and a statement that guessed would
     * publish the wrong person's history.
     *
     * @param myUid Firebase UID of the signed-in user.
     * @return How many rows were re-queued.
     */
    @Query(
        "UPDATE events SET syncedToFirestore = 0 " +
            "WHERE createdByFirebaseUid = :myUid AND isPrivate = 0"
    )
    suspend fun markOwnEventsUnsynced(myUid: String): Int
```

- [ ] **Step 4: Add the preference key**

In `PreferenceKeys.kt`, before the closing brace:

```kotlin
    /**
     * Prefix for the per-user key recording which co-parent this device has already re-published
     * its own events for — the actual key is this prefix plus the Firebase UID, and the value is
     * the partner's UID.
     *
     * Scoped per user for the same reason [PARENT_SLOT_MARKER_PREFIX] is: Room's `users` and
     * `events` rows survive sign-out, so a second account signing in on the same device must not
     * read the first account's history as its own.
     *
     * Unlike [PARENT_SLOT_MARKER_PREFIX] this key is **not** exempt from
     * `EncryptedPreferences.clear()`, and does not need to be. Losing it costs one extra
     * re-publish of documents that already carry the right audience; losing the slot marker
     * would re-stamp records into the wrong parent's slot.
     */
    const val EVENT_AUDIENCE_BACKFILL_PREFIX = "event_audience_backfill_"
```

- [ ] **Step 5: Wire the backfill into the sync**

In `SyncService.kt`, add the import:

```kotlin
import com.coparently.app.data.local.preferences.EncryptedPreferences
```

Add a trailing constructor parameter after `parentSlotMigrator`:

```kotlin
    private val parentSlotMigrator: ParentSlotMigrator,
    private val encryptedPreferences: EncryptedPreferences
) {
```

Replace the first two statements of `syncEvents` (currently lines 87–89):

```kotlin
        // Upload unsynced local events; private events never leave the device
        val unsyncedEvents = eventDao.getUnsyncedEvents().filterNot { it.isPrivate }
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }
```

with — note the order swaps, because the backfill's flag clear has to be visible to the read that follows it:

```kotlin
        val partnerId = userDao.getUserById(userId)?.partnerId?.takeIf { it.isNotBlank() }
        backfillAudienceForPartner(userId, partnerId)

        // Upload unsynced local events; private events never leave the device
        val unsyncedEvents = eventDao.getUnsyncedEvents().filterNot { it.isPrivate }
```

Add the method below `syncEvents`:

```kotlin
    /**
     * Re-publishes this user's own events once per co-parent, so a pair formed after those
     * events were created can actually read them.
     *
     * `sharedWith` is computed at upload time and never recomputed for a row already marked
     * synced, so every event created while unpaired kept an audience of one. The accepter's
     * rows were re-flagged as a side effect of the parent-slot re-stamp
     * ([EventDao.reslotOwner]); the inviter's never were, because the inviter keeps their slot.
     *
     * Keyed on the partner's uid rather than a boolean, so re-pairing with somebody else
     * re-arms it: the new co-parent has received nothing.
     *
     * The marker advances after the Room `UPDATE` commits and before the uploads it queues have
     * finished. A process death in that window is harmless — the rows stay flagged and the next
     * pass uploads them, because the marker guards the flagging and not the upload. Advancing it
     * only after the uploads would instead re-flag every event on every sync.
     */
    private suspend fun backfillAudienceForPartner(userId: String, partnerId: String?) {
        if (partnerId == null) return
        val key = "${PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX}$userId"
        if (encryptedPreferences.getString(key) == partnerId) return

        val requeued = eventDao.markOwnEventsUnsynced(userId)
        encryptedPreferences.putString(key, partnerId)
        Log.i(TAG, "Audience backfill for $userId with partner $partnerId: re-queued $requeued event(s)")
    }
```

Add the import `com.coparently.app.data.local.preferences.PreferenceKeys` alongside the `EncryptedPreferences` one.

- [ ] **Step 6: Give the test the new collaborator**

In `SyncServiceTest.setup()`, declare the field with the others:

```kotlin
    private lateinit var encryptedPreferences: EncryptedPreferences
```

create it with the other mocks:

```kotlin
        encryptedPreferences = mockk(relaxed = true)
```

stub the default "never backfilled" answer alongside the other `coEvery`/`every` defaults. Both
overload forms are stubbed because `getString`'s second parameter has a default value, and
`ParentSlotMigratorTest:65-66` already stubs both for exactly that reason:

```kotlin
        every { encryptedPreferences.getString(any()) } returns null
        every { encryptedPreferences.getString(any(), any()) } returns null
```

and pass it as the last constructor argument:

```kotlin
            parentSlotMigrator,
            encryptedPreferences
        )
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.sync.SyncServiceTest"
```

Expected: PASS, including the four pre-existing `sharedWith` tests — the backfill must not change the audience those assert on.

- [ ] **Step 8: Build and run the whole suite**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Hilt provides `EncryptedPreferences` already (it is a `@Singleton` with an `@Inject` constructor), so no module change is needed.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt app/src/main/java/com/coparently/app/data/local/preferences/PreferenceKeys.kt app/src/main/java/com/coparently/app/data/sync/SyncService.kt app/src/test/java/com/coparently/app/data/sync/SyncServiceTest.kt
git commit -m "fix(sync): deliver each parent's pre-pairing events to their co-parent"
```

---

### Task 5: Record the two behaviours in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` — the "Design refresh (August 2026)" list and "Things that are easy to get wrong"

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Add the weekend rule to the design-refresh list**

Append to the numbered list under "Design refresh (August 2026)":

```markdown
10. **The weekend is a base layer, never a competing fill.** `DayCellFills` decides a cell's
    `base` (grey on Saturday/Sunday, in every grid row including the neighbouring months' days)
    and its `overlay` (custody, holiday, today) separately, and the views draw both. A single
    `when` picking one background is what made the weekend unreachable: `CustodyModel.getCustodyFor`
    never returns null, so on any account with a custody model every in-month cell matched a
    custody branch. Do not "fix" a weekend that looks too subtle by putting it back ahead of
    custody — weekends are the days a parent checks first.
```

- [ ] **Step 2: Add the audience rule to "Things that are easy to get wrong"**

Append as a new numbered item:

```markdown
14. **`sharedWith` is computed at upload time and never recomputed for a synced row.** An event
    created while unpaired is uploaded with an audience of one uid; nothing revisits it. Pairing
    repairs this only for the accepter, as a side effect of `EventDao.reslotOwner` clearing
    `syncedToFirestore` during the slot re-stamp — and the inviter keeps their slot, so their
    pre-pairing history stayed private forever. `SyncService.backfillAudienceForPartner` now
    re-queues this user's own non-private events once per co-parent uid. Any new "publish once"
    path needs the same treatment; a boolean marker instead of the partner uid would not re-arm
    on re-pairing.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: record the weekend base layer and the audience backfill"
```

---

## Device verification

Not a task — run after Task 5, on the existing pair, with no unpairing.

1. `./gradlew clean assembleDebug`, then `adb -t <id> install -r app/build/outputs/apk/debug/app-debug.apk` on both handsets.
2. Confirm `mCurrentFocus` is CoPlanly before any scripted tap.
3. Month view, dark and light, with the active custody model: the Saturday and Sunday columns read grey through **all six** grid rows, and the pink/blue custody wash is still legible on them.
4. Week view: the same two columns carry the same grey.
5. A month with a Czech public holiday on a weekday (28 October) still shows the red tint.
6. Let one sync pass run on each phone, then confirm each phone now lists events the other created before pairing. Query it rather than trusting the grid:
   `adb -t <id> exec-out run-as com.coparently.app cat /data/data/com.coparently.app/databases/coparently_database > db && python -c "import sqlite3;print(list(sqlite3.connect('db').execute('select createdByFirebaseUid, count(*) from events group by 1')))"`
   Expect rows for **both** uids on both phones.
7. Watch `adb logcat -v time | grep -i coparently` for the `Audience backfill for … re-queued N event(s)` line, and for any `PERMISSION_DENIED`.
