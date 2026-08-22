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
