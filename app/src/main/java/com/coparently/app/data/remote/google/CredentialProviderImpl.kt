package com.coparently.app.data.remote.google

import android.content.Context
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialProvider for Google Calendar API.
 * Creates Credential from stored access token.
 * Token refresh is handled manually before API calls when needed.
 */
@Singleton
class CredentialProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googleSignInService: GoogleSignInService,
    private val encryptedPreferences: EncryptedPreferences
) : CredentialProvider {

    override fun getCredential(): Credential? {
        if (googleSignInService.getLastSignedInAccount() == null) return null
        val accessToken = encryptedPreferences.getAccessToken() ?: return null

        // Create a Credential with access token
        // The token will be refreshed manually if needed before API calls
        return Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setRequestInitializer(object : HttpRequestInitializer {
                override fun initialize(request: HttpRequest) {
                    // Always use the latest token from preferences
                    val currentToken = encryptedPreferences.getAccessToken()
                    if (currentToken != null) {
                        request.headers.setAuthorization("Bearer $currentToken")
                    }
                }
            })
            .build()
            .apply {
                setAccessToken(accessToken)
                // Don't set expiration to allow manual refresh handling
            }
    }

    /**
     * Refreshes the access token and updates stored token.
     * Call this before API calls if token might be expired.
     * Note: This is a blocking call and should be used in suspend function with Dispatchers.IO.
     */
    suspend fun refreshAccessToken(): String? {
        return try {
            val account = googleSignInService.getLastSignedInAccount() ?: return null
            val accountObj = account.account ?: return null
            val newToken = withContext(Dispatchers.IO) {
                GoogleAuthUtil.getToken(
                    context,
                    accountObj,
                    "oauth2:${CalendarScopes.CALENDAR}"
                )
            }
            encryptedPreferences.putAccessToken(newToken)
            newToken
        } catch (e: Exception) {
            android.util.Log.e("CredentialProvider", "Error refreshing token: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
}

