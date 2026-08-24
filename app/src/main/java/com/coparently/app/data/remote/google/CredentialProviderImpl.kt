package com.coparently.app.data.remote.google

import android.util.Log
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.google.api.client.auth.oauth2.BearerToken
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialProvider for Google Calendar API.
 * Creates Credential from stored access token using modern OAuth2 flow.
 * Token refresh is handled automatically by CredentialManagerService.
 */
@Singleton
class CredentialProviderImpl @Inject constructor(
    private val credentialManagerService: CredentialManagerService,
    private val encryptedPreferences: EncryptedPreferences
) : CredentialProvider {

    companion object {
        private const val TAG = "CredentialProvider"
    }

    override suspend fun getCredential(): Credential? {
        if (!credentialManagerService.isSignedIn()) {
            Log.d(TAG, "User is not signed in")
            return null
        }

        // Obtains a valid access token, refreshing over the network when it has expired.
        // This used to be wrapped in `runBlocking`, which made that refresh block whatever
        // thread asked — and the caller asked from the main one. See [CredentialProvider].
        val (accessToken, error) = credentialManagerService.getAccessToken()

        if (accessToken == null) {
            Log.e(TAG, "Failed to get access token: $error")
            return null
        }

        // Create a Credential with access token
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
                // Don't set expiration to allow automatic refresh handling
            }
    }

}


