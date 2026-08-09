# Custody proposal and approval — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A custody change becomes a proposal that changes nobody's calendar until the co-parent accepts it.

**Architecture:** The pair's existing `custody_models/{pairId}` document gains a `proposal` sub-map and a `lastDecision` sub-map. A proposal write touches neither the pattern nor `lastModified*`, so it is orthogonal to the pattern's own change history. Accept promotes the proposal into the pattern in one write. The transition table lives in a pure object so it is testable without Firestore, Room or a composition.

**Tech Stack:** Kotlin 2.1, Compose (Material 3), Room 2.7.2 (v13 → v14), Hilt, Firestore + Cloud Functions (Node, mocha), MockK + coroutines-test.

## Global Constraints

- Branch `feat/custody-proposal-2026-08`, stacked on `fix/weekend-tint-and-pairing-audience` (PR #46). **PR #46 must merge first** — it carries the `allow get` fix without which the shared document cannot be read at all.
- Jetpack Compose only; stateless composables; state in ViewModels as `StateFlow`.
- Every new user-facing string goes into all five locales (`values`, `values-cs`, `values-de`, `values-ru`, `values-uk`) **in the same commit**. Verify with `git grep -c 'name="your_key"' -- app/src/main/res/values*/*.xml` → five files. `MissingTranslation` lint is disabled and will not catch a miss.
- The app never shows "Mom"/"Dad": every parent label goes through `presentation/common/ParentLabels.kt`. `"mom"`/`"dad"` remain slot identifiers in Room, Firestore and the rules and are never renamed.
- Never debug `firestore.rules` by deploying. `firestore-tests/` runs them against the emulator; add the case there first. Needs JDK 21+ **on `PATH`** — `C:\Program Files\Android\Android Studio1\jbr\bin`.
- Room schema change = entity → version bump in `CoPlanlyDatabase` → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`) → exported schema in `app/schemas/`.
- `./gradlew assembleDebug testDebugUnitTest`; `cd firestore-tests && npm test`; `cd functions && npm test && npm run lint`.
- `detekt` is red on `main` with pre-existing issues; only this branch's delta is in scope.
- Conventional Commits. KDoc on public classes/functions. Code and comments in English.

---

## File structure

| File | Responsibility |
|---|---|
| `domain/custody/CustodyProposal.kt` (new) | The proposed pattern plus who proposed it and when. |
| `domain/custody/CustodyDecision.kt` (new) | The most recent accept/decline outcome. |
| `domain/custody/CustodyProposalTransition.kt` (new) | Pure transition table: what propose/withdraw/accept/decline produce from a given `SharedCustody`. No Firestore, no Room. |
| `domain/custody/SharedCustody.kt` (modify) | Gains `proposal` and `lastDecision`. |
| `data/remote/firebase/FirestoreCustodyDataSource.kt` (modify) | Maps both sub-maps; a proposal-only write must not send pattern fields. |
| `data/local/entity/CustodyProposalEntity.kt` (new) + `dao/CustodyProposalDao.kt` (new) | One mirrored row per pair. |
| `data/local/CoPlanlyDatabase.kt`, `DatabaseMigrations.kt` (modify) | v13 → v14. |
| `data/repository/CustodyModelRepository.kt` (modify) | `propose`/`withdrawProposal`/`acceptProposal`/`declineProposal`; mirrors the proposal; complements it on a slot change. |
| `firestore.rules` + `firestore-tests/rules/custody-models.test.js` (modify) | The proposal-only write clause. |
| `functions/index.js` + `functions/test/` (modify) | `onCustodyModelWritten`. |
| `presentation/custody/CustodySetupScreen.kt` / `ViewModel` (modify) | Propose instead of save; pending strip. |
| `presentation/custody/CustodyProposalScreen.kt` / `ViewModel` (new) | Current vs proposed, Accept / Decline-with-note. |
| `presentation/calendar/components/CalendarBanners.kt` (modify) | `CustodyProposalBanner`, `CustodyDecisionBanner`. |
| `presentation/navigation/NavGraph.kt` (modify) | The review route. |

Tasks 1–4 are the spine and are independently valuable: with them, the two phones agree on a proposal even before any UI exists. Tasks 5–6 make it enforceable and audible. Tasks 7–8 make it usable.

---

### Task 1: The transition table, as a pure object

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/custody/CustodyProposal.kt`, `CustodyDecision.kt`, `CustodyProposalTransition.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/custody/SharedCustody.kt`
- Test: `app/src/test/java/com/coparently/app/domain/custody/CustodyProposalTransitionTest.kt`

**Interfaces:**
- Consumes: `CustodyModel`, `SharedCustody`.
- Produces:
  - `data class CustodyProposal(val model: CustodyModel, val repeatYearly: Boolean, val proposedBy: String, val proposedAt: String)`
  - `enum class CustodyDecisionOutcome { ACCEPTED, DECLINED }`
  - `data class CustodyDecision(val outcome: CustodyDecisionOutcome, val by: String, val at: String, val proposalAt: String, val note: String?)`
  - `SharedCustody` gains `val proposal: CustodyProposal? = null` and `val lastDecision: CustodyDecision? = null`
  - `CustodyProposalTransition.propose(current, model, repeatYearly, byUid, atIso): Result<SharedCustody>`
  - `CustodyProposalTransition.withdraw(current, byUid): Result<SharedCustody>`
  - `CustodyProposalTransition.accept(current, byUid, atIso): Result<SharedCustody>`
  - `CustodyProposalTransition.decline(current, byUid, atIso, note): Result<SharedCustody>`

- [ ] **Step 1: Write the failing test**

`CustodyProposalTransitionTest.kt` — the transition table from the spec, one test per row plus the refusals:

```kotlin
package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustodyProposalTransitionTest {

    private val agreed = CustodyModel(
        id = "agreed", modelType = CustodyModelType.WEEK_ON_WEEK_OFF, patternDays = 14,
        momDayIndices = (0..6).toSet(), startDate = LocalDate.of(2026, 8, 3)
    )
    private val wanted = agreed.copy(id = "wanted", momDayIndices = (7..13).toSet())
    private val current = SharedCustody(
        model = agreed, lastModifiedBy = MOM, lastModifiedAt = "2026-08-03T10:00:00",
        createdAt = "2026-08-01T09:00:00"
    )

    @Test
    fun `proposing leaves the pattern and its authorship completely alone`() {
        val next = CustodyProposalTransition
            .propose(current, wanted, repeatYearly = true, byUid = DAD, atIso = NOW).getOrThrow()

        assertEquals(agreed, next.model)
        assertEquals(MOM, next.lastModifiedBy)
        assertEquals("2026-08-03T10:00:00", next.lastModifiedAt)
        assertEquals(wanted, next.proposal?.model)
        assertEquals(DAD, next.proposal?.proposedBy)
        assertEquals(NOW, next.proposal?.proposedAt)
    }

    @Test
    fun `a parent may not overwrite the other's pending proposal`() {
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        val result = CustodyProposalTransition.propose(pending, agreed, true, MOM, LATER)

        assertTrue(result.isFailure)
    }

    @Test
    fun `a parent may replace their own pending proposal`() {
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        val next = CustodyProposalTransition
            .propose(pending, agreed, true, DAD, LATER).getOrThrow()

        assertEquals(LATER, next.proposal?.proposedAt)
    }

    @Test
    fun `accepting promotes the proposal and names the accepter`() {
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        val next = CustodyProposalTransition.accept(pending, byUid = MOM, atIso = LATER).getOrThrow()

        assertEquals(wanted, next.model)
        assertEquals(MOM, next.lastModifiedBy)
        assertEquals(LATER, next.lastModifiedAt)
        assertEquals("2026-08-01T09:00:00", next.createdAt)
        assertNull(next.proposal)
        assertEquals(CustodyDecisionOutcome.ACCEPTED, next.lastDecision?.outcome)
        assertEquals(NOW, next.lastDecision?.proposalAt)
    }

    @Test
    fun `declining keeps the agreed pattern and carries the note`() {
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        val next = CustodyProposalTransition
            .decline(pending, byUid = MOM, atIso = LATER, note = "School run").getOrThrow()

        assertEquals(agreed, next.model)
        assertEquals(MOM, next.lastModifiedBy)
        assertEquals("2026-08-03T10:00:00", next.lastModifiedAt)
        assertNull(next.proposal)
        assertEquals(CustodyDecisionOutcome.DECLINED, next.lastDecision?.outcome)
        assertEquals("School run", next.lastDecision?.note)
    }

    @Test
    fun `the proposer may not decide their own proposal`() {
        // The whole feature is the co-parent's consent; self-accepting is the silent change back.
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        assertTrue(CustodyProposalTransition.accept(pending, DAD, LATER).isFailure)
        assertTrue(CustodyProposalTransition.decline(pending, DAD, LATER, null).isFailure)
    }

    @Test
    fun `only the proposer may withdraw`() {
        val pending = CustodyProposalTransition
            .propose(current, wanted, true, DAD, NOW).getOrThrow()

        assertTrue(CustodyProposalTransition.withdraw(pending, MOM).isFailure)
        val next = CustodyProposalTransition.withdraw(pending, DAD).getOrThrow()
        assertNull(next.proposal)
        assertNull(next.lastDecision)
    }

    @Test
    fun `deciding when nothing is pending is a failure, not a no-op`() {
        assertTrue(CustodyProposalTransition.accept(current, MOM, NOW).isFailure)
        assertTrue(CustodyProposalTransition.decline(current, MOM, NOW, null).isFailure)
        assertTrue(CustodyProposalTransition.withdraw(current, MOM).isFailure)
    }

    private companion object {
        const val MOM = "uid-mom"
        const val DAD = "uid-dad"
        const val NOW = "2026-08-09T08:00:00"
        const val LATER = "2026-08-09T09:00:00"
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.domain.custody.CustodyProposalTransitionTest"
```

Expected: compilation failure — `Unresolved reference: CustodyProposalTransition`.

- [ ] **Step 3: Add the two data classes**

`CustodyProposal.kt`:

```kotlin
package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel

/**
 * A custody pattern one parent has put to the other, which changes nobody's calendar until it is
 * accepted.
 *
 * Carried on the pair's shared document rather than in a collection of its own: one document is
 * one listener and one rule block, and accepting is then a single write rather than two that
 * need a transaction to stay consistent.
 *
 * @property model The proposed pattern. Expressed in the same slot terms as the agreed pattern,
 *   so `ParentSlotMigrator`'s complement has to reach it too — see `CustodyModelRepository`.
 * @property repeatYearly Mirrors `CustodyModelEntity.repeatYearly`, which lives on the entity
 *   rather than on [CustodyModel] and so travels alongside it.
 * @property proposedBy Firebase UID of the parent who proposed it. The rules require this to be
 *   the caller, and it is what stops either parent deciding their own proposal.
 * @property proposedAt ISO date-time string, as everywhere else in this Firestore schema.
 */
data class CustodyProposal(
    val model: CustodyModel,
    val repeatYearly: Boolean,
    val proposedBy: String,
    val proposedAt: String
)
```

`CustodyDecision.kt`:

```kotlin
package com.coparently.app.domain.custody

/** Whether a proposal was taken up or turned down. */
enum class CustodyDecisionOutcome { ACCEPTED, DECLINED }

/**
 * The most recent answer to a proposal, kept so the proposer learns the outcome.
 *
 * Only the latest is kept: a history of past proposals is out of scope, and the banner this
 * feeds is dismissed against [at] the same way the "schedule changed" banner is dismissed
 * against `SharedCustody.lastModifiedAt`.
 *
 * @property outcome Accepted or declined.
 * @property by Firebase UID of whoever decided — never the proposer.
 * @property at ISO date-time string of the decision.
 * @property proposalAt The [CustodyProposal.proposedAt] this answers, so a decision cannot be
 *   mistaken for the answer to a later proposal.
 * @property note Optional free text, offered on decline so a refusal can say why.
 */
data class CustodyDecision(
    val outcome: CustodyDecisionOutcome,
    val by: String,
    val at: String,
    val proposalAt: String,
    val note: String?
)
```

- [ ] **Step 4: Widen `SharedCustody`**

Add two properties with defaults, so every existing construction site keeps compiling:

```kotlin
 * @property proposal A pattern awaiting the co-parent's answer, or null when nothing is pending.
 *   Deliberately orthogonal to [lastModifiedBy]/[lastModifiedAt]: a proposal write touches
 *   neither, so proposing right after the co-parent changed the pattern cannot make their
 *   not-yet-dismissed change read as this device's own echo and swallow its banner.
 * @property lastDecision The most recent answer to a proposal, or null until the first one.
 */
data class SharedCustody(
    val model: CustodyModel,
    val lastModifiedBy: String,
    val lastModifiedAt: String,
    val createdAt: String,
    val repeatYearly: Boolean = true,
    val proposal: CustodyProposal? = null,
    val lastDecision: CustodyDecision? = null
)
```

- [ ] **Step 5: Write the transition object**

`CustodyProposalTransition.kt`:

```kotlin
package com.coparently.app.domain.custody

import com.coparently.app.domain.model.CustodyModel

/**
 * The four things that can happen to a custody proposal, as pure functions over
 * [SharedCustody] — kept out of the repository so the table can be tested without Firestore,
 * Room or a coroutine.
 *
 * Every function returns a `Result` rather than throwing or silently no-oping. A refused
 * transition is a real outcome the UI has to show ("your co-parent already has a proposal
 * waiting"), and a no-op would be the silent change this whole feature exists to remove.
 *
 * **A proposal write leaves the pattern, `lastModifiedBy` and `lastModifiedAt` exactly as they
 * were.** That is what keeps a proposal from interfering with the pattern's own change history
 * — see [SharedCustody.proposal].
 */
object CustodyProposalTransition {

    /**
     * Puts [model] to the co-parent.
     *
     * Refused when the other parent already has a proposal pending: overwriting it would erase
     * their request without either of them seeing it, which is the failure this feature exists
     * to prevent, one layer up. Replacing one's *own* pending proposal is allowed — it is a
     * correction, not an overrule.
     */
    fun propose(
        current: SharedCustody,
        model: CustodyModel,
        repeatYearly: Boolean,
        byUid: String,
        atIso: String
    ): Result<SharedCustody> {
        val pending = current.proposal
        if (pending != null && pending.proposedBy != byUid) {
            return Result.failure(
                IllegalStateException("The co-parent already has a proposal waiting")
            )
        }
        return Result.success(
            current.copy(
                proposal = CustodyProposal(
                    model = model,
                    repeatYearly = repeatYearly,
                    proposedBy = byUid,
                    proposedAt = atIso
                )
            )
        )
    }

    /** Takes back one's own pending proposal. Leaves no decision behind — nobody answered it. */
    fun withdraw(current: SharedCustody, byUid: String): Result<SharedCustody> =
        current.pendingFrom(byUid).map { current.copy(proposal = null) }

    /**
     * Takes up the co-parent's proposal: it becomes the agreed pattern, stamped with the
     * accepter as its author, and [SharedCustody.createdAt] is preserved so agreeing a change
     * does not re-date the arrangement itself.
     */
    fun accept(current: SharedCustody, byUid: String, atIso: String): Result<SharedCustody> =
        current.pendingForDecisionBy(byUid).map { pending ->
            current.copy(
                model = pending.model,
                repeatYearly = pending.repeatYearly,
                lastModifiedBy = byUid,
                lastModifiedAt = atIso,
                proposal = null,
                lastDecision = CustodyDecision(
                    outcome = CustodyDecisionOutcome.ACCEPTED,
                    by = byUid,
                    at = atIso,
                    proposalAt = pending.proposedAt,
                    note = null
                )
            )
        }

    /** Turns the co-parent's proposal down. The agreed pattern and its authorship are untouched. */
    fun decline(
        current: SharedCustody,
        byUid: String,
        atIso: String,
        note: String?
    ): Result<SharedCustody> = current.pendingForDecisionBy(byUid).map { pending ->
        current.copy(
            proposal = null,
            lastDecision = CustodyDecision(
                outcome = CustodyDecisionOutcome.DECLINED,
                by = byUid,
                at = atIso,
                proposalAt = pending.proposedAt,
                note = note?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** The pending proposal, if [uid] is the one who made it. */
    private fun SharedCustody.pendingFrom(uid: String): Result<CustodyProposal> {
        val pending = proposal
            ?: return Result.failure(IllegalStateException("No proposal is pending"))
        return if (pending.proposedBy == uid) {
            Result.success(pending)
        } else {
            Result.failure(IllegalStateException("Only the proposer may withdraw a proposal"))
        }
    }

    /**
     * The pending proposal, if [uid] is entitled to decide it — which the proposer never is.
     * A parent accepting their own proposal is exactly the unilateral change this replaces.
     */
    private fun SharedCustody.pendingForDecisionBy(uid: String): Result<CustodyProposal> {
        val pending = proposal
            ?: return Result.failure(IllegalStateException("No proposal is pending"))
        return if (pending.proposedBy == uid) {
            Result.failure(IllegalStateException("A parent may not decide their own proposal"))
        } else {
            Result.success(pending)
        }
    }
}
```

- [ ] **Step 6: Run the tests**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.domain.custody.*"
```

Expected: PASS, including the pre-existing `CustodyChangeAnnouncementTest` and `CustodyKeyTest`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/custody app/src/test/java/com/coparently/app/domain/custody
git commit -m "feat(custody): model a schedule change as a proposal the co-parent answers"
```

---

### Task 2: The proposal on the wire

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSource.kt`
- Test: `app/src/test/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSourceTest.kt`

**Interfaces:**
- Consumes: `CustodyProposal`, `CustodyDecision`, the widened `SharedCustody` (Task 1).
- Produces: `setCustody` writes `proposal`/`lastDecision`; the private `toSharedCustody` reads them back. No signature change.

- [ ] **Step 1: Write the failing tests**

Add to `FirestoreCustodyDataSourceTest`, matching the file's existing style of driving the private mappers through the public API:

```kotlin
    @Test
    fun `a document with no proposal round-trips as null rather than as an empty proposal`() {
        val parsed = parseDocument(documentWithoutProposal())

        assertNull(parsed?.proposal)
        assertNull(parsed?.lastDecision)
    }

    @Test
    fun `a proposal round-trips, with its indices narrowed from Long`() {
        // Every number crossing Firestore arrives as a Long; the pattern's own mapper already
        // narrows through Number and the proposal's must do the same, or a ClassCastException
        // is raised inside a snapshot listener where no retryWhen can see it.
        val parsed = parseDocument(documentWithProposal())

        assertEquals(setOf(7, 8, 9), parsed?.proposal?.momDayIndices())
        assertEquals("uid-dad", parsed?.proposal?.proposedBy)
        assertEquals("2026-08-09T08:00:00", parsed?.proposal?.proposedAt)
    }

    @Test
    fun `a decision round-trips, including a declined one with a note`() {
        val parsed = parseDocument(documentWithDecision())

        assertEquals(CustodyDecisionOutcome.DECLINED, parsed?.lastDecision?.outcome)
        assertEquals("School run", parsed?.lastDecision?.note)
        assertEquals("2026-08-09T08:00:00", parsed?.lastDecision?.proposalAt)
    }

    @Test
    fun `a proposal missing the two fields a pattern needs is dropped, not half-parsed`() {
        // Same rule the agreed pattern already follows: a half-read schedule assigns the wrong
        // days, which is worse than showing none.
        val parsed = parseDocument(documentWithProposalMissing("startDate"))

        assertNull(parsed?.proposal)
    }
```

Add the fixture helpers next to the file's existing ones, building raw `Map<String, Any>` documents in the shape §2 of the spec names.

- [ ] **Step 2: Run and confirm failure**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.remote.firebase.FirestoreCustodyDataSourceTest"
```

Expected: FAIL — `proposal` is not read, so the round-trip assertions fail.

- [ ] **Step 3: Map both sub-maps**

In `toDocument`, append the two entries, omitting each when null so an absent proposal is an absent field rather than an explicit `null`:

```kotlin
    private fun SharedCustody.toDocument(sortedParticipants: List<String>): Map<String, Any> =
        buildMap {
            put("id", model.id)
            put("participants", sortedParticipants)
            put("lastModifiedBy", lastModifiedBy)
            put("modelType", CustodyModelType.toString(model.modelType))
            put("patternDays", model.patternDays)
            put("momDayIndices", model.momDayIndices.sorted())
            put("startDate", model.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
            put("repeatYearly", repeatYearly)
            put("createdAt", createdAt)
            put("lastModifiedAt", lastModifiedAt)
            proposal?.let { put("proposal", it.toMap()) }
            lastDecision?.let { put("lastDecision", it.toMap()) }
        }

    /** The proposal as a sub-map; `momDayIndices` is a real array, like the pattern's. */
    private fun CustodyProposal.toMap(): Map<String, Any> = mapOf(
        "modelType" to CustodyModelType.toString(model.modelType),
        "patternDays" to model.patternDays,
        "momDayIndices" to model.momDayIndices.sorted(),
        "startDate" to model.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
        "repeatYearly" to repeatYearly,
        "proposedBy" to proposedBy,
        "proposedAt" to proposedAt
    )

    /** The decision as a sub-map. `note` is omitted when absent rather than written as null. */
    private fun CustodyDecision.toMap(): Map<String, Any> = buildMap {
        put("outcome", outcome.name)
        put("by", by)
        put("at", at)
        put("proposalAt", proposalAt)
        note?.let { put("note", it) }
    }
```

In `toSharedCustody`, read them back, narrowing every number through `Number` exactly as the pattern already does:

```kotlin
        val proposal = (this["proposal"] as? Map<*, *>)?.toProposal(documentId)
        val lastDecision = (this["lastDecision"] as? Map<*, *>)?.toDecision()
```

and add the two private mappers, each returning null when the fields a pattern cannot be rebuilt without are missing:

```kotlin
    /**
     * The proposal sub-map, or null when it cannot describe a pattern.
     *
     * Same rule as the agreed pattern: a document missing `startDate` or `patternDays` is
     * treated as having no proposal rather than half-parsed into a schedule that would put the
     * child with the wrong parent.
     */
    private fun Map<*, *>.toProposal(documentId: String): CustodyProposal? {
        val startDate = (this["startDate"] as? String)?.let { iso ->
            runCatching { LocalDate.parse(iso) }.getOrNull()
        }
        val patternDays = (this["patternDays"] as? Number)?.toInt()
        val proposedBy = (this["proposedBy"] as? String)?.takeIf { it.isNotBlank() }
        if (startDate == null || patternDays == null || proposedBy == null) return null

        return CustodyProposal(
            model = CustodyModel(
                id = documentId,
                modelType = CustodyModelType.fromString((this["modelType"] as? String).orEmpty()),
                patternDays = patternDays,
                momDayIndices = (this["momDayIndices"] as? List<*>)
                    .orEmpty()
                    .mapNotNull { (it as? Number)?.toInt() }
                    .toSet(),
                startDate = startDate,
                isActive = false
            ),
            repeatYearly = this["repeatYearly"] as? Boolean ?: true,
            proposedBy = proposedBy,
            proposedAt = (this["proposedAt"] as? String).orEmpty()
        )
    }

    /** The decision sub-map, or null when its outcome is not one this build knows. */
    private fun Map<*, *>.toDecision(): CustodyDecision? {
        val outcome = (this["outcome"] as? String)
            ?.let { name -> CustodyDecisionOutcome.entries.firstOrNull { it.name == name } }
            ?: return null
        return CustodyDecision(
            outcome = outcome,
            by = (this["by"] as? String).orEmpty(),
            at = (this["at"] as? String).orEmpty(),
            proposalAt = (this["proposalAt"] as? String).orEmpty(),
            note = (this["note"] as? String)?.takeIf { it.isNotBlank() }
        )
    }
```

Note `isActive = false` on the proposed model: it is not the active pattern and must never be mistaken for one by a caller that reads the field.

- [ ] **Step 4: Run the tests**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.remote.firebase.FirestoreCustodyDataSourceTest"
```

Expected: PASS, including the file's pre-existing cases.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSource.kt app/src/test/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSourceTest.kt
git commit -m "feat(custody): carry the proposal and its outcome on the shared document"
```

---

### Task 3: Room v14 — the mirrored proposal

**Files:**
- Create: `app/src/main/java/com/coparently/app/data/local/entity/CustodyProposalEntity.kt`, `dao/CustodyProposalDao.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt` (entity list + `version = 14` + the abstract DAO getter), `DatabaseMigrations.kt`
- Test: `app/src/androidTest/java/com/coparently/app/data/local/CoPlanlyDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `CustodyProposalEntity(pairId, modelType, patternDays, momDaysPattern, startDate, repeatYearly, proposedBy, proposedAt)`, `@PrimaryKey val pairId: String`
  - `CustodyProposalDao.observe(): Flow<CustodyProposalEntity?>`, `getSync(): CustodyProposalEntity?`, `upsert(entity)`, `clear()`
  - `CoPlanlyDatabase.custodyProposalDao(): CustodyProposalDao`
  - `DatabaseMigrations.MIGRATION_13_14`

- [ ] **Step 1: Write the entity and DAO**

```kotlin
package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The pair's pending custody proposal, mirrored from the shared Firestore document so it
 * survives process death and is readable offline.
 *
 * A table of its own rather than nullable columns on `custody_models`: a proposal is a whole
 * second pattern with its own metadata, and widening the entity would put two patterns in one
 * row with only a null check keeping them apart.
 *
 * At most one row exists — the primary key is the pair's shared document id, so a device that
 * re-pairs cannot end up mirroring two pairs' proposals at once.
 *
 * `momDaysPattern` is JSON for the same reason `CustodyModelEntity`'s is: SQLite has no array
 * type. Firestore does, and the conversion lives in `FirestoreCustodyDataSource`.
 */
@Entity(tableName = "custody_proposal")
data class CustodyProposalEntity(
    @PrimaryKey val pairId: String,
    val modelType: String,
    val patternDays: Int,
    val momDaysPattern: String,
    val startDate: String,
    val repeatYearly: Boolean,
    val proposedBy: String,
    val proposedAt: String
)
```

```kotlin
package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.coparently.app.data.local.entity.CustodyProposalEntity
import kotlinx.coroutines.flow.Flow

/** The one pending custody proposal this device knows about. */
@Dao
interface CustodyProposalDao {

    /** Emits the pending proposal, or null when nothing is pending. */
    @Query("SELECT * FROM custody_proposal LIMIT 1")
    fun observe(): Flow<CustodyProposalEntity?>

    /** The pending proposal right now, for the paths a stream cannot serve. */
    @Query("SELECT * FROM custody_proposal LIMIT 1")
    suspend fun getSync(): CustodyProposalEntity?

    /** Replaces whatever was mirrored. */
    @Upsert
    suspend fun upsert(entity: CustodyProposalEntity)

    /**
     * Empties the table.
     *
     * Not `DELETE WHERE pairId = :id`: the mirror's job is to make Room agree with the shared
     * document, and a row left behind for a pair this device is no longer in would show the
     * user a proposal nobody can answer.
     */
    @Query("DELETE FROM custody_proposal")
    suspend fun clear()
}
```

- [ ] **Step 2: Register the entity, bump the version, add the migration**

In `CoPlanlyDatabase`: add `CustodyProposalEntity::class` to `entities`, change `version = 13` to `version = 14`, and add `abstract fun custodyProposalDao(): CustodyProposalDao`.

In `DatabaseMigrations`, next to the others, and add `MIGRATION_13_14` to `ALL_MIGRATIONS`:

```kotlin
    /**
     * v13 → v14: the pair's pending custody proposal.
     *
     * Creating a table needs no data move: before this version a proposal could not exist, so
     * there is nothing to carry forward and an empty table is the correct starting state.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custody_proposal` (" +
                    "`pairId` TEXT NOT NULL, " +
                    "`modelType` TEXT NOT NULL, " +
                    "`patternDays` INTEGER NOT NULL, " +
                    "`momDaysPattern` TEXT NOT NULL, " +
                    "`startDate` TEXT NOT NULL, " +
                    "`repeatYearly` INTEGER NOT NULL, " +
                    "`proposedBy` TEXT NOT NULL, " +
                    "`proposedAt` TEXT NOT NULL, " +
                    "PRIMARY KEY(`pairId`))"
            )
        }
    }
```

- [ ] **Step 3: Add the migration test**

In `CoPlanlyDatabaseMigrationTest`, following the 12→13 case already there:

```kotlin
    @Test
    fun migrate13To14_createsTheProposalTableAndKeepsTheAgreedPattern() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                "INSERT INTO custody_models " +
                    "(id, modelType, patternDays, momDaysPattern, startDate, isActive, " +
                    "repeatYearly, createdAt, lastModifiedAt) VALUES " +
                    "('m1', 'custom', 14, '[0,1]', '2026-08-03', 1, 1, " +
                    "'2026-08-01T09:00:00', '2026-08-03T10:00:00')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, DatabaseMigrations.MIGRATION_13_14)

        db.query("SELECT COUNT(*) FROM custody_proposal").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT momDaysPattern FROM custody_models WHERE id = 'm1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("[0,1]", cursor.getString(0))
        }
    }
```

- [ ] **Step 4: Build, and export the schema**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL, and `app/schemas/com.coparently.app.data.local.CoPlanlyDatabase/14.json` appears. If Room reports an identity-hash mismatch, the `CREATE TABLE` above disagrees with the entity — match it exactly rather than editing the exported schema.

- [ ] **Step 5: Run the unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS. The migration test itself is instrumented (`androidTest`) and needs a device; run it with `./gradlew connectedDebugAndroidTest --tests "*CoPlanlyDatabaseMigrationTest*"` if a handset is attached, and record in the PR whether it was run.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/local app/src/androidTest app/schemas
git commit -m "feat(custody): mirror the pending proposal into Room (schema v14)"
```

---

### Task 4: The repository — propose, withdraw, accept, decline

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/repository/CustodyModelRepository.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/CustodyModelRepositoryTest.kt`

**Interfaces:**
- Consumes: `CustodyProposalTransition` (Task 1), the widened document mapping (Task 2), `CustodyProposalDao` (Task 3).
- Produces:
  - `suspend fun propose(model: CustodyModel): Result<Unit>`
  - `suspend fun withdrawProposal(): Result<Unit>`
  - `suspend fun acceptProposal(): Result<Unit>`
  - `suspend fun declineProposal(note: String?): Result<Unit>`
  - `fun observeProposal(): Flow<CustodyProposal?>`

- [ ] **Step 1: Write the failing tests**

Add to `CustodyModelRepositoryTest`, in its existing MockK style:

```kotlin
    @Test
    fun `proposing does not touch the local pattern`() = runTest {
        // The whole point: the proposer's own calendar keeps showing the agreed schedule until
        // the co-parent answers. A repository that saved locally "optimistically" would show
        // one parent a schedule the other never agreed to.
        givenPairedWithSharedPattern()

        repository.propose(wanted)

        coVerify(exactly = 0) { custodyModelDao.insertModel(any()) }
        coVerify(exactly = 0) { custodyModelDao.deactivateAllModels() }
    }

    @Test
    fun `accepting a proposal writes the promoted pattern locally and remotely`() = runTest {
        givenPairedWithPendingProposalFrom(DAD)

        repository.acceptProposal()

        val written = slot<SharedCustody>()
        coVerify { custodyDataSource.setCustody(any(), any(), capture(written)) }
        assertEquals(wanted.momDayIndices, written.captured.model.momDayIndices)
        assertNull(written.captured.proposal)
        assertEquals(MOM, written.captured.lastModifiedBy)
    }

    @Test
    fun `declining leaves the agreed pattern alone`() = runTest {
        givenPairedWithPendingProposalFrom(DAD)

        repository.declineProposal(note = "School run")

        val written = slot<SharedCustody>()
        coVerify { custodyDataSource.setCustody(any(), any(), capture(written)) }
        assertEquals(agreed.momDayIndices, written.captured.model.momDayIndices)
        assertEquals(CustodyDecisionOutcome.DECLINED, written.captured.lastDecision?.outcome)
    }

    @Test
    fun `a refused transition surfaces as a failure and writes nothing`() = runTest {
        // The co-parent already has one waiting. Silently overwriting it is the failure this
        // feature exists to prevent, one layer up.
        givenPairedWithPendingProposalFrom(DAD)

        val result = repository.propose(wanted)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { custodyDataSource.setCustody(any(), any(), any()) }
    }

    @Test
    fun `the mirror clears the local proposal when the shared document has none`() = runTest {
        givenPairedWithSharedPattern()

        repository.awaitMirror()

        coVerify { custodyProposalDao.clear() }
    }
```

- [ ] **Step 2: Run and confirm failure**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.CustodyModelRepositoryTest"
```

Expected: compilation failure — `Unresolved reference: propose`.

- [ ] **Step 3: Implement the four operations**

Each follows the same shape, which mirrors the existing `saveAndActivate`: resolve the pair, read the shared document, apply the transition, write. Only `accept` also writes Room, and it goes through the existing local-save path so the active-model bookkeeping (`deactivateAllModels` then insert) stays in one place.

```kotlin
    /**
     * Puts [model] to the co-parent as a proposal. Changes nothing locally: the proposer's own
     * calendar keeps showing the agreed schedule until the answer arrives, which is the
     * difference between this and [saveAndActivate].
     *
     * Fails, rather than no-oping, when the co-parent already has a proposal waiting — see
     * [CustodyProposalTransition.propose].
     */
    suspend fun propose(model: CustodyModel): Result<Unit> =
        transition { current, pair -> CustodyProposalTransition.propose(
            current, model, repeatYearly = true, byUid = pair.myUid, atIso = nowIso()
        ) }

    /** Takes back this device's own pending proposal. */
    suspend fun withdrawProposal(): Result<Unit> =
        transition { current, pair -> CustodyProposalTransition.withdraw(current, pair.myUid) }

    /**
     * Takes up the co-parent's proposal. This is the one transition that also changes this
     * device's pattern, and it does so through the same local path every other save uses.
     */
    suspend fun acceptProposal(): Result<Unit> =
        transition { current, pair ->
            CustodyProposalTransition.accept(current, pair.myUid, nowIso())
        }.onSuccess { /* the mirror promotes the new pattern into Room on the echo */ }

    /** Turns the co-parent's proposal down, optionally saying why. */
    suspend fun declineProposal(note: String?): Result<Unit> =
        transition { current, pair ->
            CustodyProposalTransition.decline(current, pair.myUid, nowIso(), note)
        }

    /** The pending proposal as Room mirrors it. */
    fun observeProposal(): Flow<CustodyProposal?> =
        custodyProposalDao.observe().map { it?.toDomain() }
```

with the shared helper:

```kotlin
    /**
     * Reads the shared document, applies [change], and writes the result back.
     *
     * Read-modify-write rather than a partial update, because every transition is expressed as
     * a whole-document function and `FirestoreCustodyDataSource.setCustody` is a full `set()`
     * — the rule requires `participants` on every write, so a partial update is not available
     * here anyway.
     *
     * The window between the read and the write is real: two devices deciding the same proposal
     * within it both succeed, and the later write wins. That is acceptable because both writes
     * express a decision *somebody* made about *this* proposal, and `lastDecision.proposalAt`
     * records which proposal was answered — unlike the pattern overwrite this feature replaces,
     * no decision can be attributed to a parent who did not make it, because the rules pin
     * `lastModifiedBy`/`proposal.proposedBy` to the caller.
     */
    private suspend fun transition(
        change: (SharedCustody, CustodyPair) -> Result<SharedCustody>
    ): Result<Unit> = ...
```

Implement `transition` against the existing `currentPair()` and `guarded(...)` helpers already in the file; reuse `guarded` so a Firestore failure is contained the same way every other write in this class contains it.

- [ ] **Step 4: Mirror the proposal, and complement it on a slot change**

In `mirrorIntoRoom`, alongside the pattern mirror:

```kotlin
        val proposal = remote?.proposal
        if (proposal == null) {
            custodyProposalDao.clear()
        } else {
            custodyProposalDao.upsert(proposal.toEntity(pairId))
        }
```

And in `saveReslotted`'s neighbourhood — the path `ParentSlotMigrator` uses when this device changes slot — complement the mirrored proposal too:

```kotlin
    /**
     * Complements the pending proposal along with the agreed pattern when this device moves
     * slot.
     *
     * `momDayIndices` means "the days slot 1 has custody", so a proposal left un-complemented
     * after this device moved to slot 2 would, on acceptance, hand the user their co-parent's
     * days — the same inversion `ParentSlotMigrator` complements the agreed pattern to avoid,
     * arriving later and with a consent step in front of it to make it look deliberate.
     */
    suspend fun complementPendingProposal() { ... }
```

Call it from `ParentSlotMigrator.reslotIfSlotChanged` and from `PairingViewModel.reconcileCustody`, immediately after the existing pattern complement, so both entry points stay auditable together.

- [ ] **Step 5: Run the tests**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.*"
```

Expected: PASS, including `ParentSlotMigratorTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/repository app/src/test/java/com/coparently/app/data/repository
git commit -m "feat(custody): propose, withdraw, accept and decline a schedule change"
```

---

### Task 5: The rules

**Files:**
- Modify: `firestore.rules` (the `custody_models` `allow update`)
- Test: `firestore-tests/rules/custody-models.test.js`

**Interfaces:**
- Consumes: the document shape from Task 2.
- Produces: a `patternUnchanged(before, after)` helper in `firestore.rules`.

- [ ] **Step 1: Write the failing cases**

One per clause, in the file's existing style — a proposal-only write that leaves `lastModifiedBy` alone must **succeed**; a proposal write that also changes the pattern, or that stamps the co-parent as proposer, or that overwrites the co-parent's pending proposal, must **fail**; accept and decline (which do change the pattern and therefore must name the caller) must succeed.

- [ ] **Step 2: Run and confirm the new cases fail**

```bash
cd firestore-tests && npm test
```

Expected: the proposal-only write is denied by the current rule, which demands `lastModifiedBy == request.auth.uid` on every update.

- [ ] **Step 3: Implement the rule** exactly as §4 of the spec states, adding `patternUnchanged` next to `canonicalPairId`.

- [ ] **Step 4: Re-run**

```bash
cd firestore-tests && npm test && npm run lint
```

Expected: all passing.

- [ ] **Step 5: Commit**

```bash
git add firestore.rules firestore-tests/rules/custody-models.test.js
git commit -m "fix(rules): admit a proposal-only write without loosening who may author a change"
```

---

### Task 6: The push

**Files:**
- Modify: `functions/index.js`, `functions/test/`

**Interfaces:**
- Consumes: the document shape from Task 2; `custodyModelKey` and the `notification_queue` shape already in the file.
- Produces: `notifyOfCustodyChange(db, before, after)` and `exports.onCustodyModelWritten`.

- [ ] **Step 1: Write the failing mocha tests** — the three branches from §5 of the spec: proposal appeared → the other participant is queued; proposal cleared with a new `lastDecision` → the **proposer** is queued with the outcome; proposal cleared with no new decision → nothing is queued.

- [ ] **Step 2: Run and confirm failure**

```bash
cd functions && npm test
```

- [ ] **Step 3: Implement** `notifyOfCustodyChange` and the `onWrite` trigger, following `notifyOfChatMessage`'s guards (missing document, missing participants, author not a participant) and its `notification_queue` write.

- [ ] **Step 4: Re-run**

```bash
cd functions && npm test && npm run lint
```

- [ ] **Step 5: Commit**

```bash
git add functions
git commit -m "feat(functions): tell the other parent a schedule is waiting, and the proposer the answer"
```

---

### Task 7: Proposing, in the setup screen

**Files:**
- Modify: `presentation/custody/CustodySetupScreen.kt`, `CustodySetupViewModel.kt`
- Modify: `res/values/custody_strings.xml` **and all four locale variants**
- Test: `app/src/test/java/com/coparently/app/presentation/custody/CustodySetupViewModelTest.kt` (new)

- [ ] **Step 1: Tests** — with no shared pattern the save path is unchanged (`saveAndActivate`); with a shared pattern it calls `propose` and does not touch the local model; a refused proposal surfaces as a message rather than as silence.
- [ ] **Step 2–4: Implement**, add the strings to five files, verify with `git grep -c 'name="custody_propose"' -- app/src/main/res/values*/*.xml` returning five.
- [ ] **Step 5: Commit** — `feat(custody): propose a schedule change instead of imposing it`

---

### Task 8: Reviewing, and the two banners

**Files:**
- Create: `presentation/custody/CustodyProposalScreen.kt`, `CustodyProposalViewModel.kt`
- Modify: `presentation/calendar/components/CalendarBanners.kt`, `CalendarViewModel.kt`, `presentation/navigation/NavGraph.kt`, `presentation/home/…` activity list
- Modify: the five `custody_strings.xml` and `calendar_strings.xml`

- [ ] **Step 1: Tests** — the banner shows only for a proposal this device did not make; the decision banner shows only to the proposer; dismissal keys on `lastDecision.at` (a new `PreferenceKeys` entry alongside `DISMISSED_CUSTODY_CHANGE_AT`).
- [ ] **Step 2–4: Implement.** Extract `CustodyConflictScreen`'s two-pattern comparison into a shared composable rather than copying it; follow `ChangeRequestBanner`'s anatomy for the "waiting on you" banner and `CustodyChangedBanner`'s for the outcome one.
- [ ] **Step 5: Commit** — `feat(custody): review a proposed schedule and answer it`

---

## Device verification

Cannot run until **PR #46 is merged and the rules are deployed** — the shared document is unreadable otherwise, which is the defect PR #46 fixes.

1. Propose on A. B gets a push, a calendar banner and a Home row; **B's calendar still shows the old pattern**, and so does A's.
2. B accepts. Both calendars change together; A gets the outcome banner.
3. Propose again, B declines with a note. Neither calendar changes; A sees the note.
4. Propose and withdraw before B answers. B's banner disappears; nobody is notified.
5. Both propose within a few seconds. The loser is told a proposal is already waiting, and neither proposal is lost.
6. Watch `adb logcat -v time | grep -i coparently` for `PERMISSION_DENIED` throughout.
