# More than one co-parenting relationship

A man has one child with one woman and two with another. A woman co-parents children with one
partner and a dog with another. Today the app cannot express either: it is built on a single
`users/{uid}.partnerId`, and every shared collection is gated on a rule that reads it.

This document is the plan. It exists because the change spans roughly 36 files and five branches,
and a plan of that size belongs in the repository rather than in a conversation.

## What the app assumes today

`isPartnerOf(userId)` — the one primitive every shared collection is gated on — reads
`users/{userId}.partnerId == request.auth.uid`. A single value, one partner. There are 32 uses of
it across 16 `match` blocks.

`assignSlots(inviterRole)` in `functions/index.js` returns the *opposite* slot to the inviter's
stored `role`, and writes both. With one partner that is coherent. With two it is not: a woman who
is slot 1 with partner X accepts an invitation from Y, who is slot 1 in *his* other family — and
`assignSlots` hands her slot 2 while her stored `role` still says slot 1. **The slot is a fact
about a family, and it is currently stored on the person.**

`User.caresFor` (`FamilyKind`) has the same shape problem, and it is the one the "children with
one partner, a dog with another" case runs into directly: one answer per account, when the
question is per family.

## The one good piece of news

**The family id already exists — it is just implicit.** `CustodyKey.of` and `ConversationKey.of`
produce a byte-identical string: two uids, sorted, joined by `"__"`. So `custody_models/{id}`,
`family_settings/{id}` and `conversations/{id}` are *already* keyed by family. Three subsystems —
the custody schedule, the agreed split and the chat thread — were designed per pair rather than
per account and need no migration at all.

What is keyed per account, and has to move:

| Collection | Today | After |
| --- | --- | --- |
| `events` | `sharedWith` computed at upload | `familyId` |
| `child_info` | creator + `isPartnerOf` | `familyId` |
| `pets` | creator + `isPartnerOf` | `familyId` |
| `expenses` | creator + `isPartnerOf` | `familyId` |
| `budgets` | creator + `isPartnerOf` | `familyId` |
| `change_requests` | creator + `isPartnerOf` | `familyId` |
| `custody_models` | already the pair id | unchanged |
| `family_settings` | already the pair id | unchanged |
| `conversations` / `messages` | already the pair id | unchanged |
| `users`, `friend_profiles`, `invitations`, `notification_queue` | not family-scoped | unchanged |

## The product model: a switcher, not a merged view

The app shows **one family at a time**. A chip in the top bar switches, and — following the rule
FAM-1 established — **it appears at two, never at one**: an account with a single family sees
every screen exactly as it does now.

Three reasons, and the first is not negotiable:

1. **The colour channels are exhausted.** Pink and blue are the two parent slots, teal is a
   calendar friend, neutral grey is the weekend. `presentation/calendar/DayCellFills.kt` exists to
   keep those from competing. A merged calendar would need a fifth channel to say *which family*,
   and there is not one. Two different women would both be pink.
2. **"Whose day is it" has no single answer across families.** Two families have two independent
   custody schedules. Merging the grid asks a question that cannot be answered.
3. **Money settles per pair.** The agreed split lives at `family_settings/{pairId}` and may be
   70/30 with one partner and even with another. A combined balance would add two different
   settlements together.

A cross-family Home — "today, across all of them" — is worth having and is deliberately *not*
part of this plan. It is additive once the switcher exists.

## What this change fixes on the way past

`familyId` on a record **is its audience**, resolved at read time by the rule. That deletes a
whole class of defect rather than working around it:

* **`sharedWith` retires**, and with it CLAUDE.md invariant 16 — "computed at upload time and
  never recomputed for a row already marked synced". Membership is no longer baked into each
  document, so it cannot go stale when a co-parent arrives later.
* **The `whereIn("createdByFirebaseUid", [a, b])` shape** becomes `whereEqualTo("familyId", …)`:
  one filter, one index, cheaper, and no composite index to keep in step.
* **CQ-5** (the sync downloads the whole event collection) becomes tractable, because the query
  is already narrowed to one family.

## The five stages

### M-1 · `families/{id}` as a first-class document

`families/{id}` where the id is the canonical pair key already in use, carrying `members` — the
two uids, sorted — and `createdAt`. `slots` and `kind` arrive in M-3, when something reads them;
a field nothing reads is the pattern this project keeps having to delete.

**The membership lives in the family document, and no client may write it.** The first draft of
this plan put a `families` array on `users/{uid}` instead, so the rule could read the caller's own
profile. That is an escalation: a user may write their own profile, so anyone could add a
stranger's family id to their own list and read that household's calendar. **A grant must never be
stored where its beneficiary can write it.** The document is written by
`acceptPairingInvitation` and deleted by `unpairCoParent`, both through the admin SDK, which
bypasses rules; the rule reads `families/{id}.members`.

Deleting it on unpair is not tidiness either — it *is* the revocation. The sweep narrows
documents, but a membership left behind would let the ex-partner back into all of them.

Pairing still writes `partnerId` until M-5, so nothing breaks mid-migration.

Reads are open to the two members, and so is a list filtered on `members array-contains` the
caller — Firestore validates a query's structure, so that query can only return documents that
would pass, while an unfiltered one is rejected. That is how a client asks "which families am I
in" without being told the answer by a field it could have forged.

### M-2 · Stamp `familyId` on the six collections

A Room column on each, nullable — **null means "mine alone", the state every record is in before
its owner pairs**. Stamped at create, never re-derived; `FamilyIdBackfill` names the family on
rows written before there was one to name, from the sync pass, once per co-parent.

**The rules do not switch here, and this heading used to say they did.** Working through it, the
order in the first draft was wrong in a way worth writing down, because the obvious fix is worse
than the problem.

A read path keyed on `familyId` needs the field pinned against a writer who is not its creator —
exactly what `sharedWith` and `permissions` are pinned against in the `events` update rule, and
for the same reason: a co-parent with `read_write` could otherwise re-point a record at a family
containing a stranger. But pinning it *now*, while both phones are still catching up, denies the
app's own writes. A device whose backfill has not run yet holds `familyId = null` on a row whose
remote document is already stamped, and every upload map writes `familyId ?: ""` — so the pin
turns an ordinary edit into `PERMISSION_DENIED`. Relaxing the pin to allow a blank-out makes it
defeatable in two writes, which is not a pin at all.

There is no version of that guard that is both safe and non-breaking during the skew window, and
nothing needs it yet: **no client reads `familyId`, remotely or locally.** So M-2 ships the field
and nothing else. This stage does not touch `firestore.rules` at all — the six collections
validate with `keys().hasAll(...)`, presence-based, so an added key is accepted by the rules
already live, and there is nothing to deploy.

The switch belongs in M-4, where it is a *replacement* rather than a second path: `sharedWith`
goes, `familyId` takes over, and the pinning question is the single one the rules answer today
instead of two questions layered on each other. Its prerequisites are that every write path
stamps (M-2), and that the documents themselves are backfilled — one admin pass over a pair,
server-side, because a client cannot re-queue a co-parent's own documents: they land in its
outbox and the create rule (`createdByFirebaseUid == auth.uid`) rejects them forever.

### M-3 · The slot and the family kind move onto the family

Two fields on `users/{uid}` are facts about a *relationship* wearing the shape of facts about a
person, and each breaks in its own way the moment there are two relationships.

**`role`** — the parent slot. A man is `"dad"` in both his families, so both his co-parents are
assigned `"mom"`: two pink parents, and `getCustody` cannot tell whose day it is. The slot says
"you are parent 1 or parent 2 *in this pair*", which is what `assignSlots` already computes and
then stores in the wrong place.

**`caresFor`** — whether the app offers child records, pet records or both, as the union of the
two parents' answers. Across two families the union is taken over all of them, so a man with
children by one woman and a dog with another gets child sections in the pet family and pet
sections in the child one. That is the exact case this whole plan was asked for.

`families/{id}` gains both, as maps keyed by uid rather than as a pair of scalars — the same
reason `custody_models` carries `participants` rather than `momUid`/`dadUid`: a map cannot get
out of step with `members`.

```
families/{familyId}
  members:  [uidA, uidB]                       // M-1, admin-only
  slots:    { uidA: "mom", uidB: "dad" }       // admin-only
  caresFor: { uidA: ["CHILDREN"], uidB: [] }   // each parent writes their own key
```

**`slots` is admin-only and `caresFor` is member-writable, and that asymmetry is the whole
security surface of this stage.** A slot decides whose events are whose; a parent who could write
their own would take the co-parent's colour and re-point what `parentOwner` means across the
whole calendar. `caresFor` decides only which sections a family's app draws, and a parent already
has exactly that authority over `users/{uid}.caresFor` today. The rule is two nested `hasOnly`
checks — the write may affect only `caresFor`, and within it only the caller's own key, so one
parent cannot answer for the other and neither can reach `members` or `slots`.

M-1 wrote `families/{id}` on the accept path only, so for every pair already in production the
document does not exist. That makes a prerequisite explicit that was not in M-1:
`backfillFamilyDocuments`, a callable that creates the family for every live pair, carrying the
`role`s and `caresFor`s those two accounts already hold. Its source is `users` and not
`invitations` — the difference from `backfillParentSlots`, which needs to know who *accepted* and
is therefore beyond helping a pair whose invitation was deleted. A family needs no such fact: two
accounts that name each other are one.

**The client writes the new location and still reads the old one — the same order M-2 used, and
for a reason worth stating rather than rediscovering.** `UserRepositoryImpl.updateUser` mirrors
`caresFor` onto the family beside the profile, from the one choke point through which a parent's
answer changes; nothing reads the family's copy yet.

Reading it early would buy nothing and cost something. Until the switcher exists a person has one
family, so a family-scoped slot *is* the profile slot — the read switch changes no behaviour
whatsoever. What it does change is that `ParentsSource` and `FamilyKindSource` grow a Firestore
listener each, in shared flows this project has already had to optimise twice for exactly that
(read `ParentsSource.shared`'s own KDoc). And the switcher needs a "which family am I looking at"
source anyway, so the family document is better read there, once, than bolted onto two sources
now and refactored again later.

**`ParentSlotMigrator` is the sharpest edge, and it cannot move yet either.** Its marker is one
slot per person and `EventDao.reslotOwner` is scoped by `createdByFirebaseUid` alone, so with two
families a slot change in one would re-stamp this parent's events in *both*. `Event.familyId` is
what the query can newly be scoped by — but a row whose backfill has not run carries null, and
scoping on the field today would silently stop re-stamping exactly the rows that need it most.
It moves once M-2's backfill has shipped and run, which is M-4.

### M-4 · The switcher, and pairing with more than one partner

The first stage a user can see, and the one that has to make the promise true: **a co-parent in
one family must have nothing at all to do with a co-parent in another.**

#### What actually leaks, checked rather than assumed

Grepping every read rule against what a second partner would change, the answer is narrower than
the plan assumed, and the narrower answer is the safer one.

**Already isolated, because they are keyed by the *pair* and not by "a partner":** a profile read
(`users/{uid}`), `change_requests` (both uids are on the document), `conversations`/`messages`,
`custody_models`, `family_settings`. Bob passes none of those for Alice's family with Carol.

**`sharedWith` does not have to go.** It is computed by the writer as `[me, creator, partner]`,
and it leaks only if `partnerId` becomes a list and *both* partners land in it. Compute it from
the record's **`familyId` members** instead and it is per-family by construction — Alice's event
in the family she shares with Carol carries `[alice, carol]`, and Bob is not in it. `events`,
`child_info` and `pets` therefore need **no rules change at all**, only a corrected audience.

**`expenses` and `budgets` are the real leak**, and the only one. They carry no per-document
audience: the rule is `isPartnerOf(createdByFirebaseUid)`, which asks "am I a co-parent of the
author" and never "is this record mine to see". Bob is a co-parent of Alice, so Bob reads *every*
expense Alice ever recorded, in every family. These two move onto family membership.

That is the whole rules diff. Keeping `sharedWith` also removes the rollout hazard that made M-2
defer this: nothing has to pin `familyId` against a co-parent's write on the three collections
that have an audience, so an older build that knows nothing about the field cannot blank it and
make a record unreadable.

#### `partnerId` becomes `partnerIds`

A single field cannot hold two co-parents. It becomes an array, and `isPartnerOf` tests
membership. Safe precisely because of the audit above: every rule that still uses it is asking a
pairwise question that both co-parents may legitimately answer yes to — Alice's name and photo
are readable by both of her co-parents, and she may address a change request to either. The one
place where "a co-parent of the author" was standing in for "in the same family" is expenses and
budgets, which is what moves.

The rule reads both shapes for one release (`partnerIds`, falling back to `partnerId`), so a
co-parent on an older build stays readable. `partnerId` keeps being written as the first entry
until M-5.

#### The parent colour stops being derived from the slot

Owner decision, Aug 2026. Today pink and blue come from the slot, which pairing assigns — so with
two families the same person could be pink in one and blue in the other, and the colour would
change under them as they switched. The fix is to stop deriving it: **the colour is a property of
the person, chosen by them.**

Each parent picks their own from a palette, in the onboarding wizard and in Settings. Nothing
stores a gender: a man picking blue and a woman picking pink gets the outcome that was asked for,
two men simply pick differently, and there is no special case in the code and no new personal
data to declare in the privacy policy. `User.colorCode` already exists for this and is finally
used for what it is named.

The slot is untouched and keeps every job it has: `parentOwner`, custody, `momDayIndices`, the
Firestore schema. What changes is only that the *colour* no longer reads it. Two parents who pick
the same colour are the one case the code must handle, and the second picker is the one nudged —
the app does not silently reassign a colour somebody already has.

#### Google Calendar imports become private

Owner decision, Aug 2026, and it dissolves the open question below rather than answering it. An
imported event is marked `isPrivate`, so it never leaves the device and never reaches a co-parent
at all. "Which family does an import belong to" then has no answer to get wrong, and the personal
appointments a parent syncs from their own calendar stop being published to their co-parent —
which is what a personal calendar is.

This is a behaviour change: imports sync today. It wants a line in the release notes and a word
on the import screen.

#### The rest

A persisted per-device choice of family. **The second family is created in Settings** — a pairing
flow reachable while already paired — and by default there is one, so nothing about a
single-family account changes. The switcher is a chip in the top bar of the four top-level screens, shown at two
or more, following the same "appears at two, not at one" rule as the child filter.

`unpairCoParent` removes **one** family rather than "the" pairing, and drops that uid from
`partnerIds` rather than clearing a field.

Badges — unread chat, pending change requests — count **across all families**, or a parent misses
what is happening in the family they are not looking at. Tapping one switches context.

Push gains `familyId` so a notification can deep-link into the right family: four places, per
CLAUDE.md item 15.

#### Order of operations, and the ops steps it depends on

`backfillRecordFamilyIds` stamps `familyId` on the documents of the six collections for every
account that has exactly **one** family — a person with two is skipped rather than guessed at,
which today is nobody, because pairing still refuses a second. It is required before the client
query switch, not merely desirable: `whereEqualTo("familyId", …)` over documents that carry none
returns nothing, and a co-parent's expense history would read as empty.

1. `firebase deploy --only functions`
2. invoke `backfillFamilyDocuments` (M-3) — every live pair gets `members`, `slots`, `caresFor`
3. invoke `backfillRecordFamilyIds` — every record gets its `familyId`
4. `firebase deploy --only firestore:rules,firestore:indexes`

Steps 2 and 3 are idempotent and report what they did; step 4 is what actually turns the
isolation on. Running 4 before 3 leaves a co-parent's expenses unreadable until 3 completes — not
lost, since Room is the source of truth, but visibly missing on the other phone.

And M-3's two deferred reads land here: the switcher's "which family am I looking at" source is
what `ParentsSource` and `FamilyKindSource` resolve slots and `caresFor` through, and
`ParentSlotMigrator` gains its `familyId` scope now that the backfill has run.

### M-5 · Cleanup

Delete `partnerId`, `User.role`, `Event.sharedWith`, `isPartnerOf`.

## Two questions this plan does not answer

**Google Calendar.** One Google account, several families. Which family do imported events land
in? The honest answer is a per-calendar mapping, but that is a screen of its own; the cheap answer
is "the selected family at import time", which is a footgun the first time somebody imports while
looking at the wrong family. `CalendarSyncRepository` stamps the importing parent's only family
today and says so at the call site; that line is where the answer goes.

**Calendar friends.** `calendar_friends/{friendUid}` is central today and is checked against an
event's creator. It has to become per family, or a grandmother admitted by one household would
read the other household's calendar.

## Why now

Nothing is published yet — REL-2 through REL-6 are open, there is no signing config and no
Play listing. **There are no installed clients whose data would have to be migrated blind.** Every
week this waits, the same change gets more expensive, and after the first release it stops being a
refactor and becomes a data migration with users attached.
