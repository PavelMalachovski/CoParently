# §11 batch 1 — the six behaviour fixes

**Date:** 2026-08-03
**Scope:** items 4, 5, 6, 7, 8 and 9 of `docs/TEST-PLAN-2026-08.md` §11.
**Baseline:** the "Baseline recorded — 3 August 2026" section of that document. Every "today"
statement below is quoted from it rather than re-derived.

Items 1a, 1b, 2 and 3 are deliberately **not** in this batch. Each of them changes the data model,
Firestore rules and (for 1b) the shape of binary storage, and each needs product decisions this
spec does not make. They get their own specs.

## What this batch is

Six defects that share three properties: no Room schema change, no `firestore.rules` change, no new
dependency. They are the ones a separated parent hits daily. Two of them — 4 and 6 — are one defect
wearing two hats, and the baseline run proved it: because the thread opens at the oldest message, a
template that sends immediately produces no visible change, reads as a missed tap, and gets tapped
again. Three identical messages reached the co-parent that way during the baseline pass.

## Decisions taken

Four product questions were settled before this spec was written. They are recorded here because
the implementation is not free to revisit them:

1. **Agenda card in a month you paged to (item 9):** show nothing until a day is tapped. The current
   month still shows today.
2. **Template placeholders (item 6):** substitute the text as-is, placeholders included. A
   fill-in-the-blanks form is a separate feature, not this fix.
3. **Where the chat card leads (item 5):** the change-request inbox, with the relevant request
   highlighted.
4. **Expenses swipe surface (item 7):** the summary card and the month switcher bar only. The list
   is not a swipe surface — its rows already own the horizontal gesture for swipe-to-delete.

## Non-goals

- **Item 8 may not land.** It is an investigation with an unknown answer (see below). If the cause
  turns out to be structural, it leaves this batch with a written diagnosis. That is an acceptable
  outcome; a plausible-looking tweak that moves the numbers without an explanation is not.
- **`ChangeRequestsScreen` is hardcoded English** — "Change Requests", "No change requests yet",
  "Back". Item 5 sends users there, which makes it more visible, but translating that screen is
  its own change and is not part of this batch. Same for the templates sheet's own chrome
  ("Message Templates", "Pickup & Drop-off", "Illness & Medical"). Both are recorded here so the
  next person finds them.
- No change to what a change request *is* (still one event, `PENDING/ACCEPTED/DECLINED/CANCELLED`).
- No change to the Firestore message schema. The card keeps `messageType = EVENT_LINK` and
  `attachments = [eventId]`, so an older build on the co-parent's phone renders it exactly as it
  does today.

## Testing strategy (applies to every item)

The project has JVM unit tests only — no instrumentation, no Compose UI tests. Assertions therefore
go on **pure functions extracted for the purpose**, and the composable keeps only the wiring:

| Item | Extracted function | Lives in |
|---|---|---|
| 4 | `ChatScrollPolicy.targetIndex(...)` | `domain/chat/` (next to `ChatReadState`) |
| 5 | `ChangeRequestHighlight.forEvent(requests, eventId)` | `domain/changerequests/` |
| 7 | `MonthSwipe.resolve(dragPx, thresholdPx)` | `presentation/expenses/` |
| 9 | `CalendarSelection.forMonth(month, today)` | `presentation/calendar/` |

Each gets a unit test covering its edge cases before the wiring is written. Device verification on
the Samsung closes each item; item 8 additionally re-runs the baseline's `gfxinfo` protocol so the
before/after numbers are comparable.

---

## Item 4 — the chat opens at the newest message

**Today.** `MessagesList` has no initial scroll. Its only scroll is `LaunchedEffect(entries.size)`,
which animates to the last item **only if** the list is already near the bottom
(`lastVisibleIndex >= total-2 || firstVisibleIndex >= total-3`). On first composition both indices
are 0, so that holds only for threads of three entries or fewer — day separators count. Anything
longer opens at the oldest message.

**Change.** One-shot jump on the first non-empty composition: `scrollToItem(lastIndex)`, not
`animateScrollToItem`. Animating a flight past forty bubbles is its own defect. A
`rememberSaveable` flag marks the jump done, so it survives a configuration change and does not
re-fire.

The existing near-bottom guard stays and keeps its current job: deciding whether an *arriving*
message pulls the view down. Reading history while the co-parent types must not yank the list.

**Pure function.**

```kotlin
/** @return index to scroll to, or null to stay put. */
fun targetIndex(
    entryCount: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    initialJumpDone: Boolean
): Int?
```

Cases to cover: empty thread; first composition with a long thread (jump); first composition with a
one-entry thread; user parked at the top when a message arrives (no scroll); user at the bottom when
a message arrives (scroll); jump already done and nothing new (null).

**Files.** `presentation/chat/MessagesList.kt`, new `domain/chat/ChatScrollPolicy.kt`, new test.

---

## Item 6 — the template prepares a message instead of sending it

**Today.** `ChatScreen.onTemplateSelected` calls `viewModel.sendTemplateMessage(template,
template.content)`, which delegates straight to `sendMessage`. No preview, no edit, no confirmation,
no undo, and the placeholders go out raw.

**Change.** Route the template through the composer, which already exists for exactly this purpose:
`ChatScreen(draft = …)` → `MessageInput(initialText = …)` is how the Expenses settle-up draft
arrives, documented there as *"Never sent automatically — a message to the co-parent is the user's
to send."*

- `MessageInput` becomes stateless — `value: String`, `onValueChange: (String) -> Unit`,
  `onSend: (String) -> Unit` — per the project's stateless-composable rule. It currently owns
  `remember(initialText) { mutableStateOf(initialText) }`, which also means picking the *same*
  template twice would not re-seed the field: the `remember` key would not change. Hoisting the
  state removes that trap rather than working around it.
- `ChatScreen` owns the composer text in `rememberSaveable`. Selecting a template sets it to
  `template.content`, closes the sheet, and moves focus to the field so the keyboard opens with the
  cursor ready.
- The incoming `draft` parameter seeds the composer once on entry, exactly as today.
- `ChatViewModel.sendTemplateMessage` has no remaining caller and is deleted. `MessageTemplate`
  stays as it is.

**Verification.** On device: tap a template, confirm the text lands in the composer and **nothing**
appears in the thread until Send is pressed. Tap the same template twice in a row and confirm the
field is re-seeded both times.

**Files.** `presentation/chat/ChatScreen.kt`, `presentation/chat/MessageInput.kt`,
`presentation/chat/ChatViewModel.kt`.

---

## Item 5 — the change-request card links to the request

**Today.** The card is posted by `RequestChangeViewModel.postChatMessage` with
`messageType = EVENT_LINK` and `attachments = listOf(event.id)`. `MessagesList.MessageItem` renders
`message.content` and nothing else: it never reads `messageType` or `attachments`, and no bubble
carries a click modifier. Three taps on it during the baseline run did nothing at all.

**Change.**

- A bubble whose `messageType == EVENT_LINK` and whose `attachments` is non-empty becomes clickable
  and gains a visible affordance (a trailing chevron inside the bubble) plus a
  `contentDescription` — an inert-looking bubble that happens to be tappable is the same defect in
  the other direction.
- `MessagesList` takes `onEventLinkClick: ((eventId: String) -> Unit)?`; `ChatScreen` passes it up;
  `NavGraph` navigates.
- `Screen.ChangeRequests` gains an optional argument: `change_requests?eventId={eventId}`. The
  existing argument-less callers keep working unchanged.
- `ChangeRequestsScreen` accepts the optional event id, scrolls the target into view and marks it —
  a tonal container plus a one-line "this one" treatment, no new colour semantics.

**Which request gets highlighted.** The card carries the *event* id, not the request id, and one
event can collect several requests over its life. Resolution order:

```kotlin
fun forEvent(requests: List<ChangeRequest>, eventId: String): ChangeRequest?
// 1. newest PENDING request for that event
// 2. else newest request of any status for that event
// 3. else null
```

When it returns null — the request was cancelled and swept, or the event is gone — the inbox still
opens and a snackbar says so. That is **one new string**, which goes into all five locales
(`values`, `values-cs`, `values-de`, `values-ru`, `values-uk`) in the same commit, per the project
rule.

**Files.** `presentation/chat/MessagesList.kt`, `presentation/chat/ChatScreen.kt`,
`presentation/navigation/NavGraph.kt`, `presentation/changerequests/ChangeRequestsScreen.kt`,
new `domain/changerequests/ChangeRequestHighlight.kt`, new test, five string files.

---

## Item 7 — month swipe in Expenses

**Today.** The chevrons in `MonthNavigation` / `MonthSwitcherBar` are the entire navigation. There
is no `pointerInput`, `draggable` or pager anywhere in `presentation/expenses`. Swipes in both
directions over the summary card, the list and the month bar changed nothing on device.

**Change.** A horizontal drag detector on the month header region only:

- the `ExpenseSummaryHeader` card that carries the `MonthNavigation` (the first one — with several
  currencies only the first card owns the switcher, and that stays true for the gesture), and
- the `MonthSwitcherBar` shown when the month has no expenses.

Drag distance is accumulated and resolved on release against a threshold (56.dp, in px at the
current density); past it, `showPreviousMonth` / `showNextMonth`. Below it, nothing moves. The
chevrons stay exactly as they are — the swipe is an addition, not a replacement.

The list keeps no horizontal gesture of its own beyond swipe-to-delete, so the two never compete.

**Pure function.** `MonthSwipe.resolve(dragPx: Float, thresholdPx: Float): MonthStep` returning
`PREVIOUS`, `NEXT` or `NONE`. Cases: below threshold either way; exactly at threshold; well past in
each direction; zero.

**Note for the implementer.** When the account has no expenses *at all*, the screen renders
`AnimatedEmptyState` and there is no month header — so there is no swipe surface either, and none
is added. That is consistent with today: the switcher does not exist in that state.

**Files.** `presentation/expenses/ExpenseScreen.kt`, `presentation/expenses/ExpenseSummaryHeader.kt`,
new `presentation/expenses/MonthSwipe.kt`, new test.

---

## Item 8 — the calendar swipe asymmetry

**Today.** Measured, twice, in the baseline: five cold-start swipes forward render 142 frames at
20.4% jank (p50 15 ms); the same five backward render 36 frames at 58.3% (p50 93 ms). A warm repeat
gave 155 vs 62. Both runs moved exactly five months, so every gesture registered. Backward paging
draws roughly a quarter of the frames — a handful of long frames instead of an animation.

**This item is an investigation, not a known fix.** `MonthView` already carries three fixes aimed at
this exact symptom: a stable `anchorMonth` so the pager's loaded range does not shift on every
settle, propagating the month only after `isScrollInProgress` goes false, and
`OutDateStyle.EndOfGrid` so short and tall months have the same height. Whatever remains is not
those three causes, and the honest first step is to find out what it is.

Method: `superpowers:systematic-debugging`, with the baseline's own measurement protocol
(`dumpsys gfxinfo … reset`, five scripted swipes, read the histogram) as the experiment harness, so
every hypothesis is accepted or rejected on numbers.

Hypotheses to test first, in order:

1. **`LaunchedEffect(selectedMonth)` racing the settle.** It guards on `!isScrollInProgress`, but
   the month is propagated from a `snapshotFlow` collector when scrolling *stops* — the two run on
   the same state transition, and only one direction may lose the race.
2. **Per-cell event lookup.** `eventsOn(events, day.date)` is called for each of 42 cells on every
   composition, scanning the full event list each time; with `OutDateStyle.EndOfGrid` the grid is
   always 42 cells. If the backward direction composes cells that the forward direction had already
   cached, this shows up as exactly this asymmetry.
3. **Custody and holiday lookups per cell** (`getCustody`, `holidays[day.date]`) — same shape as 2.

**Exit conditions.**

- *Cause found and the fix is contained:* implement it, re-run the protocol, and put the
  before/after table in the PR body.
- *Cause found but the fix is structural:* write the diagnosis into this spec's follow-up section,
  drop the item from the branch, and say so in the PR.
- *Cause not found:* same as above — record what was ruled out and by which numbers. Do not ship a
  change that moves the numbers without an explanation for why.

The `[H]` half of this item — what the asymmetry feels like under a thumb (4.2.3) — stays open
either way. Frame counters cannot express inertia.

**Files.** `presentation/calendar/MonthView.kt` and whatever the diagnosis implicates.

---

## Item 9 — the agenda card belongs to a chosen day

**Today.** `MonthView.onMonthChange` calls `setSelectedDate(newMonth.atDay(1))`
(`CalendarScreen.kt:527`), so paging a month silently selects the 1st and the card below announces
"nothing scheduled" for a day nobody chose. With the baseline account it said that on every month
paged to, in both directions.

**Change.** Separate *what the grid shows* from *what the user picked* — the two are currently the
same field, which is the whole bug.

`CalendarViewModel`:

- add `displayedMonth: StateFlow<YearMonth>`, initialised to the current month;
- `selectedDate` becomes `StateFlow<LocalDate?>`, initialised to today;
- `showMonth(month)` sets `displayedMonth` and the selection in one step: today when the month
  contains today, `null` otherwise;
- `setSelectedDate(date)` keeps its meaning (a deliberate tap) and also aligns `displayedMonth` —
  which matters because `OutDateStyle.EndOfGrid` renders leading and trailing days of the
  neighbouring months in every grid: tapping the greyed-out 31st of the previous month selects that
  day **and** pages the grid to its month, rather than selecting a day the grid is not showing;
- the Today pill calls `showMonth(currentMonth)`, which re-selects today by the same rule.

`CalendarScreen`:

- the agenda card renders only when `selectedDate != null`; with no selection the grid takes the
  freed height;
- Day and Week views need a concrete date, so they read `selectedDate ?: today`;
- the query range in MONTH mode is computed from `displayedMonth`, **by extending `queryRangeFor`**
  with that parameter rather than inlining new range arithmetic at the call sites — the project rule
  exists because this maths was duplicated four ways once already.

**Pure function.** `CalendarSelection.forMonth(month: YearMonth, today: LocalDate): LocalDate?` —
today when the month is today's month, else null. Cases: current month; a past month; a future
month; and the December/January boundary, where "same month" must not be read as "same month
number".

**Watch for.** `selectedDate` is read in several places for filtering, custody colouring and the
FAB's default date. Every one of them has to answer "what does this mean with no day selected?"
Making the type nullable is what forces that question at compile time, which is why the field
changes type rather than gaining a companion boolean.

**Files.** `presentation/calendar/CalendarViewModel.kt`, `presentation/calendar/CalendarScreen.kt`,
`presentation/calendar/MonthView.kt`, `presentation/calendar/components/CalendarBanners.kt`,
new `presentation/calendar/CalendarSelection.kt`, new test.

---

## Sequencing

4 → 6 → 5 → 7 → 8 → 9.

4 and 6 first because they are one defect and verifying either without the other is misleading —
that is exactly how the baseline run produced three duplicate messages. 8 before 9 because 9
rewrites selection handling in the same screen 8 is being measured on; measuring a moving target
would waste the only objective instrument this item has.

## Risks

- **Item 9 is the only real refactor here.** A nullable `selectedDate` touches filtering, custody
  colouring and the FAB. The compiler surfaces the call sites; the risk is a hasty `?: today` that
  quietly reintroduces "a day nobody chose" somewhere else. Each site gets a decision, not a default.
- **Item 8 may not land** (see its exit conditions). If it does not, the PR is still five fixes.
- **Item 5 links into an untranslated screen.** Recorded as a non-goal above, not silently accepted:
  it should be the next small change after this batch.
