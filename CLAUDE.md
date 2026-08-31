# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## What this project is

CoPlanly — an Android shared-calendar app for separated parents. Kotlin + Jetpack Compose
(Material 3), Clean Architecture with Hilt, Room as the offline-first source of truth,
Firebase (Auth/Firestore/FCM) for sync between the two parents, Google Calendar integration.
**No AI:** the Gemini subsystem was deleted in August 2026 (MON-7) — ~3,200 lines reachable from
no navigation graph, with the API key shipping in every APK. If AI returns it goes behind the
Cloud Function proxy (SEC-1), never with a key in the client. See `docs/AUDIT-2026-08.md` §6.

**The plan of record is `docs/ROADMAP.md`** — one document, merged on 2026-08-25 from
`docs/BACKLOG.md` and `docs/CoPlanly/MVP_phases.md`, both now deleted (`.cursor/roadmap.md` is the
historical original plan). MVP 1 **and** MVP 2 are complete: §2 re-baselines all three phases
against the code rather than against memory, which is what this line failed to do for months while
it said "MVP 2 is next". Two things to read before planning anything: **§1**, which says for every
open item whether a cloud session, a CI job, or a machine with an Android SDK and a phone can do
it, and **§10**, the dependency order.

**The latest full audit lives in `docs/AUDIT-2026-08.md`** (`AUDIT-2026-07.md` is the previous
one); `docs/ROADMAP.md` §3 is the live version of its §5. The app still cannot be published: no
hosted privacy policy, no signing config, no Play listing. Two claims that paragraph used to make
are **no longer true** and were corrected here rather than left to mislead — in-app account
deletion ships (server-side teardown plus a local wipe, PR #68), and the `applicationId` is now
`app.coplanly`, decided while it still could be. What is still permanent at first upload is that
id, so REL-1's console half has to be finished before anything is uploaded.

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
    **The custody band runs to the edge of the grid, borrowed days included** (Aug 2026, second
    pass). Those cells used to take the base and no overlay at all, so a band stopped mid-row and
    the reader had no rule to infer the rest of the pattern from — the same complaint the weekend
    fix answered, one layer up. They carry the band and the handover diagonal now, at
    `ADJACENT_MONTH_TINT_SCALE` of the custody alpha, because the grid must still say which month
    it is showing. What a borrowed cell still refuses is everything you would *act on*: the
    holiday tint, the proposal preview, the swap arrows, the long press. That is the line — a
    pattern crosses the month boundary, a thing you would answer does not.

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
  deliberate. **detekt gates again** as of CQ-12 — do not add `continue-on-error` back to turn a
  red build green; fix the finding, or regenerate the baseline through the Regenerate workflow so
  that accepting debt is a visible commit. There is still **no instrumented migration job**
  (**CQ-1**), and it buys nothing until v34: `app/schemas/` now holds 14 and 33 with the versions
  between them irrecoverable, so there is no earlier version to migrate *from*.
  `DatabaseSchemaExportTest` is what stops the gap growing — it fails the build if the database
  version outruns the newest exported schema.

  **A seventh workflow exists and is not part of CI**: `.github/workflows/regenerate.yml` runs
  `detektBaseline` and exports the Room schema, then commits both back to the branch it ran on.
  It exists because those are the two artefacts only a machine with an Android SDK can produce,
  and it is **manual on purpose** — regenerating a baseline accepts every violation that exists
  at that moment. Trigger it with `workflow_dispatch` from `main`, or, on a branch that has not
  merged, by touching `.github/regenerate-request`.

  Still run the build locally before pushing — CI is a backstop, not a substitute.
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
  short-circuits before the `get()`), with expiry compared against `request.time`.
  **The grant names one family, not one person** (M-6, Aug 2026): it carries the `familyId` it
  was issued for, and `isCalendarFriendOf` requires the event's own `familyId` to match *and* its
  creator to be one of that family's two parents. Keying on the creator alone is what leaked —
  a grandmother admitted by Alice-and-Bob matched every event **Alice** created, including the
  ones in Alice's family with Carol. Two consequences for anything built on top: a friend's list
  query must filter `whereEqualTo("familyId", …)` — the old
  `whereIn("createdByFirebaseUid", [a, b])` shape is now rejected outright, pinned by a test that
  says so — and a grant or an event with no `familyId` admits nothing until
  `backfillRecordFamilyIds` has run. Do **not** soften that with a fallback to `familyParents`
  alone: it restores exactly the check M-6 removed, which is how the same leak survived once
  already in `expenses`.
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
  `PetsScreen` and `ContactDirectory` were already plural). The calendar caught up in FAM-3:
  `Event.forMembers` names the children and pets an event is about, and the grid has a filter
  strip that appears at two. What it still does not do is mark an *individual chip* — see
  **FAM-5** before adding one, because the two obvious channels are both spoken for.
- **Who a record is about goes through `domain/family/FamilyMemberRef`** (FAM-2, Aug 2026) — one
  file defining the stored vocabulary, like `Tombstone.kt` and `PushPayload.kt`. Children *and*
  pets, because a vet's bill is an expense and the `Expense.childId` it replaced had nowhere to
  put it. `Expense.forMembers` and `Budget.forMembers` are lists of it; the wire form is a JSON
  array of the prefixed strings (`"child:abc"`, `"pet:xyz"`), never a Gson serialisation of the
  type — R8 rewrote a Gson model's field names once already and it shipped. Three things not to
  invert. **Naming nobody is not naming everybody:** an untagged record shows in the unfiltered
  list and under no chip, or every chip shows the same untagged pile and the filter says nothing;
  a budget naming members is charged only what names them back. **An unrecognised reference
  survives a round trip** as `FamilyMemberRef.Unknown`, so an older build cannot erase a tag a
  newer one wrote — dropping it on read is data loss, not a missing feature. And **a member is a
  name, never a colour**: pink and blue are the parent slots, teal is a calendar friend, neutral
  grey is the weekend, and a fifth colour channel breaks what `DayCellFills.kt` protects. The
  `childId` columns survive on `ExpenseEntity`/`BudgetEntity`, dead and documented: dropping a
  SQLite column needs a table rebuild, and while `MIGRATION_12_13` is one — proved row by row by
  `CoPlanlyDatabaseMigrationTest`, *because* `app/schemas/12.json` exists for
  `MigrationTestHelper` to build from — `app/schemas/` stops at v14 (**CQ-1**), so no such test
  can be written for a v26 database.
  `Event.forMembers` joined them in FAM-3 (schema 28) and adds one rule of its own: **it is not
  `parentOwner`.** That stays a custody slot — whose *day* an event falls on does not change
  because it is one child's dentist appointment and not the other's — and the two must never be
  collapsed. On the wire, `data/sync/EventDocument.kt` is the one place the events format is
  defined in both directions, so `SyncService`'s two event maps convert through it rather than
  repeating the conversion (item 5 above).
- **Telemetry has exactly one switch, and a provider may only ever close it** (REL-5, Aug 2026).
  `data/telemetry/TelemetryConsentApplier` is the sole caller of `setAnalyticsCollectionEnabled`
  and `setCrashlyticsCollectionEnabled`; `FirebaseModule`'s providers pass `false` and nothing
  else touches either. That is not tidiness — before it, `FirebaseModule` applied
  `BuildConfig.ENABLE_CRASHLYTICS` and `CoPlanlyApplication.onCreate` then called
  `setCrashlyticsCollectionEnabled(true)` unconditionally a moment later, so the debug flag was
  overruled on every launch and nothing failed. **A gate any other line may overrule is not a
  gate.** Three more things not to undo. Collection needs the build flag **and**
  `TelemetryConsent.GRANTED` — `telemetryCollectionEnabled` ANDs them, and neither may stand in
  for the other; a granted consent must not switch a debug build back into the production
  project. The manifest's `firebase_analytics_collection_enabled=false` and
  `firebase_crashlytics_collection_enabled=false` are what stop collection *before* any app code
  runs, and deleting either reopens that window silently — while
  `firebase_analytics_collection_deactivated` is a **different** knob that must stay `false`,
  because `true` disables Analytics permanently and no runtime call can undo it. And
  `UNANSWERED` is a third state on purpose: "said no" and "was never asked" collect the same
  nothing, but only one of them still owes the user a question.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## Architecture map

```
domain/    — models, repository interfaces, use cases, holidays, ReminderScheduler
data/      — Room (v33 + migrations), Firestore/Google clients, repository impls, sync
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
8. **Holidays come from the parent's country** (MON-13). `domain/holidays/HolidayCountry` maps a
   stored `users.countryCode` (schema 33, `NOT NULL DEFAULT 'CZ'`, so every pre-existing account
   is Czechia) to a `HolidayProvider`; the calendar reads that, never a provider directly. Three
   rules. **A country with no table draws no holidays** — five of the six do not have one, and
   the picker says so on the row, because drawing Czech holidays for a German family is the bug
   this replaced and drawing nothing silently would be design item 8's forbidden affordance.
   **The country is a property of the person, not the family**: two separated parents can live in
   two countries. The cost is that the school-vacation strips follow the viewer too, which is
   recorded rather than hidden. And **`Holiday.nameLocal` carries `localLanguage`** — the UI shows
   the local name when the device language matches and English otherwise, which is what
   `MonthView` already did, hardcoded to `"cs"`. `CzechHolidays` itself is unchanged: pure,
   computed, Easter via computus (now shared as `gregorianEasterSunday`), the nationwide MŠMT
   vacations, and the district-dependent spring break still intentionally excluded.
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
    schema v13, since superseded — the database is at v33), not a naive `LocalDateTime`, so two
    parents in different time zones agree
    on what a mark means and on when a message was sent. The Firestore field keeps its name
    (`timestamp`) and the read path still accepts a legacy ISO string, so a co-parent on an
    older build stays readable. Deliberately *not* changed: `Event`, `Expense`, `Budget`
    and `ChildInfo` dates, where a naive local time is often the right model — whether a
    custody handover follows the child's zone or the viewer's is an unmade product
    decision, not an oversight. **`CustodyModelEntity.lastModifiedAtMillis` made the same move
    (SEC-4, schema 29)**, and for a sharper reason than chat's: it is not merely displayed, it
    decides which phone's schedule survives. Its wire form is the one to copy when the same
    question comes up again — see `domain/custody/CustodyTimestamp.kt`, which explains why the
    Firestore field kept both its name *and* its type and only changed the zone it expresses.
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
18. **`familyId` names a relationship; nothing reads it yet, and that is deliberate.** Every
    shared record — event, expense, budget, child, pet, change request — carries the
    `FamilyKey.of(myUid, partnerUid)` id of the co-parenting relationship it belongs to (Room
    schema 30). It is stamped **at create, never on the sync path**, for the reason
    `createdByFirebaseUid` is: a private event never syncs and an offline device leaves
    `syncedToFirestore` false indefinitely, so a field written only when a record uploads stays
    null on exactly the rows that most need it. It is **never re-derived** — a re-pairing must
    not move a child, an expense or a settled month into a different household — and **null is a
    value**, meaning "mine alone", which is what every record written before its owner paired
    honestly is. `FamilyIdBackfill` turns those nulls into a family once there is one to name,
    locally, once per co-parent; it deliberately does **not** clear `syncedToFirestore`, because
    re-queuing all six tables would put the co-parent's own downloaded rows into this device's
    outbox where the create rule rejects them forever.
    The field is not in `firestore.rules` and no query filters on it. That is the sequencing, not
    an omission: a read path keyed on `familyId` has to pin the field against a non-creator writer
    the way `sharedWith` is pinned, and pinning it while the two phones are still catching up
    denies the app's own writes — a device whose backfill has not run writes `familyId ?: ""` over
    a stamped document. See `docs/DESIGN-multi-family.md` M-2 for why the relaxed version of that
    pin is not a pin at all. The switch is M-4, where `familyId` **replaces** `sharedWith` rather
    than joining it, after a server-side pass has stamped the documents themselves.
19. **The parent slot and `caresFor` belong to a relationship, not to a person** (M-3, Aug 2026).
    Both live on `families/{id}` as maps keyed by uid — `slots` written only by Cloud Functions,
    `caresFor` written by each parent for **their own key only**, which `firestore.rules` enforces
    with two nested `hasOnly` checks. The asymmetry is the point: a slot decides whose events are
    whose, so a parent who could set their own would take the co-parent's colour and re-point what
    `parentOwner` means across the calendar, while `caresFor` only decides which sections are
    drawn — exactly the authority a parent already had over their own profile. Both sides of the
    `caresFor` diff read through `.get('caresFor', {})`, because a family created before the field
    existed carries no such key and a missing key is an evaluation error, not null.
    As with item 18, **the client writes the new location and still reads the old one.**
    `UserRepositoryImpl.updateUser` mirrors `caresFor` onto the family from the one choke point a
    parent's answer changes through — do not add a second call site in Settings or the wizard —
    and `users/{uid}.caresFor` keeps being written until M-5 so a co-parent on an older build
    still sees the change. Nothing reads the family's copy yet: until the switcher exists a person
    has one family, so a family-scoped slot *is* the profile slot, and the read switch would buy
    no behaviour while adding a Firestore listener to `ParentsSource`/`FamilyKindSource` — shared
    flows this project has already had to optimise twice for that. `ParentSlotMigrator` cannot
    take its `familyId` scope yet either: a row whose backfill has not run carries null, so
    scoping the re-stamp on it would silently skip exactly the rows that need it.
20. **The database file is encrypted, and the passphrase is the one piece of state that must never
    be re-minted** (SEC-2, Aug 2026). Room opens through SQLCipher: `EncryptedDatabase` builds the
    open helper and converts an existing plaintext file on the way, `DatabaseKey` holds 256 random
    bits wrapped by `EncryptionManager` under a Keystore key, and `SqlCipherMigration` decides —
    from the files on disk, never from a flag — where a killed process left off. Four things not to
    undo. **The passphrase does not go in `EncryptedPreferences`**, whose recovery clears the store
    and mints a fresh keyset: correct for the OAuth token it was written for, and here it would
    hand out a different key on the next launch and leave the database unopenable. **It is written
    with `commit`, not `apply`** — it has to be on disk before anything is encrypted with it.
    **The plaintext file is deleted only after a verified encrypted copy exists beside it under a
    different name**, which is the whole safety argument; a failure that leaves it intact falls
    back to opening it unencrypted and retries next launch, because crashing makes the app unusable
    and wiping trades data the user has for a property they did not have a moment ago. And
    **field-level encryption is not the smaller version of this**: `child_info` syncs and the key is
    device-bound, so an encrypted field arrives at the co-parent's phone as ciphertext their
    Keystore cannot open — `SensitiveMedicalData` was deleted for saying otherwise. The SQLCipher
    calls have **never run**: there is no instrumented job (CQ-1) and no Android SDK in the sessions
    that wrote them, so the first launch on a device holding real data is an acceptance step.

## Known issues / do not "fix" silently

**Check an entry against the code before acting on it.** Two entries in this section, and one
claim in `storage.rules`, have described defects that were already fixed or limits that were
never real — a reader following them would have re-fixed working code, or accepted a constraint
that does not exist. When an item here turns out to be stale, correct it in the same commit as
whatever you were doing; a stale "known issue" costs more than a missing one.

- ~~**Deleting a child or a pet removes the Firestore document outright.**~~ **Fixed (CQ-19,
  schema 32.)** Both now take the treatment `data/sync/Tombstone.kt` defines for events and
  expenses, and the four rules item 14 states apply unchanged. Two things specific to these two
  tables are worth knowing before touching them. **`SyncService` syncs `child_info` as well as
  the repository does**, so the outbox split had to be made in both places — sending a pending
  tombstone through `upsertChildInfo`, which is a `set()`, would rewrite the document from a row
  that exists only to record its own deletion and resurrect the child on both phones. And
  **`getChildInfoById`/`getPetById` deliberately still return a tombstoned row**, mirroring
  `EventDao.getEventById`: the sync path needs "there is a row this device has deleted" and
  "there is no such row" to be different answers, so the filtering for a *user's* question
  happens at the repository boundary. The hard-delete methods on both Firestore data sources
  were removed rather than left beside the tombstone writers, since neither had a caller left —
  unlike `FirestoreEventDataSource.deleteEvent`, which keeps one (an event turned private).

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
  `docs/ROADMAP.md`):** an outage longer than the backoff still ends in that degraded state, and
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

- ~~**The shared custody schedule orders the two phones' writes by a naive local date-time.**~~
  **Fixed (SEC-4, schema 29).** `CustodyModelEntity.lastModifiedAtMillis` is epoch millis and
  `CustodyModelRepository.isNewer` is a `>` on two of them, so two parents in different zones
  order their writes by real time. It mattered more than a displayed date: the side `isNewer`
  judges newer is not merely kept, it is **re-pushed over the other**, so the wrong schedule
  could win *and overwrite*.
  **Read `domain/custody/CustodyTimestamp.kt` before touching the wire form**, because the two
  obvious ways to carry an instant are both wrong here and the file says why. The Firestore
  field keeps its name *and* its ISO-string type, and only the zone it expresses changed, to
  UTC. Changing the type would leave a co-parent on an older build reading a blank — and a
  blank compares equal to their last dismissal, so every future change would go silently
  un-announced, which is the one failure this product must not have. Adding a numeric field
  beside it would put a new key in `affectedKeys()`, and `firestore.rules` gates a proposal or
  swap write with `hasOnly([...])` — the first such write from an upgraded build would be
  denied outright. Widening those lists is not the way out: `lastModifiedAt` is absent from
  them precisely so a swap cannot re-date the document and win every later comparison.
  Two things that still hold: a value written by an older build carries no offset to recover,
  so reading it as UTC is wrong by that device's offset — irreducible, and no worse than it
  already was; and `saveReslotted`/`archiveRejected` still keep the stored dates on purpose,
  because re-dating them makes this device win every comparison forever
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
