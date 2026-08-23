# The data an emergency needs, and the co-parent who cannot see it — design

**Date:** 22 August 2026
**Branch:** `feat/child-parent-profiles-2026-08`
**Base:** `main` @ `14e00cbe`

This is package **B1** of the nineteen-item improvement list. It carries the data model and the
sharing behind three items:

- **Item 3 (partly).** The questionnaire asks for a parent's own details — date of birth, phone,
  blood type, allergies, hereditary conditions, vaccinations, intolerances — and the same set for
  the child. **None of it exists.** B1 adds the fields and the screens to edit them. The first-run
  wizard that walks a parent through filling them is **B2**.
- **Item 5.** "When the second parent signs in, they already see what the first one filled in."
  Today they cannot, and not by accident — see §3.
- **Items 18 and 19,** two small fixes in the same screens.

**Item 4** (the disclaimer explaining why the data is collected) belongs with the wizard that
shows it, and is B2.

---

## 1. What exists today

`ChildInfo` already carries `medications`, `activities`, `allergies`, `medicalNotes`,
`emergencyContacts` and `schoolInfo`. The relatives' contacts item 3 asks for are
`EmergencyContact` — name, relationship, phone, alternate phone — which already exists and is
already shared. `CustodySetupScreen` and `PairingScreen` (link, code and QR) exist too, so two of
the wizard's five steps are composition rather than new code. That is B2's good news, recorded
here so B2's spec does not re-derive it.

What does not exist is medical detail and anything at all about the parent:

| Item 3 asks for | Today |
|---|---|
| Parent: date of birth, phone | `User` stores neither |
| Parent: blood type, allergies, hereditary conditions, vaccinations, intolerances | nothing |
| Child: blood type, hereditary conditions, vaccinations, intolerances | only `allergies`, `medications`, `medicalNotes` |

## 2. A dead subsystem sitting exactly where the new data goes

`MedicalRecord`, `Allergy`, `Grade` and `SchoolEvent` — 18 files, four Room tables
(`medical_records`, `allergies`, `grades`, `school_events`), two Firestore data sources, two
repository implementations and two repository interfaces — are **unreachable**.
`MedicalRepositoryImpl` and `EducationRepositoryImpl` are never bound in `RepositoryModule`, no
ViewModel or use case references either interface, and `firestore.rules` has no block for their
collections. CLAUDE.md records them and asks for a decision — *delete, or wire up and add rules* —
**before anyone binds them**.

Adding medical fields to `ChildInfo` while an unbound `MedicalRecord` subsystem sits beside it
would leave two parallel models of the same idea and no way for the next reader to tell which is
real. **B1 deletes it.** The migration this package already has to write carries the four
`DROP TABLE` statements.

`AllergyEditor.kt` is **not** part of that island despite the name: it edits
`ChildInfo.allergies: List<String>` and has nothing to do with the dead `Allergy` model. It stays,
and §5 gives it a second caller.

## 3. Why the co-parent sees nothing — and why that is not a bug to find

`child_info` documents are read through `sharedWith`, and `SyncService.syncChildInfo` computes
that list as:

```kotlin
"sharedWith" to listOfNotNull(entity.createdByFirebaseUid, entity.lastModifiedBy).distinct()
```

The co-parent is never in it. The code says so itself, in a comment left deliberately:

> *SEPARATE CONCERN, deliberately not changed here: the co-parent is never added, so a paired
> parent cannot see child info the other created. That is a missing-visibility feature needing an
> audience policy of its own (the entitled-set `shareTargets` above is the shape it would take),
> not the data-corruption bug the UseLocal branch below fixes.*

So item 5 is not a defect to repair. The feature was never built, and the previous author named
the shape it should take. B1 builds it.

**The revocation half already exists.** `functions/index.js` sweeps `SHARED_AUDIENCE_COLLECTIONS =
['events', 'child_info']` on unpair, narrowing every document's `sharedWith`. Access is taken away
correctly by a server that was never asked to grant it. No Cloud Function changes in B1.

## 4. One medical shape, used twice

Parent and child need the same set, so it is one type:

```kotlin
data class MedicalProfile(
    val bloodType: BloodType? = null,
    val intolerances: List<String> = emptyList(),
    val hereditaryConditions: List<String> = emptyList(),
    val vaccinations: List<Vaccination> = emptyList()
)

data class Vaccination(val name: String, val date: LocalDate?)

enum class BloodType { A_POSITIVE, A_NEGATIVE, B_POSITIVE, B_NEGATIVE,
                       AB_POSITIVE, AB_NEGATIVE, O_POSITIVE, O_NEGATIVE }
```

A vaccination carries a **date** because that is the question a doctor actually asks — not whether
a child was vaccinated but when. The date is nullable: a parent who remembers the vaccine but not
the month should still be able to record it.

`BloodType` is an enum rather than free text so the eight real answers are the only answers, and
so the value survives a locale change — the display name comes from a string resource, the stored
value never does.

**`allergies` is deliberately outside `MedicalProfile`.** On `ChildInfo` it is already a
first-class field with a live editor. Folding it in would mean a migration that moves data between
columns, and SQLite cannot drop a column without recreating the table — real risk, bought for
symmetry that is available for free instead: `User` and `ChildInfo` each gain
`allergies: List<String>` **beside** `medicalProfile`. Both then store it the same way,
`AllergyEditor` gains a second caller unchanged, and every schema change stays an `ADD COLUMN`.

One type also means one editor composable, which is what makes B2's wizard cheap: the same
component renders the parent step and the child step.

**The parent's date of birth is a `LocalDate`, not a `LocalDateTime`.** A birth date has no time
of day, and CLAUDE.md's list of date-type decisions exists precisely so this kind of choice is
made deliberately rather than copied. `ChildInfo.dateOfBirth` is a `LocalDateTime` today and
**stays** one: changing it would mean converting stored values, which §5 exists to avoid. The
asymmetry is recorded rather than fixed, and belongs on the same backlog as the four other
naive-local-time fields CLAUDE.md tracks.

## 5. Storage — migration 13 to 14

| Table | Change |
|---|---|
| `child_info` | `+ medicalProfileJson TEXT NOT NULL DEFAULT '{}'` |
| `users` | `+ dateOfBirth TEXT`, `+ phone TEXT`, `+ allergiesJson TEXT NOT NULL DEFAULT '[]'`, `+ medicalProfileJson TEXT NOT NULL DEFAULT '{}'` |
| `medical_records`, `allergies`, `grades`, `school_events` | `DROP TABLE` |

Additive only: no table is recreated and no value is moved, so the migration cannot lose data it
does not touch. JSON columns match how `ChildInfoEntity` already stores `medicationsJson`,
`activitiesJson` and `emergencyContactsJson` — this introduces no new storage idiom.

`CoPlanlyDatabase`'s `entities` list loses the four dead entities and the version becomes 14.
`DatabaseMigrations.ALL_MIGRATIONS` picks the new migration up automatically. Exported schemas
live in `app/schemas/`.

Installs older than the chain are still wiped by `fallbackToDestructiveMigrationFrom(1,2,3,4)`;
that list is not extended.

## 6. The audience policy — what makes item 5 work

Three changes, each mirroring what `events` already does.

**The upload computes an entitled set.** `syncChildInfo` stops listing creator and last-modifier
and instead publishes the uploader, the document's creator and the uploader's **current**
co-parent — non-blank, de-duplicated.

**It does not need the `shareTargets` intersection, and the reason matters.** For events, the
entitled set is intersected with the audience stored in `EventEntity.sharedWithJson`, because the
server's unpair sweep narrows the remote document but never the local Room copy: a widen-only rule
re-grants an ex-partner access to every row still sitting `syncedToFirestore = false` at unpair
time, on the very next sync.

`ChildInfoEntity` **has no `sharedWith` column at all**. The audience has always been derived
fresh at upload time from live fields, so there is no stored list to carry a stale uid forward and
nothing for an intersection to remove. Deriving from live state is the same protection the
intersection buys events, arrived at for free. No column is added to store one — adding it would
create the very staleness the events path has to defend against.

The consequence to keep in mind: because the audience is recomputed on every upload, a row only
gets the co-parent when it is uploaded again. That is exactly what the backfill below is for.

**A DAO method to re-queue.** `ChildInfoDao.markOwnChildInfoUnsynced(userId)`, mirroring
`EventDao.markOwnEventsUnsynced`. Rows whose `createdByFirebaseUid` is null are deliberately not
matched: nothing distinguishes this user's un-stamped row from anybody else's.

**A backfill, so existing pairs are repaired too.** Child info filled in before the co-parent was
invited — which, once B2's wizard exists, is *every* child info — would otherwise stay private
forever. `SyncService` gains a child-info backfill modelled on `backfillAudienceForPartner`, with
its own preference key. Two rules carry over verbatim, both learned the hard way on events:

- The marker stores the **partner's uid**, never a boolean. A boolean never re-arms when the same
  two people pair again.
- When `partnerId` is null the marker is **blanked**, not ignored. Leaving it naming an ex-partner
  means re-pairing with that same person finds the marker already equal to their uid and skips the
  backfill — a pair that looks correctly linked while everything from before the unpair stays
  invisible to one of them, silently.

## 7. Firestore and rules

The rules barely move. `users/{userId}` already allows `isOwner(userId) || isPartnerOf(userId)` to
read and only the owner to write; `child_info` already gates on `sharedWith`. New fields need no
new branch, and the parent's medical data is readable by the co-parent because that is the point of
the questionnaire — if something happens to one parent, the other can tell a paramedic the blood
type.

That the co-parent **cannot write** the parent's profile is deliberate and load-bearing. CLAUDE.md
records that client writes to another user's `users/{uid}` document are what forced the permissive
`firestore.rules.simple` to be deployed once. Item 5's "the second parent can add or change
contact information" is therefore satisfied on the **child** document, which both parents may
write — which is where `emergencyContacts` already lives.

What does change is coverage. `firestore-tests/rules/users-profile.test.js` and
`child-info.test.js` gain cases:

- a partner can read the other's `medicalProfile` and `phone`;
- a partner **cannot** write them;
- a `child_info` document created before pairing becomes readable by the co-parent once the
  backfill has re-uploaded it, and not before.

The co-parent's profile is read for display by a dedicated query, **not** by extending
`PartnerSummary`. That model exists to name a person in a chat header and a pairing row; hanging
seven medical fields on it would load them on every screen that wants a name.

## 8. Items 18 and 19

**18 — the education section is widened, not shortened.** The phrase the item names,
"информация об учебном заведении", does not exist anywhere in the app; the label today is
"Информация о школе" / "School Information". The intent is the broader term, because a child may
be at a nursery or a college rather than a school. The section label and the field labels inside it
(`childinfo_school_name_label`, `childinfo_school_phone_optional_label`, and the rest) move to the
institution wording in all five locales — English **"Place of study"** for the section, with
"Institution name" and "Institution phone" inside it, and the equivalent broader term in each other
locale (Russian «Учебное заведение», «Название учебного заведения», …).

The key **names** do not change. `childinfo_section_school` keeps its name even though its value no
longer says "school": renaming a key touches five files plus every call site and buys nothing a
reader of the value cannot already see. `SchoolInfo` and `schoolInfoJson` keep their names for the
stronger version of the same reason — `schoolInfo` is a field in the `child_info` Firestore
document, and a co-parent on an older build must keep reading it.

`child_info_school` in `strings.xml` is a duplicate of `childinfo_section_school` with **zero**
references and is deleted. `childinfo_section_school` is the live key, used by
`ChildInfoScreen.kt:274` and `AddEditChildInfoScreen.kt:319`.

**19 — the child's information opens on tap.** `ChildInfoScreen` renders non-clickable
`Card { … }` blocks and puts editing behind a pencil in the top bar. It moves to `SectionGroup` /
`SectionRow` from `presentation/common/DesignSystem.kt`, sections open on tap, and the top-bar
pencil goes. This is the last screen still on the card-per-row shape the August 2026 refresh calls
"double surfaces"; leaving it would make it the only one of seven that disagrees.

## 8.5 Where a parent edits their own profile — a screen that does not exist yet

Found while planning, and worth stating plainly because an earlier draft of this spec was wrong
about it: there is **no** parent profile screen. Settings' Account group holds `SignedInAsRow`
(the signed-in email) and a sign-out row, nothing more. "Make the data editable from the existing
screens" is true for the child and false for the parent.

B1 adds one screen, used twice:

- **`ProfileScreen(editable = true)`** — my own profile: name, date of birth, phone, allergies,
  medical profile. Opens from a new row in Settings' **Family** group, which is where the co-parent
  and the child already live.
- **`ProfileScreen(editable = false)`** — the co-parent's, read-only. Same composable, same
  layout, no editors. Read-only is not a courtesy here: `firestore.rules` refuses the write
  anyway (§7), so an editable co-parent screen would be an affordance that promises a feature the
  server rejects — the exact defect the August 2026 refresh's rule 8 forbids.

One screen with a flag rather than two screens, because the read-only variant is the same
information in the same order. When B2 builds the wizard, its "about you" step reuses the same
section composables a third time.

## 9. Deliberately not in B1

- **The first-run wizard and its disclaimer** (items 3 and 4). B2. B1 makes the data editable
  from the existing screens; B2 walks a new parent through filling it.
- **Encryption at rest.** `EncryptionManager` and the unused `SensitiveMedicalData` model exist,
  and encrypting the new fields is a defensible future step — but encrypted values cannot be
  queried or conflict-resolved, and turning that on is a decision with its own migration. Not a
  rider on this one.
- **Cloud Functions.** The unpair sweep already covers `child_info`.
- **`custody_schedules`, chat, expenses.** Untouched.

## 10. Verification

| Check | How |
|---|---|
| Migration | A 13→14 migration test with rows present: existing child info survives, new columns default, the four dead tables are gone. |
| Audience | JVM tests for the child-info `shareTargets`: the co-parent is included while paired; an ex-partner is **not** re-granted from a stale local row; a null `createdByFirebaseUid` is not matched. |
| Backfill | JVM tests: the marker re-arms on a new partner uid; it is blanked when unpaired; it does not re-queue twice for the same partner. |
| Rules | `cd firestore-tests && npm test` — the new cases in §7. Needs a JDK 21+ on `PATH`. |
| Locales | `git grep -c 'name="<key>"' -- app/src/main/res/values*/*.xml` returns five files per new or renamed key. |
| Build | `./gradlew assembleDebug testDebugUnitTest lint detekt` |

**One of those checks needs hardware.** Room's `MigrationTestHelper` is instrumented — the
migration test lives in `app/src/androidTest/` beside `CoPlanlyDatabaseMigrationTest`, and
`connectedDebugAndroidTest` needs a device or emulator. There is no Robolectric in this project to
run it on the JVM. So the migration is the one part of B1 that cannot be signed off from a plain
`./gradlew` run, and the plan must say so rather than let a green JVM suite imply coverage it does
not have.

Everything else is covered offline. The two-device check that item 5 really lands (fill in child
info on phone A, pair, see it on phone B) belongs with B2's run, when there is a wizard to fill it
in from.
