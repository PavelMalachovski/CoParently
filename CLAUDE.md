# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## What this project is

CoPlanly — an Android shared-calendar app for separated parents. Kotlin + Jetpack Compose
(Material 3), Clean Architecture with Hilt, Room as the offline-first source of truth,
Firebase (Auth/Firestore/FCM) for sync between the two parents, Google Calendar integration,
Gemini for AI features.

**The authoritative roadmap is `docs/CoPlanly/MVP_phases.md`** (not `.cursor/roadmap.md`,
which is the historical original plan). MVP 1 is complete, and **MVP 2 appears to be complete
too** — receipts with on-device OCR, change requests, the Home dashboard, structured change
requests from chat and event images are all shipped; this line said "MVP 2 is next" for longer
than it was true. Re-baseline against `MVP_phases.md` before planning MVP 3.

**The latest full audit lives in `docs/AUDIT-2026-08.md`** (`AUDIT-2026-07.md` is the previous
one). Read §5 before planning anything: the app has no privacy policy, no in-app account
deletion and no signing config, so it cannot be published yet, and the `applicationId`
(`com.coparently.app`, against a product called CoPlanly) becomes permanent at first upload.

## Design refresh (August 2026) — implemented, keep consistent

Second pass over the six main screens, from a Claude Design audit. It builds on (does not
replace) the July 2026 overhaul below — those invariants still hold except where noted here.

1. **Shared UI primitives** live in `presentation/common/DesignSystem.kt`: `SectionGroup`
   (one tonal container per run of rows, dividers inserted for you), `SectionRow` (icon,
   title, status/value, **at most one** trailing control), `GroupLabel`, `PillChip`. Home,
   Settings, Expenses and Chat all render through these — do not reintroduce
   `Card { ListItem { … } }` per row, which is what the audit called "double surfaces".
2. **Parent colours go through `presentation/theme/ParentColors.kt`**: `fill()` for dots,
   bars and tints; `text()` for anything that is a foreground (it picks the theme-aware
   `*Light`/`*Dark` partner). The raw `MomPink`/`DadBlue` are fill-only — using them as text
   fails AA. The luminance test lives in `ParentColors`, not copy-pasted per screen.
3. **Colours come from `MaterialTheme.colorScheme`, not literal hex.** The prototype was
   authored in dark and its palette *is* `DarkColorScheme`, so every value has a role
   (`#1F1F25` = `surfaceContainer`, `#C2C1FF` = `primary`, …). Using roles is what keeps
   light theme working on the redesigned screens.
4. **Settings is four labelled groups** — Family, Sync, App, Account, in that order (Family
   first: it is the product, and it used to sit below the sync cards that depend on it).
   Google Calendar's actions expand from its row rather than stacking buttons in a card.
5. **Calendar header is one row**: title (which *is* the Month/Week/Day picker), Today,
   Filters, gear. Change requests and school vacation are inline banners over the grid
   (`components/CalendarBanners.kt`), not a badged glyph and a per-day teal strip. Month
   cells carry event **dots**, tapping a day selects it **and opens Day view** (an owner
   decision from the Aug 2026 walkthrough — a select-only tap left no route to creating an
   event on a chosen day; an empty hour slot in Day view is that route), and
   the grid fills its screen. *(Aug 2026, second pass: the `DayAgendaCard` no longer sits
   under the grid — it renders on Home as the "today" card (`HomeWeek.todayOf`), fed by the
   same `DayAgendaCard` composable so the two surfaces cannot drift. Month paging is snapped
   by `MonthView`'s own nestedScroll settle — one 500 ms tween, identical in both
   directions — with `calendarScrollPaged = false`; don't hand snapping back to the library,
   whose spring read differently per direction.)*
6. **Weekly summary has exactly one entry point** — the button at the bottom of Home. The
   unlabelled `view_list` action is gone from the calendar header; don't add a second route.
7. **The Chat tab renders the thread in place** when there is exactly one conversation
   (`ConversationsScreen` composes `ChatScreen` with `onBack = null`). Do not "fix" this by
   navigating instead: that drops the tab route, hides the bottom bar, and makes Back bounce
   off a list that immediately forwards again.
8. **No affordance may promise a feature that doesn't exist.** The composer's `+` was
   captioned "attach" and opened message templates; templates are now a labelled chip and
   there is no attach button until attachments actually ship. Same rule shaped the thread
   header (`ChatThreadHeader`): it shows the co-parent's initial, their name and whether
   **your own** messages left the device (derived from `Message.status`), not the mock's
   "Synced just now" — the app tracks no chat sync timestamp, so printing one would be the
   same defect. Destructive actions follow the sign-out anatomy: a red `SectionRow` that
   confirms, not a filled error button (Settings sign-out, Pairing unpair).
9. **New user-facing strings go into all five locales** (`values`, `values-cs`, `values-de`,
   `values-ru`, `values-uk`) in the same commit — see "Localization" below.
10. **The weekend is a base layer, never a competing fill.** `presentation/calendar/DayCellFills.kt`
    decides a cell's `base` (neutral grey on Saturday/Sunday, in every grid row *including* the
    days borrowed from the neighbouring months) and its `overlay` (custody, public holiday,
    today) separately; `MonthView` and `DayWeekView` draw both as two chained
    `Modifier.background` calls. A single `when` picking one background is what made the weekend
    unreachable: `CustodyModel.getCustodyFor` never returns null, so on any account with an
    active custody model every in-month cell matched a custody branch and only the neighbouring
    months' days kept a tint. `WeekendBackgroundLight`/`Dark` are neutral greys applied at full
    strength — the old per-call-site 0.3/0.5 alphas are gone, so month and week read as one
    system. Don't "fix" a weekend that looks too subtle by putting it back ahead of custody:
    weekends are the days a separated parent checks first.

## UX/UI overhaul (July 2026 design review) — implemented, keep consistent

Direction agreed after a live walkthrough and shipped on `feature/ux-overhaul`.
When touching the UI, keep these invariants:

1. **Bottom navigation bar** (Home / Calendar / Chat / Expenses) is the top-level
   navigation — see `presentation/navigation/BottomNavDestination.kt`. It shows only on
   those routes (`BottomNavDestination.topLevelRoutes`); detail screens hide it and keep
   an up-arrow. **Settings is NOT a tab** — it opens from a gear action in each top-level
   screen's top bar and is a detail screen (`onNavigateUp = popBackStack`, bottom bar
   hidden). `QuickActionsBottomSheet` was dead code and is gone — genuinely so as of the
   August 2026 audit; the file had in fact survived this note by several months.
   *(Aug 2026: budgets no longer open from an unlabelled Expenses top-bar action — they are
   a chip strip on the Expenses screen itself. Tab switches, including Home's stat-tile deep
   links, go through `NavHostController.navigateToTab` so they share one back-stack policy.)*
2. **Toolchain**: compileSdk/targetSdk 36, Kotlin 2.1 (+ `kotlin.plugin.compose`),
   Compose BOM 2025.10 (Material 3 1.4 / M3 Expressive), Room 2.7.2 (2.6.x kapt breaks on
   Kotlin 2.x metadata), Navigation 2.9.3, Hilt 2.56.2, predictive back on.
3. **Calendar**: month view is a classic grid from the 1st with horizontal month paging
   (kizitonwose `HorizontalCalendar`); day/week use `HorizontalPager` with fling physics.
   Event chips are single-line (`softWrap = false` + ellipsis). School vacation is a thin
   bottom strip, never a full-cell fill (it used to drown custody colors).
4. **Custody coloring** must go through the unified lookup in `CalendarScreen`
   (`getCustody`): active `CustodyModel` first, legacy `CustodyScheduleEntity` as fallback.
   Don't read the legacy schedules directly in a view — model-based custody would vanish.
5. **Event tap opens a preview bottom sheet** (`EventPreviewSheet`, details + Edit/Delete);
   the editor is the second step. The event form has a sticky bottom Save button.
6. **Color semantics**: Mom-pink/Dad-blue are parent identity ONLY, applied via
   `CoPlanlyColors.MomPink/DadBlue` directly. The theme's `secondary` slot is a neutral
   indigo (`CoPlanlyColors.Neutral*`), so generic Material selected states (FilterChips)
   are neutral — never wire pink through `colorScheme.secondary`. **Saturation rule** (so
   the day-cell wash and the event chip read as one system, not two pinks): a custody
   *day background* is the parent hue at ~14% alpha (`MomPink.copy(alpha = 0.14f)`), while
   a *chip / dot / marker* is the same hue at full strength. Same token, different alpha —
   intentional, keep it that way.
7. **Notification permission** is requested contextually via
   `rememberNotificationPermissionRequester()` (push toggle, reminder selection), never on
   cold start.
8. **Destructive list actions** use M3 `SwipeToDismissBox` with an Undo snackbar
   (see `EventListScreen`); Undo re-creates the captured event (id is preserved).
   Danger actions (e.g. "Sign out of app") live at the bottom of their screen, not
   mid-list.
9. **User-facing strings** live in tracked, feature-named `res/values/*_strings.xml`
   files (`chat_strings.xml`, `expenses_strings.xml`, `settings_account_strings.xml`,
   `navigation.xml`, `event_preview.xml`, …). All `strings.xml` files are tracked too
   (secrets were moved to BuildConfig long ago); prefer the feature files for new keys.
   Never hardcode user-visible text in composables — see "Localization (i18n)" below.

DB note: installs older than the migration chain (schema < v5) are wiped via
`fallbackToDestructiveMigrationFrom(1,2,3,4)` in `DatabaseModule` — a v3 install used to
crash with "migration from 3 to 9 required but not found".

## Build & verify

```bash
./gradlew assembleDebug          # main build — run after every code change
./gradlew testDebugUnitTest      # JVM unit tests (MockK + coroutines-test + Turbine)
./gradlew lint detekt            # static analysis (detekt config in app/config/detekt)
```

```bash
cd functions && npm test && npm run lint    # Cloud Functions (mocha + eslint)
cd firestore-tests && npm test              # firestore.rules against the local emulator
```

- **Never debug `firestore.rules` by deploying to production and watching a phone.** That
  is how a broken `expenses` delete rule shipped once already. `firestore-tests/` runs the
  rules offline against the Firestore emulator; add a case there first. See its README —
  it needs a JDK 21+ on `PATH`, not just in `JAVA_HOME`.
- Windows dev machine; Gradle wrapper works from Git Bash and PowerShell.
- `google-services.json` is required for the Google Services plugin, but the build
  degrades gracefully if it is missing (see the conditional apply in `app/build.gradle.kts`).
- No GitHub CI: builds and tests are run locally
  (`./gradlew clean assembleDebug testDebugUnitTest`). After switching branches,
  prefer `clean` — stale Hilt/kapt stubs from another branch cause errors like
  "Could not find class file for '…Application'".

## Hard project rules

- **Jetpack Compose only** — never add XML layouts.
- **Stateless composables** — state lives in ViewModels (`StateFlow`), UI receives values
  and callbacks. Follow the existing `UiState` sealed-class pattern.
- **Hilt** for all DI. New modules go to `app/src/main/java/com/coparently/app/di/`.
- **minSdk = 26** — beware of newer `java.time` additions
  (e.g. `LocalDate.ofInstant` is API 34+; use `Instant.atZone(...).toLocalDate()`).
- **KDoc** on public classes/functions; code and comments in **English**.
- Material 3 components; theme tokens from `presentation/theme/`
  (`CoPlanlyColors`, `Typography`, `CoPlanlyShapes`, `dimensions()`).
- **Parent colours identify a person, not a role.** The app never shows the words "Mom" or
  "Dad": every parent label goes through `presentation/common/ParentLabels.kt` and renders
  that person's name. `"mom"`/`"dad"` survive as the two *slot identifiers* in Room, in the
  Firestore document schema and in `firestore.rules`, and are never renamed — `Event.parentOwner`
  is part of the schema `EventRepositoryImpl.toFirestoreMap()` defines, and a co-parent on an
  older build must keep reading it. Slot 1 is pink, slot 2 is blue; pairing assigns the slots
  (`functions/index.js`, `assignSlots`), nobody chooses one.
- **A calendar friend sits beside the two slots and never occupies one** (item 16, Aug 2026).
  A guardian/friend/grandparent with their own account reads the family's calendar through a
  **central** grant, `calendar_friends/{friendUid}` — never by being fanned out into every
  event's `sharedWith`, so admitting or revoking one is a single write and no event document is
  rewritten. The `events` read rule consults it in a **last** disjunct (a parent's own read
  short-circuits before the `get()`), with expiry compared against `request.time`; the friend
  reads by `whereIn("createdByFirebaseUid", [a, b])`, the shape `expenses`/`budgets` use.
  `acceptCalendarFriendInvitation` is a **third** callable beside pairing and guest and
  `acceptPairingInvitation` refuses its `kind` outright — redeeming a friend code there would
  run `assignSlots` and hand a friend a permanent parent slot. `Event.friendParticipates`
  records who takes part and is **not** an owner: `parentOwner` stays a slot, because whose day
  an event falls on is a fact about custody. The friend's colour is `CoPlanlyColors.FriendTeal`
  — never a parent hue, and never the theme's neutral `secondary`, which is for controls.
  **Faces come from the Google account, never from an upload.** A friend's `photoUrl` is seeded
  from Firebase Auth at their first profile save and copied into `calendar_friends/{uid}` by the
  callable, so the parents' list names *and* pictures them without a second read of a document
  that is not theirs; the parents' own faces come from `users/{uid}.profilePhotoUrl` through
  `NamedParent.photoUrl` and `ParentNames.photoForUid(uid)` — keyed on the uid, because a pair
  still sharing one slot would otherwise return the same face twice. `AccountAvatar`'s
  initial-letter fallback is load-bearing, not decorative: an email/password account has no
  picture. Nothing here is ever overwritten by a later re-derivation — a friend who set their own
  picture keeps it. Not built: a photo **upload** (the field and rules admit one; the Storage
  wiring does not exist, and a button that did nothing is the promise item 8 above forbids)
  and a sweep for lapsed grants (nothing leaks — the rule refuses an expired read — but the row
  lingers).
- **Only the signed-in user has a Room `users` row.** Nothing writes one for the co-parent, so
  `userRepository.getAllUsers()` can never answer "who is the other parent" — it returns one
  row, and on a device where two accounts have signed in over time it returns rows for accounts
  that are not paired with anyone. The co-parent's name *and slot* come from their own
  `users/{uid}` document via `PartnerSummary`, and `presentation/common/ParentsSource.kt` is the
  single place that joins the two halves. A ViewModel that needs the two parents exposes
  `parents: StateFlow<Parents>` from there; a composable resolves the fallback strings with
  `rememberParentNames` and passes one `ParentNames` down its tree.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## Architecture map

```
domain/    — models, repository interfaces, use cases, holidays, ReminderScheduler
data/      — Room (v24 + migrations), Firestore/Google/AI clients, repository impls, sync
presentation/ — Compose screens per feature + ViewModels + theme
di/        — Hilt modules (Database, Firebase, Google, UseCase, Notification, …)
```

Data flow: UI → ViewModel → UseCase → Repository → Room (source of truth) → Firestore sync.

### Things that are easy to get wrong

1. **Room schema changes** require: entity change → version bump in `CoPlanlyDatabase` →
   migration in `DatabaseMigrations` (it is auto-registered via `ALL_MIGRATIONS`).
   Exported schemas live in `app/schemas/`.
2. **Event editing must preserve fields.** `AddEditEventScreen` keeps a snapshot of the
   loaded event and uses `copy()`. Never rebuild an `Event` from scratch on save —
   that wipes `sharedWith`/`permissions`/`createdByFirebaseUid` (this was a real bug).
3. **Private events (`isPrivate`)** must never be written to Firestore. Both
   `EventRepositoryImpl` and `SyncService` filter them — keep any new sync path consistent.
4. **Recurring events** are stored once and expanded to occurrences at query time via
   `RecurrenceExpander` (wired in `EventRepositoryImpl.getEventsByDateRange`).
   Occurrences share the master event id — don't use the id as a unique list key.
5. **The Firestore document schema for events** is defined in one place:
   `EventRepositoryImpl.toFirestoreMap()`. `SyncService` maps must stay in sync with it.
6. **Calendar query ranges** come from `queryRangeFor()` in `CalendarScreen.kt` —
   extend that function instead of inlining new range math.
7. **View modes** are `MONTH, WEEK, DAY` (roadmap order). There is no 3-day view anymore.
8. **Czech holidays** come from `domain/holidays/CzechHolidays` (pure, computed; Easter
   via computus). School vacations are the nationwide MŠMT ones; the district-dependent
   spring break is intentionally not included.
9. **Reminders** are scheduled through the `ReminderScheduler` domain interface
   (WorkManager impl `EventReminderScheduler`), hooked into the event use cases —
   schedule on create/update, cancel on delete.
10. **Receipt OCR is on-device only** (`ReceiptTextRecognizer`/ML Kit, parsed by
    `ReceiptParser`, wired up in `AddExpenseScreen`/`ExpenseViewModel.scanReceipt`) — no
    receipt text or photo may be sent to Gemini or any other remote service without an
    explicit product decision.
11. **Pairing writes never touch the other parent's user document from the client.**
    Accepting an invitation and unpairing go through the `acceptPairingInvitation` /
    `unpairCoParent` callables (`functions/index.js`) — `firestore.rules` allows a user
    to write only their own `users/{uid}`, and the old client-side path is why the
    permissive `firestore.rules.simple` had to be deployed. The strict rules are live
    as of this change.
12. **A Firestore list query needs a `where` filter matching whatever field the security
    rule keys its `allow read` on.** Firestore validates a *query* by checking whether
    its structure guarantees every possible result satisfies the rule — it does not
    execute the rule per already-fetched document and drop the ones that fail. An
    unfiltered collection query is rejected outright (`PERMISSION_DENIED`) the moment the
    rule references a field the query doesn't constrain, even if, coincidentally, every
    document in the collection would have passed. This is why
    `FirestoreExpenseDataSource.getAllExpenses()` takes a `creatorUids` list and filters
    with `.whereIn("createdByFirebaseUid", creatorUids)` — mirroring
    `FirestoreEventDataSource.observeEventsSharedWith()` — instead of reading the whole
    `expenses` collection. Also keep the rule's field names in sync with what the writer
    actually sets: the expenses rule used to reference a `sharedWith` array that
    `ExpenseRepositoryImpl.addExpense()` never writes (the model shares expenses via the
    `partnerId` pairing relationship, not a per-document list), so the co-parent's own
    expenses were unreadable even by document id until the rule was changed to
    `isPartnerOf(resource.data.createdByFirebaseUid)`. A `whereIn`/`whereEqualTo` +
    `orderBy` combination on different fields also needs a composite index
    (`firestore.indexes.json`) — Firestore's error message links directly to the fix.
13. **The conversation id is derived, never generated.** `ConversationKey.of(uidA, uidB)`
    sorts the two UIDs and joins them, so both devices compute the same id without
    coordination and creating the conversation is idempotent. Randomly generated ids are
    what made the two phones settle on separate threads. Read and delivery state live on
    the conversation as `{uid: epochMillis}` maps — one write per event — and the ticks and
    unread badge are derived from them by `ChatReadState`, never stored per message.
    Message times are stored the same way: `Message.sentAtMillis`, epoch millis (Room
    schema v13, since superseded — the database is at v24), not a naive `LocalDateTime`, so two
    parents in different time zones agree
    on what a mark means and on when a message was sent. The Firestore field keeps its name
    (`timestamp`) and the read path still accepts a legacy ISO string, so a co-parent on an
    older build stays readable. Deliberately *not* changed: `Event`, `Expense`, `Budget`
    and `ChildInfo` dates, where a naive local time is often the right model — whether a
    custody handover follows the child's zone or the viewer's is an unmade product
    decision, not an oversight. **`CustodyModelEntity.lastModifiedAt` is a fifth, and it
    does not belong with those four**: it is not merely displayed, it decides which phone's
    schedule survives. See the custody entry in "Known issues" below before adding to this
    list.
14. **`sharedWith` is computed at upload time and never recomputed for a row already marked
    synced.** An event created while the account was unpaired is uploaded with an audience of
    one uid, and nothing revisits it — so it stays unreadable by a co-parent who arrives later.
    Pairing repaired this only for the *accepter*, and only by accident: `EventDao.reslotOwner`
    clears `syncedToFirestore` as part of the slot re-stamp. The inviter keeps their slot
    (`PairingViewModel.withSlotReslot`), `ParentSlotMigrator.reslot` returns 0 on `from == to`,
    and their whole pre-pairing history — Google Calendar imports included — stayed private
    forever. `SyncService.backfillAudienceForPartner` now re-queues this user's own non-private
    events once per co-parent uid, from the sync path rather than from pairing, so it also
    repairs pairs that already exist without them unpairing. Two rules for anything similar:
    key the marker on the **partner uid**, not a boolean, or it never re-arms on re-pairing; and
    exclude private rows **in the statement**, because a row with the flag cleared is a row
    queued for upload. Rows whose `createdByFirebaseUid` is null are deliberately not matched —
    nothing distinguishes this user's un-stamped event from anybody else's.

## Known issues / do not "fix" silently

- `Expense.currency` is a real per-expense field. A month mixing currencies is now summarised
  **per currency** (`calculateExpenseBalancesByCurrency` → one `ExpenseSummaryHeader` per currency
  on the Expenses screen; the Home "this month" tile joins per-currency subtotals). There is still
  no FX conversion between currencies (spec §10) — deliberately: totals stay honest within each
  currency rather than being normalised. Do not reintroduce a single cross-currency total.

- **A failed chat Firestore listener is never re-established** (known, accepted at merge time —
  backlog with the time-zone item below). Both mirror branches in `MessageRepositoryImpl` end in
  `.catch { Log.w(...) }`, which *completes* the mirror flow, so `merge(mirror, local)` then runs
  on Room alone for the rest of the process. `SharingStarted.WhileSubscribed` cannot restart it:
  `rememberChatUnreadCount()` in `NavGraph` holds an Activity-scoped `ChatViewModel` collecting
  `unreadCount` for the whole process lifetime, so the subscriber count never reaches zero.
  Observed once in production, on the first launch after install — both chat listeners were
  denied ~0.5 s before `ensureConversation` created the canonical conversation document, and that
  session ran on local data only. Nothing is lost and a cold restart clears it, but the app looks
  entirely healthy while receiving nothing. Recurs on any reinstall, factory reset or account
  switch. Fix when touching this code: `retryWhen` with backoff on both branches, or await
  `ensureConversation` before subscribing the observers. Don't "fix" it by removing the `.catch` —
  an uncaught failure in `viewModelScope.launch` terminates the process.

- **Cross-time-zone chat is implemented but never verified on two devices.** The August 2026
  chat sync moved message times to epoch millis specifically so two parents in different zones
  agree (see item 13 above), and it is covered by unit tests that drive the two zones explicitly
  (`ChatReadStateTimeZoneTest`) plus a 12→13 migration test. The two-phone acceptance scenario —
  set one phone's zone 2–3 hours apart, send a message, and confirm it counts as unread, the
  badge clears on open, and the ticks reach READ — was **deferred, not run**. Backlog item for
  the next review round. Everything else in that acceptance run passed on real devices.

- **The shared custody schedule orders the two phones' writes by a naive local date-time.**
  `CustodyModelEntity.lastModifiedAt` (and the `lastModifiedAt` field of the `custody_models`
  document, which mirrors it) is `LocalDateTime.now()` formatted ISO — no zone, no offset.
  `CustodyModelRepository.isNewer` parses both sides and compares them, and `mirrorIntoRoom`
  acts on the answer: the side it judges newer is not merely kept, it is **re-pushed over the
  other**. So for two parents 2–3 zones apart the wrong side can win *and overwrite*, where
  before the custody sync existed a local pattern merely stayed local. The banner does fire on
  the loser's phone (`lastModifiedBy` is the other parent), so this is silent-wrong-answer, not
  silent-loss — but the answer can still be wrong by exactly the offset between the two zones.
  Accepted for this round rather than fixed: the correct fix is epoch millis with a Room
  migration, the same move `Message.sentAtMillis` made in item 13, and it drags the Firestore
  field, a legacy-ISO read path for a co-parent on an older build, and a migration test with it
  — a change of its own size, not a rider on the sync work. `isNewer` already degrades an
  unparseable value on either side to "not newer", so a mixed-format transition is survivable.
  Until then: do not add more decisions to `lastModifiedAt`, and do not "fix" it by comparing
  the strings (they are ISO, so string order agrees with the same wrong answer) or by stamping
  `now()` in `saveReslotted`/`archiveRejected` — those two keep the stored dates on purpose,
  and re-dating them makes this device win every comparison forever
  (`CustodyModelRepositoryTest` pins both).

- **The calendar never renders `EventUiState.Error`.** `EventViewModel` sets it when a range query
  fails, but the only `LaunchedEffect(uiState)` branch in `CalendarScreen` handles
  `OperationSuccess` — so a failed range leaves the last-loaded grid on screen, or an empty one on
  a cold start, with nothing saying anything went wrong. Milder than the chat-listener entry above,
  which is why it is listed and not fixed: a recovery lever exists (pull-to-refresh calls
  `EventViewModel.refresh()`, which re-collects the query from scratch — re-requesting the same
  range is conflated away and could not restart it), and leaving and re-entering the Calendar tab
  recreates the ViewModel anyway. What is missing is that the user has to guess at the lever. The
  fix is an `is EventUiState.Error` branch in that same `LaunchedEffect` raising a snackbar with a
  Retry action wired to `refresh()`; it needs a new string in all five locales, which is why it was
  not folded into the query-window work. Don't "fix" it by rendering `Loading` in the calendar —
  the query flips to `Loading` on every re-anchor and the grid would flicker.

- `firestore.rules` (strict) was realigned with the real document schema (ISO **string**
  dates, presence-based key validation, `change_requests`/`expenses` collections added,
  over-strict `lastModifiedBy`/`canModify` gates dropped) so it no longer rejects the app's
  own writes, and now also covers `invitations`, `conversations` and `messages` for
  co-parent pairing and chat, plus `custody_models` for the shared custody schedule. (This
  sentence used to name `custody_schedules` instead: that block was dead — the Room table it
  was named after has no Firestore data source — and it has since been **deleted**. Do not put
  it back; see the audit bullet below, which exists for exactly that reflex.) It was
  deployed live to `coparently-a39c9`
  as of this change (`firebase deploy --only firestore:rules`), replacing the permissive
  `firestore.rules.simple` the project ran on until the client's last write to another
  user's `users/{uid}` document was removed. `firestore.rules.simple` remains in the repo
  only as a historical fallback — it is no longer deployed.
- The `budgets` collection now has a rule block (`firestore.rules`, gated on
  `createdByFirebaseUid` + `isPartnerOf`, deployed live). The gap this closed was worse
  than the pre-fix `expenses` bug: budget documents written by
  `BudgetRepositoryImpl.addBudget()` carried **no owner field at all** — not even a wrong
  one — so there was nothing a rule could gate on. The fix stamps `createdByFirebaseUid`
  on write and filters `FirestoreBudgetDataSource.getAllBudgets()` on it via
  `creatorUids`/`whereIn`, the same shape as `expenses`. `addBudget`/`deleteBudget` also
  gained the same try/catch guard `ExpenseRepositoryImpl` has, since an uncaught
  `PERMISSION_DENIED` (or any Firestore error) from an unguarded suspend call inside
  `viewModelScope.launch` crashes the app, not just fails the sync. **Caveat:** any
  `budgets` documents that synced to Firestore *before* this fix have no
  `createdByFirebaseUid` field and will silently stop matching the filtered read query
  (no error — they're just excluded from `whereIn`'s results). Room stays the source of
  truth so nothing visibly disappears on the device that created them, but they won't
  restore on a reinstall or a second device until re-saved. No backfill migration was run
  as part of this fix.
- A full audit (grep every `.collection(...)` call in `app/src/main/java` and
  `functions/index.js`, diff against `firestore.rules`' match blocks) found two more
  mismatches. One is now fixed; the other is left as-is because it is not reachable in
  production:
  - `FirestoreMedicalDataSource`/`FirestoreEducationDataSource` and their unbound
    `MedicalRepositoryImpl`/`EducationRepositoryImpl` were the unreachable half of this,
    and they have since been **deleted** — this bullet described them as still present for
    some time after they were gone. The rule it argued for was correctly never written:
    don't add rules for collections no client reaches.
  - `custody_schedules` (Room's `CustodyScheduleEntity`/`CustodyScheduleDao`, the legacy
    per-parent custody table) has no Firestore data source and never will — it stays
    Room-only. Its rule block, which matched no client code, has been deleted from
    `firestore.rules`; the live custody rule now guards the real synced collection,
    `custody_models` (one document per pair, gated on `participants`, with `allow get`
    rather than `allow read` so no list query can ever be issued, and with
    `lastModifiedBy == request.auth.uid` required on create and update — the change banner
    suppresses a reader's own uid, so an unvalidated author field lets either parent
    overwrite the shared schedule without the other being told). Do not resurrect the
    `custody_schedules` block just because the Room table name is still there.
- `strings.xml` is **no longer gitignored** (older docs/audit §2.1 claim otherwise —
  stale). No secrets live in resources: the OAuth client secret is injected via
  BuildConfig (`GOOGLE_CLIENT_SECRET` gradle property / env var), `GEMINI_API_KEY`
  likewise. Real secrets belong in `gradle.properties`/env vars only.
- User-facing strings produced **inside ViewModels/services** (e.g.
  `GoogleCalendarSyncState.message`, sync/status errors) are still hardcoded English —
  extracting them needs a resource-provider abstraction and is a tracked follow-up of the
  July 2026 localization pass. Don't inject `Context` into ViewModels ad hoc to "fix" one.
- Calendar range/day queries now match multi-day & overnight events by overlap
  (`getSingleEventsByDateRange` / `getEventsByDate`), not start date only.
- Unit tests for ChildInfo/Pairing/Settings/Sync ViewModels were removed as stale
  (they targeted long-gone APIs); rewrite them against the current constructors when
  touching those features.

- **`ChildInfoViewModel` stays subscribed to the whole child list for the editor's entire
  lifetime, and any emission can overwrite the wrong child's row.** `init` calls
  `loadChildInfo()`, which collects `childInfoRepository.getAllChildInfo()` — a reactive
  Room `Flow` — for as long as the ViewModel lives, and every single emission unconditionally
  runs `_currentChildInfo.value = childInfoList.first()`, regardless of which child
  `AddEditChildInfoScreen` was actually opened to edit. The damage is not the prefill, it is
  the **save**: while a user is genuinely editing child B — not the first child in the
  household — any write that touches the `child_info` table re-emits the list and clobbers
  `_currentChildInfo` back to child A; `LaunchedEffect(currentChildInfo)` then silently
  repopulates the visible form with child A's data. The trigger needs no user error at all —
  a background Firestore sync tick is enough, and so is an unrelated edit to child A made
  from another screen. The medical-profile editor added in this round
  (`presentation/common/MedicalProfileEditor.kt`) made the blast radius concrete: the save
  button's snapshot-and-`copy()` base is `currentChildInfo`, so at save time the base is
  child A, and the write overwrites **child A's real row** — its id, `createdAt` and
  `createdByFirebaseUid` included — with a mix of stale values and whatever child B's form
  fields currently hold. The `isNewChild` guard added alongside that save
  (`base = currentChildInfo.takeIf { !isNewChild }`) does not help here: it only forces a
  fresh `ChildInfo` when adding a brand-new child, and `isNewChild` is `false` for a genuine
  edit of an existing child, so this path runs through unguarded exactly as before. The
  correct fix is for the editor to stop reading the head of a list it does not own: load the
  one child actually being edited by id and observe that, the way `loadChildInfoById` already
  does for the initial load — `ChildInfoDao.observeChildInfoById` (already wired through
  `ChildInfoRepository.observeChildInfoById`) is the right subscription for the whole screen
  lifetime, not `getAllChildInfo()`. `getAllChildInfo()`/`loadChildInfo()` belongs to the list
  screen that owns showing every child, not to an editor that owns exactly one. Left unfixed
  this round: it is pre-existing (present since before `ChildInfo.medicalProfile` did, and
  therefore before this task), and fixing the ViewModel's subscription strategy is a change of
  its own, not a rider on adding a field editor. Don't "fix" it by widening the `isNewChild`
  guard — that guard's job is stopping a brand-new child from saving on top of an existing
  row, and two *existing* children being confused for each other never involves `"new"` or a
  `null` id in the first place, so no version of that check touches this path.

## Localization (i18n) — July 2026, keep consistent

The app ships in 5 languages: **English (base `values/`), Czech, German, Russian,
Ukrainian** (`values-cs/`, `values-de/`, `values-ru/`, `values-uk/`). Rules:

- **Language selection**: device locale by default, plus a manual per-app override — the
  "Language" card in Settings (`presentation/settings/AppLanguage.kt`,
  `AppCompatDelegate.setApplicationLocales`). The choice is persisted by the
  `autoStoreLocales` manifest service and mirrored to the Android 13+ system per-app
  language setting. There is no DataStore/ViewModel state for it — AppCompat is the
  source of truth.
- **Infra invariants**: `MainActivity`/`QRScannerActivity` must stay `AppCompatActivity`
  (not `ComponentActivity`) and `Theme.CoPlanly` must stay an AppCompat theme — per-app
  locales silently stop working otherwise. `res/xml/locales_config.xml`, the `AppLanguage`
  enum, and the `values-*` folders must list the same locale set.
- **Adding a string** = add the key to the feature's base `values/<feature>_strings.xml`
  AND to all four locale variants of that file. Missing translations fall back to English
  at runtime. **Lint will not catch a missing one**: `MissingTranslation` is switched off
  outright in `app/build.gradle.kts` (`disable += "MissingTranslation"`), not demoted to a
  warning — a disabled check does not run, so it reports nothing under any severity. Verify
  locale completeness by grep instead, e.g. `git grep -c 'name="your_key"' -- app/src/main/res/values*/*.xml`,
  which should return five files and also catches a duplicate the lint check never would.
- In composables use `stringResource(...)`; for text consumed inside non-composable
  lambdas (snackbars, coroutines) capture the string in composable scope first. Language
  endonyms in the picker ("Čeština", "Русский", …) are `translatable="false"`.
- Dates/day/month names come from `java.time` formatters with the default locale —
  never from string arrays.
- There is no `values-en/` — base `values/` IS English; don't recreate it.

## Language conventions

- The user communicates in Russian; reply in Russian in chat.
- All repository content — code, comments, docs, commit messages — is **English**.
