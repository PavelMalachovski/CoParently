# Signing in with an account the phone has never seen — design

**Date:** 22 August 2026
**Branch:** `feat/auth-google-and-password-2026-08`
**Base:** `main` @ `14e00cbe`

Two complaints about the first screen of the app, and the cleanup they drag in:

1. **Sign in with Google only offers accounts already added to the device.** There is no way to
   sign in with an account the phone has never seen.
2. **Nothing offers to remember the password.** Every launch after a sign-out is a retyping
   exercise.

This is package **A** of a nineteen-item improvement list. The remaining packages (onboarding
questionnaire, calendar/custody interactions, approval flows, home restructure, expense
analytics, guest access) get their own specs.

---

## 1. What exists today

`AuthScreen` offers three ways in, of which two work:

| Path | State |
|---|---|
| Email + password sign-in | Works. `FirebaseAuthService.signInWithEmail`. |
| Email + password sign-up | Works. `FirebaseAuthService.createAccountWithEmail`. |
| Google | Works, but only for accounts already on the device — see §2. |
| "Forgot Password?" | `onClick = { /* TODO: Implement password reset */ }`. Does nothing. |

Four defects sit in the two files this change touches.

**The screen is not localized.** `AuthScreen.kt` contains **zero** `stringResource` calls: every
label is an English literal. Meanwhile `res/values/auth_strings.xml` exists — with `values-cs`,
`values-de`, `values-ru` and `values-uk` variants — and carries a key for every one of those
labels (`auth_email_label`, `auth_action_sign_in`, `auth_forgot_password_link`, …). The
translations were written and never wired up. A device set to Russian shows an English login
screen while the Russian strings sit unreferenced in the APK.

**The mapped Google error is overwritten by the raw one.** `AuthViewModel.signInWithGoogle`
catches `GetCredentialException`, maps it through `mapGoogleAuthError` to a readable sentence,
and stores it in `uiState.errorMessage`. The caller at `AuthScreen.kt:277` then calls
`updateErrorMessage(error.message ?: …)` on the same failure, replacing the prepared sentence
with the exception's own text. The friendly mapping is written and never seen.

**Error text is assembled inside the ViewModel.** `"Sign in failed"`, `"Please fill in all
fields"` and the `mapGoogleAuthError` results are English string literals in `AuthViewModel`.
CLAUDE.md lists this class of defect as a tracked follow-up and forbids the obvious shortcut:
*Don't inject `Context` into ViewModels ad hoc to "fix" one.*

**The web client ID has two sources of truth.** `AuthViewModel.createGoogleSignInRequest`
hardcodes `492948924829-….apps.googleusercontent.com` inline; `CredentialManagerService`
reads `R.string.default_web_client_id` for the same value. Out of scope for this package
(recorded in §7), but noted so the next person does not assume one is authoritative.

## 2. Why Google sign-in is limited to device accounts

`AuthViewModel.createGoogleSignInRequest` builds a `GetGoogleIdOption`:

```kotlin
GetGoogleIdOption.Builder()
    .setServerClientId("492948924829-…")
    .setFilterByAuthorizedAccounts(false) // Allow new accounts
    .build()
```

The comment on that flag describes something it does not do. `setFilterByAuthorizedAccounts(false)`
widens the list from *accounts that have previously authorized this app* to *all Google accounts
on the device*. It does not add a way to introduce an account the device does not have —
`GetGoogleIdOption` renders a bottom sheet populated from the Android account list, and that sheet
has no "add another account" entry.

The fix is a different credential option, not a different flag. **`GetSignInWithGoogleOption`** is
the "Sign in with Google button" flow: it launches Google's own full-screen sign-in, which
includes adding an account. Verified present in the resolved artifact —
`com/google/android/libraries/identity/googleid/GetSignInWithGoogleOption.class` in
`googleid-1.1.1.aar`. No dependency bump.

The credential it returns is the same `CustomCredential` of type
`GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`, so `handleCredentialResult` and the
id-token to Firebase exchange are unchanged.

**A second, unrelated cause was ruled out.** An OAuth consent screen still in *Testing* mode also
limits sign-in to an allowlist, and no code change fixes it
(`docs/google-oauth-testing-mode.md`). Its symptom is a distinct message about
"developer-approved testers", which is not what was observed. This spec assumes the consent
screen is not the constraint; if that message ever appears, the fix is in Google Cloud Console.

## 3. Activity context is now mandatory

`AuthViewModel` is injected with `@ApplicationContext` and passes it to
`credentialManager.getCredential(context, request)`. Credential Manager wants the **Activity**
context — it is what the UI is hosted on. A bottom sheet happens to survive an application
context on many devices; a full-screen sign-in flow is exactly the case that does not.

`androidx.activity:activity-compose` is at 1.9.3, which predates `LocalActivity` (added in
1.10.0). Rather than bump it for one accessor, a `Context.findActivity()` extension unwraps
`ContextWrapper` until it reaches an `Activity`. The project already casts `LocalContext.current`
to `Activity` in `AdaptiveDimensions.kt` and `Theme.kt`, so this formalises an existing move
instead of introducing one.

The `Activity` is passed **per call**, never stored: a ViewModel outlives the Activity and
holding a reference leaks it.

## 4. The change

### 4.1 `CredentialAuthenticator` — a new data-layer wrapper

`data/remote/google/CredentialAuthenticator.kt`. Three suspend operations, each taking an
`Activity`:

| Operation | Credential Manager call | Returns |
|---|---|---|
| `signInWithGoogle(activity)` | `getCredential` + `GetSignInWithGoogleOption` | Google id token |
| `savePassword(activity, email, password)` | `createCredential` + `CreatePasswordRequest` | whether the user accepted |
| `getSavedPassword(activity)` | `getCredential` + `GetPasswordOption` | email + password, or absent |

Why a new class rather than an addition to `CredentialManagerService`: that class is 340 lines
about a different problem — OAuth access/refresh tokens for the Google **Calendar** API, obtained
through the legacy `GoogleSignInClient`. Signing into the app and authorizing calendar access are
separate concerns that happen to name the same vendor. Merging them is how that file got to 340
lines.

Why not leave the calls in the ViewModel: `androidx.credentials` types in a ViewModel make it
untestable, and the operation count goes from one to three.

### 4.2 Sign in with Google

`GetGoogleIdOption` becomes `GetSignInWithGoogleOption`, called with an `Activity`. Everything
downstream of the returned credential is untouched.

### 4.3 Offer to save the password

After `signInWithEmail` **or** `createAccountWithEmail` succeeds, and **before** navigating
away, call `savePassword`. The ordering matters: navigating first puts the system's save sheet
over the home screen, attached to a screen that has nothing to do with it.

Declining is not a failure. `CreateCredentialCancellationException` — and every other failure of
the save — is swallowed and the sign-in proceeds. **A password manager must never be able to
block a successful authentication.**

### 4.4 Sign in with a saved password

A "Sign in with saved password" button, below the Google button, shown **only in sign-in mode**
(offering a saved password while creating an account is nonsense). It calls `getSavedPassword`
and feeds the returned pair into the same `signInWithEmail` path — no second authentication route
to keep in sync.

Deliberately a button and not an automatic sheet on screen entry. Credential Manager can show
saved passwords and Google accounts in one sheet the moment the screen appears, which is one tap
faster; it also covers the screen every single time, including immediately after a deliberate
sign-out, when the user's intent is the opposite. The button keeps the system UI behind an
explicit request.

`NoCredentialException` here means "nothing saved yet" and gets its own message rather than
silence.

### 4.5 Typed errors

`AuthUiState.errorMessage: String?` becomes `error: AuthError?`.

```
AuthError
  EmptyFields | InvalidCredentials | EmailAlreadyInUse | WeakPassword | InvalidEmail
  Network | TooManyRequests
  GoogleCancelled | GoogleInterrupted | GoogleNoAccount | GoogleFailed
  NoSavedPassword
  Unknown
```

`AuthError.from(Throwable)` maps `FirebaseAuthException` error codes and `GetCredentialException`
subtypes. It is pure Kotlin with no Android dependency, so it is covered by an ordinary JVM test.

`AuthErrorText.kt` holds `@Composable fun AuthError.text(): String` — the type becomes a string
in the composable, where `stringResource` is legal. This is the shape CLAUDE.md's follow-up note
asks for, applied to one screen rather than to every ViewModel at once.

The double-write defect from §1 disappears with the type change: `AuthScreen` no longer has a
raw message to overwrite the mapped one with.

### 4.6 Localization

`AuthScreen.kt` moves to `stringResource` throughout, consuming the `auth_strings.xml` keys that
already exist in all five locales. New keys — the saved-password button, its empty case, and one
per `AuthError` variant — are added to `values`, `values-cs`, `values-de`, `values-ru` and
`values-uk` in the same commit.

`MissingTranslation` lint is disabled project-wide and will not catch an omission; completeness is
verified by grep (§6).

### 4.7 "Forgot Password?" is removed

The button is a `TODO` that does nothing, which the August 2026 design refresh forbids outright:
*No affordance may promise a feature that doesn't exist.* Password reset was scoped out of this
package, so the button goes rather than staying as a lie.
`FirebaseAuthService.sendPasswordResetEmail` already exists and is untouched, so restoring the
button is a small piece of work whenever it is scheduled (§7).

## 5. What is deliberately unchanged

- **`CredentialManagerService`** and the Google Calendar OAuth path. Different concern, working.
- **`FirebaseAuthService`.** Every method this package needs is already there.
- **Firestore rules, the user document, pairing.** Authentication only; nothing downstream of a
  successful sign-in moves.
- **Error text in every *other* ViewModel.** `AuthError` is not a project-wide resource-provider
  abstraction and does not pretend to be one.

## 6. Verification

| Check | How |
|---|---|
| Error mapping | `AuthErrorTest` — Firebase error codes and `GetCredentialException` subtypes map to the expected `AuthError`. Pure JVM. |
| Locale completeness | `git grep -c 'name="<key>"' -- app/src/main/res/values*/*.xml` returns five files for every new key. |
| Build and static analysis | `./gradlew assembleDebug testDebugUnitTest lint detekt` |

Credential Manager itself cannot be unit-tested. It needs a device run:

1. Sign in with a Google account **not** on the device — the picker must offer to add one.
2. Sign in with email + password — the system must offer to save it.
3. Decline the save — sign-in must still complete.
4. Sign out, then "Sign in with saved password" — must sign in without typing.
5. Same button on a device with nothing saved — must say so, not fail silently.
6. Switch the device to Russian — the whole screen, errors included, must be Russian.

## 7. Backlog this creates

- **Password reset.** `sendPasswordResetEmail` exists; the entry point was removed in §4.7.
- **Two sources of truth for the web client ID** (§1). Pick one — most likely
  `R.string.default_web_client_id` — and delete the literal.
- **`androidx.credentials` resolves to `1.3.0-beta01`, not the declared `1.2.2`.** `googleid:1.1.1`
  pulls it up. A beta ships in production while the build file names a stable version. Decide
  whether to declare the beta explicitly or hold `googleid` back.
