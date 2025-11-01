package com.coparently.app.data.remote.google

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
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

    override fun getCredential(): Credential? {
        val account = googleSignInService.getLastSignedInAccount() ?: return null
        val accessToken = encryptedPreferences.getAccessToken() ?: return null

        return GoogleCredential.Builder()
            .setTransport(AndroidHttp.newCompatibleTransport())
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .setAccessToken(accessToken)
            .build()
    }
}

