# Named parents, and a custody schedule that reaches both phones

**Date:** 2026-08-05
**Scope:** §11 item 2 of `docs/TEST-PLAN-2026-08.md`, plus two of the requests added on 5 August:
"an event is for you by default", and showing a parent by name instead of as Mom/Dad. The naming
change replaced a third request — deriving the role from Google's gender field — which was dropped
rather than deferred; showing names removes the question it was meant to answer. §11 item 3, and
the request that an event created for the other parent needs their confirmation, are one feature and
get their own spec. See Non-goals.
**Base:** `main` (`2ca517f1`), which now carries §11 batch 1 and item 8. Not stacked on another
branch: this project has already been burned once by a stacked-PR chain stranding finished work
off `main`.

## What this is

Two changes that only make sense together.

**A parent stops being "Mom" or "Dad" and becomes their name.** The app currently shows the words
Мама/Папа everywhere and stores a `role` nobody ever chose. **A custody pattern stops being a
private local setting and becomes something both phones see.** Today it has no path to Firestore at
all, in either direction.

They belong in one spec because the seam between them is the sharpest thing in it: pairing changes
which of the two parent slots you occupy, and a custody pattern is expressed in slot terms. Get the
order wrong and a parent is shown their own schedule inside out.

## Decisions taken

Product questions settled before this spec was written. The implementation is not free to revisit
them.

1. **Parents are shown by name, never as "Mom"/"Dad".** This was the user's call, and it is better
   than the role-picker design that preceded it: it removes a question instead of asking it, and it
   describes families the mom/dad model could not.
2. **"Mom"/"dad" survive as internal slot identifiers.** They are not renamed to UIDs. See
   "Why the strings stay" below.
3. **Slot colours stay pink and blue.** `ParentColors.kt` is unchanged in code; only its documented
   meaning changes — a colour belongs to a parent, not to a role.
4. **Custody is last-write-wins, with the other parent told.** No consent mechanism in this round;
   see Non-goals.
5. **At pairing, if both parents have a schedule and the two disagree, the accepter chooses.**
   Not "the inviter wins", not "the newer timestamp wins" — nobody's schedule disappears silently
   and no clock decides.

## Why the strings stay

`"mom"` and `"dad"` appear as literals 126 times across 34 files: Room entities, the Firestore data
sources, the custody arithmetic, the expense balance, natural-language event parsing, Google
Calendar sync, and every screen. `Event.parentOwner` is part of the Firestore document schema that
`EventRepositoryImpl.toFirestoreMap()` defines as the single source of truth.

Replacing them with UIDs would mean a Room migration, a Firestore document-schema change, a
`firestore.rules` change, and **a co-parent on an older build reading a UID where it expects
`"mom"`**. That last one is the decisive argument: the two phones are not upgraded at the same
moment, and this project already knows what that costs.

So the words disappear and the identifiers do not. After this change `"mom"` means "the first
parent slot" and nothing else. That is a real cost — a field named `parentOwner` holding `"mom"`
when it means "slot 1" is a small lie the next reader has to be told about — and it is paid in
KDoc and in `CLAUDE.md`, not in a migration.

---

## Part A — a parent is their name

### The role picker does not exist

An earlier draft of this design had an onboarding screen asking "are you the mother or the father?",
a nullable `role`, a Room migration to null it, a `setParentRole` callable, and a Settings row.
All of it existed to ask a question that showing names removes. None of it is built.

The slot is assigned instead:

- a new account is slot 1 (`role = "mom"`, the value `DEFAULT_ROLE` already produces);
- accepting an invitation makes you slot 2 (`role = "dad"`), set inside the transaction that
  already reads and updates both user documents (`functions/index.js:465-466`).

This must happen server-side. `firestore.rules` lets a client write only its own `users/{uid}`, and
working around that is why the permissive `firestore.rules.simple` once had to be deployed
(`CLAUDE.md`, item 11).

### Re-stamping what the accepter already owns

An unpaired parent is slot 1, and everything they create is stamped slot 1. The moment they accept
an invitation they become slot 2, and their own past records start reading as the co-parent's.

On the slot flip, the accepter's device re-stamps what it owns:

| Where | Field | From → to |
|---|---|---|
| `EventEntity` | `parentOwner` | `"mom"` → `"dad"`, for rows the accepter created |
| `EventEntity` | `pickupConfirmedBy` | same, where non-null |
| `CustodyScheduleEntity` (legacy) | its parent columns | same |

Scoped by `createdByFirebaseUid == me`, though before pairing there is no other creator, so in
practice it is every local row. Expenses need nothing: they derive the parent from
`createdByFirebaseUid` through `roleByUid`, never from a stored slot.

Events already synced to Firestore before pairing are updated by the same pass, so the co-parent
sees them attributed correctly once they arrive.

The accepter's local custody pattern also changes meaning — that is Part B's seam, below.

### One place that knows names

A single function turns a slot into a label:

```kotlin
// presentation/common/ParentLabels.kt
fun parentLabel(slot: String, me: User?, coParent: User?): String
```

built over `UserRepository.getAllUsers()`, the flow `ExpenseViewModel` already collects. Fallbacks
are explicit, not accidental:

- my slot, no name stored → "You"
- the other slot, no name or no co-parent → "Co-parent"

Every screen goes through it. No composable formats a parent label of its own.

### The parent selector disappears when there is no co-parent

Today the event editor always offers a choice between two parents, one of whom may not exist. With
no co-parent there is nothing to choose: the selector is not rendered and the event is yours.

### An event is yours by default

`AddEditEventScreen` initialises the selector to the current user's slot
(`AddEditEventScreen.kt:151`, `:314`, `:394` all hardcode `"mom"` today). The event stays shared and
visible to the co-parent — this is a shared calendar, and what happens on your day is exactly what
the other parent needs to know. "For yourself" means *owned by you*, not hidden.

If the slot is somehow unknown, the selector starts unselected and Save is disabled until a parent
is picked. Unreachable in practice; it exists so that nothing anywhere quietly falls back to "mom"
again.

### Strings: a rewrite, not a translation

About twenty keys across five locales are built on grammatical gender:

```xml
<string name="expenses_mom_paid">Мама заплатила %1$s</string>
<string name="expenses_dad_paid">Папа заплатил %1$s</string>
```

A name cannot agree with a verb. "Оля заплатил" and "Оля заплатила" cannot be chosen between
programmatically, and "заплатил(а)" reads as an apology. The phrasings have to be restructured so
agreement never arises — a label and a value ("Оля · 500 Kč", "Payment: Оля") rather than a
sentence. The same applies in Czech, German and Ukrainian.

This is copywriting in five languages, not translation of existing strings, and it is a real part of
the work. Keys affected include `calendar_parent_mom/dad`, `custody_mom/dad`,
`custody_mom_starts_first`, `custody_week1_to_mom`, `event_parent_mom/dad`,
`event_preview_mom/dad`, `expenses_mom_paid/dad_paid`, `home_parent_mom/dad`,
`custody_with_mom/dad`, and `calendar_day_desc_with_parent`.

Per the project rule, every new key lands in all five locales in the same commit.

### `ParentColors` keeps its code and loses its rationale

No code change. `ParentColors.kt` still resolves a slot to `MomPink`/`DadBlue` through `fill()` and
`text()`, and the saturation rule (a day wash at 14% alpha, a chip at full strength) is untouched.
What changes is the KDoc and the `CLAUDE.md` invariant: **a colour identifies a parent, not a
role.** Slot 1 is pink, slot 2 is blue.

---

## Part B — the custody schedule reaches both phones

### Today

`CustodyModelRepository` talks only to `CustodyModelDao`. There is no custody Firestore data source.
`CustodyModelEntity` carries no owner field at all — the same shape as the `budgets` gap, which
`CLAUDE.md` records as worse than the `expenses` one because there was nothing for a rule to gate
on. After pairing, each parent sets a pattern locally, the two are never compared, and nothing tells
either of them they disagree.

### The document id is derived

`domain/custody/CustodyKey.of(uidA, uidB)`, the same shape as `domain/chat/ConversationKey`: sort
the two UIDs, join with `"__"`, and reject blank uids, equal uids, and uids containing the
separator — without that last check `of("x__y", "z")` and `of("x", "y__z")` both produce
`"x__y__z"`, two different pairs colliding on one id.

Both devices compute the same id with no query and no coordination, so creating the document is
idempotent. Randomly generated ids are what once settled the two phones on separate chat threads.

### A new collection, and one dead rule removed

Writes go to `custody_models/{custodyKey}`.

The existing `custody_schedules` rule block is **deleted** in the same change. It was written for
the legacy `CustodyScheduleEntity`, which is Room-only; `CLAUDE.md` records that it matches no
client code and was left in place only because the last round was mid-pairing-work. Keeping two
similar blocks, one of them dead, is how the next person fixes the wrong one.

Document fields mirror `CustodyModelEntity` — `modelType`, `patternDays`, `startDate`,
`repeatYearly`, `createdAt`, `lastModifiedAt` — with dates as ISO strings, as everywhere else in
this schema.

One field does not mirror. Room stores `momDaysPattern` as a JSON string because SQLite has no array
type; Firestore does, so the document carries `momDayIndices` as a real array of integers and the
data source does the conversion. The JSON string is a storage detail of one database and has no
business crossing the wire, where it would be opaque to a rule and to anyone reading the console.

Two fields the entity does not have at all:

- `participants: [uidA, uidB]`, sorted, matching the key — what the rule gates on;
- `lastModifiedBy`, a UID — what the banner names.

### The rule, and why no list query

```
match /custody_models/{modelId} {
  allow read:   if isAuthenticated() && request.auth.uid in resource.data.participants;
  allow create: if isAuthenticated() && request.auth.uid in request.resource.data.participants;
  allow update: if isAuthenticated() && request.auth.uid in resource.data.participants
                                     && request.auth.uid in request.resource.data.participants;
  allow delete: if isAuthenticated() && request.auth.uid in resource.data.participants;
}
```

`CLAUDE.md` item 12 is the trap here: a list query is rejected outright unless its structure
guarantees every possible result satisfies the rule. **This code issues no list query.** There is
exactly one document per pair and it is read by id, so no `whereArrayContains` is needed — stated
explicitly because the reflex to add one is what the note is about.

The rule is verified in `firestore-tests/` against the emulator. Not by deploying and watching a
phone: that is how a broken `expenses` delete rule shipped once already.

### The listener retries from the start

`CustodyModelRepository` gains a Firestore data source and observes the pair's document. Room stays
the source of truth; Firestore mirrors it.

The observer is built with `retryWhen` and backoff **from the first commit**. This project carries a
live defect of exactly this shape: both mirror branches in `MessageRepositoryImpl` end in
`.catch { Log.w(...) }`, which completes the flow, so after one denied read the chat runs on Room
alone for the rest of the process — the app looks entirely healthy and receives nothing. It is in
the known-issues list with the fix named. A new listener has no excuse to repeat it.

Writes are wrapped in `try/catch`, like `addBudget`/`deleteBudget`: an uncaught `PERMISSION_DENIED`
from a suspend call inside `viewModelScope.launch` crashes the app, it does not merely fail to sync.

### Last write wins, but never silently

When the observer delivers a model whose `lastModifiedBy` is not the current user, the calendar
raises an inline banner through `presentation/calendar/components/CalendarBanners.kt` — the
mechanism the August design refresh established for change requests and school vacation. It names
the co-parent (through `parentLabel`) and is dismissible; it returns on the next change, keyed on
`lastModifiedAt`.

No push notification. This app requests notification permission contextually and there is nothing
here worth requesting it for.

### The pairing conflict screen

Shown once, inside the invitation-accept flow in `presentation/pairing/`, and only when both parents
have an active model **and the two are not equivalent**. It presents both patterns and asks the
accepter to pick. The chosen one is written to the shared document; the other is deactivated
locally, not deleted, so it stays in `getAllModels()`.

Equivalence is semantic, not field-wise: two `startDate`s a whole number of cycles apart describe
the same schedule. `CustodyModel.isEquivalentTo(other)` compares the custody assignment across a
window rather than comparing fields.

### Where A and B meet — the sharpest thing in this spec

`momDayIndices` means "the days slot 1 has custody". When the accepter's slot flips from 1 to 2,
their own pattern must be **complemented** — every index in the cycle that was not in the set — so
that it keeps meaning "my days".

Order matters and is not negotiable:

1. the slot flips (Part A);
2. the accepter's local model is complemented;
3. only then is it compared with the co-parent's for equivalence, and only then is the conflict
   screen shown.

Complement after comparing, or skip it, and the conflict screen offers the accepter their own
schedule inverted. They would pick the co-parent's, believing they were rejecting a stranger's
pattern, and hand over the exact days they meant to keep. `CustodyModel.complemented()` is a pure
function with an obvious test.

### Unpairing

`unpairCoParent` deletes the shared document. Both parents keep their local Room copy.

### The backfill caveat, same as `budgets`

Custody models that exist locally before this change have no shared document and will not get one
until someone saves a schedule again. Room is the source of truth, so nothing disappears on the
device that created it, but the second phone shows nothing until that first save. No backfill
migration is run.

---

## Non-goals

- **No consent mechanism.** Item 3 ("edit the custody schedule and propose it to the co-parent") and
  the request that an event created for the other parent needs their confirmation are the same
  feature — a proposal that binds the other parent — and they get their own spec. Deliberately
  noted: until then, either parent can overwrite the shared custody pattern. That is consistent with
  how events already behave (`permissions` defaults to `read_write` and nothing checks it), and the
  banner makes it visible rather than silent.
- **`parentOwner` is not renamed to a UID.** See "Why the strings stay".
- **Parents do not choose their colour.** `User.colorCode` exists in the model and is the obvious
  home for it later. Slot 1 is pink and slot 2 is blue in this round.
- **No Google People API, no gender detection.** It would need a new OAuth scope and re-verification
  for a field that is usually empty, and gender is not a parental role. Showing names removes the
  question entirely.
- **`custody_schedules` the legacy Room table is not touched.** Only its dead Firestore rule block
  is removed.
- **No push notification for a custody change.**

## Testing strategy

JVM unit tests plus the Firestore emulator. This project has no instrumentation or Compose UI tests,
so anything asserted lives in a pure function and the composable keeps only wiring.

| What | Where |
|---|---|
| `CustodyKey.of` — order independence, blank, equal, separator-in-uid | new `CustodyKeyTest` |
| `CustodyModel.complemented()` — round-trip, full and empty sets, odd cycle lengths | `CustodyModelTest` |
| `CustodyModel.isEquivalentTo()` — identical, shifted by a whole cycle, shifted by part of one, different cycle lengths | `CustodyModelTest` |
| `parentLabel` — both slots, missing name, missing co-parent | new `ParentLabelsTest` |
| The re-stamp pass — rows owned by me, rows owned by nobody, `pickupConfirmedBy` null and set | new repository test |
| The `custody_models` rule — each parent reads and writes, a third account is refused, a document without `participants` is refused | `firestore-tests/` |

The emulator suite needs a JDK 21+ on `PATH`, not just in `JAVA_HOME` — see its README.

Device verification closes it: two phones, pair them, confirm the schedule arrives, change it on one
and confirm the banner names the other parent. Cross-phone acceptance is the only way to see the
slot flip actually work.

## Risks

- **The slot flip is the whole risk of this spec.** It rewrites the accepter's local rows and
  inverts their custody pattern, at a moment the user experiences as "I tapped Accept". If it half
  runs — the flip lands, the re-stamp does not — every past event of theirs is attributed to the
  co-parent. The pass must be one Room transaction and must be idempotent, so a retry after a crash
  cannot flip twice.
- **A pair created before this change has both parents at slot 1.** `DEFAULT_ROLE` gave everyone
  `"mom"` and pairing never changed it. Existing pairs therefore need the same slot assignment
  applied once, and the accepter is not identifiable after the fact — `pairedAt` is on both
  documents and the invitation records `acceptedBy`. The migration reads `acceptedBy` from the
  invitation; where no invitation survives, the pair is left alone and the parents are indistinct
  until one of them re-saves. This is the one place where the design cannot fully repair the past
  and says so.
- **Copy in five languages is on the critical path.** The gendered-verb rewrite is not translation,
  and a half-done pass leaves one locale saying "Мама заплатила" next to a name.
- **`isEquivalentTo` over a window can be wrong for patterns whose cycles differ in length.** The
  comparison window must be the least common multiple of the two cycle lengths, not a fixed 14 days,
  or a 14-day and a 21-day pattern can look equivalent across a short window.

## Files

**Part A**
- `functions/index.js` — slot assignment in the `acceptPairingInvitation` transaction
- `app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt`
- new `app/src/main/java/com/coparently/app/presentation/common/ParentLabels.kt`
- `app/src/main/java/com/coparently/app/presentation/event/AddEditEventScreen.kt`
- the screens that render a parent label: `calendar/`, `expenses/`, `home/`, `summary/`,
  `event/EventPreviewSheet.kt`, `event/EventListScreen.kt`
- `app/src/main/res/values{,-cs,-de,-ru,-uk}/*.xml`
- `app/src/main/java/com/coparently/app/presentation/theme/ParentColors.kt` (KDoc only)
- `CLAUDE.md` — the colour invariant and the slot-identifier note

**Part B**
- new `app/src/main/java/com/coparently/app/domain/custody/CustodyKey.kt`
- `app/src/main/java/com/coparently/app/domain/model/CustodyModel.kt` — `complemented`,
  `isEquivalentTo`
- new `app/src/main/java/com/coparently/app/data/remote/firebase/FirestoreCustodyDataSource.kt`
- `app/src/main/java/com/coparently/app/data/repository/CustodyModelRepository.kt`
- `app/src/main/java/com/coparently/app/presentation/pairing/` — the conflict screen
- `app/src/main/java/com/coparently/app/presentation/calendar/components/CalendarBanners.kt`
- `firestore.rules`, `firestore-tests/`
- `functions/index.js` — delete the shared document in `unpairCoParent`
