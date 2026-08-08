# A custody change the other parent has to agree to — design

**Date:** 9 August 2026
**Branch:** `feat/custody-proposal-2026-08`
**Base:** `main` + `fix/weekend-tint-and-pairing-audience` (which carries the rules fix below)

Today either parent can change the custody schedule and it lands on the other's phone with no
consent step. This makes a change a **proposal**: it applies to nobody until the co-parent
accepts it.

---

## 1. What exists today

PR #45 gave custody a Firestore path. `custody_models/{pairId}` holds one pattern for the pair;
`CustodyModelRepository` mirrors it into Room and pushes local saves out. The contract is
**last-write-wins with no consent** — `CustodyChangeAnnouncement`'s KDoc states it outright — and
the only mitigation is `CustodyChangedBanner`, which tells the losing parent after the fact and is
dismissible.

Two agreement mechanisms exist and neither covers this:

- `CustodyConflictScreen` runs **once, at pairing**, when both parents already have a pattern and
  the accepter picks one. That is a genuine agreement made interactively, and it stays a direct
  write — see §7.
- `ChangeRequest` is bound to a single `eventId` and cannot carry a pattern.

**A prerequisite shipped separately.** `firestore.rules`' `allow get` dereferenced
`resource.data.participants` unconditionally, and `resource` is null for a document that does not
exist — a Null value error, which a rule treats as a denial. Every pair whose document had never
been written could therefore never read it, the startup listener failed permanently, and the retry
loop spun forever. Reproduced on the emulator (`custody-models.test.js`, "lets a participant listen
before the document exists") and fixed on the branch above. **Custody sync has never worked in
production**, so everything below is being built on a path that must be redeployed before any of
it can be observed.

## 2. The change

A save while paired writes a **proposal** onto the shared document instead of the pattern. The
pattern is untouched — on both phones — until the co-parent accepts.

```
custody_models/{pairId}
  participants, id, modelType, patternDays, momDayIndices,      <- the agreed pattern
  startDate, repeatYearly, createdAt, lastModifiedAt, lastModifiedBy
  proposal: {                                                    <- null when nothing is pending
    modelType, patternDays, momDayIndices, startDate, repeatYearly,
    proposedBy, proposedAt
  }
  lastDecision: {                                                <- null until the first decision
    outcome: 'ACCEPTED' | 'DECLINED',
    by, at, proposalAt, note
  }
```

Transitions, each one write:

| Action | Who | Effect |
|---|---|---|
| Propose | either | sets `proposal`; **no pattern field and no `lastModifiedAt`/`lastModifiedBy` change** |
| Withdraw | the proposer | clears `proposal` |
| Accept | the other parent | promotes `proposal` into the pattern fields, bumps `lastModifiedAt`/`lastModifiedBy` to the accepter, clears `proposal`, writes `lastDecision` |
| Decline | the other parent | clears `proposal`, writes `lastDecision` with the note; pattern untouched |

**The first schedule a pair ever agrees is not a proposal.** With no shared pattern yet there is
nothing to disagree with and nobody to overrule, and requiring approval for it would leave a new
pair unable to start. A save applies directly when the document has no pattern; every later change
is a proposal. This is the rule to state in the UI, and it is the only special case.

### Why a sub-map and not a `custody_proposals` collection

One document means one listener, one rule block, and an accept that is a single `set()` rather
than two writes needing a transaction to stay consistent. The existing mirror already delivers
this document to both phones; a second collection would need a second listener with its own
retry loop — the component that has already produced one production defect on this path.

### Why a proposal write leaves `lastModifiedBy` alone

That field is not bookkeeping: `CustodyChangeAnnouncement.toAnnounce` suppresses any change whose
`lastModifiedBy` equals the reader's own uid, which is how a device ignores its own echo. If
proposing stamped the proposer, then proposing right after the co-parent changed the pattern would
make the co-parent's not-yet-dismissed change read as the proposer's own echo and silently swallow
its banner. Leaving both `lastModified*` fields untouched keeps a proposal orthogonal to the
pattern's own change history.

The rule (§4) still requires every write to name its author honestly — it just accepts
`proposal.proposedBy` as that name when the write only touches the proposal.

## 3. Room — schema v14

The pending proposal must survive process death and be readable offline, so it is mirrored, like
the pattern. A **new table** rather than nullable columns on `custody_models`: a proposal is a
whole second pattern plus its own metadata, and widening the entity would put two patterns in one
row with only a null check separating them.

```kotlin
@Entity(tableName = "custody_proposal")
data class CustodyProposalEntity(
    @PrimaryKey val pairId: String,   // one row per pair; the shared document's id
    val modelType: String,
    val patternDays: Int,
    val momDaysPattern: String,       // JSON, matching CustodyModelEntity's storage
    val startDate: String,
    val repeatYearly: Boolean,
    val proposedBy: String,
    val proposedAt: String
)
```

Version 13 → 14, migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`),
exported schema in `app/schemas/`. `lastDecision` is **not** mirrored: it is read once to raise a
banner and never queried, so it stays on the shared document and reaches the UI through
`SharedCustody`.

## 4. Firestore rules

`custody_models`' `update` currently demands `request.resource.data.lastModifiedBy ==
request.auth.uid`. That must now admit a proposal-only write, without losing the guarantee it
exists for — that no parent can author a change under the other's name.

```
allow update: if isAuthenticated() &&
  request.auth.uid in resource.data.participants &&
  request.resource.data.participants == resource.data.participants &&
  (isPartnerOf(resource.data.participants[0]) || isPartnerOf(resource.data.participants[1])) &&
  (
    // A pattern write: names its author, as before.
    (request.resource.data.lastModifiedBy == request.auth.uid)
    ||
    // A proposal-only write: the pattern and its authorship are untouched, and the
    // proposal names its own author.
    (patternUnchanged(resource.data, request.resource.data) &&
     request.resource.data.lastModifiedBy == resource.data.lastModifiedBy &&
     request.resource.data.lastModifiedAt == resource.data.lastModifiedAt &&
     request.resource.data.proposal.proposedBy == request.auth.uid)
  ) &&
  // Nobody may overwrite a proposal that is not theirs — see §7.
  (resource.data.proposal == null ||
   resource.data.proposal.proposedBy == request.auth.uid ||
   request.resource.data.proposal == null)
```

`patternUnchanged` compares `modelType`, `patternDays`, `momDayIndices`, `startDate` and
`repeatYearly`. Every clause gets a case in `firestore-tests/rules/custody-models.test.js`; per
`CLAUDE.md`, rules are never debugged by deploying.

**A deploy is owed** for this and for the `allow get` fix already on the other branch. The owner
deploys; this spec does not.

## 5. The push

A Firestore trigger, not a client write, so the notification survives the proposing device going
offline the instant it writes. Same shape as `onChatMessageCreated`, and it queues into
`notification_queue` for the existing `sendNotification` sender.

```js
exports.onCustodyModelWritten = functions.firestore
    .document('custody_models/{modelId}')
    .onWrite(async (change) => { ... });
```

- `proposal` appeared → notify the *other* participant: a new schedule is waiting.
- `proposal` cleared **and** `lastDecision.at` changed → notify the **proposer** with the outcome.
- `proposal` cleared with no new decision → a withdrawal; notify nobody.

Bodies are built from the participants' names the same way the chat trigger does, and the
notification carries `type: 'custody_proposal'` plus the pair id so tapping it opens the review
screen.

## 6. UI

All five locales in the same commit, per `CLAUDE.md`.

- **`CustodySetupScreen`.** When a shared pattern exists the primary button reads "propose",
  not "save", and after the write the screen shows a pending strip — what was proposed, when, and
  a Withdraw action — with the option cards disabled until it resolves. When no shared pattern
  exists the button and behaviour stay exactly as they are today.
- **Calendar.** `CustodyProposalBanner` beside the existing banners in `CalendarBanners.kt`, with
  a Review action, following `ChangeRequestBanner`'s anatomy (it is the one that already means
  "something is waiting on you"). The existing `CustodyChangedBanner` stays for the accepted
  outcome and for the pairing-time direct write.
- **Review screen.** `CustodyProposalScreen`: current pattern against proposed, a two-week
  preview strip so the difference is legible as days rather than as indices, Accept, and Decline
  with an optional note. `CustodyConflictScreen` already renders two candidate patterns side by
  side — its comparison composable is extracted and shared rather than copied.
- **Home.** A row in the activity list, matching the pending-change-request row.
- **The proposer's outcome.** `lastDecision` raises a banner: accepted, or declined with the note.
  Dismissal is persisted on `lastDecision.at`, the same way `DISMISSED_CUSTODY_CHANGE_AT` already
  keys on `lastModifiedAt`.

## 7. Decisions and edge cases

- **Simultaneous proposals.** The rule refuses a write that would overwrite a proposal authored by
  the other parent. The loser's client surfaces "there is already a proposal waiting" and offers
  to open it. Without this clause the second writer silently erases the first, which is the
  failure the whole feature exists to prevent, reintroduced one layer up.
- **Pairing-time conflict resolution stays a direct write.** `CustodyConflictScreen` is the
  accepter choosing between two patterns at the moment they pair; both parents are, definitionally,
  acting. Routing it through a proposal would ask one of them to approve the choice they just made.
- **No expiry.** A proposal waits indefinitely; the proposer can withdraw. A timeout that silently
  applied or discarded a pattern would be the silent change this feature removes.
- **Unpairing** already deletes the shared document (PR #45), which takes any pending proposal with
  it. Nothing to add.
- **`ParentSlotMigrator`'s complement** rewrites the local pattern when this device's slot moves. A
  pending proposal is expressed in the same slot terms and must be complemented with it, or
  accepting it later would hand the user their co-parent's days. The proposal mirror is
  complemented in the same transaction.
- **Offline.** Propose, accept and decline are single writes to a document Firestore already
  queues offline; Room shows the pending state meanwhile. No new offline machinery.

## 8. Testing

- `CustodyProposalTest` (pure): the transition table in §2 — what each action writes and leaves
  alone — extracted out of the repository the way `CustodyChangeAnnouncement` was, so it is
  testable without a composition or a `StateFlow`.
- `CustodyModelRepositoryTest`: a proposal does not touch the local pattern; accept promotes it;
  decline leaves it; the mirror complements a pending proposal on a slot change.
- `FirestoreCustodyDataSourceTest`: proposal round-trips, including a document with no proposal.
- Migration test 13 → 14, in `CoPlanlyDatabaseMigrationTest`.
- `firestore-tests/rules/custody-models.test.js`: one case per clause in §4, including a
  proposal-only write that leaves `lastModifiedBy` alone, a pattern write that does not, and a
  parent trying to overwrite the other's pending proposal.
- `functions/`: `onCustodyModelWritten`'s three branches, mocha, alongside the chat trigger's.
- Two-device: propose on A, push arrives on B, B's calendar unchanged until Accept, both change on
  Accept, decline path with a note, and withdraw. **This is the acceptance test and it cannot run
  until the rules are deployed.**

## 9. Out of scope

- Per-day custody overrides ("swap this one weekend"). That is `ChangeRequest`'s territory.
- Any change to how the pattern itself is modelled (`momDayIndices` and the un-migrated slot
  expression noted in `CLAUDE.md` stay as they are).
- A history of past proposals. `lastDecision` holds the most recent one only.
