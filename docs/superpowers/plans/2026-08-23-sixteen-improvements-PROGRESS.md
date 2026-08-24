# SDD ledger — plan: docs/superpowers/specs/2026-08-23-sixteen-improvements-design.md

Branch: `claude/custody-app-improvements-ycyqlh`, cut from `main` @ `505cfa5`.
The sixteen-item list from the owner's live walkthrough (screenshots 1–3).

## What was and was not verified here

Same environment as packages C–G: **no Android SDK, and no route to Google's Maven host**
(`dl.google.com` 403 via the proxy). So `assembleDebug`, `testDebugUnitTest`, `lint` and
`detekt` were **not** run, and no Compose or Android-dependent file has been through a compiler.

Really run:

- **Firestore rules, on the emulator** — full suite **302 passing** (280 before this package,
  then +6 custody-proposal cases in `custody-models.test.js`, a rewritten `expenses` contract
  case, a new `expenses-update.test.js`, and +17 friend cases in `friend-calendar.test.js`).
  eslint clean.
- **Cloud Functions** — **114 passing** (102 before, +12 friend cases in `friend-invite.test.js`)
  and eslint clean. One failure, `revokeSharedAudience`, pre-dates this branch and is left alone;
  the `max-len` error that also pre-dated it is fixed here because it blocked the lint gate.
- **Pure-Kotlin unit tests** under a standalone `kotlinc` 2.1 + JUnit for the files this package
  touched in the domain layer: `DayCellFillsTest` (24 → **25**, two new swap-solid-fill cases),
  `CustodyProposalTransitionTest` (**10**, unchanged, recompiled green against the wired-up
  transitions), and item 16's `CalendarFriendPolicyTest` (**6**) + `FriendMappersTest` (**8**).
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

16. **The friend (third member)** — `9023233`, `dbeb5ee`, `9e145d7`, `c00495e`. See below.

---

## Item 16 — the friend, in four layers

Built on the guest-grant system (package G2) — a friend sits **beside** the two parent slots and
never occupies one. Four commits, each verifiable on its own:

**Backend** (`9023233`). The grant is **central** — `calendar_friends/{friendUid}` — not fanned
out into every event's `sharedWith`. Admitting or revoking is one write instead of a rewrite over
the family's whole history, and no event document changes shape. The `events` read rule gains a
third disjunct that consults it, placed **last** so a parent's own read short-circuits before any
`get()`; expiry is compared against `request.time`, so access ends the instant the grant lapses
with no sweep in the path. The friend queries `createdByFirebaseUid in [a, b]` — the shape
`expenses`/`budgets` already use — with a composite index added.

`friend_profiles/{uid}` is authored by the friend; the parents read it and never write it, and
the `familyParents` read gate is immutable after create.

`acceptCalendarFriendInvitation` is the **third** callable beside pairing and guest, for the
reason the guest one states: paths that grant different things must not be one `kind` branch
apart. It requires the inviter to be a paired parent, refuses the co-parent taking a grant on
their own family, and tells both parents. **The pairing callable now refuses a friend invitation
outright** — without that, redeeming one would run `assignSlots` and hand a friend a permanent
parent slot, which is the exact failure the split exists to prevent.

**Client** (`dbeb5ee`). `CalendarFriendPolicy` states when a grant is live, with the same strict
comparison and fail-closed default `GuestGrantPolicy` uses, so it, the rule and any future sweep
agree. `FriendMappers` drops rather than guesses: a grant naming other than two parents, or with
no expiry, is not a grant. `Event.friendParticipates` (Room **23 → 24**) records who takes part —
**all seven map sites found by grepping `isImportant`** rather than trusting a list.

**UI** (`9e145d7`, `c00495e`). Settings → Friend, under pairing. The screen answers whichever side
is signed in: a parent sees the list, an invite sheet and a revoke; a friend sees their grant and
their profile. The calendar filter gains the friend as a **chip below** the parent row, not a
fourth segment — the three parent labels already clip at their fallbacks. `FriendTeal` is far from
both parent hues and is not the theme's neutral `secondary`: a person is not a control.

### Verified

- **Firestore rules on the emulator: 302 passing** (+17 friend cases over the 286 this package
  started from; profiles, grants, the friend's event read, expiry, and the stranger cases).
- **Functions: 114 passing** (+12 friend cases) and eslint clean, including the case that pins
  the pairing callable refusing a friend invitation.
- **Pure Kotlin under standalone `kotlinc` 2.1: 14 tests** (`CalendarFriendPolicyTest` 6,
  `FriendMappersTest` 8).
- **42 new strings across all five locales**; `MaxLineLength` 120 clean over every file touched.

### Not done

- **The photo.** The friend profile carries `photoUrl` end to end and the rules admit it, but no
  upload control ships: the Storage wiring (the medical-photo path from G1) is a change of its
  own, and a button that did nothing is exactly the promise this project's design rules forbid.
- **A sweep for lapsed grants.** The rule refuses an expired read and the client hides it, so
  nothing leaks; what is missing is the tidy-up that deletes the row, mirroring
  `sweepExpiredGuests`.
- **Compose files are not compiled here** — no Android SDK, as in every prior package. The
  screens follow the existing patterns but the first local `assembleDebug` is their first
  compiler.

---

## Follow-up — faces from the Google account

Asked for after item 16 shipped: show the friend's and both parents' photos, taken from their
Google account where one exists. No upload was added; this is the picture Google already holds.

**Where each face comes from.** A parent's is `users/{uid}.profilePhotoUrl`, which
`ProfileIdentity.resolvePhotoUrl` already wrote at sign-in — it reaches the UI through
`NamedParent.photoUrl` (both projections, own and co-parent) and `ParentNames.photoForUid(uid)`.
Keyed on the **uid**, not the slot, for the reason `labelForUid` exists: a pair that has not been
through slot assignment still shares one slot, and a slot lookup would return the same face for
both parents. The expenses ledger's two columns are the first caller.

A friend's is copied into `calendar_friends/{uid}` by `acceptCalendarFriendInvitation`, beside
the name and for the same reason the name is there: the parents' list would otherwise need a
second read of a document that is not theirs. `accepterPhoto()` returns a one-key object to
merge rather than a string, because `photoUrl: undefined` is a write Firestore rejects outright.
On the friend's own device `saveMyProfile` seeds `friend_profiles/{uid}.photoUrl` from Firebase
Auth **only when the profile carries none** — a friend who has set their own picture keeps it,
and nothing re-derives it on a later save.

**`observeFriendProfile` had no callers.** The profile the whole feature is built around — the
phone number and the blood group — was unreachable from a parent's device: the friend row's only
action was "remove access". `FriendDetailScreen` is that missing screen. A row now opens the
friend's card; revoke moved onto it, last, per the destructive-action anatomy. The route carries
the uid only, so the name comes from the grant the screen already observes and cannot go stale
against a friend who renamed themselves between the two screens.

`SectionRow` gained a `leading` slot for this — an avatar *instead of* the icon, never beside it,
which would be the double leading mark the anatomy exists to prevent.

### Verified

- **Pure Kotlin under standalone `kotlinc` 2.1: 15 tests** (`FriendMappersTest` 9,
  `CalendarFriendPolicyTest` 6) — the new case pins a grant's picture decoding, a blank one
  becoming null rather than an empty string, and its absence.
- **Functions: 116 passing** and eslint clean, including the two new cases: the picture copied
  into the grant, and **no `photoUrl` key at all** when the accepter has none.
- **Firestore rules: 302 passing**, unchanged — neither `calendar_friends` nor `friend_profiles`
  validates a key list, so the added field needed no rule change.
- One new string (`friend_detail_title`) in all five locales; `MaxLineLength` 120 clean over
  every file touched.

### Known, not fixed

- **The pre-existing `revokeSharedAudience` failure** (`unpair.test.js`, 6 !== 4) still fails and
  pre-dates this branch — verified against `main`.
- **Compose is still not compiled here** — no Android SDK. `FriendDetailScreen` and the changed
  rows follow existing patterns, but the first local `assembleDebug` is their first compiler.
