# SDD ledger — plan: docs/superpowers/specs/2026-08-23-sixteen-improvements-design.md

Branch: `claude/custody-app-improvements-ycyqlh`, cut from `main` @ `505cfa5`.
The sixteen-item list from the owner's live walkthrough (screenshots 1–3).

## What was and was not verified here

Same environment as packages C–G: **no Android SDK, and no route to Google's Maven host**
(`dl.google.com` 403 via the proxy). So `assembleDebug`, `testDebugUnitTest`, `lint` and
`detekt` were **not** run, and no Compose or Android-dependent file has been through a compiler.

Really run:

- **Firestore rules, on the emulator** — full suite **286 passing** (280 before this package + 6
  new custody-proposal cases in `custody-models.test.js`, plus a rewritten `expenses` contract
  case and a new `expenses-update.test.js`). eslint clean.
- **Pure-Kotlin unit tests** under a standalone `kotlinc` 2.1 + JUnit for the files this package
  touched in the domain layer: `DayCellFillsTest` (24 → **25**, two new swap-solid-fill cases),
  `CustodyProposalTransitionTest` (**10**, unchanged, recompiled green against the wired-up
  transitions).
- **Locale completeness by grep**: every new key present in exactly five `values*` files
  (verified for the auth, onboarding, home, expenses, pairing, activity and custody strings).

Compose files (screens, ViewModels, banners) were written to the existing patterns but **not
compiled** — the same posture every prior package in this repo took. Flagged per item below.

---

## Ledger (owner's item numbers)

1. **Forgot password** — `303b509`. Reconnected `sendPasswordResetEmail` (already in the service,
   zero callers) to a sign-in-only `TextButton`; new `AuthError.EmptyEmail`, a
   `resetEmailSentTo` confirmation state, two new strings ×5 locales. The link string was already
   translated — it had been removed as a dead TODO.

2. **Wipe local data on account switch** — `00c1e54`. `AccountSwitchGuard` stores the last uid in
   a **plain** prefs file (`EncryptedPreferences.clear()` runs on sign-out and would erase the
   memory this needs) and calls `db.clearAllTables()` on a uid mismatch, from both the auth-state
   collector and the top of `performFullSync` (a periodic `SyncWorker` can beat the collector).
   Closes the worse half too: A's unsynced rows uploading under B's audience. `SyncServiceTest`
   and `SessionProfileSynchronizerTest` updated for the new constructors.

3. **Onboarding intro punctuation** — `13df2ae`. Reworded `onboarding_intro_body` ×5 so the
   example list sits next to what it lists instead of behind a ", —" splice.

4 + 13. **Pending requests are answerable, and shown on Home** — `bba0a24`. The Accept/Decline
   inbox already existed; nothing pointed at it for a day swap. Now the calendar banner count
   folds in swaps awaiting this parent, Home leads with an "Awaiting your answer" row and raises a
   dialog on open (swap → Accept/Decline in place; event requests → route to inbox; "Later" holds
   for the screen instance), the chat swap card taps through, and a swap offer/answer queues an
   FCM push through the same `notification_queue` fan-out change requests use.

5. **Onboarding schedule starts today** — `8e49e1e`. The custody anchor default was already
   today; the *calendar's own* date picker opened on the 1st (read as "propose from the 1st") —
   now opens on the selected day/today. Also fixed two adjacent picker bugs: UTC-midnight millis
   read through the system zone (a day early west of Greenwich) and a hardcoded US date pattern.

6. **Contacts + child/pet info on top of Home** — `589be95`. Contacts moved to the top of the
   dashboard, joined by Child info and Pets rows (both were reachable only through Settings).

7. **Custody change needs co-parent approval** — `1fe78ff`. Saving a pattern while paired now
   writes a *proposal* to the shared document instead of activating; the co-parent accepts via a
   Home dialog / calendar Review banner / inbox card, and gets an FCM push. The proposal domain,
   serialization and calendar preview overlay already existed (package E) but nothing wrote one.
   Rules: a proposal-only write is allowed without stamping `lastModifiedBy` (it cannot move the
   pattern); accepting is an ordinary pattern write that stamps the accepter. **Not done:**
   forbidding a *direct* pattern write at the rules layer — the mirror/republish/reslot paths
   legitimately rewrite the model, so a server lockdown is a change of its own size. The client no
   longer makes automatic changes, which resolves the reported behaviour.

8. **Single tap creates an event again** — `a7fcf4c`. Two redesign passes had removed first the
   tap-to-Day jump, then the agenda card that replaced it. A month-cell tap opens Day view again
   (where an empty hour slot creates the event); the FAB now pre-fills the selected day.

9. **Smaller labelled pie + coloured categories** — `b7f0c1d`. The Analytics pie shrank to half
   the column and gained in-slice share labels for slices wide enough; the add-expense category
   dropdown wears the same palette as dots. One palette, three surfaces.

10. **Two-column expense ledger** — `b7f0c1d`. Under the breakdown table, the month's expenses
    render as one column per parent (slot order, real names), each row marked with its category's
    colour. Unpaired → a single column; unattributed rows keep a full-width row.

11. **Settle-up navigation** — `88c521f`. The settle-up draft handoff was the one path pushing the
    Chat route onto the Expenses tab's stack; it now uses the same tab semantics as every other
    tab switch, so no tab's saved stack contains another tab's screen.

12. **Only the creator edits/deletes an expense** — `5dc9e31`. `Expense.createdByFirebaseUid`
    end-to-end (Room schema **23**, domain, mappers, stamped on create, filled from Firestore). A
    co-parent's expense renders read-only; the rules' update clause drops `isPartnerOf` to match
    delete. Legacy rows with no recorded creator stay editable by both.

14. **Swapped day is one solid colour** — `de68cc9`. The handover diagonal fired on any custody
    boundary, so one accepted swap painted two half-cells. A day decided by an accepted override
    (and the day after) now suppresses the split; pattern-boundary handovers keep it.

15. **Double-confirm unpair** — `abc76ef`. The unpair flow gains a second, destructive-styled
    dialog with the owner's wording (losing child data, calendar, contacts, shared expenses),
    "Нет" the dismissing default, ×5 locales.

16. **The friend (third member)** — **NOT IMPLEMENTED this round.** See below.

---

## Item 16 — why it is its own package

The owner asked for the full feature: a third person with their own account, invited by a parent
once the pair exists, who reads the whole calendar, has a profile (photo, info, phone, blood
group), and appears in the calendar filter with their own colour.

The **guest system** (package G2 — `GuestGrant`, `GuestInvite`, `acceptGuestInvitation`,
`sweepExpiredGuests`) is the right foundation and already does exactly this shape for **one child
record**. Extending it to the calendar is not a rider on the other fifteen items; it is a
cross-cutting package touching, at once:

- **Cloud Functions** — a friend granted calendar access must be added to the audience of *every*
  event (a fan-out, like `SyncService.backfillAudienceForPartner`), and `sweepExpiredGuests`
  extended to events and pets.
- **`firestore.rules`** — an events-side expiry gate mirroring `guestGrantExpired`, which needs
  the grant readable from the events path (a central grant + a `get()` per event read, or the
  grant stamped per event). A design choice with real cost either way, and one that must be
  proven on the emulator before it ships.
- **Room** — a new friend-profile entity + migration (schema 23 → 24), plus a photo path on
  Firebase Storage (the medical-photo infra from G1 is reusable).
- **The two-slot model** — `Parents`/`ParentsSource`, `ParentFilter` (MOM/BOTH/DAD) and
  `Event.parentOwner` are all binary today; the filter's third segment and the "friend
  participates" event field extend past that.
- **Several new Compose screens** — friend profile, invite, accept — none of which can be compiled
  in this environment.

Doing it in one unverifiable pass alongside the audience fan-out and the rules gate would ship a
large interconnected feature with no way to test its highest-risk parts here. It should be its own
SDD package with a build, decomposed as: (1) friend grant + profile model + migration;
(2) Firestore profile collection + rules + emulator tests; (3) the calendar-audience fan-out +
events read gate + emulator tests; (4) the filter third segment + participation field;
(5) the profile/invite/accept UI. Items 1–3 are fully verifiable here; 4–5 follow the guest UI
that already exists.
