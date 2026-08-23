# SDD ledger — plan: docs/superpowers/plans/2026-08-23-guest-access-and-photos.md

**Two sections, one per branch.** The plan is explicit that G1 and G2 ship separately — G1 is a
list field and a Storage path, G2 changes a rule that governs live medical data, and one risk
profile should not carry the other through review. G1 is merged; **G2 is the section at the
bottom of this file**.

Branch: `claude/package-g1-medical-photos`.
Base: `main` @ `3122436`.
Tasks: 4 of the plan's 11. Tasks 1–3 implemented; Task 4 partly run.

## What was and was not verified here

Same environment as packages C, D, E and F: **no Android SDK, and no route to Google's Maven
host** (`dl.google.com`, and `maven.google.com` by redirect, are refused by the proxy with 403).
So `assembleDebug`, `testDebugUnitTest`, `lint` and `detekt` were **not** run, and no Compose file
in this package has been through a compiler.

Really run:

- **The Firestore rules suite, on the emulator** — **237 passing**, before and after. No Firestore
  rule needed changing: `child_info` validates keys by presence (`keys().hasAll([…])`), so a new
  `medicalPhotos` key is accepted as-is.
- **Every pure-Kotlin test that compiles without the Android classpath** — 33 classes against 78
  pure main sources under a standalone `kotlinc` 2.1: **298 passing**, up from 293 on `main`.
  G1's own is `ChildInfoPhotosTest` (5).
- **Locale completeness**, by grep: all 10 new keys in exactly five files each, and every one of
  them referenced from code — no key added and left unused.
- **`git diff main..HEAD -- app/build.gradle.kts` is empty.** Coil was already a dependency.
- **`MaxLineLength` 120** over every file this package touches.

---

## Ledger

Task 1 (the field): `064d088`. Room **19 → 20**, one column, `NOT NULL DEFAULT '[]'`.

  - **SEVEN map sites, not the plan's six.** Found by grepping `allergies` rather than trusting
    the list. The seventh is `SyncService`'s **`UseLocal` conflict map** — the same site package E
    found missing from its own plan's list for `Event.isImportant`, so this is now twice. The
    other six are as the plan names them.
  - **An EIGHTH site, and it is not a map: `ConflictResolver.countNonNullFields`.** It decides
    which side of a conflict survives. Without the field counted, a version holding three
    photographs and one holding none score equal, so the remote could win a tie and the
    photographs would be gone with no error anywhere. Nothing in the plan or the spec mentions it.
  - Reading the field is written once, in a pure `ChildInfoPhotos`, on the `ChildInfoAudience`
    precedent — same package, same shape, its own test. Three cases arrive there and all three are
    ordinary: an absent key (every document older than this change), a value of the wrong shape,
    and a blank URL from a failed upload that would otherwise become a thumbnail nobody can
    identify in order to remove it. All three drop rather than throw, because this is the path
    that renders a child's medical record.

Task 2 (the path and its rules): `576646b`.

  - A **third interface** rather than a third method on `EventImageStorage`. Both existing helpers
    derive one fixed object name from an entity id, so re-uploading replaces the image — right for
    a receipt, wrong for a prescription, a rash and a vaccination card, which are not versions of
    one another.
  - Deletion resolves the object **from its download URL**, not by rebuilding the path from ids.
    The record stores the URL, so rebuilding would mean parsing the URL to recover the ids in
    order to construct the path the URL already names. A URL that is not from this bucket fails
    loudly rather than reporting success: the caller drops the reference only once the object is
    gone.
  - The `storage.rules` comment says what the plan required, at length: the read rule is the same
    "any authenticated user" as its neighbours, Storage rules cannot read Firestore so they cannot
    ask who is a parent of what, the UUID path plus the URL's token is **obscurity and not access
    control**, and the real fix is a Cloud Function proxy.
  - **REPORTED, not claimed:** `firestore-tests/` covers Firestore only — `firebase.json` does not
    even configure a storage emulator — so **the Storage rules have no test coverage at all**,
    this new block included, and neither do `receipts` or `event_images`. That is a project-level
    gap rather than something this package skipped, and building the harness is its own piece of
    work. The plan's Task 2 step 3 asked for exactly this report rather than invented coverage.

Task 3 (attaching and removing): `39b45e4`.

  - **Upload happens on save, never on pick.** A parent who attaches three photographs while
    adding a child and then backs out would otherwise leave three objects in the bucket under a
    child id that was never written — unreachable, undeletable, and medical images at that. It is
    also the flow `AddExpenseScreen` already uses, which is what the plan asked for.
  - **A removal deletes the object first and keeps the URL if that fails.** A reference dropped
    from a record whose object is still in the bucket is an image nobody can see and nobody can
    delete, still readable by anyone holding the link. The snackbar distinguishes the two
    failures, because they leave opposite states.
  - The editor keeps **three** lists — stored, picked, removed — because the three have different
    consequences at save. Removing a picked photograph is just forgetting it.
  - One `MedicalPhotoStrip`, read-only by default and an editor when given callbacks, so the
    detail screen and the editor cannot drift into different ideas of what a photograph looks
    like.
  - Failures reach the screen as a `MedicalPhotoError` code rather than a message — a ViewModel
    has no `Context` and must not acquire one for user-facing text. Same shape as
    `CalendarViewModel.SwapError`. Cancellation is rethrown rather than swallowed, and
    `TooGenericExceptionCaught` is suppressed **at the site** rather than leaning on the baseline
    entry that already exists for this class and would have absorbed it silently.

Task 4 (verification): rules, pure-Kotlin, greps and the dependency check done (above). Everything
Gradle-shaped, and the device run, still outstanding.

---

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — nothing Compose-shaped here
      has compiled. The four new `ChildInfoPhotosMappingTest` cases run on that first build.
- [ ] `connectedDebugAndroidTest --tests …CoPlanlyDatabaseMigrationTest` (19→20).
- [ ] **Commit the generated `app/schemas/…/15.json` through `20.json`.** B2's, C's, D's, E's and
      now G1's are all missing. They appear on the first local `assembleDebug`.
- [ ] The device run, plan Task 4: attach three photographs to one note, confirm all three survive
      a sync and reach the co-parent's phone; delete one and confirm the **object** is gone from
      the bucket, not merely the reference.
- [ ] Record the run in spec §3.

## The check that proves this package

**Delete one photograph and then look in the bucket.** Everything else here is a list field that
either syncs or does not, and the mappers say which. The delete-before-reference order is the only
part with a failure mode that leaves no trace on any screen: the photograph disappears from the
app and stays in Storage, readable by anyone who kept the link, with nothing left pointing at it.

## Two things to watch alongside it

1. **Three photographs, then a sync, then the co-parent's phone.** Seven maps carry this field and
   only four of them are covered by a test; the other three are `SyncService`'s, which cannot be
   instantiated in a unit test. If exactly one photograph survives, or all three vanish after a
   background sync, the missing map is where to look — and the structural fix is one shared
   mapper for the child document, which this package deliberately did not attempt.
2. **A photograph on a child created *before* this build.** The migration defaults the column and
   `ChildInfoPhotos.decode` defaults the absent key, so both halves should say "none" rather than
   crashing — but they are two separate defaults and only one of them is exercised by the
   migration test.

## Handing over to G2

The rule G2 must change first is `firestore.rules` line 245: `child_info`'s update rule currently
allows any member of `sharedWith` to write. G2 puts a guest in `sharedWith` so that reading works,
which under that rule would make a grandparent an **editor of a child's medical record** — now
including these photographs. Nothing in G1 touches it; it is noted here because G1 is what gives
that rule something new to protect.

---
---

# G2 — guest access

Branch: `claude/package-g2-guest-access`.
Base: `main` @ `0fb39fc` (G1 merged as #59, F's ledger as #58).
Tasks: 5–10 of the plan's 11, all implemented. Task 11 partly run.

## What was and was not verified here

Same environment as C, D, E, F and G1: **no Android SDK, and no route to Google's Maven host**
(`dl.google.com`, and `maven.google.com` by redirect, are refused by the proxy with 403). So
`assembleDebug`, `testDebugUnitTest`, `lint` and `detekt` were **not** run, and no Compose or Hilt
file in this package has been through a compiler.

Really run, on this branch, after the rebase onto `0fb39fc`:

- **The Firestore rules suite, on the emulator — 263 passing**, up from 237 on `main` before G1.
  G2 added 26: 6 for the update hole, 11 for the guest read, 9 for the `kind` shape.
- **The Cloud Functions suite — 103 passing** and `eslint` clean, up from 71. G2 added 32: 21 for
  the guest-accept callable, 11 for the sweep.
- **Every pure-Kotlin test that compiles without the Android classpath — 321 passing**, up from
  298 at the end of G1. G2's own are `GuestGrantPolicyTest` (8), `ChildInfoGuestsTest` (7),
  `GuestInviteUriTest` (5) and `GuestAccessDurationTest` (3).
- **Locale completeness**, by grep: all 32 new keys in exactly five files each, every one
  referenced from code, and every `guest_strings.xml` parses.
- **`MaxLineLength` 120** over every Kotlin line this package adds. Nothing was added to
  `app/config/detekt/baseline.xml`.

## Ledger

Task 5 (the update hole): `b06c87c`. **Written first, before anything created a guest.**

  - The pre-existing `child_info` update rule allowed **any member of `sharedWith`** to write. G2's
    whole mechanism is putting a guest in `sharedWith` so reading works, so shipping the guest
    before this fix would have made a grandmother an editor of a child's medical record —
    photographs included. The six new cases confirm the hole was real before the fix, not
    theoretical.
  - The rule now additionally requires the writer to be the creator or the creator's co-parent,
    and requires `createdByFirebaseUid` to be unchanged. Ownership is not a field a writer gets to
    move.

Task 6 (the grant): `a680f7e`. `GuestGrant` + `GuestGrantPolicy`, pure, thirty days by default.

  - **Epoch millis, not the ISO strings the rest of this schema uses, and the reason is hard.**
    Rules have no string-to-timestamp parser, so an ISO expiry could not be compared in
    `firestore.rules` at all — the rule could only ever have checked that a guest *is* a guest.
    Discovered while writing Task 7's rule, which is why Task 6's commit was amended rather than a
    known-wrong intermediate shipped.
  - The boundary belongs to the closed side: a grant expiring at noon is inactive at noon, in all
    three implementations. `GuestGrantPolicy` is the written statement the other two must agree
    with, and both point back at it in comments.
  - Fail closed everywhere: a non-positive expiry is expired, never absent.
    `ChatReadStateTimeZoneTest`'s Prague/Tokyo shape is repeated here, because an expiry that
    depends on where the reader is standing is the custody document's bug in a worse place.

Task 7 (the field and its rule): `d986311`. Room **20 → 21**, `guestsJson TEXT NOT NULL DEFAULT '{}'`.

  - **SEVEN map sites again, plus the eighth that is not a map.** Same list G1 found, found the
    same way — by grepping an existing field rather than trusting the plan. `SyncService`'s
    `UseLocal` conflict map is now the **third** package in a row where the plan's list omits it.
  - `ConflictResolver.countNonNullFields` counts `guestsJson`, and it matters more here than it
    did for photographs: a version holding a grant and one holding none must not score equal, or a
    grandparent's access is revoked by a conflict nobody sees rather than by a parent deciding to
    revoke it.
  - `ChildInfoGuests` is pure and separate on the `ChildInfoAudience`/`ChildInfoPhotos` precedent.
    It reads *strictly*: a grant missing its name or its granter is dropped rather than defaulted
    into existence, and a missing expiry reads as 0, which every layer treats as already past.
    Being lenient in that file would hand out a permanent grant by accident.
  - Two of the eleven new rules cases failed before `guestGrantExpired()` existed. That is why it
    exists.

Task 8 (inviting and accepting): `0d0afca`. A **second callable**, `acceptGuestInvitation`.

  - Two functions rather than a `kind` branch, as the plan required. Each refuses the other's
    kind, and **both refusals are tested** — including the dangerous direction, set up with two
    unpaired accounts so that nothing but the `kind` check stands between a guest code and a
    co-parent link.
  - The callable checks what rules cannot cheaply check: that the inviter holds the record **as a
    parent**. Membership of `sharedWith` is not enough, because a guest is in it too, and a guest
    who can invite another guest is how a thirty-day grant becomes permanent.
  - `coplanly://guest` is its own host, not a query parameter on `coplanly://pair`. The two codes
    are six characters from the same generator, so the host is the only thing that says which
    callable may redeem one. `MainActivity` reads it with a second reader for the same reason the
    server has a second callable.
  - `NavGraph`'s two loose pairing parameters became one `PendingInviteCodes`. Adding the guest
    code as two more would have put the function at detekt's `LongParameterList` threshold of 6 —
    the growth its own `pendingChatOpen` doc had already predicted.
  - `GuestAccessDuration` offers a week, thirty days or ninety, and **no "no end" entry**. A test
    asserts every entry produces a grant that is active now, so the enum can never grow a member
    that hands out access nobody can use — or one that never ends.

Task 9 (seeing and revoking): `d7476e8`.

  - Revoking removes the entry from `guests` and **nothing else**. `sharedWith` is derived at
    upload time by `ChildInfoAudience.entitled` from the still-active grants, so the uid leaves the
    audience in the same write. A second place computing the audience would be a second place for
    it to disagree with the first.
  - Red row, and it confirms — the sign-out anatomy, for the reason that comment gives.
  - A failed revoke raises a snackbar, which is why `ChildInfoScreen` gained a host. Silence would
    be this feature's worst failure: the local write succeeds, the row disappears, and the parent
    walks away believing access is gone while `sharedWith` still holds the uid.

Task 10 (the sweep): `fec3ef9`. `sweepExpiredGuests`, daily at 03:00 UTC.

  - **Scans the collection**, because there is no query for it: Firestore cannot filter on a field
    inside a map's values. A denormalised "earliest expiry" column would make it queryable and is
    deliberately absent — it would be a derived field that all seven child-document map sites have
    to remember to recompute, which is the class of bug this package spent Task 7 avoiding.
  - **A defect found while writing it, fixed in the same commit.** A co-parent redeeming a guest
    code passed every check the callable had, and landed in `guests` while already being a parent
    in `sharedWith`. When the grant ran out this sweep would have taken a **parent** out of the
    audience of their own child's record. The callable now refuses an accepter who already reads
    the record, and the sweep never removes a document's creator from `sharedWith`. Both, because
    only the callable can recognise the *other* parent and only the sweep sees what is already
    stored.

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — nothing Compose- or
      Hilt-shaped in G2 has compiled. That first build is also where the Hilt binding for
      `GuestRepository` is proved.
- [ ] `connectedDebugAndroidTest --tests …CoPlanlyDatabaseMigrationTest` (20→21).
- [ ] **Commit the generated `app/schemas/…/15.json` through `21.json`.** B2's, C's, D's, E's,
      G1's and now G2's are all missing. They appear on the first local `assembleDebug`.
- [ ] `firebase deploy --only firestore:rules,functions` — the rules and both new functions are
      written and tested but **not deployed**. Until they are, the guest feature does nothing on a
      real device, and — more importantly — the Task 5 fix to the `child_info` update rule is not
      live either.
- [ ] The device run, plan Task 11: three accounts, two phones.
- [ ] Record the run in spec §7.

## The check that proves this package

**Accept a guest invitation on a third account, then try to change something.** Everything else
here either works or visibly does not. The one failure with no symptom is Task 5's: if that rule
fix were wrong or undeployed, the guest would read the record exactly as intended *and* be able to
edit a child's medications, and nothing on any screen would say so.

## Three things to watch alongside it

1. **A guest, then a background sync, then look at `sharedWith`.** Seven maps carry `guests`, and
   only the four in `ChildInfoRepositoryImpl` are covered. If a guest loses access after a sync
   tick, the missing map is `SyncService`'s.
2. **Set a grant to expire in the past and wait for 03:00 UTC.** The rule refuses the read
   immediately; the sweep is what removes the uid. Between those two the guest still appears in
   the parent's list, and the gap is bounded by a day — long enough to look like a bug.
3. **A co-parent scanning a guest code.** Should be refused with "you can already see this
   record". If it succeeds, the `already-entitled` check did not deploy, and the trap it closes
   ends with a parent swept out of their own child's audience a month later.
