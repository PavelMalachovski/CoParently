# A third pair of hands, and photographs of what the doctor said — design

**Date:** 23 August 2026
**Package:** **G** of the nineteen-item improvement list
**Base:** `main` @ `14e00cbe`
**Depends on:** package **B1** (PR #49) for the medical data the photographs attach to.

Two items that arrived together and should not be built together:

- **Item 17.** Photographs in medical notes. Small, self-contained, useful on its own.
- **Item 15.** Invite a "friend" — a grandparent, say — so that whoever is with the child can see the child's details.

**This spec splits them.** They share nothing but a sentence in the original list.

| | Scope | Ships |
|---|---|---|
| **G1 — photographs** | One Storage path, a list of URLs on the child's record, an upload flow | Independently, first |
| **G2 — guest access** | A third role in a system built for exactly two people | After, on its own |

---

## 0. Decisions taken without the owner present

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | Are 15 and 17 one package? | **No.** Two, shipped separately. | Would merge two very different risk profiles. |
| 2 | Can a guest write anything? | **No. Read-only, and the rules must enforce it** — not the UI. | Moderate. |
| 3 | Does a guest grant expire? | **Yes, and the expiry is mandatory** — 30 days by default, chosen at invite time. | Moderate; see §5. |
| 4 | What can a guest see? | **The child's record only.** Not events, expenses, chat, custody or either parent's profile. | Small to widen, and each widening needs its own rule. |
| 5 | How many photographs per medical note? | **Several.** The existing storage helper does one fixed file per entity and cannot express it. | Small. |
| 6 | Are photographs encrypted? | **No** — nothing in this app is, and a false promise about medical images is worse than none. | Large. |

---

# G1 — Photographs in medical notes

## 1. What exists

`FirebaseImageStorage` uploads to `receipts/{expenseId}.jpg` and `event_images/{eventId}.jpg`, with matching `storage.rules` blocks limiting uploads to JPEG under 5 MB. Everything else in the bucket is closed by default.

**The existing shape cannot express item 17.** Both helpers derive a single fixed path from an entity id, so an entity has at most one image. A parent photographing a prescription, a rash and a vaccination card has three. The path needs a per-photo identifier, and the child's record needs a list rather than a single URL.

## 2. The change

`ChildInfo` gains `medicalPhotos: List<String>` — download URLs, in the order they were added. Stored as JSON on the entity like every other list on that record, and carried in the Firestore document, which means **all four of `ChildInfoRepositoryImpl`'s mappers and both of `SyncService`'s own maps** must carry it. Package B1 shipped a data-destroying defect from missing exactly that: a field added to one map and not the other is deleted on every sync.

Storage path `medical_photos/{childInfoId}/{photoId}.jpg`, with a matching `storage.rules` block.

**The read rule must be tighter than the ones beside it.** `receipts` and `event_images` allow any authenticated user to read any file — the download URLs carry an access token, so this was judged acceptable for a receipt. A photograph of a child's medical record is a different thing, and "any signed-in CoPlanly user" is not an audience it should have. Storage rules cannot read Firestore, so the honest options are a path that is hard to guess plus a token, or a Cloud Function proxy. **This spec takes the first and says so plainly**: the photo id is a UUID, the URL carries a token, and the block is written to match — but this is obscurity, not access control, and it is recorded here as a limitation rather than dressed up.

**Deletion must remove the file, not just the reference.** Dropping a URL from the list leaves the image in the bucket, readable by anyone who kept the link. The remove path deletes the object first and only then updates the record.

## 3. G1's verification

| Check | How |
|---|---|
| The list survives | JVM: `medicalPhotos` round-trips through all four repository mappers **and** both `SyncService` maps. |
| Deletion | JVM: removing a photo calls the storage delete before the record update, and a failed delete does not orphan the reference. |
| Storage rules | The emulator suite, extended for the new path. |
| Build | `assembleDebug testDebugUnitTest lint detekt` |

Device: attach three photographs to one medical note, confirm all three survive a sync and appear on the co-parent's phone; delete one and confirm the file is gone from the bucket, not merely from the list.

---

# G2 — Guest access

## 4. Why this is the expensive one

Every access decision in this app is built on there being exactly **two** people:

- `firestore.rules` gates on `isPartnerOf(uid)`, which reads a single `partnerId` from a user document.
- `users/{uid}.partnerId` holds one value.
- Parent identity is two slots, `"mom"` and `"dad"`, assigned at pairing and never chosen.
- `unpairCoParent` sweeps `SHARED_AUDIENCE_COLLECTIONS` to revoke one specific uid.
- `expenses` and `budgets` are gated on the live `isPartnerOf` relationship rather than a stored list, so clearing `partnerId` revokes them implicitly.

A third person with fewer rights is not a variation on that; it is a second concept. The work is mostly in the rules and the invitation flow, not the UI.

## 5. The design

**A guest is a uid in the child record's `sharedWith`, plus an entry in a new `guests` map on the same document:**

```
child_info/{id}
  … existing fields …
  sharedWith: [parentA, parentB, guestUid]
  guests: {
    <guestUid>: { grantedBy, grantedAt, expiresAt, name }
  }
```

Being in `sharedWith` makes the existing **read** rule work unchanged. The `guests` map is what makes them a guest rather than a parent.

**The update rule must change, and this is the load-bearing part.** Today it is:

```
allow update: if request.auth.uid in resource.data.sharedWith && …
```

Adding a guest to `sharedWith` under that rule would make them an **editor of the child's medical record**. The rule must instead require the writer to be the creator or the creator's partner — never merely a member of the audience.

That is a change to a rule that already governs live data, so it must be written test-first in `firestore-tests/`, and the existing cases must all still pass. CLAUDE.md is explicit that rules are never debugged by deploying and watching a phone; a broken `expenses` delete rule shipped that way once.

**The grant expires, and the expiry is mandatory.** Item 15's example is *«когда ребенок будет с бабушкой»* — a weekend, not a lifetime. A permanent grant is the wrong default for an access token handed out casually, and a separated parent will not remember to revoke it. Default 30 days, chosen at invite time.

**Expiry is enforced by a scheduled sweep, not by a rule.** Firestore rules can compare `expiresAt` to `request.time`, but a rule alone leaves the uid in `sharedWith` forever, which means the guest keeps appearing in the audience and any list query keeps returning the document to them. `cleanupOldNotifications` already establishes the scheduled-function pattern in this project; the sweep removes expired guests from both the map and `sharedWith`.

**A guest is invited through the existing `invitations` collection with a `kind` field.** Accepting a guest invitation must **not** run `assignSlots` or write `partnerId` — the pairing callable does both, and a guest going through it would become a co-parent. Either a second callable or a clearly separated branch; the plan takes the second callable, because a branch inside a function that assigns custody slots is a mistake waiting for a tired evening.

**Revocation is explicit and immediate**, from the child's screen. Removing a guest removes them from both the map and `sharedWith`, and `revokeSharedAudience`'s existing machinery is the model — though not the same function, which is about ending a pairing.

## 6. What a guest can and cannot see

| | Guest |
|---|---|
| The child's record — name, allergies, medical profile, emergency contacts, school | **read** |
| Medical photographs (G1) | **read** |
| Events, custody, chat, expenses, budgets, either parent's profile | **no** |
| Editing anything at all | **no** |

`expenses` and `budgets` need no change: they are gated on `isPartnerOf`, which a guest never satisfies. `events` are gated on their own `sharedWith`, which a guest is never added to. Chat is gated on conversation participants. **This is the one piece of luck in the package** — the existing gates exclude a guest by construction, so G2 adds a role without widening anything it did not intend to.

## 7. G2's verification

The emulator suite is the deliverable here, not a supporting artefact:

- a guest may read the child record;
- a guest may **not** update it — including after being added to `sharedWith`;
- a parent may still update it, and every pre-existing case still passes;
- a guest may not read events, expenses, budgets, custody or either parent's profile;
- an expired guest may not read the child record;
- a guest may not add another guest;
- a non-participant may not add a guest.

Plus: JVM tests for the expiry predicate and the sweep's selection; a functions test for the guest-accept callable proving it writes **no** `partnerId` and **no** slot.

Device, two phones and a third account: invite a guest from A, accept on the guest's phone, confirm they see the child and nothing else, confirm the co-parent B sees the guest listed, revoke from A, and confirm access is gone on the next read.

## 8. Deliberately not in G

- **A guest seeing the calendar.** Item 15 says access to information about the child. A guest who can see the custody schedule can see both parents' movements, which is a different and larger consent.
- **A guest writing anything**, including a note that the child ate lunch. Write access is a much larger surface and was not asked for.
- **Encryption of medical photographs** — §0 item 6.
- **More than one guest tier.** One read-only role. A second tier should wait for a real need.
- **Reworking the two-slot parent model.** Guests sit beside it and never occupy a slot.
