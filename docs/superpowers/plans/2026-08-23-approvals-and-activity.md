# Approvals and activity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An event created for the other parent does not enter the calendar until they accept it, and every shared change announces itself in chat — in the reader's language, not the writer's.

**Architecture:** Three additive columns on `events` carry acceptance; one filter in `getEventsByDateRange` keeps unaccepted events out of every view at once. Announcements carry a structured payload rendered by the reader, posted by one `ActivityAnnouncer` called from the repositories rather than from each screen.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room, Firebase Firestore, JUnit 4 + MockK, `@firebase/rules-unit-testing`.

**Spec:** `docs/superpowers/specs/2026-08-23-approvals-and-activity-design.md`

**Ordering with package C.** If C landed first it added a bespoke day-swap chat card; Task 6 replaces it. If C has not landed, nothing here waits on it.

## Global Constraints

- **Jetpack Compose only.** Stateless composables; state in ViewModels as `StateFlow`.
- **Event editing must preserve fields.** CLAUDE.md: `AddEditEventScreen` keeps a snapshot and uses `copy()`. Never rebuild an `Event` from scratch on save — that wiped `sharedWith`, `permissions` and `createdByFirebaseUid` once already.
- **Private events (`isPrivate`) never reach Firestore.** Both `EventRepositoryImpl` and `SyncService` filter them; keep every new path consistent, announcements included.
- **The Firestore event schema is defined in one place** — `EventRepositoryImpl.toFirestoreMap()`. `SyncService`'s maps must stay in sync with it. Package B1 shipped a data-destroying defect from missing exactly this; check both.
- **Calendar query ranges come from `queryRangeFor()`**; extend it rather than inlining range maths.
- **Recurring events** are stored once and expanded by `RecurrenceExpander`; occurrences share the master id, so the id is not a unique list key.
- **Never hardcode user-visible text.** Every new key in all five locales in the same commit.
- **Room schema changes:** entity → version bump → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`); exported schemas in `app/schemas/`.
- **Never debug `firestore.rules` by deploying.** `firestore-tests/` runs offline; needs a JDK 21+ on `PATH`.
- KDoc on every public class and function; code and comments in **English**.
- detekt `MaxLineLength` **120**; `TooGenericExceptionCaught` active and lists `Exception`; nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26. Conventional Commits.
- `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew …`
- detekt exits non-zero from pre-existing findings; judge by whether **your** files appear in the report.

---

### Task 1: `EventAcceptance` and its transitions

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/events/EventAcceptance.kt`
- Create: `app/src/main/java/com/coparently/app/domain/events/EventAcceptanceTransition.kt`
- Test: `app/src/test/java/com/coparently/app/domain/events/EventAcceptanceTransitionTest.kt`

**Interfaces produced:** `enum class EventAcceptance { NOT_REQUIRED, PENDING, ACCEPTED, DECLINED }`; `EventAcceptanceTransition.required(event, creatorUid, partnerUid): Boolean`, `.accept(event, byUid, at)`, `.decline(...)`, `.cancel(...)`, each `Result<Event>`.

**`NOT_REQUIRED` is the default and carries the whole backward story:** every event that exists today, every event a parent creates for themselves, and every event created while unpaired. Nothing rewrites history.

- [ ] **Step 1: Write the failing test.** Cover at least:
  - an event whose `parentOwner` slot belongs to the co-parent, created by a paired account, **requires** acceptance;
  - an event a parent creates for themselves does **not**;
  - an event created while unpaired does **not**;
  - only the recipient may accept or decline — the creator attempting it fails;
  - a decided event cannot be re-decided;
  - the creator may cancel a pending one;
  - declining leaves `DECLINED`, not a deleted row (assert the returned event still exists and carries the status).
- [ ] **Step 2: Run it; expect a compile failure.**
- [ ] **Step 3: Implement.** Pure functions over an `Event`, returning a new one; failures are `Result.failure(IllegalStateException(...))` naming the rule broken.
- [ ] **Step 4: Run it; expect all PASS.**
- [ ] **Step 5: Commit** — `feat(events): an event for the other parent needs their word first`

---

### Task 2: Storage, sync and the grid filter

**Files:**
- Modify: `app/src/main/java/com/coparently/app/domain/model/Event.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/EventEntity.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt`, `DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/EventRepositoryImpl.kt`
- Modify: `app/src/main/java/com/coparently/app/data/sync/SyncService.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/EventRepositoryImplTest.kt` (extend), `app/src/androidTest/.../CoPlanlyDatabaseMigrationTest.kt`

**Resolve the schema version by reading `CoPlanlyDatabase`**, not from this document — B1, B2 and C may have landed first.

**Three places must carry the new fields and missing one is silent:** `EventRepositoryImpl.toFirestoreMap()`, its Firestore reader, and **`SyncService`'s own maps**, which are separate and were the site of B1's Critical defect. Grep `SyncService` for the field names before committing.

**The filter is the load-bearing change.** `getEventsByDateRange` is where the grid, the week view and the day view all read from. Filtering there — and only there — is what makes one rule cover every view.

- [ ] **Step 1: Extend `EventRepositoryImplTest`** first: a `PENDING` event is absent from a range query; a `DECLINED` one is absent; `NOT_REQUIRED` and `ACCEPTED` are present; a recurring master that is `PENDING` produces **no** occurrences.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Add the fields** to `Event` and `EventEntity` with their KDoc and `@property` entries; bump the version; write the additive migration; register it.
- [ ] **Step 4: Carry them through every mapper**, `SyncService` included. Report which sites you found.
- [ ] **Step 5: Add the filter** to `getEventsByDateRange` and `getEventsByDate`. Note that both match multi-day and overnight events by overlap — do not disturb that.
- [ ] **Step 6: Run tests; add the migration test case; run it on the device.**
- [ ] **Step 7: Commit** — `feat(events): keep an unaccepted event out of every calendar view`

---

### Task 3: The inbox and the waiting strip

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/changerequests/ChangeRequestsScreen.kt` + ViewModel
- Modify: `app/src/main/java/com/coparently/app/presentation/event/EventListScreen.kt`
- Modify or create: `app/src/main/res/values*/event_acceptance_strings.xml`

The recipient decides in the change-request inbox; the creator sees a "waiting for your co-parent" strip on the event list, so they can tell "not answered yet" from "never created".

`ChangeRequestHighlight.indexInInbox` computes a flat index across section headers — adding a section means teaching it about the new one, or its scroll-to-request lands on the wrong row.

- [ ] **Step 1: Write the strings in all five locales**, matching each file's register.
- [ ] **Step 2: Add the inbox section** with accept and decline, using Task 1's transitions. Parent names come from `rememberParentNames`; the app never shows "Mom" or "Dad".
- [ ] **Step 3: Update `indexInInbox`** and extend its existing test.
- [ ] **Step 4: Add the creator's waiting strip** to `EventListScreen`.
- [ ] **Step 5: Verify locales by grep; confirm no hardcoded text; build; commit** — `feat(events): show both parents an event that is waiting on an answer`

---

### Task 4: The activity payload, and rendering it in the reader's language

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/activity/ActivityAnnouncement.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/chat/ActivityCardText.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/model/Message.kt` (a new `MessageType` member, and the payload field)
- Modify: `app/src/main/java/com/coparently/app/data/repository/ChatMappers.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/MessageEntity.kt` + migration
- Create: `app/src/main/res/values*/activity_strings.xml`
- Test: `app/src/test/java/com/coparently/app/presentation/chat/ActivityCardTextTest.kt`

**This is the package's spine — spec §3.** The card carries facts; the reader's device turns them into a sentence in **its own** language. A card composed in the sender's locale is a card the recipient may not read, and the two parents may not share a language.

**A new `MessageType` member is forward-compatible by construction, and that must be verified rather than assumed.** `ChatMappers` parses it with `runCatching { MessageType.valueOf(...) }.getOrDefault(MessageType.TEXT)`, so a co-parent on an older build sees the `content` fallback as a plain message. Add a test that pins that degradation — it is what stops this change breaking an un-upgraded phone.

- [ ] **Step 1: Write the failing test** for `ActivityCardText`: every `kind` maps to a distinct string resource; the mapping is exhaustive over the enum with **no `else`**; an unknown type read from Firestore degrades to `TEXT` carrying `content`.
- [ ] **Step 2: Run it; expect a compile failure.**
- [ ] **Step 3: Define the payload and the enum.** Keep the Firestore field name stable from the start — a co-parent on an older build must keep reading the messages collection, and renaming later is not available.
- [ ] **Step 4: Add the strings in all five locales.** Each `kind` gets one, with the entity title as a format argument, never concatenated.
- [ ] **Step 5: Implement the renderer and the mapper changes; add the Room column and its additive migration.**
- [ ] **Step 6: Render the card** in `MessagesList` beside the existing `EVENT_LINK` handling, tapping through to the entity.
- [ ] **Step 7: Run tests; verify locales; build; commit** — `feat(chat): announce a change as facts the reader renders in their own language`

---

### Task 5: `ActivityAnnouncer`, called from the repositories

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/activity/ActivityAnnouncer.kt`
- Modify: `EventRepositoryImpl`, `ExpenseRepositoryImpl`, and the change-request write path
- Test: `app/src/test/java/com/coparently/app/domain/activity/ActivityAnnouncerTest.kt`

**Called from the repositories, not the ViewModels.** A repository is where a change becomes durable; a card posted from a screen does not appear when the same change arrives from sync, an undo, or a future screen.

**It must never break its caller.** An announcement that fails is logged and dropped. A parent's expense must not fail to save because their co-parent's chat listener is down — the same discipline package A applied to the password-save prompt.

- [ ] **Step 1: Write the failing test.** Cover: a private event produces **no** announcement; an unpaired account produces none; a parent's own change is not announced back to them; a budget change produces none; a failure inside the announcer does **not** propagate to the caller.
- [ ] **Step 2: Run it; expect a compile failure.**
- [ ] **Step 3: Implement,** resolving the conversation id through `ConversationKey.of(uidA, uidB)` — it is derived from the two UIDs, never generated; randomly generated ids are what once put the two phones on separate threads.
- [ ] **Step 4: Call it from the three write paths.** Undo re-creates a captured event with its id preserved — decide and document whether an undo announces, and be consistent.
- [ ] **Step 5: Run the full suite; build; commit** — `feat(activity): tell the co-parent what changed, from the write path`

---

### Task 6: Retire the bespoke cards

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/changerequests/RequestChangeViewModel.kt`
- Modify (only if package C landed first): the day-swap card added by C's Task 7

`RequestChangeViewModel.postChatMessage` composes hardcoded English inside a ViewModel — the defect §3 exists to end. Replace it with `ActivityAnnouncer`.

**Check what depends on the old card's shape before deleting it.** `ChangeRequestHighlight.forEvent` resolves the tap target from an `EVENT_LINK` card whose `attachments` carry the **event** id, and its KDoc explains why. If the new payload changes that, update both together and extend the existing test.

- [ ] **Step 1: Read `ChangeRequestHighlight` and its test.**
- [ ] **Step 2: Replace the bespoke post; delete `postChatMessage`.**
- [ ] **Step 3: If C landed, delete its day-swap card too** — there must be exactly one way a change reaches chat.
- [ ] **Step 4: Run the full suite; build; commit** — `refactor(chat): one way a change reaches the thread, not three`

---

### Task 7: Rules

**Files:** `firestore.rules`, `firestore-tests/rules/events.test.js`, `firestore-tests/rules/conversations-messages.test.js`

Two things to pin, neither of which should need a rule change — which is exactly why they need a test:

- **The recipient may write acceptance fields on an event they did not create.** The `events` update rule requires `createdByFirebaseUid` to be unchanged, which permits it. Finding out otherwise on a phone is what CLAUDE.md forbids.
- **A message may carry the activity payload**, and a participant may not forge one attributed to the other.

- [ ] **Step 1: Add both cases; run `cd firestore-tests && npm test`.**
- [ ] **Step 2: Only if a case fails, adjust `firestore.rules`** — and say so prominently, because a required rule change here means the audience model differs from what §5 assumed.
- [ ] **Step 3: Commit** — `test(rules): pin that a recipient can accept an event they did not create`

---

### Task 8: Full verification

- [ ] **Step 1:** `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — totals, and whether any changed file is named.
- [ ] **Step 2:** `cd firestore-tests && npm test && npm run lint`.
- [ ] **Step 3:** the instrumented migration test on the device.
- [ ] **Step 4:** locale grep — five files per new key.
- [ ] **Step 5: Two devices, in two languages.** Set phone A to Russian and phone B to English.
  1. From A, create an event whose owner is B. It is **absent** from both grids.
  2. B's inbox shows it; A's event list shows it as waiting.
  3. B's chat card reads in **English**; A's card for the same change reads in **Russian**. This is the single check that proves §3 and cannot be verified any other way.
  4. Accept on B. The event appears in both grids.
  5. Repeat with decline: it stays out of both grids, and A can see that the answer was no.
  6. Add an expense on A; B's chat announces it, in English.
  7. Create a **private** event on A; B's chat says nothing.

- [ ] **Step 6:** record the run in the spec's §7 and commit.

---

## Notes for the reviewer

**The two-language check in Task 8 Step 5.3 is the point of this package**, not a formality. Everything else could pass while the chat is unreadable to one of the two people it exists for.

**The most likely silent failure is `SyncService`.** It keeps its own copy of the event map, separate from `EventRepositoryImpl.toFirestoreMap()`. Package B1 shipped a data-destroying defect from exactly that divergence — a field added to one and not the other, deleted on every sync.

**What this package does not do:** push notifications for acceptance; retrying a failed announcement; announcing profile or child-info edits; per-occurrence acceptance of a recurring series.
