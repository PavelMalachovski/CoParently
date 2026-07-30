# Audit layouts 2a / 2b / 2c — 2026-07-30

Implements the three redesigned layouts from the "CoPlanly Code Audit and Redesign" document
(turn 2). The code-level findings behind them (C1–C8) shipped in PR #29; this spec covers only
what is left, which is **layout**.

## Already done in PR #29 — not repeated here

Several bullets in the audit's "What changed and why" lists are closed:

- chip text `8.sp` → `labelSmall` (11sp); day numbers → `bodyMedium` (14sp); weekday header →
  `labelMedium` (12sp) — no hand-set sizes remain in the calendar;
- chip fill is solid (and moved to `MomChipFill`/`DadChipFill`, because solid `MomPink` under
  white text is 4.35:1 and still fails AA — the audit's suggestion did not go far enough);
- week/day custody tint unified on `CUSTODY_TINT_ALPHA` (0.14f);
- event labels switched from `MomPink` to `onSurface`;
- expense and budget rows are no longer falsely tappable.

## Decisions taken before writing

1. **"Settle up" opens Chat with a prepared draft.** It never sends anything — the composer is
   pre-filled and the user reviews and sends. No settlement entity, no schema change.
2. **The Mom/Both/Dad parent filter moves into the Filters sheet**, as the mockup shows.
3. **All four header actions stay** (Today, weekly summary, change requests, settings). The
   change-requests badge signals work waiting on the user and must not be buried.
4. **One branch, one PR** for all three screens.

## Branch

`feat/audit-layouts-2a-2b-2c`, cut from `main` after PR #29 merges. (If #29 is still open at
implementation time, branch from it and rebase.)

---

## 2a — Month view

### Header: three stacked rows become two

`CalendarHeader.kt` today renders a `TopAppBar` whose title is a `MonthTitleSelector`
("July 2026 ▾" opening a view-mode dropdown) and whose actions include a `TodayButton` showing a
bare day number.

**Row 1 (TopAppBar).** Title becomes a two-line block: "July 2026" (`titleLarge`, bold) over a
subtitle in `labelMedium`/`onSurfaceVariant`. The subtitle reads `"Sat 25 · today"` when the
selected date is today, and just `"Mon 13"` otherwise — the bare `25` gains a label. The title is
no longer a dropdown; the chevron goes away with the menu.

Actions, in order: **Today** (a compact outlined pill reading "Today", replacing the bare-number
button), weekly summary, change requests (badge preserved), settings.

**Row 2 (new `CalendarViewModeBar`).** A `SingleChoiceSegmentedButtonRow` with Month / Week / Day,
plus a "Filters" button carrying the existing filter icon. This is where the dropdown's job goes.

**`ParentFilterBar` is removed** and its segmented control moves into `EventTypeFilterSheet` as a
"Show" section above "Event types". `EventTypeFilterSheet` gains `parentFilter: ParentFilter` and
`onParentFilterChange: (ParentFilter) -> Unit`.

### Custody ribbon replaces the 48dp banner

`CustodyIndicatorToday` (a 48dp outlined box reading "With Dad") becomes a 34dp ribbon:

- rounded 8dp, background = parent colour at `CUSTODY_TINT_ALPHA`, a 3dp full-hue bar on the
  start edge;
- an 8dp full-hue dot, then "Today with Dad" (`titleSmall`, `onSurface`);
- trailing, `onSurfaceVariant`, `labelMedium`: "→ Mom in 2 days" — the ribbon now answers *when
  it changes*, which the old banner never did.

The trailing half needs the next handover. `HomeViewModel` already computes exactly this in a
private `nextHandoverFrom`, feeding Home's "Handover in 4 days" tile.

**Extract it rather than duplicate it.** `HandoverInfo` and `nextHandoverFrom` move to a new pure
`domain/custody/HandoverCalculator.kt`; `HomeViewModel` and `CalendarViewModel` both call it.
`CalendarViewModel` exposes `nextHandover: StateFlow<HandoverInfo?>` off the active custody model.

Degradation: no custody model → the ribbon does not render (today's behaviour). Model exists but
custody never switches → the ribbon shows only "Today with Dad", no trailing text.

### Custody blocks gain a leading edge

In `MonthView.DayCell`, a day that *starts* a custody run — `getCustody(date)` differs from
`getCustody(date.minusDays(1))` — draws a 2dp full-hue bar on its start edge. The pattern stops
depending on a 14% fill alone. `getCustody` is already a lambda in scope, so this is one extra
call per cell.

### Legend row

A row at the bottom of the month view, sharing its line with the FAB: a 7dp pink square "Mom", a
7dp blue square "Dad", a 10×3dp teal bar "Vacation". Month mode only — week and day have no
vacation strip.

---

## 2b — Week view

### Custody ribbon above the day headers

A bar spanning the seven day columns (offset by the hour-gutter width), split into runs of
consecutive same-custody days, each run solid parent colour with a white `labelSmall` label
("Mom"/"Dad"). A run narrower than two days carries no label — there is no room, and a clipped
label is worse than none.

Week mode only (`daysCount > 1`); day mode is already answered by 2a's ribbon above it.

### Week event blocks carry no label

At a ~40dp column a title renders two characters, which is why the current build shows `…` — this
was visible on device and is the audit's own observation. So when `daysCount > 1` the block draws
**fill + a 3dp full-hue left bar and nothing else**. Parent identity comes from the bar; the name
comes from a tap, or from day view.

**Accessibility is not sacrificed:** the block keeps its `contentDescription` with the full title
and time, so a screen reader still announces it.

Day view (`daysCount == 1`) keeps title and time as they are now.

The 3dp left bar is added in both modes. The existing 1dp border stays — the audit's point was
that identity must not rest on same-hue *text*, which PR #29 already fixed; a border plus a bar
reads well and is the lower-risk change.

### Gridlines

Hour and column separators move to `outlineVariant` at 55% alpha, so the hour grid is actually
visible on `DarkSurface`.

---

## 2c — Expenses

### `ExpenseSummaryCards` → `ExpenseSummaryHeader`

The current `LazyRow` of 140dp `primaryContainer`/`surfaceVariant` cards carries no Mom/Dad
semantics at all — in a two-household product, the money screen never says who paid. It is
replaced by one header card:

- "July · shared spend" (`labelMedium`, `onSurfaceVariant`);
- the month total (`headlineMedium`, bold);
- an 8dp rounded **split bar**, Mom's share pink and Dad's blue, proportional to what each paid;
- under it "Mom paid $154.10" (pink) and "Dad paid $94.40" (blue);
- a divider, then the balance: a dot, "Dad owes you $29.85", and a **Settle up** outlined button.

**Balance maths.** For each expense the payer is out `amount`; every uid in `splitBetween` owes
`amount / splitBetween.size`. When `splitBetween` is empty, the expense is treated as unsplit and
contributes nothing to the balance. The user's net is `paid − owed`; positive means the co-parent
owes them.

**Resolving uid → mom/dad.** `User.role` is already `"mom"`/`"dad"`, so `getAllUsers()` gives the
map and `getCurrentUserId()` gives "you". `ExpenseViewModel` already injects `UserRepository`.

**Unpaired degradation matters here.** The app is currently unpaired, so only one user exists. In
that case the split bar and balance row are **hidden** — a 100%-pink bar and a $0.00 balance would
be noise pretending to be information. The card still shows the month label and total.

### Rows

Each row becomes a `surfaceContainerLow` card, rounded 10dp, still not clickable (C6):

- leading: a 30dp rounded square tinted with the payer's colour at 18%, holding the category icon
  in the payer's colour. **Deviation from the mockup:** when the expense has a receipt photo the
  existing tappable thumbnail is kept in that slot instead — the receipt viewer is a real working
  feature and losing its entry point to match a mockup would be a regression;
- title (`bodyMedium`), then "Medical · Mom paid · Jul 9" (`labelSmall`, `onSurfaceVariant`);
- trailing: amount (`titleSmall`, bold) over the split label (`labelSmall`, `onSurfaceVariant`):
  "split 50/50" for two participants, "split 1/N" for more, "not split" for none.

The section header reads "This month" with a right-aligned count. The count says **"3 expenses"**,
not the mockup's "3 receipts" — most expenses have no receipt attached, so "receipts" would be
wrong.

### Settle up wiring

`ExpenseScreen` gains `onSettleUp: (String) -> Unit`, passed the draft text
("Let's settle up — you owe me $29.85 for July." with the real figure and month).

`Screen.Conversations` gains an optional `draft` argument; `ConversationsScreen` forwards it to
`Screen.Chat.createRoute(conversationId, draft)` when a thread is opened. `ChatScreen` takes
`draft: String = ""` and passes it to `MessageInput`, whose private `text` state becomes
`remember(initialText) { mutableStateOf(initialText) }`.

The user lands on their thread with the composer filled and sends it themselves. Nothing is sent
automatically — an outgoing message to another person is the user's to authorise, and a
one-tap-sends button in a co-parenting app is exactly the wrong default.

---

## Strings

New user-facing strings go in a new tracked `res/values/calendar_strings.xml` and the existing
tracked `expenses_strings.xml`. Keys are prefixed `calendar_legend_*`, `calendar_viewmode_*`,
`calendar_custody_*`, `calendar_filters_*` and `expenses_*` — note the gitignored `strings.xml`
already holds `calendar_title`, `calendar_settings` and `calendar_add_event`, so those three names
are avoided to keep a fresh clone building.

## Verification

1. `./gradlew clean assembleDebug testDebugUnitTest lint detekt`.
2. Install on the Pixel 9 Pro XL and check on device:
   - month header is two rows; "Today" is labelled; Month/Week/Day switches the view; Filters
     opens a sheet that now contains Mom/Both/Dad and the parent filter still works;
   - the ribbon reads "Today with X" and a plausible "→ Y in N days" against the configured
     custody model;
   - custody runs show a leading edge on their first day only;
   - the legend appears in month mode and not in week/day;
   - week shows the custody ribbon, unlabelled blocks with a left bar, and visible gridlines;
   - day view still shows title and time;
   - Expenses shows total + split bar + balance when paired, and total only when unpaired;
   - Settle up lands in chat with the draft in the composer and **nothing sent**.
3. Toggle light/dark and re-check the ribbon, split bar and legend.

## Out of scope

- Any settlement record or payment history (the button only drafts a message).
- Expense detail / budget edit screens — the rows stay non-tappable.
- Changing custody or expense data models.
