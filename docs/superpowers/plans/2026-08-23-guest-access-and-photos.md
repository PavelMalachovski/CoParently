# Guest access and medical photographs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a parent attach photographs to a medical note, and let them hand a grandparent read-only access to the child's details for a weekend.

**Architecture:** Two independent halves. **G1** adds a list of Storage URLs to the child's record. **G2** adds a third read-only role to a system built for exactly two people — a uid in the record's `sharedWith` plus a `guests` map, with the update rule tightened so audience membership no longer implies write.

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Room, Firebase Firestore + Storage + Cloud Functions, JUnit 4 + MockK, `@firebase/rules-unit-testing`, mocha.

**Spec:** `docs/superpowers/specs/2026-08-23-guest-access-and-photos-design.md`

## Ship these as two branches

**Tasks 1–4 are G1 and merge on their own.** Tasks 5–11 are G2. Do not combine them: G1 is a list field and a Storage path; G2 changes a rule that governs live medical data. One risk profile should not carry the other through review.

**Depends on PR #49 (B1)** for the medical data photographs attach to.

## Global Constraints

- **Jetpack Compose only.** Stateless composables; state in ViewModels as `StateFlow`.
- **The Firestore document schema for a child is defined in `ChildInfoRepositoryImpl`, and `SyncService` keeps its own separate maps.** A field added to one and not the other is deleted on every sync — package B1 shipped exactly that defect, twice over.
- **Never debug `firestore.rules` or `storage.rules` by deploying to production.** `firestore-tests/` runs offline; a broken `expenses` delete rule shipped that way once. Needs a JDK 21+ on `PATH`.
- **A Firestore list query needs a `where` filter matching whatever field the rule keys its `allow read` on.** An unfiltered collection query is rejected outright.
- **Receipt OCR is on-device only.** No photograph — receipt or medical — may be sent to Gemini or any remote service without an explicit product decision.
- **Never hardcode user-visible text.** Every new key in all five locales in the same commit.
- **Room schema changes:** entity → version bump → migration registered via `ALL_MIGRATIONS`; exported schemas in `app/schemas/`.
- KDoc on every public class and function; code and comments in **English**.
- detekt `MaxLineLength` **120**; `TooGenericExceptionCaught` active and lists `Exception`; nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26. Conventional Commits.
- `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew …`

---

# G1 — photographs

### Task 1: Several photographs per record

**Files:**
- Modify: `domain/model/ChildInfo.kt`, `data/local/entity/ChildInfoEntity.kt`
- Modify: `CoPlanlyDatabase.kt`, `DatabaseMigrations.kt`
- Modify: `data/repository/ChildInfoRepositoryImpl.kt`, `data/sync/SyncService.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/ChildInfoPhotosMappingTest.kt`, plus the instrumented migration test

`ChildInfo` gains `medicalPhotos: List<String>` — download URLs in the order added, stored as JSON like every other list on that record.

**Six map sites, and missing one is silent:** `toDomain`, `toEntity`, `toFirestoreMap`, `toChildInfo` in the repository, **and both of `SyncService`'s own maps** — its upload map and `toChildInfoEntity`. Grep `SyncService` for the field name before committing. B1's Critical defect was exactly this, and its `.set()` upload means a missing field is not merely absent but **deleted**.

**Resolve the schema version by reading `CoPlanlyDatabase`.**

- [ ] **Step 1: Write the failing test** — the list survives all six map sites; a document written before this change reads as an empty list, not null.
- [ ] **Step 2: Run it; expect failure.**
- [ ] **Step 3: Add the field, the column, the migration; carry it through all six sites.** Report which you found.
- [ ] **Step 4: Add a migration test case; run it on the device.**
- [ ] **Step 5: Commit** — `feat(childinfo): carry several medical photographs, not one`

---

### Task 2: The Storage path and its rules

**Files:**
- Modify: `data/remote/firebase/FirebaseImageStorage.kt` and its interface
- Modify: `storage.rules`

Path `medical_photos/{childInfoId}/{photoId}.jpg`, `photoId` a UUID. The existing helpers derive one fixed file from an entity id and cannot express several — add a method that takes the photo id rather than bending them.

**The read rule deserves more thought than the ones beside it.** `receipts` and `event_images` allow any authenticated user to read any file. For a photograph of a child's medical record, "any signed-in CoPlanly user" is not an audience it should have. Storage rules cannot read Firestore, so the honest options are an unguessable path plus the URL's token, or a Cloud Function proxy.

**Take the first, and write it down as obscurity rather than access control** — in the rule's own comment, in the same voice the existing blocks use. A future reader must not mistake it for a boundary.

Keep the 5 MB and `image/jpeg` constraints the neighbouring blocks impose.

- [ ] **Step 1: Add the upload and delete methods**, with the photo id in the path.
- [ ] **Step 2: Write the `storage.rules` block**, with the comment above.
- [ ] **Step 3: Extend the rules tests** for the new path if the suite covers Storage; if it covers only Firestore, say so in your report rather than claiming coverage that does not exist.
- [ ] **Step 4: `assembleDebug`; commit** — `feat(storage): a path per medical photograph`

---

### Task 3: Attaching and removing

**Files:**
- Modify: `presentation/childinfo/AddEditChildInfoScreen.kt`, `ChildInfoViewModel.kt`
- Modify: `presentation/childinfo/ChildInfoScreen.kt`
- Create: `res/values*/medical_photos_strings.xml`

**Deletion removes the file first, then the reference.** Dropping a URL from the list leaves the image in the bucket, readable by anyone holding the link. Delete the object, then update the record — and if the delete fails, do not update.

The picker follows `AddExpenseScreen`'s receipt flow, which already handles permissions and the gallery. Do not invent a second image-picking idiom.

**Nothing here may send a photograph anywhere but Firebase Storage.** The on-device-only rule that governs receipts governs these too.

- [ ] **Step 1: Write the strings in all five locales.**
- [ ] **Step 2: Read `AddExpenseScreen`'s receipt flow and follow it.**
- [ ] **Step 3: Add the thumbnail strip, add and remove.**
- [ ] **Step 4: Show them on `ChildInfoScreen`'s medical group**, tapping to view full-screen.
- [ ] **Step 5: Verify locales; confirm no hardcoded text; build; commit** — `feat(childinfo): photograph what the doctor said`

---

### Task 4: G1 verification, and merge

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt`; the instrumented migration test; locale grep.
- [ ] **Device:** attach three photographs to one note; confirm all three survive a sync and reach the co-parent's phone; delete one and confirm the object is gone from the bucket, not merely from the list.
- [ ] Record the run in spec §3, commit, and **open G1's pull request here.** G2 starts from a fresh branch.

---

# G2 — guest access

**Read spec §4 before starting.** Every access decision in this app assumes exactly two people: `isPartnerOf` reads a single `partnerId`; parent identity is two slots; `unpairCoParent` revokes one specific uid. A third person with fewer rights is a second concept, not a variation.

### Task 5: The rule that must change first

**Files:** `firestore.rules`, `firestore-tests/rules/child-info.test.js`

**This is the task the whole package rests on, and it comes first for that reason.** Today:

```
allow update: if isAuthenticated() &&
                request.auth.uid in resource.data.sharedWith && …
```

Adding a guest to `sharedWith` under that rule makes them an **editor of the child's medical record**. The rule must require the writer to be the creator or the creator's partner — never merely a member of the audience.

This governs live data. Write the tests first, and every pre-existing case in the file must still pass afterwards.

- [ ] **Step 1: Add the failing cases** — a uid in `sharedWith` who is neither creator nor the creator's partner may **not** update; the creator may; the creator's partner may.
- [ ] **Step 2: Run `cd firestore-tests && npm test`; expect the first to fail.**
- [ ] **Step 3: Tighten the rule.**
- [ ] **Step 4: Run again — the new cases pass and every old one still does.** If an old case now fails, stop: the audience model differs from what §5 assumed and the plan needs revisiting, not the test.
- [ ] **Step 5: Commit** — `fix(rules): stop audience membership implying write on a child record`

---

### Task 6: `GuestGrant` and expiry

**Files:**
- Create: `domain/guests/GuestGrant.kt`, `GuestGrantPolicy.kt`
- Test: `app/src/test/java/com/coparently/app/domain/guests/GuestGrantPolicyTest.kt`

`GuestGrant(uid, name, grantedBy, grantedAtIso, expiresAtIso)`; the policy answers whether a grant is active at an instant, and produces the default expiry.

**Expiry is mandatory, not optional** — spec §5. Item 15's example is a weekend with a grandparent, and a permanent grant is the wrong default for an access token handed out casually.

**Compare instants, not wall clocks.** CLAUDE.md records that the custody document's naive local `lastModifiedAt` can pick the wrong winner across time zones, and that the fix is epoch millis. Do not repeat that here: an expiry decides whether someone can read a child's medical record, and it must not depend on which zone the reader is in. Use an ISO instant with an offset, or epoch millis, and say which in your report.

- [ ] **Step 1: Write the failing test** — active before expiry, inactive after, inactive exactly at it; a grant whose expiry will not parse is treated as **inactive** (fail closed, never open); two readers in different zones agree.
- [ ] **Step 2: Run it; expect a compile failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run it; expect all PASS.**
- [ ] **Step 5: Commit** — `feat(guests): a grant that ends by itself`

---

### Task 7: Storage, sync and the rules for `guests`

**Files:**
- Modify: `domain/model/ChildInfo.kt`, the entity, the migration, both repository and both `SyncService` maps
- Modify: `firestore.rules`, `firestore-tests/rules/child-info.test.js`

The `guests` map lives on the child record beside `sharedWith`. Same six map sites as Task 1 — check every one.

Rules to add, each with its own test:

- a participant **parent** may add a guest;
- a **guest** may not add another guest;
- a non-participant may add neither;
- a guest may read the record;
- an **expired** guest may not.

- [ ] **Step 1: Add the cases; run; expect failures.**
- [ ] **Step 2: Add the field and carry it through all six map sites.**
- [ ] **Step 3: Write the rules.**
- [ ] **Step 4: Run everything; commit** — `feat(guests): carry and gate a guest on the child record`

---

### Task 8: Inviting and accepting

**Files:**
- Modify: `functions/index.js`, `functions/test/`
- Modify: `firestore.rules` (`invitations`)
- Modify: `presentation/pairing/` or a new guest-invite screen

Reuse the `invitations` collection with a `kind` field. **A guest accept must not run `assignSlots` and must not write `partnerId`** — the pairing callable does both, and a guest going through it becomes a co-parent.

**Write a second callable rather than branching inside the existing one.** A branch inside a function that assigns custody slots is a mistake waiting for a tired evening; two functions cannot be confused by accident.

- [ ] **Step 1: Write the functions test first** — the guest-accept callable writes **no** `partnerId` and **no** slot, and adds the uid to exactly the one child record it was invited to.
- [ ] **Step 2: `cd functions && npm test`; expect failure.**
- [ ] **Step 3: Implement the callable; extend the `invitations` rules for `kind`.**
- [ ] **Step 4: Build the invite UI**, reusing the existing link/code/QR flow. Strings in all five locales.
- [ ] **Step 5: Run `npm test && npm run lint` in `functions/`, and the rules suite; commit** — `feat(guests): invite a grandparent without making them a parent`

---

### Task 9: Seeing, and revoking

**Files:**
- Modify: `presentation/childinfo/ChildInfoScreen.kt`
- Create: the guest list section and its strings

Guests are listed on the child's screen with their name and when access ends. Revoking removes them from both the map and `sharedWith`, immediately.

**Revocation follows the sign-out anatomy** the design refresh established: a red `SectionRow` that confirms, never a filled error button.

- [ ] **Step 1: Strings in all five locales.**
- [ ] **Step 2: The list and the revoke action.**
- [ ] **Step 3: Verify locales; build; commit** — `feat(guests): show who has access, and take it back`

---

### Task 10: The expiry sweep

**Files:** `functions/index.js`, `functions/test/`

A rule comparing `expiresAt` to `request.time` is not enough on its own: the uid stays in `sharedWith` forever, so the guest keeps appearing in the audience and any list query keeps returning the document to them.

A scheduled function removes expired guests from both the map and `sharedWith`. `cleanupOldNotifications` is the pattern to follow.

- [ ] **Step 1: Write the test** — an expired grant is removed from both places; an active one is untouched; a record with no guests is skipped.
- [ ] **Step 2: Implement; run `npm test && npm run lint`.**
- [ ] **Step 3: Commit** — `feat(guests): sweep grants that have ended`

---

### Task 11: G2 verification

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt`; `cd firestore-tests && npm test`; `cd functions && npm test && npm run lint`; the instrumented migration test; locale grep.
- [ ] **Three accounts, two phones.** Invite a guest from A; accept on the third account; confirm they see the child's record and **nothing else** — no events, no expenses, no chat, no custody, neither parent's profile. Confirm co-parent B sees the guest listed. Revoke from A and confirm access is gone on the next read. Then set a grant to expire in the past and confirm the sweep removes it.
- [ ] Record the run in spec §7 and commit.

---

## Notes for the reviewer

**Task 5 is the one to read first.** Everything else in G2 is additive; that task changes a rule already governing live medical data, and the failure mode if it is wrong is a grandparent able to edit a child's medical record.

**The luck in this package,** spec §6: `expenses` and `budgets` are gated on the live `isPartnerOf` relationship rather than a stored list, `events` on their own `sharedWith`, and chat on conversation participants — so a guest is excluded from all of them by construction. G2 adds a role without widening anything it did not intend to. Verify that rather than assuming it: the emulator cases in Task 11 are how.

**The Storage read rule is obscurity, not access control** — spec §2 — and Task 2 requires that to be written in the rule's own comment. A future reader must not mistake it for a boundary.

**What this package does not do:** let a guest see the calendar; let a guest write anything; encrypt photographs; add a second guest tier; or touch the two-slot parent model.
