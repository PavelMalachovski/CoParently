package com.coparently.app.data.remote.google

import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.json.gson.GsonFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialProvider for Google Calendar API.
 */
@Singleton
class CredentialProviderImpl @Inject constructor(
    private val googleSignInService: GoogleSignInService,
    private val encryptedPreferences: EncryptedPreferences
) : CredentialProvider {

    @Suppress("DEPRECATION")
    override fun getCredential(): Credential? {
        googleSignInService.getLastSignedInAccount() ?: return null
        val accessToken = encryptedPreferences.getAccessToken() ?: return null

        // GoogleCredential is deprecated but still the recommended way for google-api-client
        // There's no direct replacement in the current version of the library
        return GoogleCredential.Builder()
            .setTransport(NetHttpTransport())
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .build()
            .apply {
                setAccessToken(accessToken)
            }
    }
}

