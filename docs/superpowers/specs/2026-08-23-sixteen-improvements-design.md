# Sixteen improvements from the owner's walkthrough — design

**Date:** 23 August 2026
**Package:** the sixteen-item list from the owner's live walkthrough (screenshots 1–3)
**Base:** `main` @ `505cfa5`
**Branch:** `claude/custody-app-improvements-ycyqlh`

The owner's list, restated and grouped. Item numbers are the owner's.

---

## 0. Decisions taken with the owner (interactive Q&A, this session)

| # | Question | Owner's answer |
|---|---|---|
| 1 | Scope of the "friend" (item 16)? | **Full**: own account, invitation, read access, profile, filter. |
| 2 | Custody change approval (item 7): apply-then-notify, or hold? | **Hold** — the change does not apply on the co-parent's calendar until they accept. |
| 3 | "Всплывающее сообщение" means? | Both: an in-app dialog with Accept/Decline **and** an FCM push. |
| 4 | Wipe local data when a different account signs in (item 2)? | **Yes**, wipe on uid change (unsynced rows of the old account are lost — accepted). |
| 5 | Month-cell tap (item 8)? | **Tap opens DAY view** (the pre-redesign behaviour); the FAB also pre-fills the selected day. |
| 6 | Two-column expense table (item 10) — where? | **Under the pie chart** in the Analytics tab; the List tab stays. |
| 7 | Onboarding intro wording (item 3)? | The rewritten variant proposed in chat (em-dash list moved next to what it lists). |
| 8 | Friend photo (item 16)? | **Firebase Storage** — reuse the medical-photo upload infrastructure. |
| 9 | Friend in the calendar filter means? | **Events the friend takes part in**: a parent marks an event "friend participates"; the filter shows those; the grid marks them with the friend's colour. |

## 1. Group A — small, self-contained fixes

- **Item 1 (forgot password).** `FirebaseAuthService.sendPasswordResetEmail` exists with zero
  callers, and `auth_forgot_password_link` is already translated in all five locales — the control
  was deliberately removed as a dead TODO (§4.7 of the 2026-08-22 auth design) and left as backlog.
  Reconnect it: a `TextButton` in the sign-in branch, a ViewModel method, a "check your inbox"
  confirmation state, and error mapping. Requires a non-blank email in the field; a blank email
  gets the same inline error card the sign-in path uses.
- **Item 3 (onboarding intro punctuation).** `onboarding_intro_body` buries the list ("группу
  крови, аллергию, телефон бабушки") behind an ", —" splice three clauses away from what it
  enumerates. Reword in all five locales, keeping the meaning: the list moves next to "данные",
  and the second paragraph keeps the "everything optional except your name" promise.
- **Item 5 (schedule proposed from the 1st, not today).** The custody anchor default is already
  `LocalDate.now()`; what opens on the 1st is the **calendar screen's own date picker**
  (`CalendarScreen.kt` — `initialSelectedDateMillis = displayedMonth.atDay(1)`). Open it on the
  selected day (falling back to today when the selection is in another month). Two adjacent picker
  defects fixed while here: `CustodySetupScreen` converts Material3's UTC-midnight
  `selectedDateMillis` through `ZoneId.systemDefault()` (one day early west of UTC — convert via
  `ZoneOffset.UTC`), and hardcodes a US date pattern (use a locale-aware `FormatStyle`).
- **Item 6 (Home order).** Contacts moves to the top of the Dashboard, joined by Child info and
  Pets rows in the same `SectionGroup` (both currently reachable only through Settings). The rest
  of the order is unchanged.
- **Item 8 (tap-to-create regression).** The Aug 2026 redesign removed `setViewMode(DAY)` from
  `onDayClick`, then the second pass removed the `DayAgendaCard` that had replaced it — leaving a
  tap with no visible outcome. Restore: a month-cell tap selects the day **and opens DAY view**,
  where an empty-hour tap creates an event (that wiring still works). The FAB passes the selected
  date instead of null.
- **Item 14 (swapped day must be one solid colour).** The handover diagonal
  (`DayCellFills.handoverFrom`) triggers whenever today's custody differs from yesterday's — so an
  accepted swap paints *two* half-cells. Suppress the diagonal on any day whose custody comes from
  an accepted `DayOverride` (and on the day after): an overridden day renders as a full-strength
  single fill of the new parent. Pattern-boundary handovers (no override involved) keep the
  diagonal.
- **Item 15 (unpair confirmed twice).** The unpair flow gains a second confirmation dialog with
  the owner's wording (losing access to child data, calendar, contacts, shared expenses). First
  dialog stays as-is; the second is the harder one, destructive-styled, "Нет" as the dismissing
  default. All five locales.

## 2. Group B — expenses (items 9–12)

- **Item 9.** The pie shrinks to half the column width (centred), gains per-slice labels for
  slices wide enough to hold one (min-angle guard; smaller slices rely on the legend), and a
  legend of swatch + name + amount. The add-expense category dropdown gets a colour dot per
  category from the same `CategoryPalette` — one palette, three surfaces.
- **Item 10.** Under the chart (Analytics tab): a two-column table, one column per parent
  (left = slot 1, right = slot 2, headed by real names via `ParentNames`), each expense a row in
  its payer's column with a category-colour marker and amount. Unpaired accounts see a single
  column. The List tab is unchanged.
- **Item 11 (settle-up navigation).** Settle-up is the only path that pushes a top-level tab route
  (`conversations?draft=…`) onto the Expenses tab's stack; `navigateToTab` then saves that mixed
  stack and every later visit to Expenses restores the chat on top. Fix: give the draft handoff
  the same tab semantics as every other tab switch (`popUpTo(Home){saveState}; launchSingleTop;
  restoreState`) so no tab's saved stack ever contains another tab's screen.
- **Item 12 (only the creator edits).** `Expense` gains `createdByFirebaseUid` end-to-end (Room
  column + migration, domain, mappers) — the client currently cannot check ownership at all. UI:
  a co-parent's expense opens read-only (no edit form, no swipe-to-delete). Rules: `update` drops
  `isPartnerOf` and becomes creator-only, matching `delete` which is already creator-only.
  Receipts (`purgeReceipt`) follow the same gate. Legacy rows with no stored creator stay
  editable-by-both until re-synced — the same posture the budgets backfill took.

## 3. Group C — account switching (item 2)

Persist the last signed-in uid (plain prefs, not encrypted — it gates a wipe, it is not a secret).
On an auth emission whose uid differs, wipe Room (`clearAllTables` off the main thread) and the
per-account preference keys **before** the first sync of the new account runs, then store the new
uid. This also closes the worse half of the defect: unsynced rows of account A being uploaded
under account B's audience by `performFullSync`. Sign-out itself keeps data (a returning parent's
history survives, as today); the wipe happens only when a *different* uid arrives.

## 4. Group D — pending requests must be answerable (items 4, 13)

The Accept/Decline inbox exists (`ChangeRequestsScreen`) and works; what is broken is reach: a
pending day swap raises no banner (the count reads only `change_requests`), no Home row, and the
chat card is deliberately inert. Fix reach, not mechanics:

1. The calendar banner count becomes `pending change requests + day swaps awaiting me`
   (`DaySwapInbox.awaitsAnswerFrom` over the mirrored overrides).
2. Home's "Изменения" feed folds in pending day swaps and pending event acceptances, each row
   tapping into the inbox. (Item 13: "requests must be visible on the main page".)
3. On app open, a pending incoming request raises a **dialog** on Home — Accept / Decline / Later —
   one request at a time, never re-shown for a request already answered or dismissed this session.
4. The chat day-swap card gets a tap target into the inbox (the route gains an optional no-arg
   entry; the "looks tappable but is not" objection dies with the target).
5. **Push:** a Cloud Function fans out an FCM data message on change-request creation and on a
   day-swap offer write. Token registration and channel plumbing follow the existing notification
   module; see §6.

## 5. Group E — custody pattern changes need consent (item 7)

`CustodyProposal`, `CustodyProposalTransition` and the calendar's proposal preview overlay shipped
in package E, but nothing writes a proposal — pattern saves go straight through `saveAndActivate`
and the co-parent's calendar silently changes (last-write-wins, `CustodyChangeAnnouncement` is
the only mitigation). Close the loop:

- While paired, a pattern save becomes a **proposal write** (`custody_models/{pairId}.proposal`),
  not an activation. The initiator sees their own proposal as the existing preview overlay plus a
  "waiting for approval" banner; the active model stays in force on both phones.
- The co-parent gets the Group-D dialog (and push): Accept applies the proposal via the existing
  transition (becoming the new active pattern, `lastModifiedBy` = the accepter, as the rules
  require); Decline archives it and tells the initiator.
- While **unpaired**, a save still applies directly — there is nobody to ask.
- Rules: the `custody_models` block gains presence-validation for the `proposal` map — the
  proposer writes only their own proposal, the recipient may accept (replace pattern fields +
  clear proposal) or decline (clear proposal, keep pattern). Emulator cases first, per CLAUDE.md.

## 6. Group F — the friend (item 16)

Built on the guest-grant system (package G2) — a guest sits beside the two parent slots, never in
one — extended from "child record only" to the calendar, plus a profile and a colour:

- **Access:** the guest uid joins `events` audience (`sharedWith`) with the same expiry gate the
  child record uses; `sweepExpiredGuests` extends to events. The friend's own build reads the
  calendar read-only (create/edit/delete affordances hidden for a guest session; rules enforce it
  server-side regardless).
- **Profile:** the friend fills name, role (guardian / friend / grandmother), phone numbers, blood
  group, and a photo uploaded to Firebase Storage on the medical-photo infrastructure (own path,
  own rules; visible to the two parents and the friend).
- **Participation + filter:** an event gains `friendParticipates` (nullable guest uid); a parent
  marks it in the editor; the calendar filter grows a third segment for the friend, and a marked
  event carries a dot/outline in the friend's colour. The friend colour is a neutral third hue
  (teal family) — never the parent pink/blue, and never through `colorScheme.secondary`.
- **Push:** friend-invite acceptance notifies both parents (same fan-out function).

## 7. What is deliberately not done

- No FX conversion anywhere near the new expense table (standing rule).
- `lastModifiedAt`'s naive-local-time ordering is not touched (known issue, its own change).
- The `ChildInfoViewModel` head-of-list defect is not touched (known issue, its own change).
- Chat's `participants.size() == 2` stays — the friend does not join the conversation.

## 8. Verification in this environment

No Android SDK and no route to Google's Maven host (dl.google.com 403 via proxy), same as packages
C–G. So: firestore rules on the emulator (cases first), functions `npm test` + eslint, every
pure-Kotlin test under standalone `kotlinc` 2.1 + JUnit, locale completeness by grep, line length
by grep. `assembleDebug` / `testDebugUnitTest` / `lint` / `detekt` run on the owner's machine on
first checkout; anything Compose-only is flagged in the PROGRESS ledger as not compiled here.
