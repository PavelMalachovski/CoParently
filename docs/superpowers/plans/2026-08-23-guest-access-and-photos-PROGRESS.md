# SDD ledger — plan: docs/superpowers/plans/2026-08-23-guest-access-and-photos.md

**This covers G1 only.** The plan is explicit that G1 and G2 ship as two branches — G1 is a list
field and a Storage path, G2 changes a rule that governs live medical data, and one risk profile
should not carry the other through review. G2 has not been started.

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
