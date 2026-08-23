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
