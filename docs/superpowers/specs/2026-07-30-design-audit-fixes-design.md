# Design audit fixes (C1–C8) — 2026-07-30

Source: "CoPlanly Code Audit and Redesign" (turn 1, findings C1–C8). All eight findings were
re-verified against `main` at `543fd591` before this spec was written; every one reproduces.

Scope decision: **code fixes only**. The redesigned layouts from the audit's turn 2
(2a month header, 2b week custody ribbon, 2c expenses split bar) are explicitly out of scope
and are recorded in "Deferred" below.

## Branch

`fix/design-audit-2026-07-30`, cut from `main` at `543fd591` (which already includes PR #5,
merged as part of this work — it consolidated Room schema versions 2–8 into the
`CoPlanlyDatabase` directory alongside the existing 9/10/11).

## C1 — Custody tint is 4.7x weaker in week/day than in month (high)

`MonthView.kt:277-278` tints custody cells at `alpha = 0.14f`; `DayWeekView.kt:472-473` uses
`alpha = 0.03f`. Over `DarkSurface #1B1B21` a 3% tint shifts the channel by roughly one RGB
step, so the product's core signal is invisible in two of three views.

**Fix.** Introduce a single named constant rather than repeating the literal:

```kotlin
// Color.kt, inside CoPlanlyColors
/** Alpha for the custody background tint on calendar cells and columns. */
const val CUSTODY_TINT_ALPHA = 0.14f
```

Use it at both call sites. This is a tint behind text, not text itself, so WCAG text
thresholds do not apply — the requirement is that it be *distinguishable* from the untinted
surface, which 0.14 satisfies and 0.03 does not.

## C2 — Hardcoded font sizes below the type scale (high)

`Type.kt` bottoms out at `labelSmall` 11sp. These call sites override it:

| File | Line | Current | Becomes |
|---|---|---|---|
| `MonthView.kt` | 381 | `fontSize = 8.sp` (event chip) | `labelSmall`, no override |
| `MonthView.kt` | 394 | `fontSize = 8.sp` ("+N" counter) | `labelSmall`, no override |
| `MonthView.kt` | 350 | `fontSize = 13.sp` (day number) | `bodyMedium`, no override |
| `MonthView.kt` | 224 | `fontSize = 10.sp` (weekday header) | `labelMedium`, no override |
| `DayWeekView.kt` | 332, 360 | `fontSize = 9.sp` | `labelSmall`, no override |
| `DayWeekView.kt` | 282, 407 | `fontSize = 11.sp` | `labelSmall`, no override |
| `DayWeekView.kt` | 342 | `fontSize = 13.sp` | `bodyMedium`, no override |
| `DayWeekView.kt` | 953 | `fontSize = 10.sp` | `labelMedium`, no override |

`CalendarHeader.kt:213` (`fontSize = 20.sp`) is left alone — it is above the scale floor and
is a deliberate display size, not an accessibility problem.

Consequence to check on device: month cells are a fixed height, and 8sp -> 11sp makes chips
taller. If a chip no longer fits, the cell height grows rather than the text shrinking back.

## C3 — Same-hue text on same-hue fill (high)

`DayWeekView.kt:653-670` paints an event block with background `MomPink @ 0.3`, border
`MomPink @ 0.8`, and title text `MomPink` at full strength.

**Fix.** `textColor` becomes `MaterialTheme.colorScheme.onSurface`. Parent identity is already
carried by the border at `alpha = 0.8f`, which stays. Same change for the `"dad"` and `else`
branches.

## C4 — Two theme files disagree on the Mom/Dad rule (medium)

`Color.kt:31-34` documents that pink/blue are parent identity and must never go through the
theme's `secondary` slot. `Theme.kt:33,74` honours this with `NeutralSecondary`.
`DynamicTheme.kt:53-56,71-74` violates it with `secondary = MomPinkLight` / `MomPink`.

Note discovered during verification: `DynamicTheme.kt` and `AnimatedTheme.kt` are currently
unreferenced — `MainActivity.kt:148` uses `CoPlanlyTheme` from `Theme.kt`. Nothing outside
`presentation/theme/` calls them.

**Fix (decided: keep the files, correct them).** The files stay so the dynamic-theme path
remains available for later. `secondary` / `onSecondary` / `secondaryContainer` /
`onSecondaryContainer` in both the dark block (lines 53-56) and the light block (71-74) switch
to the `Neutral*` family, matching `Theme.kt`. A KDoc line records that these files are not
currently wired into `MainActivity`, so the next reader is not misled.

## C5 — Money has no parent semantics (medium)

**Out of scope** by decision. See "Deferred".

## C6 — Rows that look tappable and are not (medium)

`ExpenseScreen.kt:93` passes `onExpenseClick = { /* TODO: Show details */ }` and
`BudgetScreen.kt:112` passes `onEdit = { /* navigate to edit */ }`. Both feed clickable
`Card`s, so the rows ripple and nothing happens.

**Fix (decided: remove the affordance, do not build the screens).** The `onExpenseClick` and
`onEdit` parameters are removed from the row composables along with the `clickable` /
`onClick` modifier, so the cards no longer ripple. A row that cannot act should not look like
it can. Adding real detail/edit screens is deferred.

## C7 — Contrast documented for a theme that does not ship (medium)

The audit reports that `Color.kt` documents every ratio "on white" while the app ships dark.
Verification found a second, larger problem: **the documented numbers are themselves wrong.**
Recomputed with the WCAG 2.x relative-luminance formula:

| Token | Documented | Actual on white | On `DarkSurface #1B1B21` |
|---|---|---|---|
| `MomPink` `#E91E63` | 7.0:1 (and 4.56:1 in the header) | **4.35** | 3.94 |
| `DadBlue` `#1976D2` | 8.59:1 (and 4.51:1 in the header) | **4.60** | 3.72 |
| `MomPinkDark` `#C2185B` | 9.63:1 | **5.87** | 2.92 |
| `DadBlueDark` `#0D47A1` | 12.63:1 | **8.63** | 1.99 |
| `BrandPrimary` `#4F46E5` | 7.04:1 | **6.29** | 2.73 |
| `HolidayRed` `#D32F2F` | 5.9:1 | **4.98** | 3.44 |
| `VacationTint` `#26A69A` | — | **3.00** | 5.72 |

The header comment claiming "verified with WebAIM Contrast Checker" is not accurate for any
row above.

**The shape of the fix is not "pick a better hex".** No single value passes 4.5:1 against both
white and `#1B1B21` as text — Red 700 passes on white and fails on dark; Red 400 does the
reverse. So the fix distinguishes how each token is *used*:

**Non-text uses — unchanged.** Custody tints at `CUSTODY_TINT_ALPHA`, the `0.8f` identity
borders, legend dots. These are not text; WCAG 1.4.3 does not apply. `MomPink` and `DadBlue`
keep their exact hex, so brand identity is untouched.

**Text-on-parent-fill — change the fill, not the hue family.** `MonthView.kt:372` paints white
`labelSmall` on `eventColor.copy(alpha = 0.9f)`. White on solid `MomPink` is 4.35:1 and fails;
the audit's suggestion of simply dropping the alpha does not clear the bar. The chip fill
becomes `MomPinkDark` `#C2185B` (white on it: **5.87**) and `DadBlueDark` `#0D47A1` (**8.63**).
Still unmistakably pink and blue, now legible.

**Parent color used as text on a dark surface** — `CustodyIndicatorToday.kt:55-56` uses
`MomPink`/`DadBlue` as foreground (3.94 / 3.72 on dark surface). In dark theme these switch to
the existing `MomPinkLight` `#FFC1E3` (**11.42**) and `DadBlueLight` `#90CAF9` (**9.79**).

**Day-number text colors need theme-aware pairs.** Two new tokens:

- `HolidayRedDark = Color(0xFFEF5350)` — Red 400, **4.92** on `DarkSurface` (existing
  `HolidayRed` `#D32F2F` stays for light theme at 4.98 on white).
- `VacationTintLight = Color(0xFF00796B)` — Teal 700, **5.32** on white, because
  `VacationTint` `#26A69A` is only 3.00 there. `VacationTint` itself stays for dark theme at
  5.72.

Selection happens through the existing theme-detection idiom used elsewhere in this codebase:
`surface.luminance()`, **not** `isSystemInDarkTheme()` — the app theme can differ from the
system theme.

**Documentation.** Every token's KDoc records both columns, e.g.
`// contrast 4.35:1 on white / 3.94:1 on DarkSurface — tint and border use only, never text`.
Each comment also states whether the token is text-grade or fill-only, so the next reader
cannot reintroduce C3 or C7.

Three tokens — `EventGray`, `CustodyIndicatorActive`, `CustodyIndicatorInactive` — have no
usages anywhere in `app/src/main`. Their comments are corrected to real numbers; they are not
deleted (out of scope) but are marked unused.

## C8 — Undo exists in two of three delete paths (low)

`CalendarScreen.kt:483-497` already has the pattern: capture the full event, `deleteEvent(event)`,
snackbar with an action label, `createEvent(event)` on `SnackbarResult.ActionPerformed` — the id
is preserved, so Undo restores rather than duplicates. `EventListScreen.kt` uses the same shape.

Two paths bypass it and call `deleteEventById(eventId)` with no snackbar:

- `CalendarScreen.kt:259` — the red delete FAB shown after a long-press.
- `CalendarScreen.kt:389` — `onEventDelete` coming from `DayWeekView`.

**Fix.** Both look the event up in the in-scope `events` list, then follow the existing
pattern verbatim. If the lookup misses (event already gone), fall back to the current
`deleteEventById` call so behaviour never regresses.

Also `EventPreviewSheet.kt` sets no `maxLines`/`overflow` on the event title, producing the
mid-word wrap visible in the audit's screenshot 14. Add `maxLines = 2` and
`overflow = TextOverflow.Ellipsis`.

## Strings

No new user-facing strings. Undo reuses the existing `event_deleted_message` and
`event_deleted_undo` keys already referenced at `CalendarScreen.kt:473-474`. The project rule
about the gitignored `strings.xml` is therefore not engaged.

## Verification

1. `./gradlew clean assembleDebug testDebugUnitTest` — `clean` because the branch switch can
   leave stale Hilt/kapt stubs.
2. `./gradlew lint detekt`.
3. Install on the Pixel 9 Pro XL (`komodo`, connected over Wi-Fi) and check on device:
   - custody tint is visible in **all three** view modes, not just month (C1);
   - month chips are readable and cells did not break under the larger type (C2);
   - week/day event titles are no longer pink-on-pink (C3);
   - long-press delete and day/week delete both offer Undo, and Undo restores the event (C8);
   - a long event title in the preview sheet truncates instead of wrapping mid-word (C8);
   - expense and budget rows no longer ripple (C6).
4. Toggle light/dark in-app and confirm holiday and vacation day numbers stay legible in both,
   since C7 introduces theme-aware pairs.

## Deferred — recorded, not done

- **C5** — Mom/Dad semantics in Expenses: who paid, what the split is, who owes whom. The data
  is already present (`Expense.paidBy`, `Expense.splitBetween`), so this is a presentation-layer
  change whenever it is picked up.
- Audit turn 2 layouts: 2a (two-row month header with a real Today button, custody ribbon
  showing the next handover, legend row), 2b (week custody ribbon, unlabelled week blocks,
  `outlineVariant` gridlines), 2c (expenses split bar and settle-up line).
- Real detail/edit screens behind the rows de-affordanced in C6.
- Deleting `DynamicTheme.kt` / `AnimatedTheme.kt`, or wiring them into `MainActivity`. They
  remain corrected but unreferenced.
