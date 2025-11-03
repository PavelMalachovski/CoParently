package com.coparently.app.data.remote.google

import android.content.Context
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for Google Sign-In authentication.
 * Handles OAuth 2.0 authentication flow using Google Sign-In SDK.
 */
@Singleton
class GoogleSignInService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedPreferences: EncryptedPreferences
) {
    private val gso: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(CalendarScopes.CALENDAR))
        .build()

    val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    /**
     * Gets the last signed-in account if available.
     */
    fun getLastSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Checks if user is signed in.
     */
    fun isSignedIn(): Boolean {
        return getLastSignedInAccount() != null
    }

    /**
     * Gets access token for the signed-in account.
     * Note: GoogleAuthUtil.getToken() is a blocking call, but it's wrapped in suspend for compatibility.
     */
    suspend fun getAccessToken(account: GoogleSignInAccount): String? {
        return try {
            val accountObj = account.account ?: return null

            // Get token - GoogleAuthUtil will handle scope request if needed
            val token = GoogleAuthUtil.getToken(
                context,
                accountObj,
                "oauth2:${CalendarScopes.CALENDAR}"
            )

            if (token.isNotBlank()) {
                encryptedPreferences.putAccessToken(token)
                token
            } else {
                null
            }
        } catch (e: Exception) {
            // Log error for debugging (can be replaced with proper logging library)
            e.printStackTrace()
            null
        }
    }

    /**
     * Signs out the user.
     */
    suspend fun signOut() {
        googleSignInClient.signOut()
        encryptedPreferences.clear()
    }
}

