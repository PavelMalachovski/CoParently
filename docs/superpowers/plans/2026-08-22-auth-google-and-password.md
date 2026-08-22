# Auth: Google for unknown accounts, and remembering the password — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a parent sign in with a Google account the phone has never seen, and have the system offer to remember their password — with the login screen finally speaking the user's language.

**Architecture:** Credential Manager calls move out of `AuthViewModel` into a new data-layer
`CredentialAuthenticator`, which takes an `Activity` per call. `AuthUiState` carries a typed
`AuthError` instead of an English `String`, and `AuthScreen` turns that type into text with
`stringResource`, using the `auth_strings.xml` translations that already exist in five locales
and are currently referenced by nothing.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Hilt, Firebase Auth,
`androidx.credentials` + `com.google.android.libraries.identity.googleid`, JUnit 4 + MockK +
kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-08-22-auth-google-and-password-design.md`

## Global Constraints

- **Jetpack Compose only.** Never add an XML layout.
- **minSdk 26.** No `java.time` API added after 26 (`LocalDate.ofInstant` is API 34).
- **detekt `MaxLineLength` is 120**, comments included. Config: `app/config/detekt/detekt.yml`.
- **detekt `TooGenericExceptionCaught` is active and lists `Exception`.** New code must catch
  specific exception types. Do not add to `app/config/detekt/baseline.xml`.
- **KDoc on every public class and function.** Code and comments in **English**.
- **Every new user-facing string goes into all five locales in the same commit:**
  `values`, `values-cs`, `values-de`, `values-ru`, `values-uk`. `MissingTranslation` lint is
  disabled project-wide and will not catch an omission.
- **Never hardcode user-visible text in a composable.** Use `stringResource`.
- **Stateless composables.** State lives in the ViewModel as `StateFlow`.
- **A ViewModel must never store an `Activity`.** It outlives one; holding a reference leaks it.
  `Activity` is a per-call parameter.
- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).
- Build with the JDK at `C:\Program Files\Android\Android Studio1\jbr` — the machine's
  `JAVA_HOME` points at a broken install.

---

### Task 1: `AuthError` — the reason an attempt failed, as a type

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/auth/AuthError.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/auth/AuthErrorTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `sealed interface AuthError` with the twelve `data object` variants listed below, and
  three companion functions:
  - `AuthError.fromEmailPassword(cause: Throwable): AuthError` — never null.
  - `AuthError.fromGoogle(cause: Throwable): AuthError?` — **null means "say nothing"**.
  - `AuthError.fromSavedPassword(cause: Throwable): AuthError?` — same null contract.

The null return is deliberate and load-bearing: a user who dismisses Google's sheet has not hit
an error, and showing them a red card for changing their mind is a defect. Task 4 relies on
`null` meaning "clear the error", not "unknown failure".

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/auth/AuthErrorTest.kt`:

```kotlin
package com.coparently.app.presentation.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The mapping from "what the SDK threw" to "what the parent is told".
 *
 * Two properties this pins. First, a deliberate cancel is not an error: both credential paths
 * return null for it, and a caller that treats null as `Unknown` puts a red card on screen for
 * someone who merely changed their mind. Second, `NoCredentialException` is thrown by both the
 * Google flow and the saved-password flow and means something different in each, which is why
 * the mapping has one entry point per source rather than one shared `from`.
 */
class AuthErrorTest {

    @Test
    fun `a wrong password reads as bad credentials, whichever code Firebase sends`() {
        listOf("ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_CREDENTIAL")
            .forEach { code ->
                assertEquals(
                    AuthError.InvalidCredentials,
                    AuthError.fromEmailPassword(FirebaseAuthException(code, "nope")),
                    "code $code"
                )
            }
    }

    @Test
    fun `sign-up collisions, weak passwords and malformed emails each keep their own message`() {
        assertEquals(
            AuthError.EmailAlreadyInUse,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "x"))
        )
        assertEquals(
            AuthError.WeakPassword,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_WEAK_PASSWORD", "x"))
        )
        assertEquals(
            AuthError.InvalidEmail,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_INVALID_EMAIL", "x"))
        )
        assertEquals(
            AuthError.UserDisabled,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_USER_DISABLED", "x"))
        )
        assertEquals(
            AuthError.TooManyRequests,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "x"))
        )
    }

    @Test
    fun `a network failure is not a credential failure`() {
        assertEquals(
            AuthError.Network,
            AuthError.fromEmailPassword(FirebaseNetworkException("offline"))
        )
        assertEquals(AuthError.Network, AuthError.fromGoogle(FirebaseNetworkException("offline")))
    }

    @Test
    fun `an unrecognised Firebase code degrades to Unknown rather than to a wrong guess`() {
        assertEquals(
            AuthError.Unknown,
            AuthError.fromEmailPassword(FirebaseAuthException("ERROR_SOMETHING_NEW", "x"))
        )
        assertEquals(AuthError.Unknown, AuthError.fromEmailPassword(IOException("disk")))
    }

    @Test
    fun `dismissing either credential sheet is not an error`() {
        assertNull(AuthError.fromGoogle(GetCredentialCancellationException()))
        assertNull(AuthError.fromSavedPassword(GetCredentialCancellationException()))
    }

    @Test
    fun `no credential means no Google account on one path and nothing saved on the other`() {
        assertEquals(AuthError.NoGoogleAccount, AuthError.fromGoogle(NoCredentialException()))
        assertEquals(AuthError.NoSavedPassword, AuthError.fromSavedPassword(NoCredentialException()))
    }

    @Test
    fun `an interrupted sheet is distinguishable from a failed one, because retrying works`() {
        assertEquals(
            AuthError.Interrupted,
            AuthError.fromGoogle(GetCredentialInterruptedException())
        )
        assertEquals(
            AuthError.Interrupted,
            AuthError.fromSavedPassword(GetCredentialInterruptedException())
        )
    }

    @Test
    fun `a Firebase rejection of a Google token still reads as a Firebase problem`() {
        assertEquals(
            AuthError.UserDisabled,
            AuthError.fromGoogle(FirebaseAuthException("ERROR_USER_DISABLED", "x"))
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.auth.AuthErrorTest"
```

Expected: compilation failure — `Unresolved reference: AuthError`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/coparently/app/presentation/auth/AuthError.kt`:

```kotlin
package com.coparently.app.presentation.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException

/**
 * Why an authentication attempt did not succeed, as a type rather than a sentence.
 *
 * The screen used to keep an English `String` in its UI state, assembled inside the ViewModel.
 * That is the one shape CLAUDE.md rules out for localizable text, and it forbids the obvious
 * shortcut too: a ViewModel must not be handed a `Context` to solve it. Carrying the *reason*
 * instead lets `AuthError.messageRes()` name a string resource the composable resolves, where
 * `stringResource` is legal.
 */
sealed interface AuthError {

    /** Email or password left blank. Caught before any network call. */
    data object EmptyFields : AuthError

    /** Wrong password, unknown account, or a credential Firebase would not accept. */
    data object InvalidCredentials : AuthError

    /** Sign-up against an email that already has an account. */
    data object EmailAlreadyInUse : AuthError

    /** Firebase rejected the password as too weak. */
    data object WeakPassword : AuthError

    /** The email is not a well-formed address. */
    data object InvalidEmail : AuthError

    /** The account exists but has been disabled in the Firebase console. */
    data object UserDisabled : AuthError

    /** The device could not reach Firebase. Retrying later is the fix. */
    data object Network : AuthError

    /** Firebase is rate-limiting this account or device. */
    data object TooManyRequests : AuthError

    /** The credential sheet was torn down by something other than the user. Retrying works. */
    data object Interrupted : AuthError

    /** Google's flow produced no account. Distinct from [NoSavedPassword]. */
    data object NoGoogleAccount : AuthError

    /** No password has been saved for this app yet. Distinct from [NoGoogleAccount]. */
    data object NoSavedPassword : AuthError

    /** Anything the mapping does not recognise. */
    data object Unknown : AuthError

    companion object {

        /**
         * Maps a failure of the email/password path.
         *
         * Never null: typing an email and a password has no "user changed their mind" outcome,
         * so every failure here is worth reporting.
         */
        fun fromEmailPassword(cause: Throwable): AuthError = firebaseError(cause) ?: Unknown

        /**
         * Maps a failure of the Google path.
         *
         * @return null when the user dismissed Google's sheet. A deliberate cancel is not an
         *   error, and callers must clear the displayed error rather than substitute [Unknown].
         */
        fun fromGoogle(cause: Throwable): AuthError? = when (cause) {
            is GetCredentialCancellationException -> null
            is GetCredentialInterruptedException -> Interrupted
            is NoCredentialException -> NoGoogleAccount
            else -> firebaseError(cause) ?: Unknown
        }

        /**
         * Maps a failure of the saved-password path.
         *
         * `NoCredentialException` reaches this function and [fromGoogle] alike and means
         * something different in each — "nothing saved yet" here, "no Google account" there.
         * That is why the two are separate entry points rather than one shared mapping.
         *
         * @return null when the user dismissed the sheet, on the same contract as [fromGoogle].
         */
        fun fromSavedPassword(cause: Throwable): AuthError? = when (cause) {
            is GetCredentialCancellationException -> null
            is GetCredentialInterruptedException -> Interrupted
            is NoCredentialException -> NoSavedPassword
            else -> firebaseError(cause) ?: Unknown
        }

        /** @return the Firebase-shaped reason, or null when [cause] is not from Firebase. */
        private fun firebaseError(cause: Throwable): AuthError? = when {
            cause is FirebaseNetworkException -> Network
            cause is FirebaseAuthException -> when (cause.errorCode) {
                "ERROR_WRONG_PASSWORD",
                "ERROR_USER_NOT_FOUND",
                "ERROR_INVALID_CREDENTIAL" -> InvalidCredentials
                "ERROR_EMAIL_ALREADY_IN_USE",
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> EmailAlreadyInUse
                "ERROR_WEAK_PASSWORD" -> WeakPassword
                "ERROR_INVALID_EMAIL" -> InvalidEmail
                "ERROR_USER_DISABLED" -> UserDisabled
                "ERROR_TOO_MANY_REQUESTS" -> TooManyRequests
                else -> Unknown
            }
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.auth.AuthErrorTest"
```

Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/auth/AuthError.kt app/src/test/java/com/coparently/app/presentation/auth/AuthErrorTest.kt
git commit -m "feat(auth): carry the reason a sign-in failed as a type, not a sentence"
```

---

### Task 2: `Context.findActivity()`

**Files:**
- Modify: `app/src/main/java/com/coparently/app/utils/Extensions.kt` (append a new section)
- Test: `app/src/test/java/com/coparently/app/utils/FindActivityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun Context.findActivity(): Activity?` in package `com.coparently.app.utils`.

Credential Manager needs the `Activity` its UI will be hosted on. `androidx.activity:activity-compose`
is at 1.9.3, which predates `LocalActivity` (1.10.0); rather than bump a dependency for one
accessor, unwrap `ContextWrapper` by hand. `AdaptiveDimensions.kt` and `Theme.kt` already cast
`LocalContext.current` to `Activity`, so this replaces a cast that assumes with a walk that checks.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/utils/FindActivityTest.kt`:

```kotlin
package com.coparently.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Compose hands a composable whatever `Context` the tree was created with, which on a themed
 * subtree is a `ContextWrapper` several layers deep rather than the `Activity` itself. A direct
 * cast returns null there; this walks down to the real one.
 */
class FindActivityTest {

    @Test
    fun `an Activity is its own answer`() {
        val activity = mockk<Activity>()
        assertSame(activity, activity.findActivity())
    }

    @Test
    fun `a wrapped Activity is found through however many layers`() {
        val activity = mockk<Activity>()
        val inner = mockk<ContextWrapper> { every { baseContext } returns activity }
        val outer = mockk<ContextWrapper> { every { baseContext } returns inner }

        assertSame(activity, outer.findActivity())
    }

    @Test
    fun `a context chain with no Activity in it returns null instead of throwing`() {
        val application = mockk<Context>()
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns application }

        assertNull(wrapper.findActivity())
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.utils.FindActivityTest"
```

Expected: compilation failure — `Unresolved reference: findActivity`.

- [ ] **Step 3: Write the implementation**

Append to `app/src/main/java/com/coparently/app/utils/Extensions.kt` — add these two imports to
the existing import block:

```kotlin
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
```

and this section at the end of the file:

```kotlin
// ==================== Context Extensions ====================

/**
 * Walks down a `Context` chain to the [Activity] hosting it.
 *
 * Needed because Credential Manager renders its UI on an `Activity` and refuses an application
 * context, while Compose hands a composable whatever context its tree was built with — often a
 * `ContextWrapper` wrapping the `Activity` rather than the `Activity` itself.
 *
 * @return the hosting [Activity], or null when this context is not attached to one.
 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        when {
            current is Activity -> return current
            current is ContextWrapper -> current = current.baseContext
            else -> return null
        }
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.utils.FindActivityTest"
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/utils/Extensions.kt app/src/test/java/com/coparently/app/utils/FindActivityTest.kt
git commit -m "feat(utils): find the hosting Activity through a wrapped Context"
```

---

### Task 3: `CredentialAuthenticator`

**Files:**
- Create: `app/src/main/java/com/coparently/app/data/remote/google/CredentialAuthenticator.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, for Task 4:
  - `class CredentialAuthenticator @Inject constructor(@ApplicationContext context: Context)`,
    `@Singleton`, constructor-injected — **no Hilt module needed**.
  - `data class SavedPassword(val email: String, val password: String)` in the same file.
  - `suspend fun signInWithGoogle(activity: Activity): Result<String>` — the Google **id token**.
  - `suspend fun savePassword(activity: Activity, email: String, password: String)` — returns
    `Unit`, swallows every failure.
  - `suspend fun getSavedPassword(activity: Activity): Result<SavedPassword>`.

**No unit test.** Every method's body is a call into Credential Manager, which needs a real device
and a real system UI; a test here would only assert that MockK returns what MockK was told to
return. The behaviour worth pinning — that a declined save does not block sign-in — is a property
of the *caller* and is tested in Task 4. The device checklist in Task 7 covers the rest.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/coparently/app/data/remote/google/CredentialAuthenticator.kt`:

```kotlin
package com.coparently.app.data.remote.google

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.coparently.app.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** An email and password handed back by the system's password manager. */
data class SavedPassword(val email: String, val password: String)

/**
 * The three Credential Manager operations that get a parent into the app.
 *
 * Deliberately separate from [CredentialManagerService], which is 340 lines about a different
 * problem: OAuth access and refresh tokens for the Google **Calendar** API, obtained through the
 * legacy `GoogleSignInClient`. Signing into the app and authorizing calendar access are separate
 * concerns that happen to name the same vendor, and merging them is how that file reached 340
 * lines.
 *
 * Every call takes the [Activity] rather than reading a stored one. Credential Manager hosts its
 * UI on an `Activity`, and the caller is a ViewModel, which outlives one — a stored reference
 * would leak it.
 */
@Singleton
class CredentialAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    /**
     * Runs Google's full-screen sign-in.
     *
     * Uses [GetSignInWithGoogleOption], **not** `GetGoogleIdOption`. The latter renders a bottom
     * sheet populated from the Android account list, with no way to introduce an account the
     * device does not have; `setFilterByAuthorizedAccounts(false)` widens that list to every
     * device account but adds no such entry, which is the whole bug this replaces.
     *
     * @param activity Activity to host Google's UI on
     * @return the Google id token to exchange for a Firebase session
     */
    suspend fun signInWithGoogle(activity: Activity): Result<String> = try {
        val option = GetSignInWithGoogleOption
            .Builder(context.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = credentialManager.getCredential(activity, request).credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Result.success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
        }
    } catch (e: GetCredentialException) {
        Result.failure(e)
    } catch (e: GoogleIdTokenParsingException) {
        Result.failure(e)
    }

    /**
     * Asks the system to remember [email] and [password].
     *
     * Returns [Unit] and swallows every failure on purpose. The user declining the save, or the
     * device having no password manager at all, must not turn a **successful** authentication
     * into a failed one. The caller has already signed in by the time this runs; there is nothing
     * here for it to act on.
     *
     * @param activity Activity to host the save prompt on
     */
    suspend fun savePassword(activity: Activity, email: String, password: String) {
        try {
            credentialManager.createCredential(activity, CreatePasswordRequest(email, password))
        } catch (e: CreateCredentialException) {
            Log.d(TAG, "Password not saved: ${e.type}")
        }
    }

    /**
     * Offers the passwords the system has stored for this app.
     *
     * @param activity Activity to host the picker on
     * @return the chosen credential, or a failure carrying `NoCredentialException` when nothing
     *   is stored — which the caller reports as "nothing saved yet", not as a crash
     */
    suspend fun getSavedPassword(activity: Activity): Result<SavedPassword> = try {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPasswordOption())
            .build()
        when (val credential = credentialManager.getCredential(activity, request).credential) {
            is PasswordCredential ->
                Result.success(SavedPassword(credential.id, credential.password))
            else -> Result.failure(NoCredentialException())
        }
    } catch (e: GetCredentialException) {
        Result.failure(e)
    }

    private companion object {
        const val TAG = "CredentialAuth"
    }
}
```

- [ ] **Step 2: Confirm it compiles**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If `default_web_client_id` is unresolved, confirm it is still at
`app/src/main/res/values/strings.xml:175`.

- [ ] **Step 3: Confirm detekt is clean on the new file**

```bash
./gradlew detekt
```

Expected: no new findings. `TooGenericExceptionCaught` is active and lists `Exception`, which is
why every catch above names a specific type.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/remote/google/CredentialAuthenticator.kt
git commit -m "feat(auth): add a credential wrapper that can reach accounts not on the device"
```

---

### Task 4: Rewire `AuthViewModel`

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/auth/AuthViewModel.kt` (rewrite)
- Test: `app/src/test/java/com/coparently/app/presentation/auth/AuthViewModelTest.kt`

**Interfaces:**
- Consumes: `AuthError` (Task 1); `CredentialAuthenticator`, `SavedPassword` (Task 3).
- Produces, for Task 6:
  - `data class AuthUiState(email, password, isSignInMode, isLoading, error: AuthError?)` —
    the `errorMessage: String?` property **is gone**.
  - `fun signIn(activity: Activity, onSuccess: () -> Unit)`
  - `fun signUp(activity: Activity, onSuccess: () -> Unit)`
  - `suspend fun signInWithGoogle(activity: Activity)` — returns `Unit`; the screen no longer
    folds a `Result`.
  - `suspend fun signInWithSavedPassword(activity: Activity)` — returns `Unit`.
  - Unchanged: `updateEmail`, `updatePassword`, `toggleSignInMode`, `onAuthStateChanged`,
    `onAuthSuccess`.

Three defects close here. `credentialManagerService` is injected and **never referenced** — a dead
dependency, removed. `@ApplicationContext context` was only there to feed Credential Manager and
goes with it. And `signInWithGoogle` returning a `Result` the screen then re-reported is what let
`AuthScreen.kt:277` overwrite the mapped message with the raw one; returning `Unit` removes the
second reporter rather than asking it to behave.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/auth/AuthViewModelTest.kt`:

```kotlin
package com.coparently.app.presentation.auth

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.google.CredentialAuthenticator
import com.coparently.app.data.remote.google.SavedPassword
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the login screen must do around the system's password manager.
 *
 * The load-bearing case is `a declined password save still signs the parent in`: offering to
 * remember a password happens *after* Firebase has already accepted the credentials, so a refusal
 * — or a device with no password manager at all — must not turn a successful authentication into
 * a failed one. Getting that backwards locks out the user who taps "Never".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var firebaseAuthService: FirebaseAuthService
    private lateinit var credentialAuthenticator: CredentialAuthenticator
    private lateinit var crashlyticsManager: CrashlyticsManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        firebaseAuthService = mockk()
        credentialAuthenticator = mockk(relaxed = true)
        crashlyticsManager = mockk(relaxed = true)
        viewModel = AuthViewModel(
            firebaseAuthService = firebaseAuthService,
            credentialAuthenticator = credentialAuthenticator,
            analyticsManager = mockk(relaxed = true),
            crashlyticsManager = crashlyticsManager
        )
        viewModel.updateEmail("parent@example.com")
        viewModel.updatePassword("hunter2")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a declined password save still signs the parent in`() = runTest(dispatcher) {
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())
        // savePassword swallows its own failures and returns Unit, so a decline is
        // indistinguishable here from an accept - which is exactly the contract under test.
        coEvery { credentialAuthenticator.savePassword(any(), any(), any()) } returns Unit

        var signedIn = false
        viewModel.signIn(activity) { signedIn = true }
        advanceUntilIdle()

        assertTrue(signedIn, "sign-in must complete regardless of the save prompt")
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the save is offered before navigating away, not after`() = runTest(dispatcher) {
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())
        // Record the real order rather than asserting inside a callback: if navigation ran
        // first, the system's save sheet would land on top of the home screen.
        val order = mutableListOf<String>()
        coEvery { credentialAuthenticator.savePassword(activity, "parent@example.com", "hunter2") }
            .answers { order += "save" }

        viewModel.signIn(activity) { order += "navigate" }
        advanceUntilIdle()

        assertEquals(listOf("save", "navigate"), order)
    }

    @Test
    fun `blank fields are rejected without troubling Firebase`() = runTest(dispatcher) {
        viewModel.updatePassword("")

        viewModel.signIn(activity) { error("must not navigate") }
        advanceUntilIdle()

        assertEquals(AuthError.EmptyFields, viewModel.uiState.value.error)
        coVerify(exactly = 0) { firebaseAuthService.signInWithEmail(any(), any()) }
    }

    @Test
    fun `a Firebase rejection lands in the state as a type and clears loading`() =
        runTest(dispatcher) {
            coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
                Result.failure(FirebaseAuthException("ERROR_WRONG_PASSWORD", "nope"))

            viewModel.signIn(activity) { error("must not navigate") }
            advanceUntilIdle()

            assertEquals(AuthError.InvalidCredentials, viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `dismissing Google's sheet leaves no error on screen`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.signInWithGoogle(any()) } returns
            Result.failure(GetCredentialCancellationException())

        viewModel.signInWithGoogle(activity)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error, "a deliberate cancel is not an error")
        assertFalse(viewModel.uiState.value.isLoading)
        // Nor is it a fault worth reporting: a stream of cancellations would bury real ones.
        verify(exactly = 0) { crashlyticsManager.recordExceptionWithContext(any(), any()) }
    }

    @Test
    fun `a saved password is fed through the same email sign-in path`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.success(SavedPassword("saved@example.com", "s3cret"))
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        coVerify { firebaseAuthService.signInWithEmail("saved@example.com", "s3cret") }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `having nothing saved says so rather than failing silently`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.failure(NoCredentialException())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        assertEquals(AuthError.NoSavedPassword, viewModel.uiState.value.error)
    }

    @Test
    fun `signing in with a saved password does not re-offer to save it`() = runTest(dispatcher) {
        coEvery { credentialAuthenticator.getSavedPassword(any()) } returns
            Result.success(SavedPassword("saved@example.com", "s3cret"))
        coEvery { firebaseAuthService.signInWithEmail(any(), any()) } returns
            Result.success(mockk<FirebaseUser>())

        viewModel.signInWithSavedPassword(activity)
        advanceUntilIdle()

        coVerify(exactly = 0) { credentialAuthenticator.savePassword(any(), any(), any()) }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.auth.AuthViewModelTest"
```

Expected: compilation failure — the constructor still takes `credentialManagerService` and
`context`, and `signIn` takes no `Activity`.

- [ ] **Step 3: Rewrite `AuthViewModel.kt`**

Replace the whole file with:

```kotlin
package com.coparently.app.presentation.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.google.CredentialAuthenticator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the login screen: the four ways in, and why the last attempt failed.
 *
 * Every credential operation takes the [Activity] as a parameter. Credential Manager hosts its UI
 * on one, and a ViewModel outlives an Activity — storing the reference would leak it.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firebaseAuthService: FirebaseAuthService,
    private val credentialAuthenticator: CredentialAuthenticator,
    private val analyticsManager: AnalyticsManager,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    /** Invoked after any successful authentication so the host can refresh its auth state. */
    var onAuthStateChanged: (() -> Unit)? = null

    /** Invoked after authentication paths that navigate on their own. */
    var onAuthSuccess: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** @param email New email field value */
    fun updateEmail(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    /** @param password New password field value */
    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    /** Flips between the sign-in and sign-up presentations of the same form. */
    fun toggleSignInMode() {
        _uiState.update { it.copy(isSignInMode = !it.isSignInMode, error = null) }
    }

    /**
     * Signs in with the typed email and password, then offers to remember them.
     *
     * @param activity Activity to host the save prompt on
     * @param onSuccess Runs after the save prompt has been dealt with — see [completeSignIn]
     */
    fun signIn(activity: Activity, onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = AuthError.EmptyFields) }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            firebaseAuthService.signInWithEmail(state.email, state.password).fold(
                onSuccess = {
                    analyticsManager.logLogin("email")
                    completeSignIn(activity, state.email, state.password, onSuccess)
                },
                onFailure = { error -> reportEmailFailure(error, "sign_in", state.email) }
            )
        }
    }

    /**
     * Creates an account with the typed email and password, then offers to remember them.
     *
     * @param activity Activity to host the save prompt on
     * @param onSuccess Runs after the save prompt has been dealt with
     */
    fun signUp(activity: Activity, onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = AuthError.EmptyFields) }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            firebaseAuthService.createAccountWithEmail(state.email, state.password).fold(
                onSuccess = {
                    analyticsManager.logSignUp("email")
                    completeSignIn(activity, state.email, state.password, onSuccess)
                },
                onFailure = { error -> reportEmailFailure(error, "sign_up", state.email) }
            )
        }
    }

    /**
     * Runs Google's full-screen sign-in and exchanges the id token for a Firebase session.
     *
     * Reports its own failures into [uiState] and returns nothing. It used to return a `Result`
     * that the screen reported a second time, overwriting the mapped message with the raw
     * exception text — removing the second reporter is the fix.
     *
     * @param activity Activity to host Google's UI on
     */
    suspend fun signInWithGoogle(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        credentialAuthenticator.signInWithGoogle(activity).fold(
            onSuccess = { idToken ->
                firebaseAuthService.signInWithGoogleIdToken(idToken).fold(
                    onSuccess = {
                        analyticsManager.logLogin("google")
                        _uiState.update { it.copy(isLoading = false) }
                        onAuthStateChanged?.invoke()
                        onAuthSuccess?.invoke()
                    },
                    onFailure = { error -> reportCredentialFailure(error, "firebase_google_auth") }
                )
            },
            onFailure = { error -> reportCredentialFailure(error, "google_sign_in") }
        )
    }

    /**
     * Offers the passwords the system has stored, and signs in with the chosen one.
     *
     * Feeds the result into the same [FirebaseAuthService.signInWithEmail] path the typed form
     * uses, so there is no second authentication route to keep in step. Deliberately does **not**
     * offer to save the password afterwards: it came from the store it would be saved back into.
     *
     * @param activity Activity to host the picker on
     */
    suspend fun signInWithSavedPassword(activity: Activity) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        credentialAuthenticator.getSavedPassword(activity).fold(
            onSuccess = { saved ->
                firebaseAuthService.signInWithEmail(saved.email, saved.password).fold(
                    onSuccess = {
                        analyticsManager.logLogin("saved_password")
                        _uiState.update { it.copy(isLoading = false, email = saved.email) }
                        onAuthStateChanged?.invoke()
                        onAuthSuccess?.invoke()
                    },
                    onFailure = { error ->
                        crashlyticsManager.recordExceptionWithContext(
                            error,
                            mapOf("action" to "saved_password_sign_in")
                        )
                        _uiState.update {
                            it.copy(isLoading = false, error = AuthError.fromEmailPassword(error))
                        }
                    }
                )
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = AuthError.fromSavedPassword(error))
                }
            }
        )
    }

    /**
     * Offers to remember the credentials, **then** hands control to [onSuccess].
     *
     * The order is the point. Navigating first puts the system's save sheet over the home
     * screen, attached to a screen that has nothing to do with it. `savePassword` never throws,
     * so a decline cannot strand the user on the login screen after Firebase has already
     * accepted them.
     */
    private suspend fun completeSignIn(
        activity: Activity,
        email: String,
        password: String,
        onSuccess: () -> Unit
    ) {
        credentialAuthenticator.savePassword(activity, email, password)
        _uiState.update { it.copy(isLoading = false) }
        onAuthStateChanged?.invoke()
        onSuccess()
    }

    private fun reportEmailFailure(error: Throwable, action: String, email: String) {
        crashlyticsManager.recordExceptionWithContext(
            error,
            mapOf("action" to action, "email" to email)
        )
        _uiState.update { it.copy(isLoading = false, error = AuthError.fromEmailPassword(error)) }
    }

    private fun reportCredentialFailure(error: Throwable, action: String) {
        val mapped = AuthError.fromGoogle(error)
        // A null mapping means the user dismissed the sheet. That is a decision, not a fault, and
        // recording it would bury real failures under a stream of cancellations.
        if (mapped != null) {
            crashlyticsManager.recordExceptionWithContext(error, mapOf("action" to action))
        }
        _uiState.update { it.copy(isLoading = false, error = mapped) }
    }
}

/**
 * UI state for the authentication screen.
 *
 * @property email Current email field value
 * @property password Current password field value
 * @property isSignInMode True for sign-in, false for sign-up
 * @property isLoading Whether an attempt is in flight
 * @property error Why the last attempt failed, or null. A *typed* reason rather than a sentence:
 *   the string is resolved in the composable, because a ViewModel has no `Context` and must not
 *   be given one.
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isSignInMode: Boolean = true,
    val isLoading: Boolean = false,
    val error: AuthError? = null
)
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.auth.AuthViewModelTest"
```

Expected: 8 tests PASS. The build still fails to assemble at this point — `AuthScreen.kt` reads
`uiState.errorMessage` and calls `signIn(onSuccess)`. Task 6 fixes it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/auth/AuthViewModel.kt app/src/test/java/com/coparently/app/presentation/auth/AuthViewModelTest.kt
git commit -m "feat(auth): offer to remember the password, and stop reporting failures twice"
```

---

### Task 5: Strings in five locales, and `AuthErrorText`

**Files:**
- Modify: `app/src/main/res/values/auth_strings.xml`
- Modify: `app/src/main/res/values-cs/auth_strings.xml`
- Modify: `app/src/main/res/values-de/auth_strings.xml`
- Modify: `app/src/main/res/values-ru/auth_strings.xml`
- Modify: `app/src/main/res/values-uk/auth_strings.xml`
- Create: `app/src/main/java/com/coparently/app/presentation/auth/AuthErrorText.kt`

**Interfaces:**
- Consumes: `AuthError` (Task 1).
- Produces, for Task 6: `@StringRes fun AuthError.messageRes(): Int`.

Thirteen new keys: one per `AuthError` variant, plus the saved-password button. The existing 21
keys in each file are untouched — they already carry every label `AuthScreen` needs and are simply
not referenced yet.

**Do not delete `auth_forgot_password_link`.** Task 6 removes the button that used it, but the key
stays: password reset is a recorded backlog item (spec §7) and deleting five translations to
re-add them later is churn.

- [ ] **Step 1: Add the English keys**

Insert before `</resources>` in `app/src/main/res/values/auth_strings.xml`:

```xml
    <string name="auth_saved_password_sign_in">Sign in with saved password</string>
    <string name="auth_error_empty_fields">Please fill in both fields</string>
    <string name="auth_error_invalid_credentials">Wrong email or password</string>
    <string name="auth_error_email_already_in_use">This email already has an account</string>
    <string name="auth_error_weak_password">Password is too weak — use at least 6 characters</string>
    <string name="auth_error_invalid_email">That is not a valid email address</string>
    <string name="auth_error_user_disabled">This account has been disabled</string>
    <string name="auth_error_network">No connection. Check your network and try again.</string>
    <string name="auth_error_too_many_requests">Too many attempts. Try again in a few minutes.</string>
    <string name="auth_error_interrupted">Sign-in was interrupted. Please try again.</string>
    <string name="auth_error_no_google_account">No Google account was chosen</string>
    <string name="auth_error_no_saved_password">No saved password for CoPlanly yet</string>
    <string name="auth_error_unknown">Something went wrong. Please try again.</string>
```

- [ ] **Step 2: Add the Czech keys**

Insert before `</resources>` in `app/src/main/res/values-cs/auth_strings.xml`:

```xml
    <string name="auth_saved_password_sign_in">Přihlásit se uloženým heslem</string>
    <string name="auth_error_empty_fields">Vyplňte prosím obě pole</string>
    <string name="auth_error_invalid_credentials">Nesprávný e-mail nebo heslo</string>
    <string name="auth_error_email_already_in_use">K tomuto e-mailu už účet existuje</string>
    <string name="auth_error_weak_password">Heslo je příliš slabé — použijte alespoň 6 znaků</string>
    <string name="auth_error_invalid_email">Toto není platná e-mailová adresa</string>
    <string name="auth_error_user_disabled">Tento účet byl zablokován</string>
    <string name="auth_error_network">Bez připojení. Zkontrolujte síť a zkuste to znovu.</string>
    <string name="auth_error_too_many_requests">Příliš mnoho pokusů. Zkuste to za pár minut.</string>
    <string name="auth_error_interrupted">Přihlášení bylo přerušeno. Zkuste to prosím znovu.</string>
    <string name="auth_error_no_google_account">Nebyl vybrán žádný účet Google</string>
    <string name="auth_error_no_saved_password">Pro CoPlanly zatím není uloženo žádné heslo</string>
    <string name="auth_error_unknown">Něco se pokazilo. Zkuste to prosím znovu.</string>
```

- [ ] **Step 3: Add the German keys**

Insert before `</resources>` in `app/src/main/res/values-de/auth_strings.xml`:

```xml
    <string name="auth_saved_password_sign_in">Mit gespeichertem Passwort anmelden</string>
    <string name="auth_error_empty_fields">Bitte fülle beide Felder aus</string>
    <string name="auth_error_invalid_credentials">Falsche E-Mail oder falsches Passwort</string>
    <string name="auth_error_email_already_in_use">Für diese E-Mail gibt es bereits ein Konto</string>
    <string name="auth_error_weak_password">Passwort zu schwach — mindestens 6 Zeichen</string>
    <string name="auth_error_invalid_email">Das ist keine gültige E-Mail-Adresse</string>
    <string name="auth_error_user_disabled">Dieses Konto wurde deaktiviert</string>
    <string name="auth_error_network">Keine Verbindung. Prüfe dein Netzwerk und versuche es erneut.</string>
    <string name="auth_error_too_many_requests">Zu viele Versuche. Versuche es in ein paar Minuten erneut.</string>
    <string name="auth_error_interrupted">Die Anmeldung wurde unterbrochen. Bitte versuche es erneut.</string>
    <string name="auth_error_no_google_account">Es wurde kein Google-Konto ausgewählt</string>
    <string name="auth_error_no_saved_password">Für CoPlanly ist noch kein Passwort gespeichert</string>
    <string name="auth_error_unknown">Etwas ist schiefgelaufen. Bitte versuche es erneut.</string>
```

- [ ] **Step 4: Add the Russian keys**

Insert before `</resources>` in `app/src/main/res/values-ru/auth_strings.xml`:

```xml
    <string name="auth_saved_password_sign_in">Войти сохранённым паролем</string>
    <string name="auth_error_empty_fields">Заполните оба поля</string>
    <string name="auth_error_invalid_credentials">Неверная почта или пароль</string>
    <string name="auth_error_email_already_in_use">На эту почту уже есть аккаунт</string>
    <string name="auth_error_weak_password">Пароль слишком простой — нужно хотя бы 6 символов</string>
    <string name="auth_error_invalid_email">Это не похоже на адрес электронной почты</string>
    <string name="auth_error_user_disabled">Этот аккаунт заблокирован</string>
    <string name="auth_error_network">Нет соединения. Проверьте сеть и попробуйте снова.</string>
    <string name="auth_error_too_many_requests">Слишком много попыток. Попробуйте через несколько минут.</string>
    <string name="auth_error_interrupted">Вход прервался. Попробуйте ещё раз.</string>
    <string name="auth_error_no_google_account">Аккаунт Google не выбран</string>
    <string name="auth_error_no_saved_password">Для CoPlanly пока нет сохранённых паролей</string>
    <string name="auth_error_unknown">Что-то пошло не так. Попробуйте ещё раз.</string>
```

- [ ] **Step 5: Add the Ukrainian keys**

Insert before `</resources>` in `app/src/main/res/values-uk/auth_strings.xml`:

```xml
    <string name="auth_saved_password_sign_in">Увійти збереженим паролем</string>
    <string name="auth_error_empty_fields">Заповніть обидва поля</string>
    <string name="auth_error_invalid_credentials">Неправильна пошта або пароль</string>
    <string name="auth_error_email_already_in_use">На цю пошту вже є акаунт</string>
    <string name="auth_error_weak_password">Пароль надто простий — потрібно щонайменше 6 символів</string>
    <string name="auth_error_invalid_email">Це не схоже на адресу електронної пошти</string>
    <string name="auth_error_user_disabled">Цей акаунт заблоковано</string>
    <string name="auth_error_network">Немає зʼєднання. Перевірте мережу та спробуйте ще раз.</string>
    <string name="auth_error_too_many_requests">Забагато спроб. Спробуйте за кілька хвилин.</string>
    <string name="auth_error_interrupted">Вхід перервано. Спробуйте ще раз.</string>
    <string name="auth_error_no_google_account">Акаунт Google не вибрано</string>
    <string name="auth_error_no_saved_password">Для CoPlanly ще немає збережених паролів</string>
    <string name="auth_error_unknown">Щось пішло не так. Спробуйте ще раз.</string>
```

- [ ] **Step 6: Write `AuthErrorText.kt`**

Create `app/src/main/java/com/coparently/app/presentation/auth/AuthErrorText.kt`:

```kotlin
package com.coparently.app.presentation.auth

import androidx.annotation.StringRes
import com.coparently.app.R

/**
 * The string resource that explains an [AuthError] to the parent.
 *
 * This is the half of the split that knows about resources. Keeping it out of the ViewModel is
 * what lets that class stay free of a `Context` — the arrangement CLAUDE.md asks for and which
 * the login screen, alone among the redesigned screens, did not have.
 *
 * Deliberately **not** `@Composable`. Returning the resource id rather than the resolved string
 * lets the call site decide when to resolve it, which matters inside `AnimatedVisibility`: its
 * content lambda still runs while the card animates out, at which point the error is already
 * null and there is nothing to resolve.
 *
 * @return the id of the localized message for this error
 */
@StringRes
fun AuthError.messageRes(): Int = when (this) {
    AuthError.EmptyFields -> R.string.auth_error_empty_fields
    AuthError.InvalidCredentials -> R.string.auth_error_invalid_credentials
    AuthError.EmailAlreadyInUse -> R.string.auth_error_email_already_in_use
    AuthError.WeakPassword -> R.string.auth_error_weak_password
    AuthError.InvalidEmail -> R.string.auth_error_invalid_email
    AuthError.UserDisabled -> R.string.auth_error_user_disabled
    AuthError.Network -> R.string.auth_error_network
    AuthError.TooManyRequests -> R.string.auth_error_too_many_requests
    AuthError.Interrupted -> R.string.auth_error_interrupted
    AuthError.NoGoogleAccount -> R.string.auth_error_no_google_account
    AuthError.NoSavedPassword -> R.string.auth_error_no_saved_password
    AuthError.Unknown -> R.string.auth_error_unknown
}
```

- [ ] **Step 7: Verify every new key exists in all five locales**

```bash
for k in auth_saved_password_sign_in auth_error_empty_fields auth_error_invalid_credentials auth_error_email_already_in_use auth_error_weak_password auth_error_invalid_email auth_error_user_disabled auth_error_network auth_error_too_many_requests auth_error_interrupted auth_error_no_google_account auth_error_no_saved_password auth_error_unknown; do echo "$k -> $(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l)"; done
```

Expected: every key prints `-> 5`. Any other number is a missing or duplicated translation —
`MissingTranslation` lint is disabled and will not report it.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/values/auth_strings.xml app/src/main/res/values-cs/auth_strings.xml app/src/main/res/values-de/auth_strings.xml app/src/main/res/values-ru/auth_strings.xml app/src/main/res/values-uk/auth_strings.xml app/src/main/java/com/coparently/app/presentation/auth/AuthErrorText.kt
git commit -m "feat(auth): translate every sign-in failure into all five locales"
```

---

### Task 6: `AuthScreen` — localized, with the saved-password button

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/auth/AuthScreen.kt`

**Interfaces:**
- Consumes: `AuthUiState.error`, `signIn(activity, onSuccess)`, `signUp(activity, onSuccess)`,
  `signInWithGoogle(activity)`, `signInWithSavedPassword(activity)` (Task 4);
  `AuthError.messageRes()` (Task 5); `Context.findActivity()` (Task 2).
- Produces: nothing. `AuthScreen(onAuthSuccess, onViewModelReady, viewModel)` keeps its signature,
  so `NavGraph.kt:127` needs no change.

Three edits, in one pass over the file:

1. **Every literal becomes `stringResource`.** Twenty-one labels, all of which already have keys.
2. **The saved-password button**, between the Google button and the "or" divider, in sign-in mode
   only.
3. **"Forgot Password?" is deleted** — the `TextButton` at the end of the file whose `onClick` is
   `{ /* TODO: Implement password reset */ }`. The August 2026 design refresh forbids an
   affordance that promises a feature which does not exist, and password reset was scoped out of
   this package (spec §4.7, §7).

- [ ] **Step 1: Add the imports**

Add to the import block of `AuthScreen.kt`:

```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.coparently.app.R
import com.coparently.app.utils.findActivity
```

- [ ] **Step 2: Resolve the Activity once, at the top of the composable**

Immediately after `val coroutineScope = rememberCoroutineScope()`, add:

```kotlin
    // Credential Manager hosts its UI on an Activity and refuses an application context.
    // Compose may hand this composable a ContextWrapper, so unwrap rather than cast.
    val activity = LocalContext.current.findActivity()
```

- [ ] **Step 3: Replace every hardcoded label**

Apply these substitutions in order. Each left-hand side appears exactly once in the file.

| Current literal | Replacement |
|---|---|
| `contentDescription = "CoPlanly Logo"` | `contentDescription = stringResource(R.string.auth_cd_logo)` |
| `text = "Shared Calendar for Co-Parenting"` | `text = stringResource(R.string.auth_tagline)` |
| `if (uiState.isSignInMode) "Welcome Back!" else "Create Your Account"` | `if (uiState.isSignInMode) stringResource(R.string.auth_welcome_back) else stringResource(R.string.auth_create_your_account)` |
| `"Sign in to continue managing your co-parenting schedule"` | `stringResource(R.string.auth_sign_in_subtitle)` |
| `"Join thousands of parents working together"` | `stringResource(R.string.auth_sign_up_subtitle)` |
| `label = { Text("Email Address") }` | `label = { Text(stringResource(R.string.auth_email_label)) }` |
| `placeholder = { Text("your@email.com") }` | `placeholder = { Text(stringResource(R.string.auth_email_placeholder)) }` |
| `label = { Text("Password") }` | `label = { Text(stringResource(R.string.auth_password_label)) }` |
| `placeholder = { Text("Enter your password") }` | `placeholder = { Text(stringResource(R.string.auth_password_placeholder)) }` |
| `"Hide password"` | `stringResource(R.string.auth_hide_password)` |
| `"Show password"` | `stringResource(R.string.auth_show_password)` |
| `text = "Sign in with Google"` | `text = stringResource(R.string.auth_google_sign_in)` |
| `text = "or"` | `text = stringResource(R.string.auth_divider_or)` |
| `if (uiState.isSignInMode) "Sign In" else "Create Account"` | `if (uiState.isSignInMode) stringResource(R.string.auth_action_sign_in) else stringResource(R.string.auth_action_create_account)` |
| `if (uiState.isSignInMode) "Don't have an account?" else "Already have an account?"` | `if (uiState.isSignInMode) stringResource(R.string.auth_no_account_question) else stringResource(R.string.auth_have_account_question)` |
| `if (uiState.isSignInMode) "Sign Up" else "Sign In"` | `if (uiState.isSignInMode) stringResource(R.string.auth_action_sign_up) else stringResource(R.string.auth_action_sign_in)` |

- [ ] **Step 4: Render the typed error**

Immediately above the `AnimatedVisibility` error block, resolve the id once:

```kotlin
                    // Resolved outside the block: AnimatedVisibility still runs its content
                    // while the card animates out, when the error is already null.
                    val errorRes = uiState.error?.messageRes()
```

Replace the block's condition:

```kotlin
                    AnimatedVisibility(
                        visible = errorRes != null,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
```

and, inside it, the message — `let` is inline, so `stringResource` stays legal there:

```kotlin
                                Text(
                                    text = errorRes?.let { stringResource(it) }.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
```

- [ ] **Step 5: Rewrite the Google button's `onClick`**

The ViewModel now reports its own failures, so the screen only launches the call:

```kotlin
                    OutlinedButton(
                        onClick = {
                            activity?.let { host ->
                                coroutineScope.launch { viewModel.signInWithGoogle(host) }
                            }
                        },
```

- [ ] **Step 6: Add the saved-password button**

Immediately **after** the Google `OutlinedButton` and **before** the "or" divider `Row`:

```kotlin
                    // Sign-in only: offering a stored password while creating an account is
                    // nonsense. Deliberately a button rather than a sheet that opens with the
                    // screen - the system UI stays behind an explicit request, which matters
                    // most right after a deliberate sign-out.
                    if (uiState.isSignInMode) {
                        TextButton(
                            onClick = {
                                activity?.let { host ->
                                    coroutineScope.launch { viewModel.signInWithSavedPassword(host) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isLoading
                        ) {
                            Text(
                                text = stringResource(R.string.auth_saved_password_sign_in),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
```

- [ ] **Step 7: Pass the Activity to the primary button**

```kotlin
                    Button(
                        onClick = {
                            activity?.let { host ->
                                if (uiState.isSignInMode) {
                                    viewModel.signIn(host, onAuthSuccess)
                                } else {
                                    viewModel.signUp(host, onAuthSuccess)
                                }
                            }
                        },
```

- [ ] **Step 8: Delete the "Forgot Password?" block**

Remove these lines entirely from the end of the composable:

```kotlin
            // Forgot Password
            if (uiState.isSignInMode) {
                TextButton(
                    onClick = { /* TODO: Implement password reset */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Forgot Password?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
```

- [ ] **Step 9: Confirm no hardcoded text survives**

```bash
grep -nE '(Text\(|text = |contentDescription = |placeholder|label)' app/src/main/java/com/coparently/app/presentation/auth/AuthScreen.kt | grep '"'
```

Expected: **no output**. Any line printed is a literal that still needs a resource. `"CoPlanly"`
in the branding block is the product name and is the one acceptable literal — if it appears,
leave it.

- [ ] **Step 10: Build and run the whole unit-test suite**

```bash
./gradlew assembleDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; all tests pass, including Tasks 1, 2 and 4.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/auth/AuthScreen.kt
git commit -m "feat(auth): speak the user's language on the login screen, and drop the dead reset link"
```

---

### Task 7: Full verification

**Files:** none changed unless a check fails.

- [ ] **Step 1: Clean build, tests, lint and detekt**

```bash
./gradlew clean assembleDebug testDebugUnitTest lint detekt
```

Expected: BUILD SUCCESSFUL, no new detekt findings. If detekt reports a finding **introduced by
this branch**, fix it — do not add it to `app/config/detekt/baseline.xml`. Findings that already
exist on `main` are not this branch's to fix.

- [ ] **Step 2: Confirm the dead dependency really is gone**

```bash
grep -rn "credentialManagerService\|ApplicationContext" app/src/main/java/com/coparently/app/presentation/auth/
```

Expected: **no output**. `CredentialManagerService` was injected into `AuthViewModel` and never
referenced; the application context existed only to feed Credential Manager.

- [ ] **Step 3: Confirm `CredentialManagerService` is still used elsewhere**

```bash
grep -rn "CredentialManagerService" app/src/main/java --include=*.kt
```

Expected: its own file plus the Google Calendar call sites. If this returns only the declaration,
the class is now dead and that is a finding for the follow-up review — **do not delete it in this
branch**, the calendar path is out of scope.

- [ ] **Step 4: Device run**

Install on a phone and walk the checklist from spec §6. Verify `mCurrentFocus` is CoPlanly before
any `adb` tap.

```bash
./gradlew installDebug
```

1. **Google, unknown account.** Sign out. Tap "Sign in with Google". Google's full-screen flow
   must appear with an option to add an account — not a bottom sheet limited to device accounts.
   Sign in with an account the phone does not have.
2. **Password saved.** Sign out. Sign in with email + password. The system must offer to save,
   **before** the home screen appears.
3. **Decline is survivable.** Repeat, and decline the save. Sign-in must still complete and land
   on the home screen.
4. **Saved password works.** Sign out. Tap "Sign in with saved password" and pick the entry. It
   must sign in with no typing.
5. **Nothing saved says so.** On a device with no stored CoPlanly password, tap the same button.
   Expect "No saved password for CoPlanly yet", not silence.
6. **Cancel is not an error.** Tap "Sign in with Google", then dismiss the sheet. No red error
   card may appear.
7. **Russian.** Settings → Language → Русский. The whole login screen, including an error (type a
   wrong password), must be Russian.

- [ ] **Step 5: Record the device run**

Append the outcome of each of the seven checks to the spec's §6, marking any that failed.

```bash
git add docs/superpowers/specs/2026-08-22-auth-google-and-password-design.md
git commit -m "docs: record the device run for the auth package"
```

- [ ] **Step 6: Open the pull request**

```bash
gh pr create --base main --title "feat(auth): Google sign-in for accounts not on the device, and remembering the password" --body "Package A of the nineteen-item improvement list. Spec: docs/superpowers/specs/2026-08-22-auth-google-and-password-design.md"
```

---

## Notes for the reviewer

**A judgment call worth flagging.** The spec (§7) records "two sources of truth for the web
client ID" as backlog: `AuthViewModel` hardcoded
`492948924829-m22iudtoaj437i518qm2p4do8t35vv1g.apps.googleusercontent.com` while
`CredentialManagerService` read `R.string.default_web_client_id`. The two values are byte-identical
and both match `google-services.json`. Task 3 has to pick one when it moves the call, and copying
a hardcoded credential-ish constant into a brand-new file is the worse of the two options — so
`CredentialAuthenticator` reads the resource. The backlog item resolves itself as a side effect
rather than as extra scope.

**What this package does not do,** each recorded in spec §5 and §7: password reset (the entry
point is removed, `FirebaseAuthService.sendPasswordResetEmail` is untouched and ready);
`CredentialManagerService` and the Google Calendar OAuth path; error text in any other ViewModel;
and the `androidx.credentials` version drift — the build declares `1.2.2` while `googleid:1.1.1`
pulls the resolved version up to `1.3.0-beta01`.
