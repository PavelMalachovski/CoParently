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

`ParentsSource` becomes family-scoped. `assignSlots` assigns within a family, and the collision
described above stops being expressible. `ParentSlotMigrator` retargets.

### M-4 · The switcher, and pairing with more than one partner

A persisted per-device choice of family; a chip in the top bar of the four top-level screens,
shown at two or more. Pairing can be started again while already paired. `unpairCoParent` removes
**one** family rather than "the" pairing.

Badges — unread chat, pending change requests — count **across all families**, or a parent misses
what is happening in the family they are not looking at. Tapping one switches context.

Push gains `familyId` so a notification can deep-link into the right family: four places, per
CLAUDE.md item 15.

This is also where the read side moves onto `familyId`, for the reason M-2 gives above. Three
pieces, in order: a Cloud Function backfills `familyId` onto the documents of every existing
pair; `firestore.rules` replaces the `sharedWith` / `isPartnerOf` read gates with family
membership, pinning `familyId` the way `sharedWith` is pinned today; and the client queries
become `whereEqualTo("familyId", …)`. Until the first of those has run, a rule keyed on
`familyId` denies every document written before the field existed.

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
