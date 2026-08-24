# CoPlanly — backlog

Everything known to be missing, broken or worth improving, in one place. Sourced from
`docs/AUDIT-2026-08.md` (the full reasoning behind most items lives there, under the § numbers
cited), from `CLAUDE.md`'s "Known issues", and from what CI found on its first four runs.

Last updated: 2026-08-24. #68, #69, #70 and #71 are merged; the items marked **DONE** below landed after
them, in #70, and are listed rather than deleted so the reasoning stays findable. **DONE** means
merged or awaiting review in #70 — not verified on a device, which for anything visual is a
different claim (see **REL-7**).

## How to read this

**Every item has a stable id** (`REL-3`, `SEC-2`, `CQ-7`, `UX-11`, `MON-5`). Use it in commit
messages and PR titles, so an item can be traced from the plan to the diff without matching
prose.

| Priority | Means |
| --- | --- |
| **P0** | Ships broken, loses data, or blocks the release outright. Do before anything else. |
| **P1** | A user hits it in normal use and cannot work around it. |
| **P2** | Real, but survivable and not on the launch path. |
| **P3** | Hygiene. Do it while touching the area anyway. |

Sizes are **S** (a day or less), **M** (a few days), **L** (a week or more).

The sections are the five axes: **[REL] release blockers**, **[SEC] security**,
**[CQ] code quality and platform**, **[UX] design and usability**, **[MON] monetisation and
product**. §7 orders them into an actual sequence — read that one if you only read one.

---

## 1. [REL] Release blockers — the app cannot be published until these are done

None of these is engineering. They are decisions, accounts, and a lawyer.

### REL-1 · **decided, half done** · `applicationId` is now `app.coplanly`

Decided and changed in code while it still could be. After the first Play upload an
`applicationId` can never change — a different one is a different app, with no upgrade path for
anyone who installed the first. The old id said `com.coparently.app` while the product is
CoPlanly and the deep-link scheme is `coplanly://`.

`namespace` stays `com.coparently.app` on purpose: it is the Kotlin package and therefore where
`R` and `BuildConfig` are generated. Renaming it would touch every file in the tree for no
user-visible gain, and the two are allowed to differ.

- [x] Change `applicationId` in `app/build.gradle.kts`.

**The rest needs the consoles, and until it is done a local build fails.** That is deliberate,
not a mistake: the Google Services plugin matches `google-services.json` on the package name and
will report *"No matching client found for package name 'app.coplanly'"*. CI is unaffected —
`google-services.json` is gitignored, so the plugin is not applied there.

- [ ] **Firebase console** → project `coparently-a39c9` → Add app → Android → package name
      `app.coplanly`. Register it alongside the existing app rather than deleting that one;
      nothing has shipped, but keeping it costs nothing and deleting it is irreversible.
- [ ] Download the new `google-services.json` and replace `app/google-services.json`. The file
      can hold both clients, so one download covers it.
- [ ] **Google Cloud console** → APIs & Services → Credentials → the Android OAuth client used
      for Calendar: set the package name to `app.coplanly` and re-enter the debug SHA-1
      (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass
      android -keypass android`). Google Sign-In and the Calendar scope both stop working
      otherwise, and the failure looks like a generic sign-in error rather than a config one.
- [ ] Add the **release** SHA-1 too, once **REL-2** produces a keystore.
- [ ] Re-check that pairing, guest and chat deep links still open the app. They use a custom
      scheme rather than the applicationId, so they should be unaffected — confirm rather than
      assume.
- [ ] Uninstall the old build from any test device before installing the new one: to Android
      these are two different apps and both will sit on the launcher otherwise.

### REL-2 · P0 · Signing configuration and the keystore

- [ ] Generate a release keystore.
- [ ] **Back it up in two places.** Losing it means the app can never be updated again — not
      re-signed, not recovered, not appealed. The single most irreversible item here.
- [ ] Add a `signingConfig` reading passwords from `~/.gradle/gradle.properties` or the
      environment, never a tracked file — the pattern `GOOGLE_CLIENT_SECRET` already uses.
- [ ] Decide whether to enrol in Play App Signing.

### REL-3 · P0 · Deploy the rules and the functions

Everything server-side from both audits is **inert until this runs** — including the fix for a
live full-calendar disclosure (audit §2.1).

- [ ] `firebase deploy --only firestore:rules`
- [ ] `firebase deploy --only functions` — `deleteAccount` and real invitation email do not
      exist in production until then.
- [ ] Set `functions/.env`: `SENDGRID_API_KEY`, `INVITE_FROM_EMAIL`, `INVITE_FROM_NAME`.
      Until they are set, invitations record `emailDelivery: 'not_configured'` and are not sent.
- [ ] Verify the sending domain (SPF/DKIM) with the provider, or invitations land in spam —
      which looks identical to not being sent.
- [ ] **Decide the Firestore region.** An EU region makes the whole GDPR story simpler and
      cannot be changed once data exists. Check what `coparently-a39c9` uses today.
- [ ] Sign a DPA with Google covering Firebase, and Gemini if AI ever ships.

### REL-4 · P0 · Legal documents: review, host, link

Drafts are in `docs/legal/`. They are drafts. **Have a lawyer read them before publishing** —
this app processes a child's health data, which is special-category data under GDPR Art. 9,
and no template survives that unread.

- [ ] Fill every `{{PLACEHOLDER}}` in `PRIVACY-POLICY.md` and `TERMS-OF-SERVICE.md`:
      controller identity, address, contact.
- [ ] Legal review.
- [ ] Host both at stable URLs. Play requires the privacy-policy URL in the listing.
- [ ] Publish a **web account-deletion page**. Play requires a deletion route that works
      without the app installed; the in-app path alone does not satisfy it.
- [ ] Link both from Settings once the URLs resolve. Deliberately not wired yet: a row
      pointing at a dead URL is exactly the affordance-promising-nothing that design rule #8
      forbids.

Note this unblocks **CQ-16** and **UX-19** too — all three want the same domain.

### REL-5 · P0 · Analytics consent for the EU

`ENABLE_ANALYTICS` / `ENABLE_CRASHLYTICS` are honoured per build type, but a release build
still collects by default. An EU launch needs a consent gate, not a build flag.

- [ ] First-run consent screen, defaulting to **off**, persisted, reachable again from Settings.
- [ ] Wire it to `setAnalyticsCollectionEnabled` / `setCrashlyticsCollectionEnabled` at
      **runtime**, not only at injection time.

### REL-6 · P0 · Play Console

- [ ] Data Safety declaration — answers derived from the real schema are drafted in
      `docs/legal/DATA-SAFETY.md`. Check them against the code before submitting; a wrong
      declaration is a policy violation, not a typo.
- [ ] Store listing, screenshots, feature graphic. Czech first, English second.
- [ ] Content rating questionnaire.
- [ ] Closed testing track with **real co-parent pairs** — this product cannot be tested by
      one person, and the failure modes only appear across two devices.

### REL-7 · P0 · Prove R8 on a device — the one test CI cannot run

- [ ] Install a **release** build, save a child's medical profile, confirm it reaches the
      co-parent non-empty.

A green `assembleRelease` proves the build survives shrinking. It does **not** prove Gson still
finds its field names afterwards, which is the defect (audit §2.8) the new keep rules in
`proguard-rules.pro` were written for, and which had already shipped once. Nothing but a real
APK answers this.

---

## 2. [SEC] Security

The August audit closed fifteen findings, four of them critical. What follows is what it
deliberately did **not** close, plus what has appeared since. Full reasoning in audit §3.

### SEC-1 · P0 · L · Cloud Function proxy — one build closes three holes

The single highest-value piece of infrastructure left. Audit §3.1, §3.2, §6.2.

1. **Cloud Storage** — every rule is `request.auth != null`. Any signed-in CoPlanly user who
   learns an object path can **overwrite or delete** it: a receipt, an event photo, a
   photograph attached to a child's medical record. Paths are not secrets — they are built
   from ids a co-parent has held, and an ex-partner's local Room copy survives both sign-out
   and the unpair sweep. `storage.rules` documents this at length; read the block comment
   before touching it. Not patched with an owner check on purpose: **both** parents
   legitimately manage the same files, so a uid check would deny the co-parent a deletion the
   app itself offers them.
2. **OAuth token exchange** — the Google client secret ships in every APK, with no PKCE.
   Carried over unfixed from the July 2026 audit.
3. **Model calls, if AI ever returns.** The Gemini key used to ship in the APK too; the
   subsystem and the key are gone (**MON-7**), so this is now a precondition rather than a live
   hole — nothing goes to a model again without the proxy in front of it.

One proxy, resolving the caller against Firestore, answers all three. It also makes per-user rate
limits possible at all, and turns prompts and the model version into server-side config instead
of an app release.

### SEC-2 · P1 · M · Room is not encrypted at rest

`EncryptionManager` (AES-256-GCM, Keystore) exists, is correct, and **is not applied to the
database**. A child's medical profile, the full chat history and every expense sit in plain
SQLite. SQLCipher plus a migration, or — as a smaller first step — field-level encryption of
the medical profile alone. Audit §3.3.

### SEC-3 · P1 · S · Notification text is composed on the client

So a push can claim to be anything. Length is now bounded (audit §2.7); composition is not.
Blocked behind CQ-14 (service-layer strings are still hardcoded English, so moving composition
server-side needs the localisation story settled first).

### SEC-4 · P2 · S · `CustodyModelEntity.lastModifiedAt` is a naive local date-time

Two phones 2–3 time zones apart can have the **wrong side win and overwrite** the shared
custody schedule. `CLAUDE.md` documents the hazard and why it was accepted; the fix is epoch
millis plus a Room migration, a legacy-ISO read path for co-parents on older builds, and a
migration test — the same move `Message.sentAtMillis` already made.

Promoted from "someday" the moment anything is exported for legal use: see **MON-4**, which
depends on whose clock orders writes.

### SEC-5 · P3 · S · `androidx.security:security-crypto` is on an alpha

`1.1.0-alpha06`, holding OAuth tokens in production, on a branch that is effectively frozen.
Decide: pin and document, or move off it.

---

## 3. [CQ] Code quality, correctness and platform

### CQ-1 · P0 · M · Restore the Room schemas (v15–v24)

`CoPlanlyDatabase` is at `version = 24`; `app/schemas/` stops at `14.json`. The files were
never committed, so they cannot be recovered from git — they must be regenerated. Every
migration test above v14 fails with *"Cannot find the schema file in the assets folder"*,
which means **migrations 15→24 have never run against real SQLite**. `DatabaseModule`
deliberately does not fall back to destructive migration above v4: a broken migration is a
**crash on launch** for a user with real data, not a wipe.

- [ ] Regenerate per version (`./gradlew kaptDebugKotlin` at each version-bump commit), or
      accept the gap, export 24 only, and document the untested migrations.
- [ ] Write the missing tests for 21→22, 22→23, 23→24 — all three shipped in `versionCode 2`
      untested (**CQ-2**).
- [ ] Then add the instrumented job to CI. It is deliberately absent from
      `.github/workflows/ci.yml` today because it would be red from its first run.
- [ ] Have CI assert `version == max(schemas)` so this cannot silently recur.

### CQ-3 · **DONE** · Deletions never reach the other parent

`deleteEvent` hard-deleted locally and called Firestore. The **downstream** path in
`SyncService` only ever inserted: no branch removed a local row absent from the remote snapshot,
and there were no tombstones anywhere. So parent A deleted an event and **parent B kept it
forever**. Worse: if the remote delete failed (offline, permission), the exception was logged and
dropped with no retry queue — the document survived in Firestore and the next sync **restored
it locally**. `ExpenseRepositoryImpl` was the same.

For a co-parenting app, "a cancelled event only one parent can see" is not cosmetic. It is the
argument the app exists to prevent. Audit §8.3.

**Fixed with tombstones**, in `data/sync/Tombstone.kt` — one definition of what a deleted
document looks like on the wire (`deletedAtMillis`, epoch millis, plus `deletedBy`). A delete
marks the document instead of removing it, so the deletion travels down the same query that
delivers every other change; Room gains `deletedAtMillis` on `events` and `expenses`
(schema 25) as a **pending-tombstone outbox**, hidden from every read query and retried on each
sync until the write lands, then removed for real. `sweepDeletedDocuments` (Cloud Functions,
daily) purges tombstones after 90 days.

Three decisions worth knowing before touching it:

- **Not reconciliation by absence.** "Delete whatever is not in the remote snapshot" would take
  the whole calendar the first time `sharedWith` narrowed at unpair, or CQ-5 bounded the
  download window, or a snapshot came back partial. Absence is not deletion.
- **A deletion wins outright, never by timestamp.** `updatedAt` is a naive `LocalDateTime`
  with SEC-4's cross-time-zone ordering defect, so a tombstone beats a concurrent edit by rule
  rather than by comparison. An event that should not exist is at least visible; an edit that
  loses is simply gone.
- **No rule was widened.** Tombstoning turns a `delete` into an `update`, so `events` admits a
  `read_write` co-parent (who could already rewrite every field) and `expenses` stays
  creator-only (the August 2026 owner decision). Pinned in
  `firestore-tests/rules/deletion-tombstones.test.js`, including that a tombstoned document
  stays readable — a deletion nobody may read is a deletion nobody is told about.

**Left open:** a device offline for longer than the 90-day retention keeps that one event, since
the document it would have learned from is gone. Bounded, rare, and the reason the window is not
smaller.

### CQ-4 · **DONE** · Daily recurring events vanished after ~2 years

`RecurrenceExpander.MAX_OCCURRENCES = 730`, and `count++` runs on **every loop iteration**, not
per occurrence emitted. The walk always starts at `event.startDateTime`, so a *daily* event
stops 730 days after its start regardless of the window queried. Ask for a month three years
out and you get an empty list — the master row is intact, the calendar simply lies. Weekly hits
the cap at 14 years, monthly at 60, so daily is the live case.

Fixed: occurrences are indexed rather than walked, so the cap bounds what is emitted and a
distant range costs the same as a near one. The month-end drift went with it — a monthly event on
the 31st no longer becomes the 28th permanently after one February.

### CQ-5 · P1 · M · Sync downloads the entire event collection every 15 minutes

`observeEventsSharedWith` has no date window and no limit; `.limit(` appears exactly **once**
in all of `app/src/main`. A couple with ~4 events a day reaches 4–5 thousand documents in three
years, and `SyncWorker` runs every 15 minutes on both devices. A Firestore bill that scales
with tenure rather than usage, landing first on the users who stayed longest.

Add a rolling window (the composite index on `sharedWith` + `startDateTime` already exists),
keep a `lastSyncAt` delta, load history on demand. Related: `HomeViewModel` holds three
subscriptions to the whole events table plus one to all expenses and filters the current month
**in memory**, while `EventDao.getEventsForParentPaginated` sits written and never called.
Audit §8.6.

### CQ-6 · **PARTLY DONE** · P2 · M · Chat has no limit at either end

`MessageDao` selected a whole conversation with no `LIMIT`; `FirestoreMessageDataSource`
attached a snapshot listener with no `limitToLast`. Ten messages a day for three years is
~11,000 loaded in full on every open — and `HomeViewModel` ran `ChatReadState.unreadCount`
across all of them on every emission, on the home screen. Audit §8.7.

**Two of the three costs are gone.**

- **The home screen no longer loads the thread to count it.**
  `MessageRepository.observeUnreadCount` answers with a Room `COUNT(*)` over the same
  predicate `ChatReadState.unreadCount` states, so rendering one integer costs an index
  lookup instead of eleven thousand entity-to-domain mappings per emission. It also drops
  Home's second subscription to the remote message listener.
- **The remote listener is bounded** to the newest 200 messages (`limitToLast`, not `limit` —
  the order is ascending, so `limit` would pin the window to the oldest messages and a live
  thread would stop updating at the bound). Room keeps everything it has already received, so
  only a **fresh install** sees less: it now receives the last 200 messages of the thread
  rather than all of them.

**What is left, and why it was not done here.** `MessageDao.getMessages` is still unbounded,
so the chat screen and `ChatViewModel` still materialise the whole thread out of Room.
Bounding it needs a "load earlier" affordance — silently showing only the tail would be the
CQ-7 defect again, in a different collection. It is also entangled with **CQ-8**:
`ChatViewModel.unreadCount` is the subscription `NavGraph.rememberChatUnreadCount()` holds for
the process lifetime, and it is the only thing keeping the remote mirror alive, so it cannot be
converted to the cheap count until something else keeps the mirror running. Do those two
together.

### CQ-7 · **DONE** · The Google Calendar import silently truncated at 50 events

`GoogleCalendarApi` set `maxResults = 50` and never followed `pageToken`. "Found 50 events" is
also what a complete import reports, so the user believed it had finished. Audit §8.8.

Fixed: every page is followed, within a stated window (twelve months by default) and an event cap
that produces a *different* message when it is reached.

### CQ-8 · **PARTLY DONE** · P2 · S · A failed chat listener now reconnects, but only for a while

Both mirror branches in `MessageRepositoryImpl` end in `.catch { Log.w(...) }`, which
*completes* the flow — so `merge(mirror, local)` runs on Room alone for the rest of the
process. `SharingStarted.WhileSubscribed` cannot restart it, because `rememberChatUnreadCount()`
holds an Activity-scoped ViewModel collecting for the whole process lifetime, so the subscriber
count never reaches zero.

**Observed in production**, on the first launch after install: both listeners denied ~0.5 s
before `ensureConversation` created the document, and that whole session ran on local data. The
app looks entirely healthy while receiving nothing. Recurs on any reinstall, factory reset or
account switch. Fix: `retryWhen` with backoff on both branches, or await `ensureConversation`
before subscribing. **Do not** "fix" it by removing the `.catch` — an uncaught failure in
`viewModelScope.launch` kills the process. `CLAUDE.md`, Known issues.

### CQ-9 · **DONE** · `ChildInfoViewModel` could overwrite the wrong child's record

`init` subscribes to the whole child list for the editor's entire lifetime, and every emission
unconditionally sets `_currentChildInfo` to `list.first()`. While a user edits child B, any
write touching `child_info` — a background sync tick is enough — re-emits and clobbers the
state back to child A; the save then overwrites **child A's real row**, id and `createdAt`
included. Fix: observe the one child being edited by id (`ChildInfoDao.observeChildInfoById`,
already wired through the repository), not the head of a list the editor does not own.
`CLAUDE.md` documents this in full, including why widening the `isNewChild` guard does not help.

### CQ-10 · P2 · S · `syncWithFirestore()` means two incompatible things — a trap

Implemented as a one-shot in `PetRepositoryImpl`/`EventRepositoryImpl` and as an **endless**
`callbackFlow` listener in `ExpenseRepositoryImpl`, `BudgetRepositoryImpl` and
`ChangeRequestRepositoryImpl`. `SyncService.performFullSync()` already calls the pet one;
adding the expense one beside it by analogy would make `performFullSync()` **never return**,
`SyncWorker` would be killed at WorkManager's ten-minute ceiling, and sync would stop entirely
— with no exception and no log. Rename to `pullOnce()` / `observeRemote()`. Cheap, five files,
prevents a silent outage. Audit §8.11.

### CQ-11 · **PARTLY DONE** · P3 · S · Error handling is declared but not wired

**Done:** all ten `printStackTrace()` calls — in notifications, Google sign-in and calendar
import — now record to Crashlytics, and the three that had no logging at all also log.
`SyncWorker` logs and reports both its failure paths (it discarded the exception unlogged under a
comment claiming to log it) and gained the `NetworkType.CONNECTED` constraint it was missing, so
it no longer wakes every 15 minutes offline to fail on its first network call.

**Still open:** `domain/error/AppError.kt` and `ErrorHandler.kt` have three references outside
their own package and `presentation/common/ErrorDialog.kt` is never called — the declared error
model is still not the one in use, and 228 `catch` blocks, 116 of them
`catch (e: Exception)`, are. Audit §8.12.

### CQ-12 · P2 · S · Regenerate the detekt baseline, then let detekt gate again

CI's first run found **194 weighted issues**, essentially all pre-existing: `AddEditEventScreen`
at 1,246 lines, `AnalyticsManager` with 22 functions, and every screen added since the baseline
was last generated. detekt therefore runs with `continue-on-error: true` — a report, not a gate.
Deliberate and temporary, commented as such in the workflow.

- [ ] `./gradlew detektBaseline`, commit the result.
- [ ] Delete `continue-on-error: true` so new violations fail again.
- [ ] Optionally work the debt down afterwards; the baseline records what was accepted.

### CQ-13 · P2 · M · Test coverage is concentrated in pure domain logic

360 source files, 96 unit-test files, 5 instrumented. **Seventeen of twenty-five ViewModels
have no tests** — including `ChildInfoViewModel`, whose overwrite-the-wrong-child defect
(**CQ-9**) has no regression test guarding it. `SettingsViewModel` and `SyncViewModel` were
removed as stale and never rewritten.

The first four CI runs are the argument: 30 unit tests were failing because their mocks had
gone stale against collaborators added months earlier, and nobody knew. Tests that do not run
are not coverage.

### CQ-14 · P2 · M · User-facing strings produced inside ViewModels and services

`GoogleCalendarSyncState.message`, sync/status errors, `NavGraph`'s "Checking authentication…"
— hardcoded English, unreachable by `stringResource`. Extracting them needs a resource-provider
abstraction. **Do not** inject `Context` into a ViewModel ad hoc to fix one; that is the rule
`SwapError` and `CLAUDE.md` both exist to protect. Blocks **SEC-3** and part of **UX-14**.

### CQ-15 · P3 · S · Dead code

Unreachable today: `utils/ComposeOptimizations.kt`, `ErrorDisplay`, `ErrorDialog`,
`CoPlanlySnackbarHost`, `LoadingButton`, `SyncStatusIndicator`, `LottieAnimations` (the *only*
consumer of the `lottie-compose` dependency), `AdaptiveDimensions`, `CalendarNavigation`,
`AccessibilityUtils`, `SensitiveMedicalData`, five `EventDao` methods including the project's
only pagination — plus the whole AI subsystem (**MON-7**).

Note the overlap with §4: several of these are components the design section is asking for.
Delete what is genuinely dead; **wire** `SkeletonLoading`, `ErrorDisplay` and
`CoPlanlySnackbarHost` rather than deleting them (UX-2, UX-3).

### CQ-16 · P3 · S · No Digital Asset Links

`CredManMissingDal` is disabled in `app/build.gradle.kts` with that rationale. Credential
Manager's password sign-in cannot share a credential with a website, and the pairing deep link
stays a custom scheme rather than a verified App Link. Both need a domain the project does not
own — the same domain **REL-4** needs for hosting. Do all three together.

### CQ-17 · P3 · S · Dependencies worth moving

| Dependency | Now | Why |
| --- | --- | --- |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | See **SEC-5**. |
| `retrofit` | 2.9.0 (2020) | Five years stale — and possibly removable: its only consumer is the AI layer, which already has the official `generativeai` SDK beside it. |
| `play-services-auth` | 21.2.0, deprecated | Both it and Credential Manager are in the graph — two sign-in paths, twice the size. |
| `androidx.work` | 2.9.0 | 2.10.x fixes the Doze/foreground bugs that hit a 15-minute sync. |
| `google-api-services-calendar` | `v3-rev20220715` | A 2022 revision. |
| `firebase-functions` (Node) | ^4.5.0, gen-1 API | Two generations behind; ESLint 8 is EOL. |

### CQ-18 · P3 · S · Cross-time-zone chat was implemented but never verified on two devices

Epoch-millis message times are covered by unit tests that drive two zones explicitly
(`ChatReadStateTimeZoneTest`) plus a 12→13 migration test. The **two-phone acceptance run** —
set one phone 2–3 hours apart, send, confirm unread counts, badge clearing and READ ticks — was
deferred, not run. `CLAUDE.md`, Known issues.

---

## 4. [UX] Design and usability

The theme layer is genuinely good: contrast documented pair by pair, `ParentColors` solving the
fill-versus-text problem properly, Settings and the month grid exemplary. The gap is between
that layer and the screens — roughly 700 lines of tokens and ten shared components sit
unreferenced while the rules they encode get broken in the screens that matter most.

### UX-1 · **DONE** · A paired parent was told they had no co-parent, on every cold start

`PairingState.Loading` collapses to `paired = false` and `HomeUiState` initialises as
`AskForCoParent`, so an existing user's launch is: splash → a **full screen** saying "add your
co-parent" → the dashboard.

The trade-off was made deliberately and argued in the code — the alternative was an unpaired
user seeing a hollow dashboard. That reasoning was sound when the prompt was a *card*; it has
since become the whole screen, so the cost of guessing wrong grew while the guess stayed the
same. There is a third option neither branch takes: `PairingState` already has `Loading`, and
`SkeletonLoading.kt` is written and unreferenced. Assert nothing until the answer is known.
Audit §9.2.

Fixed: `Loading` is now its own state and the page asserts nothing while it holds. An answer that
never arrives falls back to the invitation after a settle window, because `observePairingState`
also recovers into `Loading` when its listener fails permanently and a page that waits for ever is
worse than one that offers something to do.

### UX-2 · **DONE** · No main screen had a loading state

None of the six had one. Every list exposed `StateFlow<List<T>>` starting at `emptyList()` and
branched on `isEmpty()`, so "nothing yet" and "nothing at all" were the same value and each screen
**asserted a fact it did not have** for the first frames after a cold start. Home was worst
because it asserted several — "$0.00", "All settled", no events this week — and Contacts was the
most costly, because a parent opening the emergency surface in a hurry was told there were no
contacts a frame before being shown them. Audit §9.3.

Fixed with one type rather than six ad-hoc flags: `Loadable<T>` in `presentation/common/`, with a
`stateInLoadable` helper that seeds `Loading` instead of a fabricated empty value. Home (UX-1),
Chat, Expenses, Budgets, Contacts and Child info now render the shared `ListSkeleton` while the
answer is unknown, and their empty states only when the answer is genuinely empty. The Russian
KDoc in `presentation/components/SkeletonLoading.kt` was translated with it — it had been left
there precisely until something wired the skeletons up.

**The calendar is deliberately not among them.** Its grid is structurally present whichever way
the query is going: the days are drawn, only the event dots are missing, so a skeleton would
replace a correct calendar with a shimmer. It is also the one screen where `CLAUDE.md` forbids
rendering `Loading` outright, because the query flips to `Loading` on every re-anchor and the grid
would flash on every settle. A first-load-only skeleton is possible via a sticky "has ever
loaded" flag, but it buys little against that cost and is not obviously an improvement.

Still open, and separate: `presentation/common/animations/LoadingSkeleton.kt` duplicates
`SkeletonBox` and remains unreferenced. One of the two files should go — see **CQ-15**.

### UX-3 · **DONE** · Budgets could not be edited or deleted

`BudgetItem` has no click handler anywhere; `BudgetViewModel.deleteBudget()` is never called and
`updateBudget` does not exist. **A typo in a limit is permanent.** Also `BudgetScreen:100` holds
`var spentAmount by remember { mutableStateOf(0.0) }` **without a key** inside `items{}`, so
recycled rows show another budget's figure. Audit §9.9.

Fixed: both, including the keyless `remember`.

### UX-4 · **DONE** · There was no way to jump to a date

`CalendarScreen` declares `showDatePicker`, builds the dialog, and never sets it to `true`.
Forty lines of unreachable UI, and no route to a date eight months out except swiping. Audit §9.7.

Fixed: the dialog opens from the calendar header.

### UX-5 · **DONE** · "Today" did not survive midnight

`val today = remember { LocalDate.now() }`, with no key. An app left open overnight keeps
highlighting yesterday. For a product whose entire question is *whose day is it today*, that is
an answer that quietly becomes wrong. Audit §9.8.

Fixed: `rememberToday()` in `presentation/common`. Reading `LocalDate.now()` inline was no better
on its own — correct whenever it ran, but nothing made it run, because midnight is not a
recomposition trigger. Now it is one.

### UX-6 · **DONE** · Adaptive sizing and font scale were switched off at the entry point

`MainActivity` calls `CoPlanlyTheme(darkTheme = …)` without a window size class, so `Theme.kt`
always resolves `compactDimensions` — phone padding on tablets and unfolded folds.
`adaptiveDimensions()`, the only code that reads `fontScale` and `isTouchExplorationEnabled`, is
never called. Audit §9.6.

Fixed: the window size class reaches the theme, so `adaptiveDimensions()` stops being dead code.

### UX-7 · **DONE** · Touch targets outside `PillChip`

`PillChip` is fixed (48dp + `Role.Button`, PR #69). The **calendar header still has no control
at or above 48dp**: the month title — which *is* the Month/Week/Day switcher — is a bare
`clickable` at ~28dp, Today sits at 40dp, Filters is a 32dp `FilterChip`.
`Constants.MIN_TOUCH_TARGET` is declared and referenced nowhere;
`minimumInteractiveComponentSize()` is never called. Audit §9.5.

Fixed: the calendar header's three controls, the month title included — which is the
Month/Week/Day switcher and was a bare `clickable`, so TalkBack did not announce it as a control
either.

### UX-8 · P2 · S · The answer to "whose day is it" is the smallest, greyest text on screen

`CalendarBanners:239` renders it as `labelMedium` (12sp) in `onSurfaceVariant` with no parent
colour, while `HomeScreen:673` gives the *next* handover 26sp bold. The hierarchy is inverted:
the future event is louder than the present fact the app is opened for. The two surfaces also
colour from different sources (`event.parentOwner` versus `entry.dayParent ?: event.parentOwner`),
so one visual channel carries two meanings on adjacent cards. Audit §9.11.

### UX-9 · P2 · M · Five different empty-state anatomies

`AnimatedEmptyState` in five places, plus bespoke variants in Contacts, ChildInfo, Pets, Friends
and Home — Home's being the `Card { Text }` pattern the August refresh explicitly outlawed. The
previous design review asked for consolidation to one; the count went from two to five.

**Contacts matters most**: an emergency surface, first on Home, and empty it offers two grey
sentences and no action. Separately, `AnimatedEmptyState` takes no `modifier` and hardcodes
`fillMaxSize().padding(32)`, so `Scaffold` padding does not apply and content renders under the
top bar in `ConversationsScreen` and `BudgetScreen`; it also does not scroll, so it clips at
large font scales. Audit §9.12.

### UX-10 · P2 · S · Budget status is carried by colour alone

Under / near / over is a 6dp dot in green/amber/red, decorative (no semantics), and the amber
`CoPlanlyBudgetWarning` is theme-independent — about 1.9:1 on a light background. A screen reader
gets "School 800/1000" and no status at all. WCAG 1.4.1. Audit §9.13.

### UX-11 · P2 · S · The Google Calendar row breaks the one-trailing-control rule

`SettingsScreen:313` puts a `Switch` **and** a chevron in the trailing slot of a row that is
itself clickable — three interaction models in one row, against `DesignSystem.kt:143`. The
chevron has `contentDescription = null`, so TalkBack announces a switch and never mentions that
the row expands. Audit §9.14.

### UX-12 · P2 · S · Clerical English success messages

"Event created successfully", "Event rescheduled" and friends are still English literals — and
`CalendarScreen` **branches on the literal** `"Event rescheduled"`, so localising that string
silently removes the undo snackbar. Fix the branch first, then the strings. Part of **CQ-14**.

### UX-13 · P3 · M · Light theme is unverifiable rather than incomplete

`LightColorScheme` is complete and correct and the setting works — but there are six
`LightDarkPreviews` across 148 UI files, two of them on dead components, and **none on any of
the six main screens**. There is no `values-night/`, and `themes.xml` uses an AppCompat *Light*
parent regardless of theme, so a cold start in dark mode flashes a white window before Compose
draws. Audit §9.15.

### UX-14 · P3 · S · Four different brand purples

`brand_primary` `#6750A4` (system splash), `BrandPrimary` `#4F46E5` (Compose), launcher
background `#6200EE`, and a splash gradient between the first two. Icon, system splash, Compose
splash and app do not agree. Audit §9.16.

### UX-15 · P3 · S · `ParentColors` is adopted at roughly a quarter

Thirteen `ParentColors.*` calls against forty-four direct `MomPink`/`DadBlue` uses outside
`theme/`. Most are legitimate fills — but the rule exists so the decision lives in one place,
and the place it broke (parent hues as 8sp text on Custody Setup, fixed in #69) is exactly where
it was bypassed. Audit §9.17.

---

## 5. [MON] Monetisation and product

**There is no billing layer at all** — no Play Billing dependency, no purchase code, no
entitlement model, no paywall. Everything below assumes that gets built; **MON-1** is the
decision that shapes it, and it should be made before the code.

### MON-1 · P0 · decision · Pricing, and who pays

The audit's recommendation (§10.4), for a Czech-first launch:

- **99–149 CZK/month**, or **990–1,490 CZK/year.** 149 is the top of the "without thinking
  about it" band; 1,200 CZK/year is about 2.4% of one average monthly wage.
- **One subscription per family, the second parent free.** This is what the winning European
  products do (CoParently.de, 2houses, ParentDocket) and the opposite of the American per-parent
  model — which correlates with OurFamilyWizard's 1.4★ on Trustpilot against 4.6★ in the stores,
  the signature of court-mandated use plus per-seat billing. There is a product reason as well
  as a market one: in a conflicted pair, **one** person will pay. Charging both loses both.
- **A free tier is not optional.** The product does nothing until *both* parents install it, so
  a paywall at the door kills the network effect that makes it work. Free should cover calendar
  and custody **completely**; charge for documentation, export and AI.

A warning from the same data: **Onward closed on 8 October 2024**, built entirely on expense
splitting and payments. Expense reimbursement does not carry a product on its own — worth
weighing against MVP 3's "Payments (XL)".

- [ ] Decide the price, the unit (family, not seat), and what free contains.
- [ ] Then build: Play Billing, an entitlement model, a paywall, restore-purchases, and the
      server-side check that a second parent inherits the family's entitlement.

### MON-2 · P0 · decision · Verify the market facts before acting on any of this

Direct page fetching was blocked in the audit environment, so competitor prices, ratings and the
Czech statistics come from search-result summaries. Good enough to plan with, **not** good
enough to publish. In order of how much each answer moves the plan (audit §10.7):

1. **app2us "Rodina": is there an Android build, and what does it cost in CZK?** This single
   answer changes the Czech strategy more than anything else found.
2. Custody X Change's price (sources disagreed: $72 vs $144/year for Bronze).
3. Fayr Premium's price; AppClose and 2houses Play ratings.
4. The registered family-mediator count, against the justice.cz register.
5. Czech mobile ARPU by country (only a global Android figure was available).
6. Current single-parent household numbers — the figure found (~175,700) is from 2015.
7. Czech Facebook groups: closed groups are not indexed and need manual search.

### MON-3 · P1 · M · Export to PDF/CSV — the first paid feature

Nothing in the app produces CSV or PDF. `MVP_phases.md` lists exports in MVP 3 at **Low**
priority; for a paid tier that is backwards. In this category willingness to pay concentrates on
**documentation you can hand to a lawyer or a court**: an immutable log of who changed what and
when, handover punctuality, an expense ledger with receipts.

CoPlanly already *records* all three — the activity feed, `ChangeRequest`, `HandoverCalculator`,
expenses with per-currency balances and receipt photos. The data exists. What is missing is the
one step that turns a nice app into something a parent pays for in the month they need it.
Audit §7.2.

### MON-4 · P1 · M · Decide what a court-facing record guarantees — **prerequisite for MON-3**

An export that says "this is what happened" is only as good as the record behind it. Today
`events` are freely editable by the creator with no history, conversations can be re-pointed,
and the custody schedule is last-write-wins ordered by a naive local date-time (**SEC-4**).

Before selling documentation, decide: which records are append-only, what an edit does to
history, and whose clock orders writes. This is not a nice-to-have once anything is exported for
legal use — it is what makes the export worth paying for. Audit §7.5.

### MON-5 · P1 · M · Digitise the official Rodičovský plán — the cheapest local moat

The Ministry of Justice publishes an official parenting-plan template
(`vyzivne.justice.cz/rodicovsky-plan`). In practice each parent fills it in separately and a
mediator or OSPOD compares the two to surface agreement and disagreement.

Digitising it — two parents, separate answers, a diff, an export — is a feature no global
competitor has, is hard for a non-Czech team to copy, fits the post-2026 legal emphasis on
agreement, and hands the mediator channel a concrete reason to recommend the app. Cheaper than
the school import and lands in the same place. Audit §10.6.

### MON-6 · **DONE** · Add the Czech custody preset that is actually common

`CustodyModelType` offered `WEEK_ON_WEEK_OFF`, `TWO_TWO_THREE`, `THREE_FOUR_FOUR_THREE`,
`CUSTOM` — the two middle ones are US family-law vocabulary. The arrangement a large share of
Czech families have, **výhradní péče se stykem** (sole custody, every other weekend plus a
midweek afternoon), had no preset and had to be built by hand. Audit §7.4.

`EVERY_OTHER_WEEKEND` is in, listed **second** — the enum's order is the picker's order, so the
two arrangements Czech families actually have now lead. Five strings in all five locales, and a
test that names the days as weekdays rather than indices, because this pattern read backwards
produces an equally plausible schedule that has simply handed over the school days.

**The switch above the preview asks a different question for it.** The other three alternate
blocks of time, so "who starts first" is the whole of it; this one does not, and a parent asked
who "starts" would answer about the first weekend and set it inverted. It asks "who does the
child live with" instead.

**Deliberately not done, and both are decisions rather than work:**

- **The midweek afternoon is not in the preset.** `CustodyModel` assigns a whole day to exactly
  one parent, so there is no half-day to give; folding the afternoon into a whole Wednesday
  would hand over an overnight nobody agreed to. Half-day granularity is a real feature and a
  real schema change — see below.
- **The two US patterns stay.** Removing one would make an existing user's saved `modelType`
  unparseable, and whether they earn their place in a Czech-first launch is an owner's call.

### MON-7 · **DONE** · The AI subsystem is deleted

23 files and ~3,200 lines, reachable from no navigation graph, while the Gemini key shipped in
every APK. Deleted — along with `generativeai`, `retrofit`, `converter-gson`, `okhttp` and
`logging-interceptor`, which had no consumer left once it went (retrofit had none before). The
code is in git history and comes back whenever it is wanted.

**When AI returns, it returns behind SEC-1's proxy**, never with a key in the client, and as
*one* feature rather than eight. The ranking, if it helps: **tone check before sending** (audit
§6.3) is what competitors charge for everywhere — OFW's ToneMeter, TalkingParents' Sentiment
Scanner, CoParently.de's tone detector at €4.99. Two hard constraints, both non-negotiable: it
must **never block** sending, and the analysis must **never be stored** — a saved "your message
was aggressive" verdict is discoverable material in a custody dispute, which makes it a liability
to the user rather than a feature.

Boundaries that outlive the deletion (audit §6.5): receipt OCR stays on-device; AI never acts on
the co-parent's behalf; AI never adjudicates who is right or who is late more often; chat content
reaches a model only on an explicit user action. Anything resembling emotion inference deserves a
legal read under the EU AI Act before launch.

### MON-6b · P2 · L · Half-day custody, so contact afternoons can be described

`CustodyModel` assigns each day of the cycle to exactly one parent (`momDayIndices`), so an
arrangement of the form "every second weekend **plus Wednesday afternoon**" — which is most
Czech contact orders, not an edge case — can only be entered by rounding the afternoon up to a
whole day or dropping it. MON-6's preset drops it and says so; `CUSTOM` cannot express it
either.

Not a small change: it touches the pattern representation, the Room entity, the Firestore
document, `getCustodyFor`, `complemented`, `isEquivalentTo`, the custom-pattern editor and the
day-cell fills. Worth doing before claiming the app describes a Czech family's real schedule;
worth costing properly first.

### MON-8 · P2 · L · Bakaláři / EduPage school import

`MVP_phases.md` lists it in MVP 3 at **XL, Low**. It is the highest strategic item in the
roadmap and mispriced. Every Czech parent's school schedule lives in one of those two systems; an
import that fills the calendar on day one solves cold-start, is a local moat no US competitor will
build, and is the most credible reason to choose CoPlanly over a generic shared calendar. Document
understanding makes the XL estimate smaller than when the line was written. Audit §6.4, §7.3.

### MON-9 · P2 · ongoing · Distribution: the channel is professional, not search

This audience does not search for the category — it is handed to them at a specific moment, by a
professional, during the worst month of their year. Audit §10.5.

- **Mediators.** A Czech court can order a first meeting with a registered mediator, up to three
  hours (§ 100(3) o.s.ř.) — a guaranteed moment with **both parents present at once**, the
  hardest thing to arrange in this market. One source puts registered *family* mediators at ~25,
  about half active; if that holds, the entire channel is coverable personally in a week.
- **Courts running Cochem practice** (Nový Jičín since 2016, Most since 2017): interdisciplinary
  by design and already oriented toward agreement.
- **OSPOD** offices at municipalities with extended competence.
- **NGOs and portals**: stridavka.cz (which already publishes a co-parenting tools roundup — a
  directly reachable placement), zustavamerodici.cz, APERIO, sancedetem.cz, Unie otců, Liga
  otevřených mužů.
- **The OFW playbook, localised**: free professional accounts with unlimited clients, plus promo
  codes to hand to families. Revenue-sharing with courts is neither available nor legally
  plausible in Czechia; free professional accounts are.

### MON-10 · P3 · S · Re-baseline the roadmap

`MVP_phases.md` and `CLAUDE.md` planning notes are behind the code: MVP 2's items are all
present — receipts with on-device OCR, change requests with their own screens and collection, the
weekly section and recent-changes feed on Home, structured change requests from chat, event image
attachments. Re-baseline before planning MVP 3, or the plan describes work already done. Audit §7.8.

---

## 6. Done — so it is not re-litigated

Closed in PR #68 and #69, listed only to stop these reappearing as "todo":

- **Security (audit §2):** the `calendar_friends` self-issued grant that disclosed a whole
  family's calendar; `users.partnerId` self-reference revocation bypass; invitations accepting an
  unverified email; `change_requests` forgery; `events` update rewriting the audience; membership
  reads against absent fields; R8 destroying a child's medical profile in release;
  `EncryptedPreferences` falling back to plaintext permanently; personal data in diagnostics;
  telemetry flags nothing read; the Gemini key bound as a bare `String`; unfiltered collection
  queries.
- **Account deletion**, server-side teardown plus local wipe — Play's requirement and GDPR Art. 17.
- **Invitation email actually sends**, and a bounced delivery no longer destroys a working code.
- **CI**: three jobs on every push and PR, including `assembleRelease` so R8 runs.
- **UX**: the calendar's silent errors, an OAuth refresh on the main thread, `PillChip` at 48dp
  with a role, parent hues no longer used as 8sp text.
- **i18n**: the onboarding editors (47 keys that already existed and were wired to nothing) and
  the chat templates (which shipped Russian text under English headings to every user).
- **Docs**: legal drafts written from the real data model, Data Safety answers, this backlog, and
  a README that no longer advertises features no navigation graph can reach.

---

## 7. The order to actually do it in

Not a wish-list ordering — a dependency ordering. Each block assumes the one above it.

**This week, before anything else**

1. **REL-1** — ~~decide the `applicationId`~~ **decided** (`app.coplanly`); the console half is
   still open, and a local build fails until it is done.
2. **REL-3** — deploy rules and functions. Every security fix from both audits is inert until
   this runs, and one of them closes a live full-calendar disclosure.
3. **MON-2 §1** — find out whether app2us "Rodina" has an Android build. One afternoon; it moves
   the plan more than any other single fact.
4. **CQ-1** — restore the Room schemas. Every migration since v14 is untested, and a broken
   migration is a crash on launch, not a wipe.

**Before any launch**

5. **REL-2, REL-4, REL-5, REL-6, REL-7** — keystore, legal, consent, Play Console, and the one
   device test CI cannot run.
6. **SEC-1** — the Cloud Function proxy. Now two holes rather than three, MON-7 having removed
   the AI key from the APK by deleting the subsystem — and it is the precondition for AI ever
   coming back.
7. ~~**CQ-3** — deletions that replicate.~~ **Done.** Tombstones, an outbox that retries, and a
   daily server-side sweep. See the item for the three decisions it rests on.
8. ~~**UX-1 → UX-7** — the P1 usability set.~~ **Done.** What remains in `UX` is P2 and below:
   the empty-state anatomies (**UX-9**), budget status carried by colour alone (**UX-10**), the
   Settings row with three interaction models (**UX-11**), and the English success strings
   (**UX-12**, blocked on **CQ-14**).

**Then, the product bets, in descending confidence**

9. **MON-4 then MON-3** — settle what the record guarantees, then sell the export. This order is
   not negotiable: an export of a record nobody can vouch for is worth nothing to a lawyer.
10. **MON-5** — the Rodičovský plán. The cheapest local moat and the reason a mediator recommends
    you.
11. ~~**MON-6** — the Czech custody preset.~~ **Done.** What it exposed is **MON-6b**: the
    schedule cannot describe a half-day, so the contact afternoon most Czech orders include has
    nowhere to go.
12. ~~**MON-7** — one AI feature behind the proxy, or delete the subsystem.~~ **Deleted.** If AI
    returns it returns behind **SEC-1**, as one feature rather than eight.
13. **MON-8** — the school import.

**Structural, whenever it fits**

14. **CQ-5**, and the rest of **CQ-6**. Both grow worse with tenure, so they land on your
    longest-standing users first. CQ-6's network and home-screen halves are done; what remains
    is the Room query behind the chat screen, which needs a "load earlier" affordance and has
    to be done together with **CQ-8** — see the item. CQ-5 is the harder one and is *not* a
    rolling window plus a `lastSyncAt` delta, as this document used to say: a delta on
    `updatedAt` would miss every deletion, because tombstones deliberately do not move it (and
    `updatedAt` carries SEC-4's ordering defect anyway), and a date window on `startDateTime`
    cuts off the master row of a recurring series that began before it. Settle those two before
    writing any of it.
15. **CQ-12, CQ-13** — make detekt gate again, and write the ViewModel tests. The first four CI
    runs are the argument for both.

**One thread runs through this document.** The security holes, the release-only Gson corruption,
the plaintext refresh token, the two-year recurrence bug, thirty unit tests failing against a
constructor that changed months ago — none of it was carelessness. They are the failure modes of
a codebase with careful reasoning and, until last week, no automation to check it. CI is not
low on the list because it was urgent; it was built first because everything above it is a
symptom.
