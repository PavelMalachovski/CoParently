# Where the money went — design

**Date:** 23 August 2026
**Package:** **F** of the nineteen-item improvement list
**Base:** `main` @ `14e00cbe`
**Depends on:** nothing unmerged. The smallest and most self-contained of the remaining packages.

One item:

- **Item 14.** A pie chart of spending by category — education, clothing, sport and so on — a table beneath it showing how much went where, sorted with the largest first, and a filter for who paid.

---

## 0. Decisions taken without the owner present

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | Chart library, or drawn by hand? | **By hand**, a Compose `Canvas`. No new dependency. | Small either way. |
| 2 | How is more than one currency handled? | **A currency chip selector**, one chart at a time. Never a mixed total. | Small, but see §2 — the alternative is forbidden. |
| 3 | Where does it live? | **A tab on the existing Expenses screen**, beside the list. | Small. |
| 4 | What period? | **The month already selected on the Expenses screen.** One month control, not two. | Small. |
| 5 | Does a "sport" category get added? | **No** — `ACTIVITIES` already covers it. | One enum value plus five locales. |

## 1. What exists

`ExpenseCategory` has nine members — `EDUCATION`, `MEDICAL`, `CLOTHING`, `FOOD`, `ACTIVITIES`, `TRANSPORTATION`, `TOYS`, `HOUSEHOLD`, `OTHER` — and every expense carries one. `Expense.paidBy` is a Firebase uid; `calculateExpenseBalancesByCurrency` already maps a uid to a `"mom"`/`"dad"` slot and produces per-currency figures. The Expenses screen already has a month control and a budget chip strip.

So the data is all there. This package is presentation and arithmetic, no schema change, no rules change, no migration.

**Item 14 names "sport" as a category.** `ACTIVITIES` is it — the label already reads "Activities", and its icon is `sports_soccer`. Adding a tenth category that overlaps a ninth would leave every existing football expense filed under the wrong one, with no way to tell which parent meant which.

## 2. The one thing that must not be built

CLAUDE.md records, as a decision rather than a gap:

> *There is still no FX conversion between currencies — deliberately: totals stay honest within each currency rather than being normalised. Do not reintroduce a single cross-currency total.*

A pie chart is a single total, cut into slices. **A pie of expenses in two currencies is precisely the forbidden thing**, and worse than the plain number it replaced: a slice's angle is a claim about proportion, and ¥1000 next to €50 makes a claim that is simply false.

So the analytics view shows **one currency at a time**. When a month contains more than one, a chip row selects between them, defaulting to the user's default currency if present and otherwise to the currency with the most expenses. The chip row is absent when there is only one — which is the usual case, and it should not pay for the unusual one.

The per-currency subtotals already on the screen keep their existing shape; this adds a view, it does not change the arithmetic.

## 3. The chart

A `Canvas` drawing one arc per category with a non-zero total, largest first, clockwise from twelve o'clock.

**Drawn rather than imported.** A pie chart is `drawArc` in a loop; a charting dependency for one screen is a large surface for a small need, and this project has none today. The one thing a library would give — animation and touch hit-testing — is not asked for.

**Colour comes from the theme, never from a hand-picked palette.** Nine categories need nine distinguishable fills, and `MaterialTheme.colorScheme` does not have nine. The palette is derived once, in one place, from the theme's primary and secondary with varied tone, and every slice, legend swatch and table row reads from that one function — so light and dark stay consistent and no screen invents a tenth colour.

**Parent pink and blue are not available for this.** They identify a person, and using them for "clothing" would break the one colour rule the app has held throughout.

**The chart is not the accessible representation — the table is.** Item 14 asks for both, which is convenient: the chart gets `contentDescription = null` and the table carries the numbers a screen reader needs. A pie chart with a read-aloud description of its slices would be a worse version of the table sitting directly beneath it.

## 4. The table

One row per category with a non-zero total: the category, the amount, and its share as a percentage. Sorted by amount descending — item 14 asks for that explicitly, and it is right: the question is "what is eating the money", and the answer should be the first row.

Categories with nothing in them are omitted rather than shown as zero. A parent scanning for the big number should not read past six empty lines to find it.

## 5. The payer filter

Three states: everyone, and one per parent. Named with each parent's actual name, never "Mom" or "Dad" — every parent label in this app goes through `ParentLabels`, and `Expense.paidBy` is a uid that `ParentsSource` resolves.

**While unpaired, or before the two parents resolve, the filter is hidden rather than shown broken.** `calculateExpenseBalancesByCurrency` already models this — its `splitKnown` is false when the two parents cannot both be identified — and a filter offering "Parent" and "Parent" would be worse than no filter.

Filtering changes both the chart and the table together. They are two views of one number and must never disagree.

## 6. Where it lives

A segmented control on the Expenses screen switching between **List** and **Analytics**, sharing the screen's existing month control.

Not a separate route, because it would need its own month control and the two could drift apart — a parent looking at August's chart and September's list, with nothing on screen saying so. One month control, two views of it.

The budget chip strip belongs to the list and stays there.

## 7. Verification

| Check | How |
|---|---|
| Aggregation | JVM: totals per category; empty categories omitted; sorted descending; percentages sum to 100 within rounding. |
| Currency isolation | JVM: a month with two currencies produces two independent breakdowns and **never** a combined total. |
| Payer filter | JVM: filtering by a parent selects on `paidBy`; "everyone" matches the unfiltered totals exactly. |
| Palette | JVM: nine categories map to nine distinct colours, and the mapping is exhaustive with no `else`. |
| Locales | grep, five files per key. |
| Build | `assembleDebug testDebugUnitTest lint detekt` |

**Device checks:** a month with two currencies shows the chip row and never a mixed pie; a month with one hides it; the filter renames itself with the two parents' real names; and the chart and table agree after every filter change. Then switch to Russian and confirm the category labels and the empty state are translated.

### What was run, 23 August 2026

Implemented on `claude/start-e-schemas-docs-3kn5c6`, on top of package E (merged as PR #56). The
full ledger — every decision, deviation and measurement — is
`docs/superpowers/plans/2026-08-23-expense-analytics-PROGRESS.md`.

| Check | Result |
|---|---|
| Aggregation | **13 tests passing**, including the cross-currency case and its subtler sibling (two totals sharing one denominator). |
| Palette | **6 tests passing**, pinned to values a colour-blindness validator approved. See the finding below. |
| Pure-Kotlin unit tests, whole tree | **312 passing** across 34 classes, up from 293. |
| Locales | all **12** new keys in exactly five files each; none added and left unreferenced. |
| No dependency added | `git diff main..HEAD -- app/build.gradle.kts` is empty. |
| Line length | nothing over 120 in any file this package touches. |
| Build (`assembleDebug testDebugUnitTest lint detekt`) | **Not run** — no Android SDK and no route to Google's Maven host in this container. No Compose file here has been compiled. |
| Device checks | **Not run.** All seven are outstanding. |

**§3's palette premise did not survive measurement.** "Nine categories need nine distinguishable
fills" is not achievable: nine hues at fixed lightness separate by ΔE 1.0 under deuteranopia, and
a search over several hundred lightness/chroma/ordering combinations could not lift the all-pairs
worst case above ΔE 6.9 in light or 1.3 in dark. It is a property of nine, not of these nine. The
shipped palette clears every check on *adjacent* pairs — ΔE 10.5 light / 11.1 dark under
colour-blindness simulation, against a target of 8 — which is the pairlist a pie is graded on,
because only neighbouring arcs touch. The rest is carried by the table: §3 already made it the
accessible representation, and this makes that load-bearing rather than a courtesy. Nothing on
this screen asks a reader to tell two slices apart by colour.

## 8. Deliberately not in F

- **FX conversion.** §2. It is a recorded product decision, not an oversight.
- **A tenth "sport" category.** §1.
- **Trends over months.** Item 14 asks for a breakdown of a period, not a time series. A second chart type is a package of its own.
- **Export.** Not asked for.
- **Touching the balance arithmetic.** `calculateExpenseBalancesByCurrency` is correct and this package reads it rather than reimplementing it.
