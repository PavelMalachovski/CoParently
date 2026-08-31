# What a CoPlanly export may honestly claim — MON-4

**A decision paper, not a plan.** Three questions here are the owner's, and MON-3 (export to
PDF/CSV, the first paid feature) cannot be built until they are answered. Each section states what
the code does today, lays out the options, and recommends one. The recommendations are arguable;
the facts about the code are checked and cited.

Companion to `docs/ROADMAP.md` MON-4 and `docs/LAUNCH-PLAYBOOK.md` §4.4.

---

## 1. Why this blocks the export rather than following it

Willingness to pay in this category concentrates on documentation you can hand to a lawyer. That is
also the most dangerous thing to sell, because **an export is only worth what the record behind it
is worth**, and the first opposing counsel who reads one closely decides that in public.

The failure mode is specific and cheap to walk into: a parent exports a PDF, hands it to their
lawyer, and the other side points out that the app lets its own users rewrite the entries with no
trace. The document is then worse than nothing — it has been introduced as evidence and discredited,
and the app's name is attached to that.

So the question to settle first is not "what can we export" but **"what can we truthfully say the
export is"**.

---

## 2. What the code guarantees today

Checked against `firestore.rules`, `functions/index.js` and the repositories on `main` at
`494301a`. This is the real starting position, and it is stronger in one place and weaker in
another than the roadmap's one-line summary suggests.

| Record | Can it be changed after the fact? | Ordered by | Notes |
| --- | --- | --- | --- |
| **Chat messages** | **No, and this is enforced server-side.** `allow delete: if false`; update is two disjoint `hasOnly` branches — `isRead` alone, or a constrained `conversationId` re-point. Content, sender, timestamp and attachments cannot be touched by anybody. | `sentAtMillis`, **epoch millis** (schema 13) | `MessageRepositoryImpl.deleteMessage` is Room-only and says so in a comment. A message can vanish from one phone; it cannot vanish from the record. |
| **Activity announcements** | **No** — they *are* chat messages, in the same collection, under the same rules | epoch millis | `ActivityAnnouncement` carries facts, not a sentence, so the reader's device renders it in their own language |
| **Custody schedule** | Yes, by either parent | `lastModifiedAtMillis`, **epoch millis** (SEC-4, schema 29) | Ordering was a naive local date-time until SEC-4, and the winner is *re-pushed over* the loser, so the wrong schedule could win and overwrite |
| **Expense split ratio** | The agreement can be renegotiated; **the price of a recorded expense cannot** | — | `Expense.splitBasisPoints` snapshots the ratio in force when the expense was recorded, deliberately, so renegotiating cannot re-price a settled month |
| **Change requests** | Status changes; the *before* state does not | `createdAt` / `respondedAt`, naive `LocalDateTime` | `currentStartDateTime`/`currentEndDateTime` capture the event as it stood when the request was made — a genuine before-image, already stored |
| **Events** | **Yes, freely, by the creator, with no history** | `updatedAt`, **naive `LocalDateTime`** | `createdByFirebaseUid` is immutable and a partner editor cannot change `sharedWith`/`permissions`, but the content is unpinned |
| **Expenses, budgets, child and pet records** | Same as events | naive `LocalDateTime` | |
| **Deletions** | Tombstoned, not removed: `deletedAtMillis` + `deletedBy` | epoch millis | CQ-3. A deletion is itself a dated, attributed record for 90 days |

**Two things worth pulling out of that table.**

**The chat is already the strongest record in the app**, and nobody has been treating it as one.
Neither parent can edit or delete a message server-side; the timestamps are real instants, not local
wall clocks; and the activity feed — every announced change to the calendar, the schedule and the
expenses — flows through the same immutable collection. TalkingParents charges $32/month for a tier
whose headline is "Unalterable Records". CoPlanly has had them since the August 2026 chat work and
has never said so.

**Events are the weak half**, and they are the half a custody argument is actually about. A parent
can move an event and nothing records that it moved, who moved it, or what it said before.

---

## 3. Decision 1 — what the export claims to be

This is the decision the other two follow from.

| Option | The claim | What it needs | The risk |
| --- | --- | --- | --- |
| **A. A truth record** | "This is what happened" | Append-only everything, server-authored timestamps, edits as new versions, probably a signed hash chain | The app cannot make this claim honestly for anything a user types on their own phone, ever. Offline-first means the device is the source of truth for a while, and a determined user controls the device. Selling this is selling something the architecture cannot deliver |
| **B. A communication record** ⭐ | "This is what was said between the two of you, and what each of you was told had changed, and when" | Almost entirely already true (§2) | Narrower than what a parent hopes to buy. Has to be said plainly in the product, or the parent discovers the limit in front of a judge |
| **C. Nothing — no export** | — | — | Leaves the category's most-paid-for feature unbuilt, and MON-3 with it |

**Recommendation: B.**

Not as a compromise but because it is the only one that is *true*, and because it is what the
competitors who charge most actually sell. "Unalterable Records" at TalkingParents, OFW's court
packet — these are records of the **exchange**, not audits of reality. Nobody claims to know
whether a handover happened; they claim to know exactly what each parent said about it and when.

B also has a property A does not: it is honest about the adversary. The thing a court cares about
is whether *the other parent* can alter the record. A cannot protect against the exporting parent
and pretends to; B protects against exactly the party it should and says so.

**What B lets the product say**, and these sentences should appear verbatim in the export's own
header rather than only in marketing:

> This document lists messages exchanged in CoPlanly and the changes each parent was notified of.
> Messages cannot be edited or deleted by either parent once sent. Times are recorded as absolute
> instants and shown in *(the reader's zone)*.
>
> Calendar entries can be edited by the parent who created them. Where an entry was changed, this
> document records the notification the other parent received, not the entry's full history.

That second paragraph is the honest limit. It is also the sentence that makes decision 2 worth
money: shrink it and the export gets more valuable.

---

## 4. Decision 2 — which records become append-only

Given B, the question narrows usefully: **what has to become append-only for the second paragraph
above to get shorter?**

| Record | Recommendation | Why |
| --- | --- | --- |
| **Chat + announcements** | Already append-only. **Pin it in the rules tests** and treat any change as a breaking one | The guarantee exists but nothing asserts it; a future rule edit could quietly widen `hasOnly(['isRead'])` and no test would fail |
| **Events** | **Add an append-only edit trail — not full versioning** | See below |
| **Custody schedule** | Already announced through the activity feed; leave the document mutable | Two parents negotiating a schedule need to edit it. What matters is that each change was announced, and it is |
| **Expenses** | Leave mutable; the split snapshot already covers the part that is money | A corrected amount is a correction, not a falsification |
| **Child medical profile** | **Leave mutable and out of the export entirely** | Special-category data under GDPR Art. 9. An export that carries a child's medication into a court filing is a harm the product should not make easy |

### The events recommendation, concretely

Not versioning — an **edit trail**: one append-only record per edit, carrying what changed, who
changed it, and when, without storing a full copy of the old event.

```
event_edits/{editId}
  eventId          the event this is about
  familyId         so the read rule can be family-scoped, like everything since M-2
  editedBy         uid, server-verified: request.auth.uid, unlike lastModifiedBy today
  editedAtMillis   epoch millis, server-stamped
  changed          { title?: {from, to}, start?: {from, to}, end?: {from, to}, … }
```

Rules: `allow create` when the author is the caller and may edit the event; **`allow update, delete:
if false`.** That is the whole guarantee, and it is one rule block.

Three reasons this shape rather than full event versions:

- **It is cheap.** One small document per edit, written on the path that already writes the event.
  Full versioning duplicates every event forever and makes the 90-day tombstone sweep meaningless.
- **It answers the question a court asks**, which is "was this moved, by whom, after what" — not
  "reconstruct the calendar as of last March".
- **It does not need the old event to be readable.** A `{from, to}` pair is the evidence; the
  historical row is not.

**What it deliberately does not do:** prevent the edit. A parent may still move their own event.
The trail records it, the co-parent is already notified through the activity feed, and the export
can then say *"changed twice, both times by Alice, on these dates"* — which is the sentence that
was missing.

---

## 5. Decision 3 — whose clock orders writes

Today: `sentAtMillis`, `lastModifiedAtMillis` and the tombstones are epoch millis; `updatedAt`,
`createdAt` and `respondedAt` are naive `LocalDateTime` with no zone.

| Option | Assessment |
| --- | --- |
| **Device clock, local wall time** (today, for events) | Unusable in an export. Two parents in two zones produce times that cannot be ordered, and a device clock can simply be wrong |
| **Device clock, epoch millis** ⭐ for existing fields | What SEC-4 already did for custody and chat. Orders correctly across zones; still trusts the device |
| **Server timestamp** (`FieldValue.serverTimestamp()`) for the edit trail | The right answer for a record whose whole purpose is to be evidence — but it cannot be the only timestamp, because the app is offline-first and a queued write must still record when the *parent* acted |

**Recommendation: both, on the new record, and say which is which.**

`editedAtMillis` is when the parent acted, from their device. A second field — `recordedAt`,
server-stamped on arrival — is when the system saw it. An export shows the first and can cite the
second where they disagree by more than a plausible offline window. That is more honest than either
alone, and it is the shape `CustodyTimestamp.kt` already argues for in a narrower case.

For the naive fields on existing records: **migrate `Event.updatedAt` to epoch millis** the way
SEC-4 migrated custody, and read `domain/custody/CustodyTimestamp.kt` before touching the wire
form — it explains why the Firestore field kept both its name *and* its ISO-string type and only
changed the zone it expresses. The same reasoning applies here, and the same trap: a co-parent on
an older build must keep reading the field.

`ChangeRequest.createdAt`/`respondedAt` can stay naive for now. They are displayed, not compared.

---

## 6. What an edit does to history

Falls out of §4: **nothing is rewritten and nothing is hidden.** The event changes in place, a new
immutable `event_edits` row appears beside it, and the co-parent's notification is already an
immutable chat message. There is no "history" to corrupt because there is no historical copy —
only a growing list of facts about changes.

One rule worth stating before somebody adds it: **an edit trail entry is never deleted with its
event.** When an event is tombstoned, its trail survives; when the 90-day sweep removes the
tombstone, the trail still survives. A deletion is the most interesting edit of all, and a trail
that vanished with the thing it describes would be missing exactly the entry a dispute is about.
Account deletion is the one exception — `deleteAccountDataImpl` must delete the trail rows the
departing parent authored, or erasure is incomplete. (That is a fourth collection for
`AUTHORED_COLLECTIONS`; `web/README.md` already warns that this list is what drifts.)

---

## 7. What this costs

| Piece | Size | Notes |
| --- | --- | --- |
| `event_edits` collection, rules block, rules tests | S | The rule is four lines; the tests are the point |
| Writing the trail on the event update path | S | One place — `EventRepositoryImpl` |
| `Event.updatedAt` → epoch millis, with migration | M | Schema bump; follow SEC-4's wire-form reasoning exactly |
| Pinning chat immutability in `firestore-tests/` | S | Asserts a guarantee that already holds |
| Excluding the medical profile from anything exportable | S | A decision expressed as a filter |
| **Then** MON-3 itself | M | Unblocked |

Everything above is cloud work. None of it needs a device.

---

## 8. What not to promise, at any price

- **Not "tamper-proof".** The exporting parent controls their device and their own entries. The
  claim is that the *other* parent cannot alter the record, and that messages cannot be altered by
  anybody.
- **Not a legal opinion on admissibility.** That varies by court and is not the app's to give.
  Say what the record is; let a lawyer say what it proves.
- **Not the child's medical data in an exportable document.** §4.
- **Not a signed or notarised artefact** unless someone has actually built the signing. OFW posts a
  physical court packet; that is a service, not a PDF button, and claiming its weight without its
  work is the single fastest way to lose the credibility this whole feature is for.

---

## 9. The three answers, in one place

Fill these in and MON-3 is unblocked:

1. **The export is a ☐ truth record / ☑ communication record** *(recommended: communication)*
2. **Append-only:** ☑ chat *(already)*, ☑ a new event edit trail, ☐ full event versioning
   *(recommended: trail, not versions)*
3. **Clock:** ☑ epoch millis on every compared field, ☑ a server-stamped `recordedAt` beside the
   device time on the trail *(recommended: both, labelled)*
