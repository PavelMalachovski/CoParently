package com.coparently.app.data.remote.firebase

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Google returned for an authorization code.
 *
 * @property accessToken The short-lived token the Calendar API is called with.
 * @property refreshToken Empty when Google did not issue one — which it does not when the
 *   account has already granted consent. An empty value must never overwrite a stored token.
 * @property expiresInSeconds How long [accessToken] is good for.
 */
data class GoogleTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long
)

/**
 * The Google OAuth token exchange, performed by a Cloud Function (SEC-1 §2).
 *
 * **Why this exists.** Google's *web* OAuth client — the one the app uses, because that is the
 * client type the Calendar scope is granted through — requires a client secret to redeem an
 * authorization code and to refresh an access token. That secret used to be compiled into the
 * APK as `BuildConfig.GOOGLE_CLIENT_SECRET`. An APK is not a secret: anybody who installs the
 * app has it, and with it can mint tokens against the project's OAuth client. It now lives only
 * in the functions' environment and this class is how the app reaches it.
 *
 * **The tokens still live on the device**, in `EncryptedPreferences`, exactly as before. Moving
 * their storage server-side is a larger change and a separate decision; this one closes the hole
 * the secret was.
 *
 * Failures come back as a plain [Result] with the underlying exception. Unlike [PairingFunctions]
 * there is no error table: the caller's only two responses are "retry" and "ask the parent to
 * connect Google Calendar again", and both are decided by whether a token came back at all.
 */
@Singleton
class GoogleOAuthFunctions @Inject constructor(
    private val functions: FirebaseFunctions
) {

    /**
     * Redeems an authorization code for tokens.
     *
     * @param authCode The code Google Sign-In returned.
     */
    suspend fun exchangeAuthCode(authCode: String): Result<GoogleTokens> =
        call("exchangeGoogleAuthCode", mapOf("authCode" to authCode)) { data ->
            GoogleTokens(
                accessToken = data["accessToken"] as? String
                    ?: error("exchangeGoogleAuthCode returned no accessToken"),
                refreshToken = data["refreshToken"] as? String ?: "",
                expiresInSeconds = (data["expiresInSeconds"] as? Number)?.toLong() ?: 0L
            )
        }

    /**
     * Trades a refresh token for a fresh access token.
     *
     * The callable refuses a refresh token it did not issue to this account, so a failure here
     * can mean "reconnect", not only "network". That includes every account whose Calendar was
     * connected before this shipped: their token predates the fingerprint the function checks
     * against, and they consent once more.
     *
     * @param refreshToken The stored refresh token.
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<GoogleTokens> =
        call("refreshGoogleAccessToken", mapOf("refreshToken" to refreshToken)) { data ->
            GoogleTokens(
                accessToken = data["accessToken"] as? String
                    ?: error("refreshGoogleAccessToken returned no accessToken"),
                // The refresh grant never returns a new refresh token; the stored one stands.
                refreshToken = "",
                expiresInSeconds = (data["expiresInSeconds"] as? Number)?.toLong() ?: 0L
            )
        }

    private suspend fun <T> call(
        name: String,
        payload: Map<String, Any>,
        parse: (Map<*, *>) -> T
    ): Result<T> = try {
        val result = functions.getHttpsCallable(name).call(payload).await()
        Result.success(parse((result.getData() as? Map<*, *>) ?: emptyMap<String, Any>()))
    } catch (e: CancellationException) {
        // Cancellation must propagate rather than be reported as a sign-in failure: navigating
        // away mid-call would otherwise surface a spurious error.
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Result.failure(e)
    }
}
