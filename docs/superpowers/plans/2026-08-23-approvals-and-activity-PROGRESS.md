# SDD ledger — plan: docs/superpowers/plans/2026-08-23-approvals-and-activity.md

Branch: `claude/package-d-approvals-and-activity`, cut from `claude/update-main-v2-code-94m9b9`
(package C, open as PR #53). Base for review is therefore **C, not `main`**.
Tasks: 8. Tasks 1–7 implemented; Task 8 partly run.

## What was and was not verified here

Same environment constraint as package C: **no Android SDK, no route to Google's Maven host**, so
`assembleDebug`, `testDebugUnitTest`, `lint`, `detekt` and `connectedDebugAndroidTest` have not run
and no Compose or Android-dependent file has been through a compiler.

Really run:

- **Firestore rules, on the emulator** — full suite **237 passing, 0 failing**, eslint clean.
  Neither of Task 7's cases needed a rule change, which is exactly why both were written.
- **Every pure-Kotlin unit test in the tree** — compiled with a standalone `kotlinc` 2.1 and run
  under JUnit: **116 passing**, covering packages B2, C and D together.

Tests written but **not runnable here** (they need the Android/Firebase classpath, and run on the
first local build): 5 new cases in `EventRepositoryImplTest`, 4 in `ChatMappersWireFormatTest`,
2 in `ActivityCardTextTest`, and 2 instrumented migration cases.

---

## Ledger

Task 1 (`EventAcceptance`, transitions): `6c54628`. 12 tests. Two of the `required` guards are
about **data loss**, not etiquette, because a pending event is hidden from every calendar view: a
**private** event never reaches Firestore so the co-parent could not answer it even in principle,
and an **unpaired** account has nobody to ask. Either would have deleted an event from its own
creator's calendar with nobody able to give it back.

Task 1: DEVIATION. `cancel` authorises deletion rather than producing a status. There is no
`WITHDRAWN` for one to mean — the recipient never agreed, so nothing about the event is worth
keeping — and a fifth enum member would have to be filtered out of every view that already filters
the other two. It still returns `Result<Event>` as the plan specified; the event is the row to
delete, and the `Result` carries the refusal.

Task 2 (storage, sync, the filter): `e7870ea`, plus `c106d9d` for stamping at creation.
Schema **16 → 17**, three columns, nothing rewritten. Four mapper sites carry the fields, found by
grepping `imageUrl` rather than trusting the plan's list: `toFirestoreMap`, `toDomain`, `toEntity`,
and `SyncService`'s **three** (two upload maps and `toEventEntity`).

Task 2: the creator's slot for `required()` comes from `User.role` — the *same* lagging mirror
`AddEditEventScreen` reads to pick `parentOwner`. That matters more than its accuracy: reading a
fresher value from elsewhere would let the two disagree and hide a parent's own event from their
own calendar.

Task 3 (inbox and waiting strip): `132ef3c`. Done **after** Tasks 4–7 because it was clearly
essential rather than optional — without it a `PENDING` event exists on neither phone, which is
worse than not having the feature. Section renders above the other two; `indexInInbox` takes both
new sections' counts.

Task 4 (the payload and its rendering): `7be87c2`. Schema **17 → 18**, one nullable column.
Found and fixed while here: **the Room reader had no type guard.** It called `valueOf` unguarded,
so a row whose `messageType` this build did not know threw on the path that draws the whole
thread, while its Firestore counterpart degraded safely. Both now degrade, and both are pinned.

Task 4: LIMITATION. An announcement about an event or a change request taps through; one about an
expense or a day swap renders without a tap target. Neither has a route reachable from chat today,
and a bubble that looks tappable and does nothing is worse than one that plainly is not.

Task 5 (`ActivityAnnouncer`): `7443cad`, and `94f3437` for day swaps.

Task 5: DECISION the spec glossed. It says `content` is "a plain-text fallback, sender's locale",
but a repository has no `Context` and CLAUDE.md forbids injecting one to reach a string.
`ActivityFallbackText` writes it in the app's **base locale** (English) for everyone instead, and
says so at length: the sender's language is not a property of the message and the reader's is
unknown to the sender, so a single fixed language at least makes the fallback predictable. It is
only ever shown by a build that predates `MessageType.ACTIVITY`, and disappears once both phones
carry this one. **This is the one deliberate exception to the no-hardcoded-strings rule in this
package**; flag it if the owner disagrees.

Task 6 (retire the bespoke cards): `644ccba`. Package C added no day-swap card — it was left out
precisely so this package could provide the one mechanism — so only the change-request one existed
to retire.

Task 6: BEHAVIOUR CHANGE worth naming. **A change proposed from the calendar used to reach the
thread not at all.** The card was posted only when a `conversationId` was passed, which happened
only from the chat screen. The announcer resolves the thread from the two uids, so the route
argument, the screen parameter and the two-argument callback that carried it are gone.

Task 7 (rules): `b225245`. No rule change needed, as §5 predicted. Pinned both ways: a stranger and
a `read_only` recipient still cannot write acceptance, so it is not a hole cut around the
permission model.

Task 8 (verification): rules and pure-Kotlin done. Everything Gradle-shaped, and the two-device
two-language run that is the point of this package, still outstanding.

---

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — nothing Compose-shaped has
      compiled.
- [ ] Commit the generated `app/schemas/…/15.json` through `18.json`. B2's, C's and D's are all
      missing; without them the instrumented migration tests have no schema to validate against.
- [ ] `connectedDebugAndroidTest --tests …CoPlanlyDatabaseMigrationTest` (through 17→18).
- [ ] The two-device, **two-language** run — plan Task 8 step 5, all seven steps.

## The check that proves this package

Set phone A to Russian and phone B to English, then create an expense on A. B's chat card must
read in **English** and A's card for the same change in **Russian**. Nothing else demonstrates
that the card is composed by the reader; a card that happens to be right in one language proves
nothing, because a sender-composed card would look identical on the sender's own phone.

## Two things to watch alongside it

1. **An event created for the co-parent must be in neither grid** until answered, and must be
   visible in B's inbox and as a count on A's event list. If it is in neither place, the filter
   is right and the two screens are not — which is the failure mode Task 3 exists to prevent.
2. **A private event must announce nothing.** It never reaches Firestore, so announcing one would
   leak it through the channel the sync path is careful to close.
