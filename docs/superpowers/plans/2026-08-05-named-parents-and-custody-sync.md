# Named Parents and Custody Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each parent by name instead of as "Mom"/"Dad", and give the custody schedule a Firestore path so both phones see the same pattern.

**Architecture:** `"mom"`/`"dad"` stop being words and become opaque slot identifiers — slot 1 and slot 2 — assigned automatically by pairing rather than chosen. Every user-facing label goes through one pure function that resolves a slot to a person's name. The custody pattern gains one shared Firestore document per pair, keyed by a derived id, mirrored into Room which stays the source of truth. The two halves meet where pairing flips the accepter's slot: their local pattern is expressed in slot terms and must be complemented before it means anything again.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Hilt, Room 2.7.2, Firebase (Auth/Firestore/FCM), Cloud Functions (Node + mocha + sinon), `@firebase/rules-unit-testing` against the Firestore emulator, JUnit 4 + MockK + coroutines-test + Turbine.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-05-named-parents-and-custody-sync-design.md`. The implementation is not free to revisit its decisions.
- **Branch:** `feat/named-parents-and-custody-sync`, based on `main` (`2ca517f1`). Not stacked on another branch.
- **Gradle needs an explicit JDK on this machine.** System `JAVA_HOME` points at a broken JBR. Prefix every Gradle command in PowerShell with `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr";` (OpenJDK 21, verified present 4 August 2026).
- **`"mom"` and `"dad"` are never renamed.** They stay as the two slot identifiers in Room, in the Firestore document schema, and in `firestore.rules`. `Event.parentOwner` is part of the schema `EventRepositoryImpl.toFirestoreMap()` defines, and a co-parent on an older build must keep reading it.
- **No user-facing string says "Mom" or "Dad"** after Task 5. A parent is their name, or the agreed fallback.
- **Every new or changed string lands in all five locales in the same commit**: `values`, `values-cs`, `values-de`, `values-ru`, `values-uk`.
- **Never debug `firestore.rules` by deploying to production.** `firestore-tests/` runs the rules offline against the emulator; add the case there first. It needs a JDK 21+ on `PATH`, not just in `JAVA_HOME`.
- **Jetpack Compose only**, stateless composables with state in ViewModels, Hilt for DI, Material 3 tokens from `presentation/theme/`.
- **KDoc on public declarations; code, comments, docs and commit messages in English.** The user is addressed in Russian in chat only.
- **Conventional Commits.** Message bodies explain *why*.
- **minSdk 26** — beware `java.time` additions above it (`LocalDate.ofInstant` is API 34+).
- **Room schema changes** require entity change → version bump in `CoPlanlyDatabase` → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`) → exported schema in `app/schemas/`. Current version is 13.
- **Firestore writes from a suspend function inside `viewModelScope.launch` must be guarded.** An uncaught `PERMISSION_DENIED` crashes the app, it does not merely fail to sync.
- **detekt is red on `main`.** Measure the baseline in a worktree; only own this branch's delta.

## Verification commands

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

```bash
cd functions && npm test && npm run lint
```

```bash
cd firestore-tests && npm test
```

---

## File Structure

| File | Responsibility |
|---|---|
| `functions/index.js` | Slot assignment inside the `acceptPairingInvitation` transaction; shared-document deletion in `unpairCoParent`; the one-off backfill for pairs created before this change. |
| `presentation/common/ParentLabels.kt` *(new)* | The only place that turns a slot into a person's label. Pure. |
| `domain/custody/CustodyKey.kt` *(new)* | Derives the shared custody document id from two UIDs. Pure. |
| `domain/model/CustodyModel.kt` | Gains `complemented()` and `isEquivalentTo()`. Pure. |
| `data/repository/ParentSlotMigrator.kt` *(new)* | The local re-stamp pass run when this device's slot changes. One Room transaction, idempotent. |
| `data/remote/firebase/FirestoreCustodyDataSource.kt` *(new)* | Reads and writes `custody_models/{key}`. Owns the Room↔Firestore field mapping. |
| `data/repository/CustodyModelRepository.kt` | Write-through to Firestore, observe-with-backoff from it. Room stays the source of truth. |
| `presentation/pairing/CustodyConflictScreen.kt` *(new)* | One-time choice when both parents arrive with different patterns. |
| `presentation/calendar/components/CalendarBanners.kt` | Gains `CustodyChangedBanner`, alongside `ChangeRequestBanner` and `VacationBanner`. |
| `firestore.rules`, `firestore-tests/rules/custody-models.test.js` *(new)* | The `custody_models` rule and its emulator coverage. |

## Sequencing

1 → 2 → 3 → 4 → 5 → 6 → **0** → 7 → 8 → 9 → 10 → 11 → 12 → 12b → 13.

Task 0 is numbered zero because it belongs to Part A and was written after Part A merged:
it is the three defects the Part A reviews deferred rather than new feature work. It runs
first in the Part B round because two of the three are visible to a user today.

Task 12b was added in the Part B pre-flight scan: the Task 12 backfill flips a slot
server-side for pairs that paired long ago, and nothing on those devices re-stamps in
response — `ParentSlotMigrator.reslot` is reachable only from the accept path. Without 12b
the backfill inflicts exactly the damage Task 2 exists to prevent.

Part A is Tasks 1–6 and stands on its own: after Task 6 the app shows names and defaults an event to you, with no custody sync at all. Part B is Tasks 7–12. Task 11 (the conflict screen) is the one place they cross and it depends on both Task 2 (the slot flip) and Task 7 (`complemented`). Task 13 is device acceptance and closes both.

If the branch has to be cut short, cut it after Task 6. Part B without Part A works, but the conflict screen without the slot flip shows a parent their own schedule inverted.

---

## Task 0: The three defects Part A deferred

Added after Part A merged (PR #44). Two of these are visible to a user right now; the third
is cheap only until something else copies it. All three are recorded in the ledger with their
diagnosis — this task re-states it so the implementer needs nothing else.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/common/ParentsSource.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/common/ParentNames.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/event/AddEditEventScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/data/sync/CalendarSyncRepository.kt`
- Modify: `app/src/main/res/values{,-cs,-de,-ru,-uk}/common_strings.xml`
- Test: `app/src/test/java/com/coparently/app/presentation/common/` (whichever `ParentsSource`
  / `ParentNames` tests exist — find them, do not assume the file name)

**Interfaces:**
- Consumes: nothing.
- Produces: `Parents.loaded: Boolean`; `ParentNames.isKnown(slot: String): Boolean`.
  Nothing in Part B depends on either, but every later task that reads `Parents` gets them.

### Defect 1 — the parent selector flashes open on every new-event screen

`AddEditEventScreen.kt:711` gates the selector on `isPaired || parentOwner == null`.
`EventViewModel.kt:83` seeds its `parents` StateFlow with `Parents()`, whose defaults are
`me = null, coParent = null, isPaired = false`, so on the first composition the gate is
`false || true` → the two-card block renders. When `ParentsSource` resolves, the
`LaunchedEffect(currentUser)` at `:286` sets `parentOwner`, the gate turns false, and the
block disappears — a layout jump in the middle of a form, for every user including one with
no co-parent at all. `ParentsSource.shared` uses `replayExpirationMillis = 0`, so this
happens on any re-entry more than 5 s after the last screen left, not only on cold start.

**The fix is to make "not loaded yet" representable.** `Parents` cannot express it today, and
its own KDoc's claim that "a null `me` is a profile that has not loaded yet" is false — `me`
stays null forever for an account `UserRepositoryImpl` never wrote a Room row for. Add:

```kotlin
data class Parents(
    val me: NamedParent? = null,
    val coParent: NamedParent? = null,
    val isPaired: Boolean = false,
    /**
     * Whether this is a real answer rather than the synthetic starting value.
     *
     * False only before [ParentsSource]'s upstream has emitted once. It is not "we know who
     * both parents are": [me] can be null in a loaded answer forever, for an account with no
     * Room profile row. A control that appears and then vanishes is worse than one that
     * appears late, so anything that *hides* itself once the answer arrives waits on this.
     */
    val loaded: Boolean = false
)
```

set `loaded = true` inside the `combine` in `ParentsSource.shared`, and gate the selector on
it. Correct the `Parents` KDoc's null-`me` sentence in the same change.

### Defect 2 — the escape hatch offers two cards with the same label

When `me` is null and there is no co-parent, both `parentNames.labelFor("mom")` and
`labelFor("dad")` fall through `parentLabel`'s `else` branch to `parent_label_unknown`
("Родитель"), so `AddEditEventScreen.kt:722` renders two cards reading "Родитель" and
"Родитель", told apart only by tint after one is selected. That is a choice with no caption.

**`parentLabel` does not change** — not its signature, not its semantics, not its three
fallbacks. It answers "who is this", and "we do not know" is the correct answer it already
gives. The selector is asking a different question: it is a slot picker, and slots have an
order. Add to `ParentNames`:

```kotlin
/** Whether [slot] resolves to a named person rather than to the unknown fallback. */
fun isKnown(slot: String): Boolean
```

and in the selector, when `!isKnown(slot)`, render the ordinal instead of the unknown
fallback. RULING (human, 5 August 2026): the ordinals, not "You"/"Co-parent" positionally —
an unpaired account's slot may be either one, and captioning slot 1 "You" is the same
inversion this branch exists to remove.

New keys, in `common_strings.xml` beside `parent_label_unknown`, in all five locales:

| key | en | cs | de | ru | uk |
|---|---|---|---|---|---|
| `parent_label_slot_first` | First parent | První rodič | Erster Elternteil | Первый родитель | Перший з батьків |
| `parent_label_slot_second` | Second parent | Druhý rodič | Zweiter Elternteil | Второй родитель | Другий з батьків |

Verify with a grep that each key appears exactly five times across `values*` —
`MissingTranslation` is disabled outright in `app/build.gradle.kts`, not merely non-fatal.

### Defect 3 — a data-layer repository imports from presentation

`CalendarSyncRepository.kt` (package `com.coparently.app.data.sync`) imports
`com.coparently.app.presentation.common.ParentsSource` and injects it, for one call at `:50`:

```kotlin
val ownerSlot = parentsSource.signedInSlot()
    ?: throw IllegalStateException("Not signed in. Please sign in to CoPlanly.")
```

This is the first `data → presentation` edge in the tree and it contradicts the architecture
map in `CLAUDE.md`. `signedInSlot()` is two calls on a domain interface:

```kotlin
val uid = userRepository.getCurrentUserId() ?: return null
return userRepository.getUserById(uid)?.role
```

so the repository takes `UserRepository` (already `@Binds`-bound in `FirebaseModule.kt:133`)
and does the lookup itself. `ParentsSource.signedInSlot()` stays where it is — its other two
callers (`EventSuggestionsViewModel.kt:62`, `EventViewModel.kt:406`) are presentation and are
correct as they are. Update any test that constructs `CalendarSyncRepository`.

- [ ] **Step 1: Find the existing tests before changing anything**

```bash
git grep -ln "ParentsSource\|CalendarSyncRepository" -- app/src/test
```

Whatever comes back is what you extend, and what must still pass. Do not create a parallel
test file for a class that already has one.

- [ ] **Step 2: Write the failing tests**

Three properties, one per defect:

1. `ParentsSource.observe()` emits `loaded = true` on its first real emission, and
   `Parents()` — the synthetic starting value every `stateIn` uses — has `loaded = false`.
2. `ParentNames.isKnown` is false for both slots when `me` and `coParent` are both null, and
   true for a slot that matches a named parent.
3. `CalendarSyncRepository` reads the owner slot through `UserRepository`: with
   `getCurrentUserId()` returning a uid and `getUserById(uid)` a `User(role = "dad")`, the
   entity it builds is stamped `"dad"`; with `getCurrentUserId()` null it throws rather than
   stamping anything.

The selector's flash is Compose state and this repo has no Compose UI tests (the plan's
testing strategy says so). Assert the property that causes it — `loaded` — not the pixels.

- [ ] **Step 3: Run them to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.common.*" --tests "com.coparently.app.data.sync.*"
```

- [ ] **Step 4: Implement all three**

As described above. Keep them in three commits, one per defect — they share no code and a
reviewer should be able to judge the layering change without reading the copy change.

- [ ] **Step 5: Build and run the full suite**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

- [ ] **Step 6: Confirm the layering edge is gone**

```bash
git grep -n "presentation" -- app/src/main/java/com/coparently/app/data
```

Expected: no output. Any hit is another edge of the same kind.

- [ ] **Step 7: Commit**

Three commits:

```
fix(events): stop the parent selector flashing open before the profile loads

The selector hides itself once it knows there is nobody to choose between, and
"knows" was not representable: Parents() seeds isPaired=false and me=null, which
is indistinguishable from a loaded answer for an unpaired account. So the gate
was true on every first composition and the block appeared and vanished mid-form
- including for a family of one, which is the case it exists to spare.

Parents gains `loaded`, false only before the upstream has emitted once. Not
"both parents are known": me stays null forever for an account with no Room
profile row, and the KDoc claiming otherwise is corrected in the same change.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

```
fix(events): caption the slot picker when neither parent is known

With no profile row and no co-parent, both cards resolved to the unknown
fallback and read "Родитель" twice - a choice between two options with the same
name, told apart only by tint after one was already chosen.

parentLabel is untouched: "we do not know who this is" is the right answer to the
question it asks, and guessing is what this branch exists to remove. The selector
asks a different question - it picks a slot, and slots have an order - so it
captions them first and second when, and only when, neither resolves to a person.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

```
refactor(sync): read the owner slot from the domain, not from presentation

CalendarSyncRepository is in data/ and imported ParentsSource from presentation/
for one call. It is the first data -> presentation edge in the tree and it
contradicts the architecture map in CLAUDE.md.

signedInSlot() is two calls on UserRepository, a domain interface already bound
in the graph, so the repository makes them itself. ParentsSource keeps the helper
for its presentation-layer callers, which are where it belongs.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 1: Pairing assigns the second slot

**Files:**
- Modify: `functions/index.js` — the `acceptPairingInvitation` transaction, around lines 455–475
- Test: `functions/test/pairing.test.js`

**Interfaces:**
- Consumes: nothing.
- Produces: after a successful pairing, the inviter's `users/{uid}.role` is `"mom"` and the accepter's is `"dad"`. Task 2 reacts to that flip on the accepter's device.

- [ ] **Step 1: Read the transaction you are changing**

Open `functions/index.js` and find the block that reads both user documents and calls `tx.update(inviterRef, …)` / `tx.update(accepterRef, …)`. Both snapshots are already loaded as `inviterSnap` and `accepterSnap`. You are adding two fields to updates that already exist — not a new read, not a new transaction.

- [ ] **Step 2: Write the failing test**

Append to `functions/test/pairing.test.js`, inside the existing `describe('acceptPairingInvitation', …)`:

```javascript
  it('puts the two parents in different slots', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('mom'),
        {inviterRole: 'mom', accepterRole: 'dad'},
        'a pair where both defaulted to mom must be separated');
  });

  it('keeps the inviter slot and gives the accepter the other one', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('dad'),
        {inviterRole: 'dad', accepterRole: 'mom'});
  });

  it('is idempotent for a pair that is already separated', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('mom'),
        {inviterRole: 'mom', accepterRole: 'dad'});
  });

  it('falls back to mom for the inviter when no slot is stored', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots(undefined),
        {inviterRole: 'mom', accepterRole: 'dad'});
  });
```

- [ ] **Step 3: Run it to verify it fails**

```bash
cd functions && npm test
```

Expected: FAIL — `assignSlots is not a function`.

- [ ] **Step 4: Implement `assignSlots` and wire it into the transaction**

Add near the other helpers in `functions/index.js`:

```javascript
/**
 * The two parent slots after pairing.
 *
 * "mom" and "dad" are slot identifiers, not roles: no user picks them and no screen shows
 * them. What matters is only that the two parents end up in different slots, so custody,
 * event ownership and parent colours can tell them apart. The inviter keeps whatever slot
 * they already had — their existing events are stamped with it — and the accepter takes the
 * other one, which is why the accepter's device has re-stamping to do (ParentSlotMigrator).
 *
 * The accepter's own stored slot never factors in: their slot is always the strict
 * inverse of the inviter's, whatever value they currently carry.
 *
 * @param {string|undefined} inviterRole Slot stored on the inviter, if any.
 * @return {{inviterRole: string, accepterRole: string}} The slots to write.
 */
function assignSlots(inviterRole) {
  const inviter = inviterRole === 'dad' ? 'dad' : 'mom';
  return {inviterRole: inviter, accepterRole: inviter === 'mom' ? 'dad' : 'mom'};
}
exports.assignSlots = assignSlots;
```

Then, inside the transaction, replace the two existing update calls:

```javascript
    tx.update(inviterRef, {partnerId: acceptingUserId, pairedAt});
    tx.update(accepterRef, {partnerId: invite.fromUserId, pairedAt});
```

with:

```javascript
    const slots = assignSlots(inviterSnap.data().role);
    tx.update(inviterRef, {
      partnerId: acceptingUserId, pairedAt, role: slots.inviterRole,
    });
    tx.update(accepterRef, {
      partnerId: invite.fromUserId, pairedAt, role: slots.accepterRole,
    });
```

- [ ] **Step 5: Run the tests and the linter**

```bash
cd functions && npm test && npm run lint
```

Expected: PASS, no lint errors.

- [ ] **Step 6: Commit**

```bash
git add functions/index.js functions/test/pairing.test.js
git commit
```

Message:

```
feat(pairing): put the two parents in different slots

Every account starts at "mom" because that is what DEFAULT_ROLE gives it, and
pairing never changed it - so both parents in every existing pair occupy slot 1.
Nothing downstream can tell them apart: custody colouring, event ownership and
the expense payer all read the same value for both people.

The inviter keeps the slot their existing events are already stamped with and
the accepter takes the other one. Assigning it here, inside the transaction that
already writes both user documents, is not a convenience: firestore.rules lets a
client write only its own users/{uid}, and working around that is why the
permissive ruleset once had to be deployed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 2: Re-stamp what the accepter already owns

**Files:**
- Create: `app/src/main/java/com/coparently/app/data/repository/ParentSlotMigrator.kt`
- Create: `app/src/test/java/com/coparently/app/data/repository/ParentSlotMigratorTest.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt` — both the `acceptIncoming` path and the `redeemCode` path (manual code entry, QR scan and deep link all funnel through `PairingRepository.redeem`, which reaches the same `acceptPairingInvitation` callable and can flip this device's slot the same way; both paths must reach the migrator through one shared comparison, not two copies of it)

**Interfaces:**
- Consumes: Task 1's slot assignment — after `acceptInvitation` returns, this device may occupy a different slot than it did.
- Produces: `ParentSlotMigrator.reslot(from: String, to: String, myUid: String)` — suspending, idempotent, one Room transaction.

- [ ] **Step 1: Add the DAO queries the migration needs**

In `EventDao.kt`, next to the other `@Query` declarations:

```kotlin
    /**
     * Re-stamps the parent slot on events this user created. Used when pairing moves this
     * device from one slot to the other; without it, every event the accepter created before
     * pairing reads as the co-parent's.
     */
    @Query(
        "UPDATE events SET parentOwner = :to " +
            "WHERE parentOwner = :from AND createdByFirebaseUid = :myUid"
    )
    suspend fun reslotOwner(from: String, to: String, myUid: String): Int

    /** Re-stamps a recorded pickup confirmation for the same reason as [reslotOwner]. */
    @Query(
        "UPDATE events SET pickupConfirmedBy = :to " +
            "WHERE pickupConfirmedBy = :from AND createdByFirebaseUid = :myUid"
    )
    suspend fun reslotPickup(from: String, to: String, myUid: String): Int
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/coparently/app/data/repository/ParentSlotMigratorTest.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.EventDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The re-stamp runs at the moment a user experiences as "I tapped Accept". A half-completed
 * pass leaves every event they ever created attributed to their co-parent, so these tests
 * pin the two properties that stop that: it touches only rows this user created, and running
 * it twice changes nothing the second time.
 */
class ParentSlotMigratorTest {

    private val eventDao: EventDao = mockk(relaxed = true)
    private val database: CoPlanlyDatabase = mockk()
    private val migrator = ParentSlotMigrator(database, eventDao)

    @Before
    fun setup() {
        // `withTransaction` is an extension on RoomDatabase, so it is mocked statically and
        // made to simply run its block — these tests are about which rows the migration
        // targets, not about Room's transaction machinery.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Int>()) } coAnswers {
            // secondArg, not firstArg: `withTransaction` is an extension on RoomDatabase, so
            // under static mocking the receiver occupies argument 0 and the block is argument 1.
            secondArg<suspend () -> Int>().invoke()
        }
    }

    @After
    fun tearDown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    @Test
    fun `re-stamps only rows this user created`() = runTest {
        coEvery { eventDao.reslotOwner(any(), any(), any()) } returns 3
        coEvery { eventDao.reslotPickup(any(), any(), any()) } returns 1

        migrator.reslot(from = "mom", to = "dad", myUid = "u1")

        coVerify(exactly = 1) { eventDao.reslotOwner("mom", "dad", "u1") }
        coVerify(exactly = 1) { eventDao.reslotPickup("mom", "dad", "u1") }
    }

    @Test
    fun `a slot that did not change is a no-op`() = runTest {
        migrator.reslot(from = "mom", to = "mom", myUid = "u1")

        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
        coVerify(exactly = 0) { eventDao.reslotPickup(any(), any(), any()) }
    }

    @Test
    fun `running it twice is harmless because the second pass matches nothing`() = runTest {
        coEvery { eventDao.reslotOwner("mom", "dad", "u1") } returnsMany listOf(3, 0)
        coEvery { eventDao.reslotPickup("mom", "dad", "u1") } returnsMany listOf(1, 0)

        val first = migrator.reslot(from = "mom", to = "dad", myUid = "u1")
        val second = migrator.reslot(from = "mom", to = "dad", myUid = "u1")

        assertEquals(4, first)
        assertEquals(0, second)
    }

    @Test
    fun `a blank uid is refused rather than re-stamping everything`() = runTest {
        val failure = runCatching { migrator.reslot(from = "mom", to = "dad", myUid = "") }
        assert(failure.isFailure) { "a blank uid must not match every row in the table" }
        coVerify(exactly = 0) { eventDao.reslotOwner(any(), any(), any()) }
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.ParentSlotMigratorTest"
```

Expected: compilation failure — `Unresolved reference: ParentSlotMigrator`.

- [ ] **Step 4: Implement the migrator**

Create `app/src/main/java/com/coparently/app/data/repository/ParentSlotMigrator.kt`:

```kotlin
package com.coparently.app.data.repository

import androidx.room.withTransaction
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.dao.EventDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves this device's records from one parent slot to the other.
 *
 * An unpaired parent occupies slot 1 and everything they create is stamped with it. Accepting
 * an invitation moves them to slot 2, at which point their own past records would read as the
 * co-parent's. This re-stamps them.
 *
 * Scoped to rows this user created, so it can never touch the co-parent's records if it is
 * ever run on a device that already has both parents' data. Idempotent by construction: the
 * second run matches nothing, because the first left no rows in the old slot.
 */
@Singleton
class ParentSlotMigrator @Inject constructor(
    private val database: CoPlanlyDatabase,
    private val eventDao: EventDao
) {
    /**
     * Re-stamps this user's rows from [from] to [to].
     *
     * @param myUid Firebase UID of the signed-in user; must not be blank, or the scoping
     *   clause would match every row in the table including the co-parent's.
     * @return How many rows changed, across all tables touched.
     */
    suspend fun reslot(from: String, to: String, myUid: String): Int {
        require(myUid.isNotBlank()) { "reslot needs a uid to scope by" }
        if (from == to) return 0
        return database.withTransaction {
            eventDao.reslotOwner(from, to, myUid) + eventDao.reslotPickup(from, to, myUid)
        }
    }
}
```

Imports the test file needs: `androidx.room.withTransaction`, `com.coparently.app.data.local.CoPlanlyDatabase`, `io.mockk.mockkStatic`, `io.mockk.unmockkStatic`, `io.mockk.coEvery`, `org.junit.Before`, `org.junit.After`.

- [ ] **Step 5: Run the test to verify it passes**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.ParentSlotMigratorTest"
```

Expected: PASS.

- [ ] **Step 6: Call it when pairing succeeds**

In `PairingViewModel`, the `acceptIncoming(invitationId)` path calls the repository, which calls `PairingFunctions.acceptInvitation`. After that call returns successfully, read the signed-in user's slot before and after — the callable has just written it — and run the migrator:

```kotlin
    // Pairing may have moved this device to the other slot (see functions/index.js
    // assignSlots). Everything this user created before pairing is stamped with the old one.
    val before = userRepository.getCurrentUser()?.role
    pairingRepository.acceptInvitation(invitationId)
    val after = userRepository.getCurrentUser()?.role
    if (before != null && after != null && before != after) {
        parentSlotMigrator.reslot(from = before, to = after, myUid = uid)
    }
```

`getCurrentUser()` must re-read the remote document rather than a cached one, or `after` will equal `before`. If it reads Room only, add an explicit refresh of the user document before the second read.

- [ ] **Step 7: Build and run the full suite**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/repository/ParentSlotMigrator.kt app/src/main/java/com/coparently/app/data/local/dao/EventDao.kt app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt app/src/test/java/com/coparently/app/data/repository/ParentSlotMigratorTest.kt
git commit
```

Message:

```
feat(pairing): re-stamp this device's records when its slot changes

Accepting an invitation moves the accepter from slot 1 to slot 2. Everything
they created while unpaired is stamped with slot 1, so without this pass their
own history starts reading as their co-parent's the moment they pair - at the
exact point a user experiences as "I tapped Accept".

Scoped by createdByFirebaseUid so it can never reach the co-parent's rows, and
idempotent by construction: the second run matches nothing because the first
left nothing in the old slot. A crash mid-pairing therefore retries safely
instead of flipping twice.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 3: One function that knows a parent's name

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/common/ParentLabels.kt`
- Create: `app/src/test/java/com/coparently/app/presentation/common/ParentLabelsTest.kt`

**Interfaces:**
- Consumes: `com.coparently.app.domain.model.User`.
- Produces: `parentLabel(slot: String, me: User?, coParent: User?, youFallback: String, coParentFallback: String, unknownFallback: String): String`. Task 5 and Task 6 call it; the three fallbacks are passed in as already-resolved strings because a pure function cannot call `stringResource`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/common/ParentLabelsTest.kt`:

```kotlin
package com.coparently.app.presentation.common

import com.coparently.app.domain.model.User
import org.junit.Test
import kotlin.test.assertEquals

class ParentLabelsTest {

    private val me = User(
        id = "u1", email = "a@b.c", name = "Olya", role = "mom", colorCode = "#FF4081"
    )
    private val coParent = User(
        id = "u2", email = "d@e.f", name = "Pavel", role = "dad", colorCode = "#2196F3"
    )

    @Test
    fun `my own slot is my name`() {
        assertEquals(
            "Olya",
            parentLabel("mom", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the other slot is the co-parent's name`() {
        assertEquals(
            "Pavel",
            parentLabel("dad", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `my slot with no name stored falls back to You`() {
        assertEquals(
            "You",
            parentLabel("mom", me.copy(name = ""), coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `an unmatched slot with no co-parent resolves to unknown fallback`() {
        // When the co-parent hasn't loaded, their slot is unknown, not guessed.
        assertEquals(
            "Parent",
            parentLabel("dad", me, null, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `the other slot with a nameless co-parent falls back to Co-parent`() {
        assertEquals(
            "Co-parent",
            parentLabel("dad", me, coParent.copy(name = "   "), "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `both unloaded users resolve slots to the unknown fallback`() {
        assertEquals(
            "Parent",
            parentLabel("mom", null, null, "You", "Co-parent", "Parent")
        )
        assertEquals(
            "Parent",
            parentLabel("dad", null, null, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `an unmatched slot with known parents resolves to the unknown fallback`() {
        // Defensive: a stale row could carry a slot string from a future schema.
        assertEquals(
            "Parent",
            parentLabel("guardian", me, coParent, "You", "Co-parent", "Parent")
        )
    }

    @Test
    fun `a loaded co-parent does not cause the unknown me slot to be guessed`() {
        // Regression test: when me hasn't loaded, we must not guess "mom" is their slot.
        // With the bug (`?: "mom"`), calling parentLabel("mom", null, coParent) would
        // incorrectly return youFallback, guessing that an unknown me has role "mom".
        assertEquals(
            "Parent",
            parentLabel("mom", null, coParent, "You", "Co-parent", "Parent")
        )
    }
}
```

Note the last case: with `me.role == "mom"`, the slot `"guardian"` is not mine, so it resolves through the co-parent branch, and the co-parent's slot is `"dad"` — not a match either — so it falls back.

- [ ] **Step 2: Run it to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.common.ParentLabelsTest"
```

Expected: compilation failure — `Unresolved reference: parentLabel`.

- [ ] **Step 3: Implement it**

Create `app/src/main/java/com/coparently/app/presentation/common/ParentLabels.kt`:

```kotlin
package com.coparently.app.presentation.common

import com.coparently.app.domain.model.User

/**
 * The label for a parent slot: that person's name.
 *
 * `"mom"` and `"dad"` are slot identifiers, not roles — nobody chooses them and no screen
 * shows them. Every surface that names a parent resolves it here, so the app can never say
 * "Mom" in one place and a name in another, and so families the mom/dad model does not
 * describe are not told who they are.
 *
 * When a parent cannot be identified (their User is null, or the slot matches neither parent),
 * the function returns `unknownFallback`. It never guesses a slot: an unloaded profile or
 * an invalid slot identifier is a fact to report, not a coin to flip. This ensures that
 * after a cold start, before profiles load, the calendar shows "Parent" instead of inverting
 * the names by assuming who is who.
 *
 * The fallbacks arrive already resolved because this function is pure and cannot call
 * `stringResource`; composables resolve them in composable scope and pass them down, the same
 * way `CalendarScreen` resolves its snackbar strings before the effect that uses them.
 *
 * @param slot The stored slot identifier, typically `Event.parentOwner`.
 * @param me The signed-in user, or null before the profile has loaded.
 * @param coParent The paired co-parent, or null when unpaired.
 * @param youFallback Shown for my own slot when no name is stored.
 * @param coParentFallback Shown for the other slot when there is no co-parent or no name.
 * @param unknownFallback Shown when the parent cannot be identified (slot does not match either user).
 */
fun parentLabel(
    slot: String,
    me: User?,
    coParent: User?,
    youFallback: String,
    coParentFallback: String,
    unknownFallback: String
): String = when (slot) {
    me?.role -> me.name.trim().ifBlank { youFallback }
    coParent?.role -> coParent.name.trim().ifBlank { coParentFallback }
    else -> unknownFallback
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.common.ParentLabelsTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/common/ParentLabels.kt app/src/test/java/com/coparently/app/presentation/common/ParentLabelsTest.kt
git commit -m "feat(common): resolve a parent slot to a person's name in one place

Nine screens format a parent label today and every one of them hardcodes the
words Mom and Dad. Routing all of them through one function is what makes the
next task a rewrite of twenty strings rather than a hunt through nine files, and
what stops the app saying a name in one place and a role in another.

Fallbacks are parameters rather than resource lookups so the function stays pure
and testable; composables resolve them in composable scope, the same shape
CalendarScreen already uses for its snackbar strings.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Rewrite the strings so grammatical gender never arises

**Files:**
- Modify: `app/src/main/res/values/*.xml` and the four locale variants of the same files: `values-cs`, `values-de`, `values-ru`, `values-uk`

**Interfaces:**
- Consumes: nothing.
- Produces: the string keys Task 5 wires up.

**This task only adds keys. It deletes none.** The old role-specific keys stay in place and stay referenced until Task 5 has rewired their call sites, so this commit — like every other commit on the branch — builds. Task 5 deletes them once nothing points at them.

- [ ] **Step 1: Inventory the call sites before touching a single file**

```bash
git grep -n "calendar_parent_mom\|calendar_parent_dad\|custody_mom\|custody_dad\|custody_with_mom\|custody_with_dad\|custody_mom_starts_first\|custody_dad_starts_first\|custody_week1_to_mom\|custody_week2_to_mom\|event_parent_mom\|event_parent_dad\|event_preview_mom\|event_preview_dad\|expenses_mom_paid\|expenses_dad_paid\|home_parent_mom\|home_parent_dad\|calendar_day_desc_with_parent" -- app/src/main/java
```

Write the list into your report. Each hit is a place Task 5 has to change, and the surrounding layout decides which replacement shape fits.

- [ ] **Step 2: Apply the rule**

**The rule: a parent's name never appears in a sentence that agrees with it.** Russian, Czech and Ukrainian inflect verbs and adjectives for gender; "Оля заплатил" and "Оля заплатила" cannot be chosen between programmatically, and "заплатил(а)" reads as an apology for the software. German avoids verb agreement but still needs articles to agree with role nouns.

So every affected string becomes a label-and-value or a bare substitution:

| Old key(s) | New key | English base value |
|---|---|---|
| `calendar_parent_mom`, `calendar_parent_dad` | *(none — Task 5 deletes them)* | resolved by `parentLabel`, no resource |
| `event_parent_mom`, `event_parent_dad` | *(none — Task 5 deletes them)* | same |
| `event_preview_mom`, `event_preview_dad` | *(none — Task 5 deletes them)* | same |
| `home_parent_mom`, `home_parent_dad` | *(none — Task 5 deletes them)* | same |
| `custody_mom`, `custody_dad` | *(none — Task 5 deletes them)* | same |
| `expenses_mom_paid`, `expenses_dad_paid` | `expenses_paid_by` | `Paid by %1$s` |
| `custody_mom_starts_first`, `custody_dad_starts_first` | `custody_starts_first` | `Starts first: %1$s` |
| `custody_week1_to_mom`, `custody_week2_to_mom` | `custody_week_to` | `Week %1$d: %2$s` |
| `custody_with_mom`, `custody_with_dad` | `custody_with` | `With %1$s` |
| `calendar_day_desc_with_parent` | keep the key | reword so the parent is a trailing substitution, never a subject |

Add three fallback keys used by `parentLabel`:

```xml
<string name="parent_label_you">You</string>
<string name="parent_label_coparent">Co-parent</string>
<string name="parent_label_unknown">Parent</string>
```

- [ ] **Step 3: Write the four locales**

Each new key goes into `values-cs`, `values-de`, `values-ru`, `values-uk` in the same commit, in the same agreement-free shape. Russian examples, to fix the register:

```xml
<string name="expenses_paid_by">Оплатил(а): %1$s</string>   <!-- WRONG: still agrees -->
<string name="expenses_paid_by">Плательщик: %1$s</string>    <!-- right: a noun label -->
<string name="custody_starts_first">Первым идёт: %1$s</string>  <!-- WRONG -->
<string name="custody_starts_first">Начинает: %1$s</string>      <!-- right -->
<string name="custody_with">С кем: %1$s</string>
<string name="parent_label_you">Вы</string>
<string name="parent_label_coparent">Сородитель</string>
<string name="parent_label_unknown">Родитель</string>
```

The pattern is the same in Czech and Ukrainian: a noun label plus a colon, never a verb the name has to agree with.

- [ ] **Step 4: Verify no locale is missing a key**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew lint
```

**`MissingTranslation` will not help you here.** `app/build.gradle.kts:90` does `disable += "MissingTranslation"` — the check is off entirely, not merely non-fatal, despite the comment beside it claiming it "stays a warning". Verify locale completeness by grepping each new key across the five `values*` directories instead, and confirm the count is five every time.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res
git commit -m "feat(i18n): reword every parent string so a name never needs agreement

Showing a parent by name breaks twenty strings that were built on grammatical
gender: 'Мама заплатила %1\$s' and 'Папа заплатил %1\$s' have no form a name can
take, and 'заплатил(а)' reads as the software apologising. The same holds in
Czech and Ukrainian.

So the phrasings change shape rather than being translated: a noun label and a
value, never a sentence the name is the subject of. Ten role-specific keys become
unnecessary entirely - the label is now the person's name, which is not a
resource - and the next commit deletes them once nothing points at them.

Nothing is removed here, so this commit builds like every other one on the
branch. The split exists so the copy can be reviewed as copy rather than hunted
for inside a diff across eleven screens.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Every screen shows the name

**Files:**
- Modify: `presentation/calendar/CalendarScreen.kt`, `presentation/calendar/MonthView.kt`, `presentation/calendar/DayWeekView.kt`, `presentation/calendar/components/CustodyRibbon.kt`, `presentation/custody/CustodySetupScreen.kt`, `presentation/event/EventPreviewSheet.kt`, `presentation/event/EventListScreen.kt`, `presentation/expenses/ExpenseList.kt`, `presentation/expenses/ExpenseSummaryHeader.kt`, `presentation/home/HomeScreen.kt`, `presentation/summary/WeeklySummaryScreen.kt`
- Modify: `presentation/theme/ParentColors.kt` — KDoc only
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `parentLabel(...)` from Task 3; the string keys from Task 4.
- Produces: no new API. After this task the build compiles again and no screen renders the words Mom or Dad.

- [ ] **Step 1: Make the two users available where labels are rendered**

Each screen's ViewModel already has, or can add, the signed-in user and the co-parent. `ExpenseViewModel` shows the shape to copy: it collects `userRepository.getAllUsers()` and derives `roleByUid`. Expose from each affected ViewModel:

```kotlin
    /** Signed-in user and paired co-parent, for resolving parent labels. */
    val parents: StateFlow<Pair<User?, User?>> = /* me to coParent */
```

Do not inject `Context` into a ViewModel to resolve the fallback strings — that is called out in `CLAUDE.md` as a thing not to do ad hoc. Resolve them in the composable:

```kotlin
val youFallback = stringResource(R.string.parent_label_you)
val coParentFallback = stringResource(R.string.parent_label_coparent)
val unknownFallback = stringResource(R.string.parent_label_unknown)
val (me, coParent) = parents
val label = parentLabel(event.parentOwner, me, coParent, youFallback, coParentFallback, unknownFallback)
```

- [ ] **Step 2: Replace every deleted key's call site**

Work through the list from Task 4 Step 1. A call that was

```kotlin
Text(stringResource(if (parentOwner == "mom") R.string.event_parent_mom else R.string.event_parent_dad))
```

becomes

```kotlin
Text(parentLabel(parentOwner, me, coParent, youFallback, coParentFallback, unknownFallback))
```

- [ ] **Step 3: Update `ParentColors` KDoc**

No code change. Replace the KDoc's claim that the colours mean Mom and Dad with what is now true:

```kotlin
/**
 * Parent identity colours.
 *
 * A colour identifies a *parent*, not a role: the app no longer shows the words Mom and Dad,
 * and `"mom"`/`"dad"` survive only as slot identifiers assigned by pairing. Slot 1 is pink and
 * slot 2 is blue. Which person holds which slot is decided in `functions/index.js`
 * (`assignSlots`) and shown by name everywhere else.
 *
 * `fill()` for dots, bars and tints; `text()` for anything that is a foreground — the raw
 * palette entries are fill-only and fail AA as text. The saturation rule is unchanged: a
 * custody day background is the hue at ~14% alpha, a chip or dot is the same hue at full
 * strength.
 */
```

- [ ] **Step 4: Update `CLAUDE.md`**

In the "Hard project rules" list, replace

> - Parent color semantics are product-level: **Mom = pink, Dad = blue** — do not repurpose.

with

> - **Parent colours identify a person, not a role.** The app never shows the words "Mom" or
>   "Dad": every parent label goes through `presentation/common/ParentLabels.kt` and renders
>   that person's name. `"mom"`/`"dad"` survive as the two *slot identifiers* in Room, in the
>   Firestore document schema and in `firestore.rules`, and are never renamed — `Event.parentOwner`
>   is part of the schema `EventRepositoryImpl.toFirestoreMap()` defines, and a co-parent on an
>   older build must keep reading it. Slot 1 is pink, slot 2 is blue; pairing assigns the slots
>   (`functions/index.js`, `assignSlots`), nobody chooses one.

- [ ] **Step 5: Delete the role keys nothing points at any more**

Task 4 deliberately left them in place so its own commit would build. Now that every call site
resolves through `parentLabel`, remove these from `values` and all four locale variants:
`calendar_parent_mom`, `calendar_parent_dad`, `event_parent_mom`, `event_parent_dad`,
`event_preview_mom`, `event_preview_dad`, `home_parent_mom`, `home_parent_dad`, `custody_mom`,
`custody_dad`, and the old gendered pairs `expenses_mom_paid`, `expenses_dad_paid`,
`custody_mom_starts_first`, `custody_dad_starts_first`, `custody_week1_to_mom`,
`custody_week2_to_mom`, `custody_with_mom`, `custody_with_dad`.

Verify each is unreferenced before deleting it:

```bash
git grep -n "R.string.calendar_parent_mom\|R.string.custody_with_dad" -- app/src/main/java
```

Expected: no output. Repeat for every key in the list; a hit means Step 2 missed a call site.

- [ ] **Step 6: Build and run the full suite**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest lint detekt
```

Expected: BUILD SUCCESSFUL. detekt is red on `main`; compare in a worktree and own only this branch's delta.

- [ ] **Step 7: Confirm no user-facing role words survive**

```bash
git grep -n "Мама\|Папа\|Máma\|Táta" -- app/src/main/res
```

Expected: no output. Any hit is a string Task 4 missed.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java app/src/main/res CLAUDE.md
git commit -m "feat(ui): show each parent by name instead of as Mom or Dad

Eleven screens formatted a parent label and every one of them chose between two
hardcoded role words. They now all resolve through parentLabel, so a name in one
place and a role in another is no longer expressible.

ParentColors keeps its code and loses its rationale: the colour identifies the
person in slot 1 or slot 2, not a mother or a father. CLAUDE.md's invariant is
rewritten in the same commit rather than left to be discovered - it said the
colours were role semantics and that is no longer what they are.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: An event is yours by default

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/event/AddEditEventScreen.kt` — lines 151, 314, 394 and the parent selector around line 658

**Interfaces:**
- Consumes: `parentLabel(...)` from Task 3; the signed-in user's slot.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Default the selector to the signed-in user's slot**

Line 151 is `var parentOwner by remember { mutableStateOf("mom") }`. It becomes nullable, seeded from the current user:

```kotlin
    // Yours unless you say otherwise. Hardcoding "mom" here meant every event either parent
    // created was attributed to the same person.
    var parentOwner by remember(currentUser) { mutableStateOf(currentUser?.role) }
```

Lines 314 and 394 hardcode `parentOwner = "mom"` for the draft-clearing and new-event paths; both become `currentUser?.role`.

- [ ] **Step 2: Hide the selector when there is no co-parent**

Wrap the selector block (around line 658) so it renders only when a co-parent exists. With nobody to choose between, the control offers a choice that does not exist:

```kotlin
    if (coParent != null) {
        // …existing selector, with each option's label from parentLabel(...)
    }
```

- [ ] **Step 3: Require an explicit choice when the slot is unknown**

Save is disabled while `parentOwner == null`. Through pairing this is unreachable — every account has a slot — but nothing anywhere may quietly fall back to `"mom"` again, which is the defect this whole branch exists to remove.

```kotlin
    Button(
        onClick = { /* existing save */ },
        enabled = title.isNotBlank() && parentOwner != null
    ) { /* … */ }
```

- [ ] **Step 4: Build and run the suite**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/event/AddEditEventScreen.kt
git commit -m "feat(events): create an event for yourself by default

The editor seeded parentOwner to \"mom\" regardless of who was using it, so one
of the two parents had to correct the owner on every single event and the other
never noticed the default was there at all.

It now seeds from the signer's own slot, and disappears entirely when there is
no co-parent - a two-option control for a family of one. The event stays shared:
\"for yourself\" means owned by you, not hidden from the other parent, because
what happens on your day is exactly what a shared calendar is for.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: The custody primitives

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/custody/CustodyKey.kt`
- Create: `app/src/test/java/com/coparently/app/domain/custody/CustodyKeyTest.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/model/CustodyModel.kt`
- Modify (or create): `app/src/test/java/com/coparently/app/domain/model/CustodyModelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CustodyKey.of(uidA: String, uidB: String): String`
  - `CustodyModel.complemented(): CustodyModel`
  - `CustodyModel.isEquivalentTo(other: CustodyModel): Boolean`

- [ ] **Step 1: Write the failing `CustodyKey` test**

Create `app/src/test/java/com/coparently/app/domain/custody/CustodyKeyTest.kt`:

```kotlin
package com.coparently.app.domain.custody

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CustodyKeyTest {

    @Test
    fun `the same pair yields the same id in either order`() {
        assertEquals(CustodyKey.of("aaa", "bbb"), CustodyKey.of("bbb", "aaa"))
    }

    @Test
    fun `the id is the two uids sorted and joined`() {
        assertEquals("aaa__bbb", CustodyKey.of("bbb", "aaa"))
    }

    @Test
    fun `different pairs yield different ids`() {
        assert(CustodyKey.of("aaa", "bbb") != CustodyKey.of("aaa", "ccc"))
    }

    @Test
    fun `a blank uid is refused`() {
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("", "bbb") }
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("aaa", "  ") }
    }

    @Test
    fun `a user has no custody arrangement with themselves`() {
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("aaa", "aaa") }
    }

    @Test
    fun `a uid containing the separator is refused`() {
        // Without this, of("x__y", "z") and of("x", "y__z") both join to "x__y__z":
        // two different pairs colliding on one document.
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("x__y", "z") }
        assertFailsWith<IllegalArgumentException> { CustodyKey.of("x", "y__z") }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.custody.CustodyKeyTest"
```

Expected: compilation failure — `Unresolved reference: CustodyKey`.

- [ ] **Step 3: Implement `CustodyKey`**

Create `app/src/main/java/com/coparently/app/domain/custody/CustodyKey.kt`:

```kotlin
package com.coparently.app.domain.custody

/**
 * The id of the single custody document shared by a pair of co-parents.
 *
 * Derived from the two UIDs rather than generated, so both devices arrive at the same id with
 * no query and no coordination, and creating the document is idempotent. The same shape as
 * [com.coparently.app.domain.chat.ConversationKey], for the same reason: randomly generated
 * ids are what once settled the two phones on separate chat threads.
 */
object CustodyKey {

    /** Separator between the two sorted UIDs; not a Firebase UID character. */
    private const val SEPARATOR = "__"

    /**
     * Returns the custody document id for the pair [uidA]/[uidB], in either order.
     *
     * @throws IllegalArgumentException if either uid is blank, the two are equal — a user has
     *   no custody arrangement with themselves — or either contains [SEPARATOR]. Without that
     *   last check `of("x__y", "z")` and `of("x", "y__z")` both join to `"x__y__z"`: different
     *   pairs colliding on one document, which is exactly what this function prevents.
     */
    fun of(uidA: String, uidB: String): String {
        require(uidA.isNotBlank() && uidB.isNotBlank()) { "Both uids must be non-blank" }
        require(uidA != uidB) { "A user has no custody arrangement with themselves" }
        require(!uidA.contains(SEPARATOR) && !uidB.contains(SEPARATOR)) {
            "A uid must not contain the separator '$SEPARATOR'"
        }
        return listOf(uidA, uidB).sorted().joinToString(SEPARATOR)
    }
}
```

- [ ] **Step 4: Write the failing `complemented` and `isEquivalentTo` tests**

Append to `app/src/test/java/com/coparently/app/domain/model/CustodyModelTest.kt` (create the file with the same package and imports if it does not exist):

```kotlin
    private fun model(
        patternDays: Int,
        momDays: Set<Int>,
        start: LocalDate = LocalDate.of(2026, 8, 3)
    ) = CustodyModel(
        id = "m", modelType = CustodyModelType.CUSTOM,
        patternDays = patternDays, momDayIndices = momDays, startDate = start
    )

    @Test
    fun `complemented swaps which days belong to which slot`() {
        val original = model(4, setOf(0, 1))
        assertEquals(setOf(2, 3), original.complemented().momDayIndices)
    }

    @Test
    fun `complementing twice returns the original`() {
        val original = model(14, setOf(0, 1, 4, 5, 6, 9, 10))
        assertEquals(original.momDayIndices, original.complemented().complemented().momDayIndices)
    }

    @Test
    fun `complementing a full set yields an empty one and back`() {
        val everyDay = model(7, (0..6).toSet())
        assertEquals(emptySet(), everyDay.complemented().momDayIndices)
        assertEquals((0..6).toSet(), everyDay.complemented().complemented().momDayIndices)
    }

    @Test
    fun `a model is equivalent to itself`() {
        val m = model(14, setOf(0, 1, 2, 3, 4, 5, 6))
        assert(m.isEquivalentTo(m))
    }

    @Test
    fun `a start date shifted by a whole cycle describes the same schedule`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 3))
        val b = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 17))
        assert(a.isEquivalentTo(b)) { "14 days later in a 14-day cycle is the same pattern" }
    }

    @Test
    fun `a start date shifted by part of a cycle describes a different schedule`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 3))
        val b = model(14, setOf(0, 1, 2, 3, 4, 5, 6), LocalDate.of(2026, 8, 10))
        assert(!a.isEquivalentTo(b))
    }

    @Test
    fun `a complemented model is not equivalent to the original`() {
        val a = model(14, setOf(0, 1, 2, 3, 4, 5, 6))
        assert(!a.isEquivalentTo(a.complemented()))
    }

    @Test
    fun `cycles of different lengths are compared over their least common multiple`() {
        // A 14-day and a 21-day pattern can agree for the first 14 days and diverge after.
        // Comparing over a fixed window would call these equivalent.
        val fortnight = model(14, (0..6).toSet(), LocalDate.of(2026, 8, 3))
        val threeWeeks = model(21, (0..6).toSet(), LocalDate.of(2026, 8, 3))
        assert(!fortnight.isEquivalentTo(threeWeeks))
    }
```

- [ ] **Step 5: Run them to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.model.CustodyModelTest"
```

Expected: compilation failure — `Unresolved reference: complemented`.

- [ ] **Step 6: Implement both**

Add to `CustodyModel.kt`, inside the class:

```kotlin
    /**
     * This pattern with the two slots swapped.
     *
     * [momDayIndices] means "the days slot 1 has custody". When pairing moves this device to
     * the other slot, the same set would silently start describing the co-parent's days, so
     * the set is complemented to keep meaning "my days".
     *
     * Getting this wrong is not a cosmetic bug: the pairing conflict screen would offer a
     * parent their own schedule inverted, they would reject it, and hand over exactly the days
     * they meant to keep.
     */
    fun complemented(): CustodyModel =
        copy(momDayIndices = (0 until patternDays).toSet() - momDayIndices)

    /**
     * Whether [other] assigns custody the same way this model does, on every day.
     *
     * Compared by outcome rather than by field: two models with start dates a whole number of
     * cycles apart describe the same schedule, and two different [modelType]s can produce
     * identical assignments. The window is the least common multiple of the two cycle lengths,
     * because a shorter window can make a 14-day and a 21-day pattern look identical.
     */
    fun isEquivalentTo(other: CustodyModel): Boolean {
        if (patternDays <= 0 || other.patternDays <= 0) return false
        val window = lcm(patternDays, other.patternDays)
        val from = minOf(startDate, other.startDate)
        return (0 until window).all { offset ->
            val date = from.plusDays(offset.toLong())
            getCustodyFor(date) == other.getCustodyFor(date)
        }
    }
```

and, as a private top-level helper in the same file:

```kotlin
/** Least common multiple, for sizing the comparison window in [CustodyModel.isEquivalentTo]. */
private fun lcm(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return a / x * b
}
```

- [ ] **Step 7: Run both test classes to verify they pass**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.*"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain app/src/test/java/com/coparently/app/domain
git commit -m "feat(custody): derive the shared document id and compare patterns by outcome

Three pure functions the rest of the custody sync stands on.

CustodyKey mirrors ConversationKey exactly, including the separator check that
stops of(\"x__y\",\"z\") and of(\"x\",\"y__z\") colliding on one document - two
different pairs sharing a custody schedule is the worst failure this feature
could have.

complemented() exists because momDayIndices means \"slot 1's days\", so a parent
whose slot flips at pairing needs the set inverted to keep meaning \"my days\".
isEquivalentTo compares outcomes, not fields, over the least common multiple of
the two cycle lengths: a fixed window makes a fortnightly and a three-weekly
pattern look identical.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: The rule, and the dead one removed

**Files:**
- Modify: `firestore.rules`
- Create: `firestore-tests/rules/custody-models.test.js`
- Modify: `CLAUDE.md` — the "known issues" bullet that records `custody_schedules` as a dead
  rule left in place

**Interfaces:**
- Consumes: the document shape Task 9 writes — `participants: [uidA, uidB]`, `lastModifiedBy`, and the pattern fields.
- Produces: the deployed rule Task 9's writes must satisfy.

- [ ] **Step 1: Write the failing rules test**

Create `firestore-tests/rules/custody-models.test.js`. The harness API below is the real one,
verified 5 August 2026 against `firestore-tests/harness.js:68` — an earlier draft of this plan
invented `rulesFixture`/`as`/`asAdmin`, none of which exist. `seed()` writes with rules
disabled and is what stands in for an admin write. Mocha globs `rules/**/*.test.js`, so the
file needs no registration; a root hook already tears the environment down.

```javascript
/**
 * The one custody document a pair shares. Gated on a `participants` array that must match the
 * derived document id; read by id only, so no list query has to mirror the rule.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-custody';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const STRANGER = 'uid-stranger';
const KEY = [MOM, DAD].sort().join('__');
const PATH = `custody_models/${KEY}`;

const PAIRED_USERS = {
  'users/uid-mom': {name: 'Olya', email: 'o@x.test', partnerId: DAD},
  'users/uid-dad': {name: 'Pavel', email: 'p@x.test', partnerId: MOM},
  'users/uid-stranger': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/** Builds the document as `FirestoreCustodyDataSource` writes it. */
function custodyDoc(overrides) {
  return Object.assign({
    participants: [MOM, DAD].sort(),
    lastModifiedBy: MOM,
    modelType: 'WEEK_ON_WEEK_OFF',
    patternDays: 14,
    momDayIndices: [0, 1, 2, 3, 4, 5, 6],
    startDate: '2026-08-03',
    repeatYearly: true,
    createdAt: '2026-08-03T10:00:00',
    lastModifiedAt: '2026-08-03T10:00:00',
  }, overrides);
}

describe('custody_models', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets a participant create the pair document', async () => {
    const db = env.authenticatedContext(MOM).firestore();
    await assertSucceeds(db.doc(PATH).set(custodyDoc({})));
  });

  it('lets both participants read it', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).get());
    await assertSucceeds(env.authenticatedContext(DAD).firestore().doc(PATH).get());
  });

  it('lets the other participant overwrite it, which is last-write-wins', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).update({
      momDayIndices: [7, 8, 9, 10, 11, 12, 13], lastModifiedBy: DAD,
    }));
  });

  it('refuses a third account', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(STRANGER).firestore();
    await assertFails(db.doc(PATH).get());
    await assertFails(db.doc(PATH).update({patternDays: 7}));
    await assertFails(db.doc(PATH).delete());
  });

  it('refuses a create that leaves the author out of participants', async () => {
    const db = env.authenticatedContext(STRANGER).firestore();
    await assertFails(db.doc(PATH).set(custodyDoc({})));
  });

  it('refuses a create whose participants are not a pair', async () => {
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).set(custodyDoc({participants: [MOM]})));
    await assertFails(
        db.doc(PATH).set(custodyDoc({participants: [MOM, DAD, STRANGER]})));
  });

  it('refuses an update that removes the other participant', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({participants: [MOM]}));
  });

  it('refuses an update that swaps a stranger in for the co-parent', async () => {
    // Without the immutability check this passes every other clause: the author is still in
    // participants and there are still two of them - but the document's id no longer names
    // the pair it is now shared with, and the co-parent silently loses their schedule.
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(
        db.doc(PATH).update({participants: [MOM, STRANGER].sort()}));
  });

  it('lets a participant delete it, which is what unpairing does', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).delete());
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd firestore-tests && npm test
```

Expected: FAIL — with no `custody_models` block, every operation is denied, so the "lets…" cases fail.

- [ ] **Step 3: Add the rule and delete the dead block**

In `firestore.rules`, **delete** the entire `match /custody_schedules/{scheduleId} { … }` block. It was written for the legacy Room-only `CustodyScheduleEntity` and matches no client code; `CLAUDE.md` already records it as dead and left in place only because the previous round was mid-pairing-work. Two similar blocks, one dead, is how the next person fixes the wrong one.

Add:

```
    // ---- Custody -------------------------------------------------------
    // One document per pair, id derived from the two UIDs (CustodyKey). Read by id only:
    // there is no list query here, so no `where` filter has to mirror this rule.
    match /custody_models/{modelId} {
      allow read: if isAuthenticated()
        && request.auth.uid in resource.data.participants;
      allow create: if isAuthenticated()
        && request.auth.uid in request.resource.data.participants
        && request.resource.data.participants.size() == 2;
      // participants is immutable. Without this an existing participant could swap the other
      // one out for a third account: they would still be in the array and it would still hold
      // two uids, but the document id - derived from the original pair - would no longer name
      // who it is shared with, and the co-parent would lose the schedule with no trace.
      allow update: if isAuthenticated()
        && request.auth.uid in resource.data.participants
        && request.resource.data.participants == resource.data.participants;
      allow delete: if isAuthenticated()
        && request.auth.uid in resource.data.participants;
    }
```

- [ ] **Step 4: Correct the `CLAUDE.md` entry that describes the deleted block**

Its "Known issues / do not fix silently" section records `custody_schedules` as a rule with no
data source, "left in place rather than removed mid-pairing-feature-work". That is now false
in two ways: the block is gone, and there is a live `custody_models` rule beside where it was.
Replace the bullet rather than deleting it — the next reader needs to know the Room table
`custody_schedules` still exists and is deliberately Room-only.

- [ ] **Step 5: Run the suite to verify it passes**

```bash
cd firestore-tests && npm test
```

Expected: PASS, including the neighbouring suites — deleting the `custody_schedules` block must not break any of them.

- [ ] **Step 6: Commit**

```bash
git add firestore.rules firestore-tests/rules/custody-models.test.js
git commit -m "feat(rules): gate the shared custody document, and drop the dead one

custody_models is one document per pair, gated on a participants array that
matches the derived id. Read by id only - there is no list query, so nothing
here needs a where filter mirroring the rule, which is the trap this project has
walked into twice.

The custody_schedules block goes. It was written for a Room-only legacy entity
whose table name happens to match, matches no client code, and CLAUDE.md already
records it as dead. Leaving a dead block beside a live one with a similar name is
how the next person debugs the wrong file.

Verified on the emulator, not by deploying and watching a phone - which is how a
broken expenses delete rule shipped once already.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: The custody document, written and observed

**Files:**
- Create: `app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSource.kt`
- Create: `app/src/main/java/com/coparently/app/domain/custody/SharedCustody.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/CustodyModelRepository.kt`
- Create: `app/src/test/java/com/coparently/app/data/repository/CustodyModelRepositoryTest.kt`

**Interfaces:**
- Consumes: `CustodyKey.of` (Task 7); the rule from Task 8.
- Produces:
  - `CustodyModelRepository.observeShared(): Flow<SharedCustody?>` — survives a denied read.
  - `CustodyModelRepository.getShared(): SharedCustody?` — suspending, one-shot. Task 11 needs
    the co-parent's pattern *at the moment of accept*, which a stream cannot answer.
  - `saveAndActivate` writes through to Firestore, guarded.

**Two facts an earlier draft of this task got wrong, verified 5 August 2026:**

1. **`CustodyModel` cannot carry the document's metadata.** It is
   `(id, modelType, patternDays, momDayIndices, startDate, isActive)` and nothing else —
   no `lastModifiedBy`, no `lastModifiedAt`, no `repeatYearly`. Those last three live on
   `CustodyModelEntity`, not on the domain model. Task 10's banner is specified as "the last
   remote change whose `lastModifiedBy` is not me, keyed on `lastModifiedAt`", so a
   `Flow<CustodyModel?>` cannot feed it. Hence `SharedCustody`, an envelope:

   ```kotlin
   package com.coparently.app.domain.custody

   /**
    * The pair's shared custody document: the pattern, plus what only the shared copy knows.
    *
    * [CustodyModel] is the pattern and deliberately stays that — it is what the calendar asks
    * "who has the child on this date". Who last changed it and when are facts about the
    * *document*, not about the schedule, and they exist only because two people write to it.
    */
   data class SharedCustody(
       val model: CustodyModel,
       val lastModifiedBy: String,
       val lastModifiedAt: String,
       val createdAt: String,
       val repeatYearly: Boolean = true
   )
   ```

2. **No Hilt module changes.** Firestore data sources in this project are plain
   `@Singleton class X @Inject constructor(private val firestore: FirebaseFirestore)` and are
   constructor-injected wherever they are needed; `FirebaseModule` only provides the
   `FirebaseFirestore` singleton. `CustodyModelRepository` is a concrete `@Singleton` class
   with no domain interface and no entry in `RepositoryModule` — leave it that way. Extracting
   an interface for one implementation is not this task's job.

- [ ] **Step 1: Write the data source**

`FirestoreCustodyDataSource` owns the mapping, including the one field that does not mirror Room:

```kotlin
/**
 * Reads and writes the one custody document a pair shares.
 *
 * Room stores `momDaysPattern` as a JSON string because SQLite has no array type. Firestore
 * has one, so the document carries `momDayIndices` as a real array of integers and the
 * conversion lives here: a JSON blob on the wire is opaque to a security rule and to anyone
 * reading the console.
 */
```

Fields written: `participants` (sorted), `lastModifiedBy`, `modelType`, `patternDays`, `momDayIndices` (array), `startDate`, `repeatYearly`, `createdAt`, `lastModifiedAt` — dates as ISO strings, as everywhere else in this schema.

Read `FirestoreBudgetDataSource.kt` first: it is the closest shape (`callbackFlow` +
`addSnapshotListener` + `awaitClose { subscription.remove() }`), and unlike this one it has to
carry a `whereIn` filter. **This collection is read by document id only** — no list query, so
no filter mirrors the rule. `CLAUDE.md` item 12 is about the opposite mistake; say so in the
KDoc, because the reflex here is to add a `whereArrayContains` that nothing needs.

Numbers cross Firestore as `Long`. `patternDays` and every entry of `momDayIndices` must be
narrowed on the way in, not cast blindly — a `ClassCastException` inside a snapshot listener
is not caught by the retry.

- [ ] **Step 1b: Decide where the two UIDs come from — domain only**

`CustodyKey.of(myUid, partnerUid)` and the `participants` array need both. Take them from a
domain interface: `UserRepository` if `User`/`getCurrentUserId` already reach `partnerId`,
otherwise `PairingRepository`. **Not `ParentsSource`** — that is a presentation-layer class,
and Task 0 has just removed the only `data → presentation` edge in the tree. Do not add it back
one task later.

When there is no partner, there is no shared document: `observeShared()` emits `null` and
`getShared()` returns `null`. An unpaired user writes Room and nothing else.

- [ ] **Step 2: Make the observer survive a denial**

The document may not exist yet, and the rules may deny the first read by seconds if pairing has only just completed. Build the observer with `retryWhen` and backoff:

```kotlin
    /**
     * The pair's shared pattern, or null when there is none.
     *
     * Retries with backoff rather than ending on the first failure. This project already ships
     * a defect of exactly the opposite shape: both mirror branches in `MessageRepositoryImpl`
     * end in `.catch { Log.w(...) }`, which *completes* the flow, so one denied read leaves the
     * chat running on Room alone for the rest of the process — the app looks entirely healthy
     * and receives nothing. It is in CLAUDE.md's known issues with the fix named. A new
     * listener has no excuse to repeat it.
     */
    fun observeShared(): Flow<SharedCustody?> = … .retryWhen { cause, attempt ->
        Log.w(TAG, "custody listener failed (attempt $attempt), retrying", cause)
        delay(RETRY_BASE_MS * (1L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT).toInt()))
        true
    }
```

Share it. Two collectors want this stream — the mirror in Step 2b and the banner in Task 10 —
and each raw collection attaches its own snapshot listener. `ParentsSource.shared` is the
precedent this project already set for exactly that, including the two parameters that matter:
`SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0)` and `replay = 1`.
The expiration is not optional — a replayed value that outlives a sign-out serves the previous
account's custody schedule to the next one.

- [ ] **Step 2b: Mirror the remote document into Room**

Room stays the source of truth, so a remote change has to land there or the calendar never
shows it. Follow the shape `MessageRepositoryImpl` already uses — a mirror branch merged with
the local flow — so that one subscription point does both, and change the one thing that shape
gets wrong: **it ends in a terminal `.catch { Log.w(...) }`, which completes the flow.** That
is the live defect in `CLAUDE.md`'s known issues. `retryWhen` from Step 2 is the fix; do not
also add a terminal `.catch`.

A remote model arrives with the writer's `id`. Reuse it rather than generating a new one, so
the two devices converge on one row instead of accumulating a copy per sync.

- [ ] **Step 3: Write through on save, guarded**

`saveAndActivate` keeps writing Room first — Room is the source of truth — then pushes to Firestore inside `runCatching`. An uncaught `PERMISSION_DENIED` from a suspend call inside `viewModelScope.launch` crashes the app rather than failing the sync; `addBudget`/`deleteBudget` gained the same guard for the same reason.

`saveAndActivate` is the only write path that matters: `createWeekOnWeekOff`,
`createTwoTwoThree`, `createThreeFourFourThree` and `createCustom` all funnel through it.
Confirm that by reading the file rather than trusting this sentence, and if any of them
bypasses it, route it too — a pattern that syncs from one entry point and not another is worse
than one that does not sync at all.

`lastModifiedBy` is the signed-in uid on every write. `createdAt` is preserved from the
existing document when there is one, so an update does not re-date the pair's arrangement.

- [ ] **Step 4: Write the repository tests**

Case names below; write real assertions, not skeletons — mock `CustodyModelDao` and
`FirestoreCustodyDataSource` with MockK the way the existing repository tests in
`app/src/test/java/com/coparently/app/data/repository/` do. Read one of them first.

```kotlin
    @Test fun `saving writes Room before Firestore`()
    @Test fun `a Firestore failure on save leaves the local model in place`()
    @Test fun `an unpaired user saves to Room and writes no document`()
    @Test fun `the observer keeps delivering after a failure`()
    @Test fun `a remote model is mirrored into Room under the id it arrived with`()
```

The retry test is the one that matters and it is the one that is easy to write vacuously: make
the data source's flow throw on its first collection and emit a value on its second, and assert
the value arrives. A test that only checks `retryWhen` was called proves nothing.

- [ ] **Step 5: Run tests and build**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/data app/src/main/java/com/coparently/app/di app/src/test/java/com/coparently/app/data
git commit -m "feat(custody): give the schedule a Firestore path in both directions

CustodyModelRepository talked only to Room, so after pairing the second phone had
no custody pattern at all: each parent set one locally, the two were never
compared, and nothing told either of them they disagreed.

One document per pair now mirrors it. The listener retries with backoff from the
first commit rather than ending on the first denial - the chat listener's
.catch-completes-the-flow defect is documented in CLAUDE.md and there is no
excuse to ship a second instance of it. Writes are guarded, because an uncaught
PERMISSION_DENIED inside viewModelScope.launch crashes the app.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: Say when the schedule changed under you

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/components/CalendarBanners.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/res/values{,-cs,-de,-ru,-uk}/calendar_strings.xml`

**Interfaces:**
- Consumes: `observeShared(): Flow<SharedCustody?>` (Task 9), `parentLabel(...)` (Task 3).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the banner**

`CustodyChangedBanner(byName: String, onDismiss: () -> Unit)` next to `ChangeRequestBanner` and `VacationBanner`, in the same visual treatment — the August design refresh established inline banners over the grid as the pattern, and a badged glyph or a per-day strip is explicitly not it.

`VacationBanner` is the closest shape to copy (a tinted `Row`, a coloured dash, a `Text`).
Neither existing banner is dismissible, so the dismiss affordance has no precedent in that
file: give it a plain trailing `IconButton`, not a second visual language.

The name comes from `parentLabel` resolving `SharedCustody.lastModifiedBy`'s **uid**, not a
slot. `Parents.roleByUid` maps uid to slot; go uid → slot → `parentLabel`. A uid that maps to
neither parent resolves to the unknown fallback and the banner still shows — it says the
schedule changed, which is true, without naming a stranger.

- [ ] **Step 2: Add the string, in five locales**

```xml
<string name="calendar_custody_changed_by">Custody schedule changed by %1$s</string>
```

- [ ] **Step 3: Raise it when the remote change is not mine**

In `CalendarViewModel`, expose the last remote change whose `lastModifiedBy` is not the current user, keyed on `lastModifiedAt` so a dismissed banner returns on the next change and not before. No push notification: this app requests notification permission contextually and there is nothing here worth requesting it for.

`CalendarViewModel` already injects `ParentsSource` and `EncryptedPreferences`, so it can name
the changer and remember the dismissal without new dependencies. Persist the dismissed
`lastModifiedAt` rather than holding it in ViewModel state: a change the user has already
acknowledged should not reappear because they killed the app, and the value is one string.

- [ ] **Step 4: Build, test, commit**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
git add app/src/main/java/com/coparently/app/presentation/calendar app/src/main/res
git commit -m "feat(custody): tell the other parent when the schedule changed

Last write wins until a consent mechanism exists, which is consistent with how
shared events already behave - permissions defaults to read_write and nothing
checks it. What must not happen is that it wins silently: a custody pattern is
the thing a separated parent plans their life around.

The banner names who changed it, through the same inline treatment the design
refresh established for change requests and school vacation. No push: this app
asks for notification permission contextually and this is not worth asking for.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 11: The pairing conflict screen

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/CustodyConflictScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt`
- Modify: `app/src/main/res/values{,-cs,-de,-ru,-uk}/` — a new `custody_conflict_*` string set
- Create: `app/src/test/java/com/coparently/app/presentation/pairing/CustodyConflictResolverTest.kt`

**Interfaces:**
- Consumes: `ParentSlotMigrator.reslot` (Task 2), `CustodyModel.complemented()` and `isEquivalentTo()` (Task 7), `CustodyModelRepository.getShared()` (Task 9).
- Produces: nothing later tasks depend on.

**Scope correction, verified 5 August 2026.** An earlier draft called the screen "wiring".
It is not: `PairingViewModel` emits **no navigation signal at all** — `NavGraph.kt:435` passes
`PairingScreen` only `onNavigateBack`, and a caller learns about success by watching `state`
become `PairingState.Paired`. So this task also adds a one-shot event channel on the ViewModel,
a `Screen` entry and a `composable` route, and the decision of what Back does on the conflict
screen. Treat that as part of the task, not as a surprise: a conflict screen the accepter can
dismiss with Back and never see again silently keeps whichever pattern was already local.
Back therefore is not offered — the screen has two actions, one per pattern, and no third exit.

- [ ] **Step 1: Write the failing resolver test**

The decision is a pure function. `CustodyConflictResolver.resolve(mineAfterFlip, theirs)`
returns one of `NoConflict(model)`, `NoLocal(theirs)`, `Conflict(mine, theirs)`.

Write real assertions for these cases — they are a list of cases, not code to transcribe:

```kotlin
    @Test fun `equivalent patterns are not a conflict`()
    @Test fun `no local pattern means take theirs without asking`()
    @Test fun `no remote pattern means keep mine without asking`()
    @Test fun `neither pattern present is not a conflict`()
    @Test fun `different patterns are a conflict`()
```

- [ ] **Step 2: Pin the ordering that matters most in this branch**

```kotlin
    @Test
    fun `my pattern is complemented before it is compared`() {
        // I was slot 1 with days 0-6. After pairing I am slot 2, so "my days" are 7-13.
        // The co-parent, in slot 1, has days 7-13 — describing the same arrangement.
        // Comparing without complementing calls this a conflict and offers me my own
        // schedule inverted; I reject it and hand over exactly the days I meant to keep.
        val mineBeforeFlip = model(14, (0..6).toSet(), id = "mine")
        val theirs = model(14, (7..13).toSet(), id = "theirs")
        assertEquals(
            CustodyConflict.NoConflict(theirs),
            CustodyConflictResolver.resolve(mineBeforeFlip.complemented(), theirs)
        )
    }
```

**The two models must differ in `id`.** With the same id they are equal `data class`
instances, and the assertion passes whether `resolve` returns `NoConflict(mine)` or
`NoConflict(theirs)` — the test would pin nothing. Which one `NoConflict` carries is itself a
decision: it is **theirs**, because the shared document is what both phones will converge on
and the local copy is the one that has to move.

- [ ] **Step 3: Implement the resolver, then the screen**

The screen renders both patterns as a week grid using the existing custody colours and `parentLabel`, with one primary action per option and no default selection. The chosen model is written through `CustodyModelRepository`; the other is deactivated locally, not deleted, so it stays in `getAllModels()`.

- [ ] **Step 4: Wire the order in `PairingViewModel`**

Strictly: slot flip (Task 2) → complement the local model → resolve → show the screen only on `Conflict`.

The flip already happens in `withSlotReslot` (`PairingViewModel.kt:201`), which both accept
paths — `redeemCode()` and `acceptIncoming()` — funnel through. Extend that one place, not the
two callers: the reason it exists is that an earlier round shipped the re-stamp on one path
only. `getShared()` (Task 9) supplies "theirs" — a one-shot read, because a stream cannot
answer "what was there when I accepted".

- [ ] **Step 5: Build, test, commit**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
git add app/src/main/java/com/coparently/app/presentation app/src/main/res app/src/test/java/com/coparently/app/presentation/pairing
git commit -m "feat(pairing): let the accepter choose when the two schedules disagree

Nobody's schedule disappears silently and no clock decides. The screen appears
only when both parents have an active pattern and the two are not equivalent -
equivalence compared by outcome, so start dates a whole cycle apart do not look
like a disagreement.

The ordering is the sharpest thing on this branch and the test pins it: the slot
flips, then the local pattern is complemented, and only then are the two
compared. Complement after comparing and the screen offers a parent their own
schedule inverted - they reject it, and hand over exactly the days they meant to
keep.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 12: Unpairing, and the pairs that already exist

**Files:**
- Modify: `functions/index.js` — `unpairCoParentImpl`, and a one-off backfill
- Test: `functions/test/unpair-callable.test.js`

**Interfaces:**
- Consumes: `assignSlots` (Task 1).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Delete the shared document on unpair**

In `unpairCoParentImpl`, alongside the existing `partnerId` clearing, delete `custody_models/{key}` where the key is the two UIDs sorted and joined with `__`. Both parents keep their local Room copy; only the shared document goes.

- [ ] **Step 2: Backfill the slots of pairs created before this change**

Every existing pair has both parents at `"mom"`: `DEFAULT_ROLE` gave everyone that and pairing never changed it. After the fact the accepter is not identifiable from the user documents — but the invitation records `acceptedBy`, which is what the backfill reads.

Where no invitation survives, **leave the pair alone**. Guessing which parent to move would re-stamp the wrong person's events. Those pairs stay indistinct until one of them re-saves, and that is stated in the spec rather than papered over.

**Shape (RULING, human, 5 August 2026): an admin-gated callable.** There is no precedent for a
one-off migration anywhere in `functions/` — no script, no npm entry, nothing — so this
invents one, and a callable is the shape that matches everything around it and can be tested by
the pattern the neighbouring tests already use.

```javascript
exports.backfillParentSlots = functions.https.onCall(async (data, context) => { … });
exports.backfillParentSlotsImpl = backfillParentSlotsImpl;   // what the tests call
```

Rules for it, all load-bearing:

- **It refuses everyone but an operator.** Gate on an allow-list of admin UIDs read from
  functions config or an env var, or on a custom claim — not on "is authenticated". A callable
  that re-slots arbitrary pairs is not a user-facing feature.
- **It is idempotent.** A pair whose two parents already hold different slots is skipped, not
  re-flipped. Running it twice must be indistinguishable from running it once, because it
  will be run twice.
- **It reads `invitations` where `status == 'accepted'` and `acceptedBy != null`.** That field
  exists: `PairingRepositoryImpl.kt:334` seeds it null at creation and
  `functions/index.js:464` sets it on accept.
- **It reuses `assignSlots(inviterRole)`** — one parameter, corrected in Task 1. The inviter is
  `invite.fromUserId` and keeps their stored slot; `acceptedBy` takes the other one.
- **It verifies the pair is still a pair** before touching either document: both users must
  still carry `partnerId` pointing at each other. An invitation accepted and later unpaired
  must not re-slot two strangers.
- **It reports what it did** — counts of scanned, updated and skipped, and the reason for each
  skip class — as the callable's return value. A migration whose only output is "ok" cannot be
  checked afterwards.

- [ ] **Step 3: Test all of it**

Follow the `fakeDb` + direct `*Impl` call pattern of `functions/test/unpair-callable.test.js`,
and `test.wrap` only for the auth gate. Case names:

```javascript
  it('deletes the shared custody document when unpairing');
  it('leaves the co-parent local copies alone when unpairing');
  it('gives the accepter the other slot, reading acceptedBy from the invitation');
  it('leaves a pair with no surviving invitation alone');
  it('skips a pair whose parents already hold different slots');
  it('skips an invitation whose pair has since unpaired');
  it('refuses a caller who is not an operator');
```

- [ ] **Step 4: Run and commit**

```bash
cd functions && npm test && npm run lint
git add functions/index.js functions/test/unpair-callable.test.js
git commit -m "feat(pairing): drop the shared custody document on unpair, and backfill old pairs

Every pair created before this change has both parents in slot 1, because
DEFAULT_ROLE gave everyone \"mom\" and pairing never touched it. The accepter is
not identifiable from the user documents afterwards, so the backfill reads
acceptedBy from the invitation.

Where no invitation survives the pair is left alone rather than guessed at:
moving the wrong parent would re-stamp the wrong person's events. Those pairs
stay indistinct until one of them saves a schedule again. This is the one place
the design cannot repair the past, and it says so rather than pretending.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 12b: React to a slot that changed while you were not looking

Added in the Part B pre-flight scan, 5 August 2026. RULING (human): build the detector.

**The gap.** `ParentSlotMigrator.reslot` is reachable from exactly one place —
`PairingViewModel.withSlotReslot` (`PairingViewModel.kt:201`), on the accept path. Task 12's
backfill flips the accepter's slot server-side for a pair that accepted months ago. Nothing on
that device is watching. Their `role` becomes `"dad"` while every event they ever created is
still stamped `"mom"`, so their whole history reads as the co-parent's — which is precisely the
damage Task 2 exists to prevent, delivered by our own migration.

The accepter's local custody model has the same problem for the same reason: `momDayIndices`
means "slot 1's days", and their slot just stopped being slot 1.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt` — or
  wherever the remote `users/{uid}` document is written into Room. Find it; do not assume.
- Modify: `app/src/main/java/com/coparently/app/data/repository/ParentSlotMigrator.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/CustodyModelRepository.kt`
- Test: extend `app/src/test/java/com/coparently/app/data/repository/ParentSlotMigratorTest.kt`

**Interfaces:**
- Consumes: `ParentSlotMigrator.reslot` (Task 2), `CustodyModel.complemented()` (Task 7).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Find the one place a remote profile lands in Room**

```bash
git grep -n "insertUser\|updateUser" -- app/src/main/java
```

There is a single write path for the signed-in user's document (`UserRepositoryImpl` around
lines 242, 333, 386 per the Task 5 investigation — verify, the numbers are from before Part A
landed). The detector belongs there, at the moment the new value is known and the old one has
not been overwritten yet. Not at app start: a cold start is not when the change arrives, and
a startup hook re-runs on every launch for a fact that changes twice in a lifetime.

- [ ] **Step 2: Write the failing tests**

```kotlin
    @Test fun `a profile arriving with a different slot re-stamps this user's rows`()
    @Test fun `a profile arriving with the same slot changes nothing`()
    @Test fun `the first profile this device has ever seen is not treated as a change`()
    @Test fun `a slot change complements the active custody model`()
    @Test fun `a slot change with no active custody model is not an error`()
```

The third is the one that protects everyone: on a fresh install there is no previous row, and
"null → dad" must not be read as a flip. There is nothing to re-stamp anyway, but the custody
complement would invert a model that arrived from the co-parent's document.

- [ ] **Step 3: Implement**

Read the existing Room row before writing the incoming one. If both carry a non-blank slot and
they differ, run `reslot(from = old, to = new, myUid = uid)` and complement the active custody
model, in that order, in the same suspend function — the same order Task 11 fixes for the
pairing path, for the same reason.

Complementing the custody model is a Room write, not a Firestore write: the shared document is
expressed in slot terms and is already correct for the pair. Writing the complement back would
invert it for the co-parent, whose slot did not change.

- [ ] **Step 4: Build, test, commit**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; ./gradlew assembleDebug testDebugUnitTest
```

```
feat(pairing): re-stamp when a slot changes outside the accept flow

reslot was reachable from one place: the moment a user taps Accept. That covered
every slot change that existed when it was written, and stopped covering them the
moment the backfill in the previous commit could flip a slot for a pair that
accepted months ago. Their device would have gone on stamping the old slot while
the server held the new one, and every event they had ever created would have
started reading as their co-parent's - the exact damage the accept-path re-stamp
exists to prevent, delivered by our own migration.

The detector sits where the remote profile is written into Room, which is where
the two values are both in hand. A first profile is not a change: on a fresh
install there is no previous row, and reading "nothing -> dad" as a flip would
complement a custody model that was never in the other slot.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

**Release order this creates.** The app carrying this task must be in users' hands *before*
the Task 12 backfill is invoked. Say so in the PR body: the backfill is a manual call and
running it early leaves the accepter's device on the old stamp until they update.

---

## Task 13: Two phones

**Files:**
- Modify: `docs/TEST-PLAN-2026-08.md` — §11 item 2's result
- Modify: `docs/superpowers/specs/2026-08-05-named-parents-and-custody-sync-design.md` — an acceptance section

**Interfaces:**
- Consumes: the built branch.
- Produces: the acceptance record for the PR body.

Cross-phone is the only way to see the slot flip work. Both handsets are on wireless ADB; verify `mCurrentFocus` names `com.coparently.app` before any scripted input.

- [ ] **Step 1: Unpair the two accounts and confirm both show a name, not a role**
- [ ] **Step 2: Pair them, and confirm the two parents render as two different names on both phones**
- [ ] **Step 3: Confirm the accepter's pre-pairing events still read as theirs** — this is the re-stamp, and it is the single most damaging thing on this branch if it half-runs
- [ ] **Step 4: Set a custody pattern on phone A; confirm it reaches phone B**
- [ ] **Step 5: Change it on phone B; confirm phone A shows the banner naming B's owner**
- [ ] **Step 6: Create an event on each phone and confirm it defaults to that phone's owner**
- [ ] **Step 7: Unpair; confirm both keep a local schedule and the shared document is gone**
- [ ] **Step 8: Open the new-event screen on an unpaired account and confirm the selector does not appear and vanish** — Task 0's flash, the one defect here that no unit test can see
- [ ] **Step 9: Run the Task 12 backfill against the legacy pair and confirm Task 12b re-stamps** — pair the two phones on the *old* build if one is still installable, or simulate by setting both `users/{uid}.role` to `"mom"` in the console; then invoke the callable and confirm the accepter's own events still read as theirs and their custody pattern did not invert
- [ ] **Step 10: Record the result in both documents and commit**

Report what the run actually shows. A step that did not happen is recorded as not run, not as passed.

---

## Self-Review

**Spec coverage.** Part A: slot assignment → Task 1; re-stamp → Task 2; `parentLabel` → Task 3; the gendered-string rewrite → Task 4; screens, `ParentColors` KDoc and `CLAUDE.md` → Task 5; event default and the hidden selector → Task 6. Part B: `CustodyKey`, `complemented`, `isEquivalentTo` → Task 7; rule plus the dead block → Task 8; data source, write-through and the retrying listener → Task 9; banner → Task 10; conflict screen and the ordering → Task 11; unpair deletion and the existing-pairs backfill → Task 12. Non-goals are constraints, not work. Every risk in the spec has a task: the slot flip (2, 11), existing pairs (12), five-language copy (4), the LCM window (7).

**Type consistency.** `assignSlots(inviterRole) → {inviterRole, accepterRole}` is defined in Task 1 and consumed in Task 12. `ParentSlotMigrator.reslot(from, to, myUid): Int` is defined in Task 2 and consumed in Task 11. `parentLabel(slot, me, coParent, youFallback, coParentFallback): String` is defined in Task 3 and consumed in Tasks 5, 6, 10, 11. `CustodyKey.of`, `complemented()`, `isEquivalentTo()` are defined in Task 7 and consumed in Tasks 8, 9, 11.

**Known weak points, stated rather than hidden.**

- **Tasks 9, 10, 11 and 12 carry less literal code than Tasks 1–8.** Their shape depends on files this plan describes but does not quote in full (`FirebaseModule`, `PairingViewModel`, `harness.js`). Each names the file to read first. If an implementer reaches one of them and cannot proceed from what is written, that is a plan defect worth reporting rather than improvising around.
- **Task 5 touches eleven screens in one commit.** It is the largest task here and the least mechanical. If it grows past what one reviewer can judge, split it by feature area — calendar, expenses, home/summary, events — and say so.
