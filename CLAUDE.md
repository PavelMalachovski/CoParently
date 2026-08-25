# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## What this project is

CoPlanly — an Android shared-calendar app for separated parents. Kotlin + Jetpack Compose
(Material 3), Clean Architecture with Hilt, Room as the offline-first source of truth,
Firebase (Auth/Firestore/FCM) for sync between the two parents, Google Calendar integration.
**No AI:** the Gemini subsystem was deleted in August 2026 (MON-7) — ~3,200 lines reachable from
no navigation graph, with the API key shipping in every APK. If AI returns it goes behind the
Cloud Function proxy (SEC-1), never with a key in the client. See `docs/AUDIT-2026-08.md` §6.

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
- **GitHub CI runs on every pull request, and on every push to `main`** — a push to a
  feature branch with no PR open is not built (`.github/workflows/ci.yml`, added
  August 2026 — this line used to say there was none). Six jobs: `changes` (a cheap gate,
  below), three Android ones — `build-test` (`assembleDebug` + `testDebugUnitTest` in a
  single invocation), `static` (`lint`, then `detekt`), `release` (`assembleRelease`, where
  R8 runs) — plus Cloud Functions and the Firestore rules suite against the emulator. They
  run **in parallel**; the Android three were one sequential job until the August 2026 CI
  pass, which is why a run took 13:22 for about 7 minutes of critical path. Two caveats, both
  deliberate and both tracked in `docs/BACKLOG.md` (**CQ-12**, **CQ-1**): **detekt reports but does not gate**
  (`continue-on-error`) until its baseline is regenerated locally, and there is **no
  instrumented migration job**, because `app/schemas/` stops at v14 while the database is at
  v25. Still run the build locally before pushing — CI is a backstop, not a substitute.
  After switching branches, prefer `clean` — stale Hilt/kapt stubs from another branch cause
  errors like "Could not find class file for '…Application'".
- **A docs/functions/rules-only pull request skips the Android jobs.** The `changes` job
  diffs against the base and sets one output; the three Android jobs are `if:`-gated on it.
  Two things not to get wrong. The ignore list is deliberately conservative — a path wrongly
  *on* it silently stops building real changes, which is far worse than a path wrongly off it
  costing a few free runner minutes — and `.github/workflows/**` is deliberately **not** on
  it, because editing the workflow is exactly when you want the build it describes to run. A
  gated-out job reports as *skipped*, which branch protection counts as passing; nothing is a
  required check today, so this is safe, but marking one required later means a docs-only PR
  merges on a skip rather than a build.
- **No Gradle invocation in CI passes `--no-daemon`** — `gradle/actions/setup-gradle` manages
  the daemon itself and asks you not to, and without one every invocation re-pays JVM and
  Kotlin-compiler startup. `org.gradle.caching=true` and a 4 GB heap live in
  `gradle.properties` and apply locally too; the build cache is local-only (there is no
  remote cache), so in CI it pays off on a re-run of the same branch, where `setup-gradle`'s
  per-job cache of `~/.gradle/caches` carries the previous run's task outputs forward.

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
- **How many children or pets a family has is derived, never stored** (FAM-1, Aug 2026). Nothing
  asks "one child or several", and no flag records the answer: the onboarding wizard's child and
  pet steps are repeatable lists that collect *names*, and everything downstream reads
  `children.size`. A stored count is a fact that goes stale the day a second child arrives or a
  pet dies, and would then need a settings toggle to correct; a derived one cannot disagree with
  the records. It is the same reasoning `FamilyKind` documents for reading an unanswered account
  as "show everything". The visible consequence, and the rule for every screen that grows a
  per-child affordance: **it appears at two, not at one.** A family with one child must see the
  screen they saw before — a picker for a set of one is design item 8 in miniature. The wizard
  was the last place in the app insisting on exactly one of anything (`ChildInfoScreen`,
  `PetsScreen` and `ContactDirectory` were already plural); what is still singular is the
  calendar, which carries no child reference at all — see **FAM-2/FAM-3** in `docs/BACKLOG.md`
  before adding one, because the shape is one `FamilyMemberRef` covering children *and* pets, not
  a `childId`.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## Architecture map

```
domain/    — models, repository interfaces, use cases, holidays, ReminderScheduler
data/      — Room (v25 + migrations), Firestore/Google clients, repository impls, sync
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
    receipt text or photo may be sent to a model or any other remote service without an
    explicit product decision. The rule outlived the AI subsystem on purpose: on-device OCR is
    a privacy asset worth keeping, not an accident of what happened to be wired up.
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
    schema v13, since superseded — the database is at v25), not a naive `LocalDateTime`, so two
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
14. **A delete is a tombstone, never a document removal** (CQ-3). `data/sync/Tombstone.kt` is
    the one definition: the client writes `deletedAtMillis` (epoch millis) and `deletedBy` onto
    the document with `update()` — never `set()`, which would replace the `createdByFirebaseUid`
    and `sharedWith` the read rules are keyed on and leave a tombstone the co-parent may not
    read. Room's `deletedAtMillis` on `events`/`expenses` (schema 25) is a **pending-tombstone
    outbox**: hidden from every read query, retried on each sync, and hard-deleted only once the
    remote write lands. Four things not to undo. **Do not reconcile by absence** — "delete what
    is not in the snapshot" takes the whole calendar the first time `sharedWith` narrows at
    unpair, a download window bounds the query (CQ-5), or a snapshot comes back partial.
    **Do not decide a deletion by timestamp**: `updatedAt` is a naive `LocalDateTime` with
    SEC-4's ordering defect, so a tombstone beats a concurrent edit by rule, deliberately —
    an event that should not exist is visible and can be deleted again, an edit that loses is
    gone. **Do not filter tombstones out of `getUnsyncedEvents`/`getUnsyncedExpenses`**, which
    are the outbox. And **do not shorten the 90-day sweep** (`sweepDeletedDocuments`): it is
    the deadline for a co-parent's phone to come back and collect the deletion, and sweeping
    early reintroduces exactly the bug. `FirestoreEventDataSource.deleteEvent` still removes a
    document outright and has exactly one legitimate caller — an event turned private has to
    leave Firestore with no trace.
15. **A push carries a type, never a sentence** (SEC-3). `data/remote/firebase/PushPayload.kt`
    is the vocabulary; `CoPlanlyMessagingService` writes the text from *its own* string
    resources and **drops a type it has no wording for**. Never reintroduce a `title`/`body`
    fallback for an unrecognised type — that fallback is the forgery, not a nicety, and
    `firestore.rules` refuses both keys from a client precisely so nothing legitimate needs
    one. Two halves, and both are load-bearing: the rule's **allow-list** of client types keeps
    `pairing_accepted`, `pairing_removed` and `chat_message` producible only by Cloud Functions
    (which write as admin and bypass rules), so a co-parent cannot announce a pairing that did
    not happen. Adding a type means four places agreeing — `PushPayload`, the rule's allow-list,
    `CoPlanlyMessagingService.PUSH_TEXT`, and the five `push_strings.xml` — and a type missing
    from any of them is a push that silently never appears. This is also why service-layer
    string extraction (**CQ-14**) was *not* a prerequisite: the string is read on the receiving
    device, which has a `Context` and all five translations.
16. **`sharedWith` is computed at upload time and never recomputed for a row already marked
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
17. **A save path never reads a `WhileSubscribed` StateFlow's `.value`.** Every ViewModel shares
    `ParentsSource`/`FamilyKindSource` with `SharingStarted.WhileSubscribed`, so in a ViewModel
    instance no screen has collected — which is exactly what a **form-only route** is — `.value`
    is still the initial value and always will be. `ExpenseViewModel.sharedWith` read
    `parents.value` to decide who a shared expense divides between, and the Add Expense screen
    collects `agreedRatio` but not `parents`: every expense was written naming only the payer, so
    the payer's month looked right and the co-parent's showed nothing owed at all. The cheap
    facts have suspend accessors for this — `ParentsSource.signedInSlot()` and
    `ParentsSource.coParentUid()`, both one Room row — and a save path must use those. The
    stream is for what the screen *renders*. Same reason a Settings dialog seeds from
    `FamilyKindSource.observeMine()` and not `observe()`: the union of both parents' answers is
    what the app *shows*, while the dialog *writes* this parent's row alone, so seeding it with
    the union made every checkbox a lie.

## Known issues / do not "fix" silently

**Check an entry against the code before acting on it.** Two entries in this section, and one
claim in `storage.rules`, have described defects that were already fixed or limits that were
never real — a reader following them would have re-fixed working code, or accepted a constraint
that does not exist. When an item here turns out to be stale, correct it in the same commit as
whatever you were doing; a stale "known issue" costs more than a missing one.

- **Deleting a child or a pet removes the Firestore document outright, which item 14 forbids.**
  `ChildInfoRepositoryImpl.deleteChildInfo` and `PetRepositoryImpl.deletePet` both call the data
  source's `.delete()` and both discard the `Result`. Two consequences, and they are the ones the
  tombstone rule exists to prevent: the co-parent's phone never learns of the deletion — nothing
  reconciles by absence, correctly — so the record stays on their device forever; and a refused or
  offline remote delete leaves the local row gone and the document alive, so the next download
  re-inserts it. Pre-existing and systemic across both record types, not something the multi-child
  work introduced — the August 2026 branch only made the child half *reachable*, by putting a
  Delete action on the editor where before there was none. The fix is the treatment
  `data/sync/Tombstone.kt` already defines for events and expenses: `update()` with
  `deletedAtMillis`/`deletedBy`, hide tombstoned rows from the read queries, hard-delete locally
  only once the remote write lands. It wants a Room column and a migration on both tables, which
  is why it is recorded here rather than folded into a bug-fix branch. Do not "fix" it by removing
  the Delete action — parity with pets is what was asked for.

- **A change request says "Sent" whether or not it left the phone.**
  `ChangeRequestRepositoryImpl.publish` catches everything and returns, leaving
  `syncedToFirestore = false`, and `RequestChangeViewModel` sets `Sent` unconditionally before
  the screen pops. `ChangeRequest.syncedToFirestore` reaches the domain model and **no** screen
  reads it, so there is no queued badge and no way to tell a request the co-parent has from one
  sitting in Room. The August 2026 outbox (`flushOutbox`, drained on every sync) means it does
  eventually go — this is a wording and visibility gap, not a loss — but "sent" is still a claim
  the app cannot make. `MessagesList` already renders the honest version for a message; a request
  should say "queued" the same way, with a string in all five locales.

- **A proposed split ratio cannot be withdrawn, and the proposer is told nothing.**
  `SplitRatioTransition.withdraw` exists and is unit-tested; nothing calls it.
  `FamilySettingsRepository` exposes `submitRatio`/`acceptProposal`/`declineProposal` only, and
  Settings renders the agreed ratio with no sign that a proposal of your own is pending — the
  banner is the *co-parent's* view. The sibling feature wires the whole shape
  (`CustodyModelRepository.withdrawProposal`, plus a Withdraw button on the inbox card); the split
  ratio wants the same. Until then do not delete `withdraw`: the gap is the missing UI, not the
  transition.

- **A ratio agreed before pairing reaches the pair silently.**
  `FamilySettingsRepository.publishCachedRatioIfMissing` writes `family_settings/{pairId}` with no
  `notifyPartner`, where `submitRatio`'s propose branch sends `PushPayload.SPLIT_RATIO_PROPOSED`.
  Deliberate as far as it goes — this is the *first* agreement, so there is no proposal to confirm
  and nothing for the co-parent to answer — but the effect is that a parent who set 70/30 in the
  wizard has it become the pair's agreement of record, priced onto every expense from that moment,
  and the co-parent learns of it only by opening Settings. The honest fix is a push type of its own
  — an agreement, not a proposal: "the split is now X/Y, set before you paired" — which is four
  places per CLAUDE.md item 15 and five locales. Do not "fix" it
  by routing the publish through `propose` instead: an unanswered proposal would leave the pair
  splitting evenly, which is the exact bug `publishCachedRatioIfMissing` was written to end.

- **`storage.rules` has never been deployed past its July 2026 state, and that is why attaching a
  photo to a pet fails.** The file in this repo covers `receipts/`, `event_images/`,
  `medical_photos/` and `pet_photos/`; the live bucket, on the evidence, still covers only the
  first two, so `pet_photos/**` falls through to `match /{allPaths=**} { allow read, write: if
  false; }` and every pet — and, silently, every medical — photo upload is refused. The client
  path is sound and was ruled out end to end. **The fix is an ops action nobody has taken:
  `firebase deploy --only storage`**, which also closes the still-unchecked box at
  `docs/REVIEW-2026-07-23.md:65`. Nothing catches this: `firebase.json` configures a Firestore
  emulator only, and Storage rules have no test coverage at all. The upload handlers now write a
  `Log.e` line so the next occurrence is at least diagnosable on a device — they reported only
  through Crashlytics before, which writes nothing to logcat.

- **The expense split is agreed per pair, and each expense is priced at the split in force when it
  was recorded.** `family_settings/{pairId}` (same derived id as `custody_models`) holds the agreed
  `momShareBasisPoints` and any pending proposal; `Expense.splitBasisPoints` is a **snapshot** of
  it. Do not "simplify" that by reading the current agreement when the balance is computed — the
  whole point is that renegotiating cannot re-price a month the two parents have already settled
  and argued about. A null on an expense means it predates the agreement and divides evenly, which
  is what it was. The ratio is also ignored while both parents still read the same slot, the same
  condition that leaves `ExpenseBalance.splitKnown` false: applying a slot-keyed share there would
  charge one parent the other's part.

- **`Expense.splitBetween` used to be empty on every row production ever wrote**, so
  `calculateExpenseBalance`'s guard never fired, `currentUserOwes` was always zero, and both
  parents were told at once that the other owed them their whole month's spend. `addExpense` now
  names both parents on a shared expense. The unit suite missed it for months because
  `ExpenseBalanceTest`'s fixture defaults `splitBetween` to both parents — a shape production never
  produced. Be suspicious of any fixture whose default is the thing under test.

- `Expense.currency` is a real per-expense field. A month mixing currencies is now summarised
  **per currency** (`calculateExpenseBalancesByCurrency` → one `ExpenseSummaryHeader` per currency
  on the Expenses screen; the Home "this month" tile joins per-currency subtotals). There is still
  no FX conversion between currencies (spec §10) — deliberately: totals stay honest within each
  currency rather than being normalised. Do not reintroduce a single cross-currency total.

- **A failed chat Firestore listener now reconnects, but only for a while.** Both mirror branches
  in `MessageRepositoryImpl` go through `reconnecting()` — `retryWhen` with exponential backoff,
  eight attempts, capped at a minute apart — before reaching the `.catch` that ends the mirror.
  That covers the case seen in production: on the first launch after install both listeners were
  denied ~0.5 s before `ensureConversation` created the conversation document, and the whole
  session then ran on local data while looking entirely healthy. **What is still open (CQ-8 in
  `docs/BACKLOG.md`):** an outage longer than the backoff still ends in that degraded state, and
  still lasts until the process restarts. `catch` *completes* the mirror flow, so
  `merge(mirror, local)` runs on Room alone afterwards, and `SharingStarted.WhileSubscribed`
  cannot restart it — `rememberChatUnreadCount()` in `NavGraph` holds an Activity-scoped
  `ChatViewModel` collecting `unreadCount` for the whole process lifetime, so the subscriber count
  never reaches zero. The structural fixes are awaiting `ensureConversation` before subscribing,
  or dropping that Activity-scoped collector. Don't "fix" it by removing the `.catch` — an
  uncaught failure in `viewModelScope.launch` terminates the process — and don't make the retry
  unbounded: a genuinely broken rule would then reconnect for the life of the process, and any
  test of the give-up path spins on the virtual clock instead of finishing.

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

- ~~**The calendar never renders `EventUiState.Error`.**~~ **Fixed** — and this entry described
  the defect for some time after it was gone. `CalendarScreen`'s `LaunchedEffect(uiState)` has an
  `is EventUiState.Error` branch raising a snackbar with a Retry action wired to
  `EventViewModel.refresh()`, which is the fix this entry used to prescribe, strings and all.
  Kept rather than deleted because the reasoning is worth finding: `EventViewModel` raises
  `Error` from eleven places, a failed *write* is the dangerous one (a parent drags an event, the
  optimistic UI moves it, the write fails, the next sync puts it back), and `Loading` is still the
  wrong thing to render on the grid — the query flips to `Loading` on every re-anchor and the grid
  would flicker.

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
  BuildConfig (`GOOGLE_CLIENT_SECRET` gradle property / env var). `GEMINI_API_KEY` is gone
  with the AI subsystem — don't reintroduce a model key in the client. Real secrets belong in
  `gradle.properties`/env vars only.
- User-facing strings produced **inside ViewModels/services** (e.g.
  `GoogleCalendarSyncState.message`, sync/status errors) are still hardcoded English —
  extracting them needs a resource-provider abstraction and is a tracked follow-up of the
  July 2026 localization pass. Don't inject `Context` into ViewModels ad hoc to "fix" one.
- Calendar range/day queries now match multi-day & overnight events by overlap
  (`getSingleEventsByDateRange` / `getEventsByDate`), not start date only.
- Unit tests for ChildInfo/Pairing/Settings/Sync ViewModels were once removed as stale (they
  targeted long-gone APIs). **Three of the four are back**: `ChildInfoViewModelTest`,
  `PairingViewModelTest` and `SyncServiceTest` all exist and run. Settings still has none —
  that is the one to write when touching it.

- **`ChildInfoViewModel`'s editor state is loaded by id, never from the head of a list.**
  `loadChildInfo()` serves the list screen and touches nothing else; `loadChildInfoById()` is the
  only writer of `currentChildInfo`, and it cancels a previous observation before starting the
  next. This used to be the other way round — `init` collected `getAllChildInfo()` for the
  ViewModel's whole lifetime and set `_currentChildInfo = childInfoList.first()` on **every**
  emission — so while a parent edited child B, any write touching `child_info` (a background sync
  tick was enough) reset the state to child A and repopulated the visible form. The damage was at
  save: the snapshot-and-`copy()` base had become child A, so the write landed on **child A's real
  row**, id and `createdAt` included, carrying child B's field values. Keep the split: a screen
  that shows every child reads the list it already holds (`ChildInfoScreen` reads
  `state.childInfoList`), and an editor that owns exactly one child observes that one by id.

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
