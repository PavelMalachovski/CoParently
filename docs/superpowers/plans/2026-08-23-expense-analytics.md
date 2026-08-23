# Expense analytics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a parent where the month's money went — a pie by category, a table sorted with the largest first, and a filter for who paid — without ever mixing two currencies into one total.

**Architecture:** Pure aggregation in the domain layer, one currency at a time; a hand-drawn Compose `Canvas` for the chart; a segmented control on the existing Expenses screen so there is one month control, not two.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), JUnit 4. **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-23-expense-analytics-design.md`

**Depends on nothing unmerged.** This is the most self-contained of the remaining packages and can be built at any time.

**Before Task 3, load the `dataviz` skill.** It carries the project-independent rules for chart colour, legend and accessibility, and this is the app's first chart.

## Global Constraints

- **Jetpack Compose only.** Stateless composables; state in ViewModels as `StateFlow`.
- **Never reintroduce a single cross-currency total.** CLAUDE.md records this as a decision: totals stay honest within each currency rather than being normalised. A pie of two currencies is exactly that, and worse than the number it replaces — a slice angle is a claim about proportion.
- **`Expense.currency` is a real per-expense field**, and a month may legitimately mix them.
- **Parent colours identify a person, not a category.** `MomPink`/`DadBlue` may not be used for a slice. Every parent label goes through `presentation/common/ParentLabels.kt`; the app never shows the words "Mom" or "Dad".
- **Colours from `MaterialTheme.colorScheme`**, never literal hex.
- **Never hardcode user-visible text.** Every new key in all five locales in the same commit; `MissingTranslation` is disabled and reports nothing.
- **The Expenses screen already uses `SectionGroup` / `SectionRow`.** Do not reintroduce `Card { ListItem { … } }` per row.
- KDoc on every public class and function; code and comments in **English**.
- detekt `MaxLineLength` **120**; nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26. Conventional Commits.
- `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew …`
- detekt exits non-zero from pre-existing findings; judge by whether **your** files appear in the report.

---

### Task 1: The aggregation

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/expenses/CategoryBreakdown.kt`
- Test: `app/src/test/java/com/coparently/app/domain/expenses/CategoryBreakdownTest.kt`

**Interfaces produced:**

```kotlin
data class CategorySlice(
    val category: ExpenseCategory,
    val amount: Double,
    val share: Double          // 0.0..1.0 of the currency's total
)

data class CurrencyBreakdown(
    val currency: String,
    val total: Double,
    val slices: List<CategorySlice>   // descending by amount, empty categories omitted
)

fun breakdownByCurrency(
    expenses: List<Expense>,
    paidByUid: String? = null          // null = everyone
): List<CurrencyBreakdown>
```

Pure, no Android, no repository — so the arithmetic that the chart makes a visual claim about is testable without a device.

- [x] **Step 1: Write the failing test.** Cover at minimum:

```kotlin
@Test
fun `two currencies produce two independent breakdowns and never a combined total`() {
    val result = breakdownByCurrency(
        listOf(
            expense(amount = 100.0, currency = "EUR", category = ExpenseCategory.CLOTHING),
            expense(amount = 1000.0, currency = "CZK", category = ExpenseCategory.CLOTHING)
        )
    )

    assertEquals(2, result.size)
    assertEquals(setOf("EUR", "CZK"), result.map { it.currency }.toSet())
    // The forbidden thing: no entry may total across currencies.
    assertTrue(result.none { it.total == 1100.0 })
}
```

Plus: slices are sorted by amount descending; a category with nothing in it is **absent**, not a zero row; shares sum to 1.0 within rounding for each currency independently; `paidByUid` selects on `Expense.paidBy` and `null` matches the unfiltered totals exactly; an empty input produces an empty list rather than a zero-total entry.

- [x] **Step 2: Run it; expect `Unresolved reference: breakdownByCurrency`.**
- [x] **Step 3: Implement.** Read `ExpenseBalance.kt` first and follow its shape — it already does per-currency grouping and its KDoc explains the reasoning this file extends.
- [x] **Step 4: Run it; expect all PASS.**
- [x] **Step 5: Commit** — `feat(expenses): break a month down by category, one currency at a time`

---

### Task 2: The category palette

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/expenses/CategoryPalette.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/expenses/CategoryPaletteTest.kt`

**Interfaces produced:** `@Composable fun ExpenseCategory.sliceColor(): Color`, exhaustive over the nine members with **no `else`**.

Nine categories need nine distinguishable fills and `MaterialTheme.colorScheme` has no nine-colour ramp. Derive them once, here, from the theme's primary and secondary with varied tone, so light and dark stay consistent and no screen invents a tenth.

**`MomPink` and `DadBlue` are not available.** They identify a person; using one for "clothing" would break the one colour rule this app has held throughout.

- [x] **Step 1: Write the failing test** — all nine map to distinct colours, and the `when` is exhaustive (a new category must fail to compile rather than fall through to grey).
- [x] **Step 2: Run it; expect a compile failure.**
- [x] **Step 3: Implement.** Keep the derivation in one function; do not scatter tone maths across call sites.
- [x] **Step 4: Run it; expect PASS.**
- [x] **Step 5: Commit** — `feat(expenses): one palette for nine categories, derived from the theme`

---

### Task 3: The chart

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/expenses/CategoryPieChart.kt`

**Load the `dataviz` skill before writing this** — it is the app's first chart and carries the rules for legend, colour and accessibility.

`Canvas` + `drawArc`, one arc per slice, largest first, clockwise from twelve o'clock. Sizes from `dimensions()`; colours from Task 2.

**`contentDescription = null` on the chart.** The table beneath it is the accessible representation and carries the actual numbers — a read-aloud description of slice angles would be a worse version of the table sitting directly below.

**A single slice must render as a full circle, not a degenerate arc**, and a total of zero must render nothing rather than dividing by it. Both are one-line guards and both are easy to leave out.

- [x] **Step 1: Write the composable**, stateless: it takes `List<CategorySlice>` and draws.
- [x] **Step 2: Add `@Preview`s** for one slice, nine slices, and an empty list, following the project's `LightDarkPreviews` convention.
- [~] **Step 3: `assembleDebug`; commit** — `feat(expenses): draw the month as a pie, without a charting dependency`

---

### Task 4: The analytics view

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/expenses/ExpenseAnalytics.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/expenses/ExpenseScreen.kt`, `ExpenseViewModel.kt`
- Create: `app/src/main/res/values*/expense_analytics_strings.xml`

A segmented control switches the Expenses screen between **List** and **Analytics**, **sharing the screen's existing month control**. Not a separate route: a second month control could drift, leaving a parent looking at August's chart and September's list with nothing on screen saying so.

**The currency chip row appears only when the month holds more than one currency.** Default to the user's default currency if it is present, otherwise the currency with the most expenses. One currency is the usual case and should not pay for the unusual one.

**The payer filter is hidden while the two parents cannot both be resolved** — unpaired, or before pairing resolves. `ExpenseBalance` already models this with `splitKnown`; a filter offering "Parent" and "Parent" is worse than no filter. Names come from `rememberParentNames`.

The chart and the table are two views of one number: a filter change must move both, in the same recomposition.

- [x] **Step 1: Write the strings in all five locales** — the two tab labels, the filter's three states, the table's column headers, and an empty state. Match each locale file's register.
- [x] **Step 2: Add the breakdown and the two filters to `ExpenseViewModel`** as `StateFlow`, derived from the expenses it already collects. Do not add a second repository read.
- [x] **Step 3: Build the analytics composable** — chart, then legend, then table. `SectionGroup` / `SectionRow` for the table rows.
- [x] **Step 4: Add the segmented control to `ExpenseScreen`.** The budget chip strip belongs to the list and stays there.
- [~] **Step 5: Verify locales by grep; confirm no hardcoded text; `assembleDebug testDebugUnitTest`.**
- [x] **Step 6: Commit** — `feat(expenses): a month's spending as a chart and a sorted table`

---

### Task 5: Full verification

- [~] **Step 1:** `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — totals, and whether any changed file is named by detekt or lint.
- [x] **Step 2:** locale grep — five files per new key.
- [x] **Step 3: Confirm no dependency was added** — `git diff main..HEAD -- app/build.gradle.kts` must be empty.
- [~] **Step 4: Device checks.**
  1. A month with expenses in two currencies: the chip row appears, and **no** view anywhere shows a total across both.
  2. A month with one currency: no chip row.
  3. The payer filter carries the two parents' real names; while unpaired it is absent.
  4. Change the filter: the chart and the table move together and agree.
  5. A month with one category: the pie is a full circle, not a sliver.
  6. An empty month: an empty state, not a blank canvas or a crash.
  7. Switch to Russian: category labels, tab labels and the empty state are all translated.

- [x] **Step 5:** record the run in the spec's §7 and commit. *(Both sessions recorded; the second added the measured build failure and the re-run test totals.)*

> `[~]` = done except for the Gradle half. No Android SDK and no route to Google's Maven host in
> the container this was implemented in, so every `assembleDebug` / `testDebugUnitTest` step and
> all seven device checks are outstanding; the code, the tests and the strings are written and
> committed. The colour validator, every pure-Kotlin test, the locale greps and the
> no-new-dependency check *were* run — see `2026-08-23-expense-analytics-PROGRESS.md`.
>
> **Re-attempted after the merge, on `main` @ `2e3a7c2`, with the same outcome.** Step 1 was run
> this time and fails before reaching a source file: the Android Gradle Plugin cannot be resolved
> because `dl.google.com` is denied by the egress policy (403 to CONNECT). Steps 1 and 4 therefore
> stay `[~]`. The pure-Kotlin suite was re-run on the merged base — **312 passing, 0 failing**,
> `CategoryPaletteTest` among them, so the pinned palette still matches the theme. Details in the
> ledger.
>
> **Task 2's premise did not survive measurement**: nine distinguishable fills are not
> achievable, in any palette. What shipped instead, and why the table now carries the load, is in
> the ledger.

---

## Notes for the reviewer

**The cross-currency check is the one that matters.** CLAUDE.md records the no-FX decision explicitly, and a pie chart is the most natural place in this app to violate it by accident — a slice angle is a claim about proportion, and one drawn across two currencies is a false one. Task 1's second test exists for exactly that and should be read before anything else.

**No dependency is added.** If a reviewer sees a charting library in the diff, the chart was not built as designed.

**What this package does not do:** FX conversion; a tenth "sport" category (`ACTIVITIES` is it); trends across months; export; or any change to `calculateExpenseBalancesByCurrency`, which is correct and is read rather than reimplemented.
