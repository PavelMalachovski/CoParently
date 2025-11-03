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

            // Check if account has granted the calendar scope
            val grantedScopes = account.grantedScopes
            val hasCalendarScope = grantedScopes.any { scope ->
                scope.toString().contains(CalendarScopes.CALENDAR, ignoreCase = true)
            }

            if (!hasCalendarScope) {
                // Scope not granted - this might be the issue
                android.util.Log.w("GoogleSignIn", "Calendar scope not granted. Granted scopes: $grantedScopes")
            }

            // Get token - GoogleAuthUtil will handle scope request if needed
            val scopeString = "oauth2:${CalendarScopes.CALENDAR}"
            android.util.Log.d("GoogleSignIn", "Requesting token with scope: $scopeString")

            val token = GoogleAuthUtil.getToken(
                context,
                accountObj,
                scopeString
            )

            if (token.isNotBlank()) {
                encryptedPreferences.putAccessToken(token)
                android.util.Log.d("GoogleSignIn", "Token obtained successfully")
                token
            } else {
                android.util.Log.e("GoogleSignIn", "Token is blank")
                null
            }
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            // User needs to grant permission
            android.util.Log.e("GoogleSignIn", "UserRecoverableAuthException: ${e.message}", e)
            e.printStackTrace()
            null
        } catch (e: com.google.android.gms.auth.GoogleAuthException) {
            // Auth error
            android.util.Log.e("GoogleSignIn", "GoogleAuthException: ${e.message}", e)
            e.printStackTrace()
            null
        } catch (e: Exception) {
            // Log error for debugging
            android.util.Log.e("GoogleSignIn", "Exception getting token: ${e.javaClass.simpleName}: ${e.message}", e)
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

