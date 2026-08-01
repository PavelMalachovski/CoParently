# Co-parent pairing — design

**Date:** 2026-08-01
**Branch:** `feature/coparent-collab`
**Scope:** part A of three. B = shared custody + custody-day swap requests, C = chat. Each gets its own spec, plan and PR.

## Problem

Two parents must be able to link their accounts reliably from two phones. Today the flow is
half-built and partly broken:

1. **The QR path cannot work.** `QRScannerActivity` renders a placeholder `Card` labelled
   "Camera preview" — there is no camera, no `ImageAnalysis`, and `onQRCodeScanned` is never
   invoked. Separately, `PairingViewModel.generateQRCode()` encodes a synthetic id
   (`qr_<uid>_<millis>`) that is never written to Firestore, so even a working scanner would
   fail with "Invitation not found".
2. **Accepting an invitation writes the other parent's user document.**
   `CoParentPairingService.acceptInvitation` calls `updateUser(fromUserId, …)`, and
   `removePartnership` writes both documents. `firestore.rules` allows `update` on
   `users/{userId}` only for the owner. Both operations therefore only work because the
   permissive `firestore.rules.simple` is what is actually deployed. Tightening the rules
   would break pairing; the write path is what has to move.
3. **The inviter never learns the invitation was accepted.** `partnerId` is refreshed only by
   an explicit `syncWithFirestore()` call, so the inviting phone keeps showing "not paired"
   until something else happens to trigger a sync.
4. **Pairing is hard to find.** It lives behind Settings; the second parent has no prompt
   anywhere in the app telling them to link up.
5. **A dead App Link.** `AndroidManifest.xml` declares an `autoVerify` intent filter for
   `https://coparently.app/pair` on `QRScannerActivity`. That domain is not ours, so
   verification always fails and the filter is noise.

## Decisions taken

| Question | Decision |
|---|---|
| Push infrastructure | Cloud Functions are already deployed (6 functions, nodejs20, us-central1) and the project is on Blaze. Adding callable functions is cheap and allowed. |
| Entry points | All four: short code, share link, real QR, email invite. |
| Deep link | No domain available → custom scheme `coplanly://pair?code=…`, no App Links. |
| Unpair | One-sided. Clears the link for both, leaves all existing data (events, chat, expenses) untouched. |
| Accounts | Two distinct, currently unlinked accounts on Pixel 9 Pro XL (Android 17) and Samsung SM-A176B (Android 16). |

## Architecture

### One invitation, three representations

A single `invitations` document backs the code, the QR image and the share link. This is the
root fix for the QR bug: the QR encodes the same `code` the user can read aloud, and the same
URI the share sheet sends.

```
invitations/{invitationId}
  id            string
  fromUserId    string      // Firebase UID of the inviter
  fromUserEmail string
  fromUserName  string
  code          string      // 6 chars from A-Z2-9 minus O/0/I/1/L — unambiguous by voice
  toEmail       string      // "" for code/QR/link invites; set for email invites
  status        string      // pending | accepted | rejected | cancelled
  createdAt     number      // epoch millis
  expiresAt     number      // createdAt + 24h (code/QR/link) or + 7d (email)
  acceptedBy    string|null // UID of the parent who accepted
```

A user has at most one active invite. Opening the pairing screen reuses the existing pending,
unexpired document instead of creating a new one, so the code shown on screen never silently
changes underneath a code the user already sent by WhatsApp.

Code generation: 6 characters from a 32-symbol alphabet ≈ 1.07e9 combinations. Uniqueness is
enforced by the accepting function querying `where('code','==',code).where('status','==','pending')`
and rejecting when more than one document matches; generation retries on collision (checked
client-side against the same query, best-effort — the function is the authority).

### Two new callable Cloud Functions

Accepting an invitation and unpairing both, by their nature, write the *other* parent's user
document. No Firestore rule can grant that to a client without also letting any authenticated
user write any other user's profile. Both move server-side, where the Admin SDK bypasses rules
legitimately.

**`acceptPairingInvitation({ code?, invitationId? })`** — exactly one of the two must be given.

Guards, in order, each with a distinct error code so the UI can show a specific message:
- `unauthenticated` — no `context.auth`
- `not-found` — no matching document, or more than one pending doc shares the code
- `failed-precondition` / `invitation-expired` — `expiresAt < now`
- `failed-precondition` / `invitation-not-pending` — `status !== 'pending'`
- `invalid-argument` / `self-pairing` — `fromUserId === context.auth.uid`
- `failed-precondition` / `already-paired` — either user already has a non-empty `partnerId`
- `permission-denied` / `wrong-recipient` — `toEmail` is set and differs from `context.auth.token.email`

On success, one Firestore transaction sets `partnerId` on both user documents and flips the
invitation to `accepted` with `acceptedBy`. Then a `notification_queue` document is written for
the inviter (`type: 'pairing_accepted'`).

**`unpairCoParent()`** — reads the caller's `partnerId`, transactionally clears it on both
documents, queues a `type: 'pairing_removed'` notification for the ex-partner. No-op success if
the caller has no partner.

**`acceptQRInvitation` is deleted.** It requires `invitation.toEmail === context.auth.token.email`,
which a QR/code invite never has, and it duplicates the new function's job.

Functions stay on the v1 API (`firebase-functions@^4.5.0`, `functions.https.onCall`) to match
the five existing handlers — this change is not the place to migrate the codebase to v2.

### Client data layer

`PairingViewModel` is 580 lines and contains the same 40-line Firestore-error `when` block
twice (in `sendInvitation` and in `acceptInvitation`). It talks directly to
`CoParentPairingService`, `FirebaseAuthService`, `UserRepository`, `MessageRepository`,
`AnalyticsManager` and `QRCodeService`. That is the file-doing-too-much signal; the feature is
split behind a repository so the ViewModel becomes state plus five actions.

| File | Responsibility |
|---|---|
| `domain/model/PairingState.kt` (new) | `sealed interface PairingState { Loading; NotPaired(activeInvite: PairingInvite?, incoming: List<PairingInvite>); Paired(partner: PartnerSummary) }` |
| `domain/model/PairingInvite.kt` (new) | `id, code, fromUserId, fromUserName, fromUserEmail, toEmail, expiresAt` |
| `domain/model/PairingError.kt` (new) | `sealed interface PairingError { NotFound; Expired; NotPending; SelfPairing; AlreadyPaired; WrongRecipient; Network; Unknown(message) }` — the typed result of a failed callable |
| `domain/repository/PairingRepository.kt` (new) | `observePairingState(): Flow<PairingState>`, `createOrReuseInviteCode(): Result<PairingInvite>`, `sendEmailInvitation(email): Result<Unit>`, `redeem(code): Result<Unit>`, `acceptIncoming(invitationId): Result<Unit>`, `rejectIncoming(invitationId): Result<Unit>`, `revokeActiveInvite(): Result<Unit>`, `unpair(): Result<Unit>` |
| `data/repository/PairingRepositoryImpl.kt` (new) | Firestore reads/writes plus callable invocations |
| `data/remote/firebase/PairingFunctions.kt` (new) | Thin wrapper over `FirebaseFunctions.getInstance()`; maps `FirebaseFunctionsException` to typed `PairingError` |
| `data/remote/firebase/FirebaseErrorMapper.kt` (new) | The duplicated `when` block, extracted once, returning `@StringRes Int` rather than an English literal |
| `presentation/pairing/PairingViewModel.kt` (rewritten) | State + actions only |
| `data/remote/firebase/CoParentPairingService.kt` (shrunk) | Keeps only `getPartnerInfo`; invitation CRUD moves into the repository, `acceptInvitation`/`removePartnership` are deleted (superseded by the callables) |

`observePairingState()` is built on a **realtime snapshot listener on `users/{uid}`**, combined
with a listener on pending invitations. This is what fixes problem 3: the moment the callable
sets `partnerId`, the inviter's phone flips to `Paired` on its own, with or without a push.

Conversation creation on pairing (currently inline in `PairingViewModel.acceptInvitation`) moves
to a `PairingState.Paired` side effect in the repository: when a transition to `Paired` is
observed and no 1:1 conversation exists yet, create it. This makes it work on *both* phones,
not just on the accepting one.

### UI

`PairingScreen` is rebuilt around `PairingState`, with the pieces extracted as private
composables in a `presentation/pairing/components/` package (`InviteCodeCard`, `CodeEntryField`,
`IncomingInviteCard`, `PairedPartnerCard`) so no single file carries the whole screen.

**NotPaired**
1. Hero card: the code in large, letter-spaced type; tap to copy; "valid 24 h" with a live
   countdown; **Share** and **Show QR** buttons; an overflow menu with "New code", which calls
   `revokeActiveInvite()` and then `createOrReuseInviteCode()` — the way a user invalidates a
   code they sent to the wrong chat.
   Share text: `<Name> invites you to CoPlanly. Code: 4F7K2M · coplanly://pair?code=4F7K2M`
2. "or" divider.
3. **I have a code** — a 6-character input (auto-uppercase, accepts a pasted full URI and
   extracts the code) plus **Scan QR**.
4. Collapsed "Invite by email" — the existing path, unchanged in behaviour.
5. Incoming invitations list — as today, with Accept/Reject.

**Paired** — partner card (name, email, initial avatar, "paired since DD.MM.YYYY"). "Unpair"
sits alone at the bottom of the screen with a `ConfirmationDialog`, per CLAUDE.md UX rule 8
(danger actions at the bottom, not mid-list).

**QR scanner** — `QRScannerActivity` is rewritten: CameraX `PreviewView` hosted in `AndroidView`,
an `ImageAnalysis` use case feeding ML Kit `BarcodeScanning` (already a dependency), a framing
overlay, and a torch toggle. New dependencies: `androidx.camera:camera-core`,
`camera-camera2`, `camera-lifecycle`, `camera-view` (1.4.x). The scanner parses
`coplanly://pair?code=…` and returns the code — the same parser the deep link uses.

**Deep link** — `coplanly://pair?code=…` declared on `MainActivity`, whose `launchMode` changes
from `singleTop` to `singleTask` with `onNewIntent` handling so a link arriving while the app
runs is routed rather than dropped. NavGraph gains `pairing?code={code}`. The code is
pre-filled but **never auto-redeemed**: the user confirms "Link with Pavel (pavel@…)?" first,
because a share link can be forwarded to a third party. The dead `https://coparently.app/pair`
filter on `QRScannerActivity` is removed.

**Discoverability** — when the account is not paired, the Home screen shows a dismissible
banner leading to the pairing screen, and any incoming invitation is surfaced there too.

### Rules and notifications

`firestore.rules`:
- `invitations`: `create` additionally accepts `code` and `expiresAt`; read stays restricted to
  the inviter and the addressed email. Clients no longer need to query by `code` — the callable
  does that with Admin rights.
- `users`: unchanged. The client-side cross-user writes that violated it are gone.
- `firestore.rules.simple` stays as the documented fallback but the intent is to deploy the
  strict file once this lands (`firebase deploy --only firestore:rules`).

`CoPlanlyMessagingService` gains handling for `pairing_accepted` and `pairing_removed`: tap
opens the pairing screen. Two existing defects there are fixed in passing because they will
otherwise show up immediately in two-phone testing:
- `onMessageReceived` posts a notification for the `data` payload *and* again for the
  `notification` payload; the Cloud Function sends both, so a foreground push appears twice.
  Only one notification is posted.
- the small icon is `android.R.drawable.ic_dialog_info`, a framework placeholder — replaced
  with the app's notification icon.

### Strings

All new user-facing text goes into `res/values/pairing_strings.xml` and the four locale
variants (`values-cs`, `values-de`, `values-ru`, `values-uk`), per the i18n rules in CLAUDE.md.
Error messages currently hardcoded in English inside `PairingViewModel` become string resources
resolved in the composable, with `FirebaseErrorMapper` returning resource ids.

## Testing

**Unit (JVM, MockK + coroutines-test + Turbine)**
- `InviteCodeGenerator`: length 6, alphabet excludes `O 0 I 1 L`, 10k generations produce no
  disallowed character.
- `PairingRepositoryImpl`: `redeem` success; `already-paired`, `invitation-expired`,
  `self-pairing`, `not-found` each map to their own `PairingError`.
- `PairingRepositoryImpl.observePairingState`: emits `NotPaired` → `Paired` when the user
  snapshot gains a `partnerId`.
- `PairingViewModel`: code entry validation, unpair confirmation gating.

**Cloud Functions** — `firebase-functions-test` is already a devDependency: `acceptPairingInvitation`
guard matrix (each error branch), the happy path writing both `partnerId`s, and `unpairCoParent`
clearing both.

**Manual, on both phones** — the acceptance run:
1. Pixel generates a code → Samsung types it → both flip to Paired without restarting the app.
2. Unpair from Samsung → Pixel notices (listener + push).
3. Pixel shows QR → Samsung scans it → paired.
4. Pixel shares link → open it on Samsung → confirmation dialog → paired.
5. Email invite from Pixel → appears on Samsung's Home banner → accepted.
6. Expired code (manually aged in Firestore) → clear "expired" message.
7. Redeem a code while already paired → "already paired" message, no state corruption.
8. Kill the app on Pixel, accept from Samsung → Pixel gets a push.

## Out of scope

- App Links / a real domain (none available).
- Invitation by phone number.
- More than one co-parent per account — the model is a single `partnerId`.
- Two-sided unpair approval (explicitly decided against).
- Migrating the Cloud Functions codebase from the v1 to the v2 API.
