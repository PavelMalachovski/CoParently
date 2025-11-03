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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Note: GoogleAuthUtil.getToken() is a blocking call that must run on background thread.
     *
     * @return Pair of (token, errorMessage). If token is null, errorMessage contains reason.
     */
    suspend fun getAccessToken(account: GoogleSignInAccount): Pair<String?, String?> {
        return try {
            val accountObj = account.account ?: return Pair(null, "Account object is null")

            // Check if account has granted the calendar scope
            val grantedScopes = account.grantedScopes
            val hasCalendarScope = grantedScopes.any { scope ->
                scope.toString().contains(CalendarScopes.CALENDAR, ignoreCase = true)
            }

            if (!hasCalendarScope) {
                // Scope not granted - this might be the issue
                android.util.Log.w("GoogleSignIn", "Calendar scope not granted. Granted scopes: $grantedScopes")
                return Pair(null, "Calendar scope not granted. Please grant permission when signing in.")
            }

            // Get token - GoogleAuthUtil.getToken() is blocking and must run on IO dispatcher
            val scopeString = "oauth2:${CalendarScopes.CALENDAR}"
            android.util.Log.d("GoogleSignIn", "Requesting token with scope: $scopeString")

            // Execute blocking call on IO dispatcher to avoid main thread deadlock
            val token = withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(
                    context,
                    accountObj,
                    scopeString
                )
            }

            if (token.isNotBlank()) {
                encryptedPreferences.putAccessToken(token)
                android.util.Log.d("GoogleSignIn", "Token obtained successfully")
                Pair(token, null)
            } else {
                android.util.Log.e("GoogleSignIn", "Token is blank")
                Pair(null, "Token is empty. Please check Google Calendar API and OAuth configuration.")
            }
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            // User needs to grant permission via Activity
            android.util.Log.e("GoogleSignIn", "UserRecoverableAuthException: ${e.message}", e)
            e.printStackTrace()
            Pair(null, "Permission required. Please grant access to Google Calendar when prompted: ${e.message}")
        } catch (e: com.google.android.gms.auth.GoogleAuthException) {
            // Auth error - usually means API not enabled or OAuth not configured
            android.util.Log.e("GoogleSignIn", "GoogleAuthException: ${e.message}", e)
            e.printStackTrace()
            val errorMsg = when {
                e.message?.contains("API", ignoreCase = true) == true ->
                    "Google Calendar API is not enabled. Please enable it in Google Cloud Console."
                e.message?.contains("OAuth", ignoreCase = true) == true ||
                e.message?.contains("client", ignoreCase = true) == true ->
                    "OAuth 2.0 Client ID is not configured. Please configure it in Google Cloud Console."
                else ->
                    "Authentication error: ${e.message ?: "Please check Google Calendar API and OAuth configuration."}"
            }
            Pair(null, errorMsg)
        } catch (e: com.google.android.gms.auth.GooglePlayServicesAvailabilityException) {
            // Google Play Services issue
            android.util.Log.e("GoogleSignIn", "GooglePlayServicesAvailabilityException: ${e.message}", e)
            e.printStackTrace()
            Pair(null, "Google Play Services error: ${e.message ?: "Please update Google Play Services."}")
        } catch (e: com.google.android.gms.auth.UserRecoverableNotifiedException) {
            // User was notified but didn't grant permission
            android.util.Log.e("GoogleSignIn", "UserRecoverableNotifiedException: ${e.message}", e)
            e.printStackTrace()
            Pair(null, "Please grant permission to access Google Calendar in your device settings.")
        } catch (e: Exception) {
            // Log error for debugging
            android.util.Log.e("GoogleSignIn", "Exception getting token: ${e.javaClass.simpleName}: ${e.message}", e)
            e.printStackTrace()
            Pair(null, "Error getting access token: ${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Returns sign-in intent to re-authenticate with calendar scope.
     * Use this to request calendar permission by signing in again.
     * The scope is already configured in GoogleSignInOptions.
     */
    fun getSignInIntentWithScope(): android.content.Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Checks if calendar scope is granted for the account.
     */
    fun hasCalendarScope(account: GoogleSignInAccount): Boolean {
        val grantedScopes = account.grantedScopes
        return grantedScopes.any { scope ->
            scope.toString().contains(CalendarScopes.CALENDAR, ignoreCase = true)
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

