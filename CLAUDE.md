# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## What this project is

CoPlanly — an Android shared-calendar app for separated parents. Kotlin + Jetpack Compose
(Material 3), Clean Architecture with Hilt, Room as the offline-first source of truth,
Firebase (Auth/Firestore/FCM) for sync between the two parents, Google Calendar integration,
Gemini for AI features.

**The authoritative roadmap is `docs/CoPlanly/MVP_phases.md`** (not `.cursor/roadmap.md`,
which is the historical original plan). MVP 1 is complete; MVP 2 (receipts, change requests,
dashboards) is next. The latest full audit lives in `docs/AUDIT-2026-07.md`.

## UX/UI overhaul (July 2026 design review) — implemented, keep consistent

Direction agreed after a live walkthrough and shipped on `feature/ux-overhaul`.
When touching the UI, keep these invariants:

1. **Bottom navigation bar** (Home / Calendar / Chat / Expenses) is the top-level
   navigation — see `presentation/navigation/BottomNavDestination.kt`. It shows only on
   those routes (`BottomNavDestination.topLevelRoutes`); detail screens hide it and keep
   an up-arrow. **Settings is NOT a tab** — it opens from a gear action in each top-level
   screen's top bar and is a detail screen (`onNavigateUp = popBackStack`, bottom bar
   hidden). Budgets open from an Expenses top-bar action. `QuickActionsBottomSheet` was
   dead code and is gone.
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
- Parent color semantics are product-level: **Mom = pink, Dad = blue** — do not repurpose.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## Architecture map

```
domain/    — models, repository interfaces, use cases, holidays, ReminderScheduler
data/      — Room (v9 + migrations), Firestore/Google/AI clients, repository impls, sync
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
    `FirestoreEventDataSource.observeEventsForParents()` — instead of reading the whole
    `expenses` collection. Also keep the rule's field names in sync with what the writer
    actually sets: the expenses rule used to reference a `sharedWith` array that
    `ExpenseRepositoryImpl.addExpense()` never writes (the model shares expenses via the
    `partnerId` pairing relationship, not a per-document list), so the co-parent's own
    expenses were unreadable even by document id until the rule was changed to
    `isPartnerOf(resource.data.createdByFirebaseUid)`. A `whereIn`/`whereEqualTo` +
    `orderBy` combination on different fields also needs a composite index
    (`firestore.indexes.json`) — Firestore's error message links directly to the fix.

## Known issues / do not "fix" silently

- `Expense.currency` is a real per-expense field. A month mixing currencies is now summarised
  **per currency** (`calculateExpenseBalancesByCurrency` → one `ExpenseSummaryHeader` per currency
  on the Expenses screen; the Home "this month" tile joins per-currency subtotals). There is still
  no FX conversion between currencies (spec §10) — deliberately: totals stay honest within each
  currency rather than being normalised. Do not reintroduce a single cross-currency total.

- `firestore.rules` (strict) was realigned with the real document schema (ISO **string**
  dates, presence-based key validation, `change_requests`/`expenses` collections added,
  over-strict `lastModifiedBy`/`canModify` gates dropped) so it no longer rejects the app's
  own writes, and now also covers `invitations`, `custody_schedules`, `conversations` and
  `messages` for co-parent pairing and chat. It was deployed live to `coparently-a39c9`
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
  mismatches, both left as-is because neither is reachable in production:
  - `FirestoreMedicalDataSource` (`medicalRecords`, `allergies`) and
    `FirestoreEducationDataSource` (`grades`, `schoolEvents`) have no rule coverage, but
    `MedicalRepositoryImpl`/`EducationRepositoryImpl` are never bound in
    `RepositoryModule` and no ViewModel/UseCase references either interface — dead code
    with the same shape as the `CoParentPairingService` Task 11 deleted. Decide (delete,
    or wire up + add rules) before anyone binds them; don't add rules for unreachable
    collections speculatively.
  - `custody_schedules` has a rule block but no Firestore data source anywhere touches
    it — `CustodyScheduleEntity`/`CustodyScheduleDao` are Room-only (the table name just
    happens to match the rule's collection name). The rule is dead, not dangerous;
    left in place rather than removed mid-pairing-feature-work.
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
  at runtime (the `MissingTranslation` lint check is a warning, not an error).
- In composables use `stringResource(...)`; for text consumed inside non-composable
  lambdas (snackbars, coroutines) capture the string in composable scope first. Language
  endonyms in the picker ("Čeština", "Русский", …) are `translatable="false"`.
- Dates/day/month names come from `java.time` formatters with the default locale —
  never from string arrays.
- There is no `values-en/` — base `values/` IS English; don't recreate it.

## Language conventions

- The user communicates in Russian; reply in Russian in chat.
- All repository content — code, comments, docs, commit messages — is **English**.
