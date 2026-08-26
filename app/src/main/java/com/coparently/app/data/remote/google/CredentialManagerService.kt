package com.coparently.app.data.remote.google

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.coparently.app.R
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.remote.firebase.GoogleOAuthFunctions
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.services.calendar.CalendarScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сервис для аутентификации Google с использованием Google Sign-In API и OAuth2.
 * Обеспечивает полный OAuth2 flow с access и refresh токенами.
 *
 * @see <a href="https://developers.google.com/identity/sign-in/android/start">Google Sign-In for Android</a>
 */
@Singleton
class CredentialManagerService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedPreferences: EncryptedPreferences,
    private val googleOAuthFunctions: GoogleOAuthFunctions
) {
    private val credentialManager: CredentialManager? = try {
        CredentialManager.create(context)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create CredentialManager", e)
        null
    }
    private val _googleSignInClient: GoogleSignInClient? by lazy {
        try {
            // One client id for both, and it cannot be otherwise: `GoogleSignInOptions.Builder`
            // rejects an id token and a server auth code naming different web clients
            // ("two different server client ids provided") from inside `requestServerAuthCode`,
            // before any request reaches Google. Splitting them was tried and threw on the
            // builder, taking Google sign-in down with it.
            //
            // So the Cloud Function that redeems the code must hold *this* client's secret.
            val webClientId = getWebClientId()
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestServerAuthCode(webClientId)
                .requestEmail()
                .requestScopes(Scope(CalendarScopes.CALENDAR))
                .build()
            GoogleSignIn.getClient(context, gso)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create GoogleSignInClient", e)
            null
        }
    }

    // Separate client for authentication (without calendar scope)
    private val _authGoogleSignInClient: GoogleSignInClient? by lazy {
        try {
            val webClientId = getWebClientId()
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(context, gso)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create auth GoogleSignInClient", e)
            null
        }
    }

    companion object {
        private const val TAG = "CredentialManager"

        /**
         * What an access token is assumed to live for when Google says nothing.
         *
         * One hour, which is what Google issues in practice. A wrong guess here costs at worst
         * one refused API call and a refresh, so a default is safer than treating a missing
         * value as "already expired" and refreshing on every single call.
         */
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
    }

    /**
     * Получает Google Sign-In Client для использования в UI компонентах.
     */
    fun getGoogleSignInClient(): GoogleSignInClient? {
        return _googleSignInClient
    }

    /**
     * Получает Google Sign-In Client для аутентификации (без calendar scope).
     */
    fun getAuthGoogleSignInClient(): GoogleSignInClient? {
        return _authGoogleSignInClient
    }

    /**
     * Выполняет аутентификацию через Google Sign-In и получает OAuth2 токены.
     * Это полноценный OAuth2 flow для Google Calendar API.
     *
     * @return Pair of (GoogleSignInAccount?, errorMessage?)
     */
    suspend fun signInWithGoogle(): Pair<GoogleSignInAccount?, String?> {
        return try {
            val client = _googleSignInClient
            if (client == null) {
                Pair(null, "Google Sign-In client is not available. OAuth may not be configured.")
            } else {
                val signInIntent = client.signInIntent
                // Note: This method assumes the sign-in intent is handled by an Activity
                // The actual implementation should be in an Activity or Fragment
                Pair(null, "Sign-in intent should be handled by Activity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sign-in: ${e.message}", e)
            Pair(null, "Failed to start sign-in: ${e.message}")
        }
    }

    /**
     * Обрабатывает результат Google Sign-In и обменивает authorization code на токены.
     *
     * @param completedTask Task с результатом Google Sign-In
     * @return Pair of (GoogleSignInAccount?, errorMessage?)
     */
    suspend fun handleSignInResult(completedTask: Task<GoogleSignInAccount>): Pair<GoogleSignInAccount?, String?> {
        return try {
            val account = completedTask.await()
            Log.d(TAG, "Sign-in successful for: ${account.email}")

            // Получаем authorization code
            val authCode = account.serverAuthCode
            if (authCode != null) {
                // Обмениваем authorization code на access и refresh токены
                exchangeAuthCodeForTokens(authCode, account.email ?: "")
                Pair(account, null)
            } else {
                Pair(null, "No authorization code received")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed with code: ${e.statusCode}", e)
            val errorMsg = when (e.statusCode) {
                12500 -> "Google Play Services not available"
                12501 -> "Sign-in cancelled by user"
                12502 -> "Sign-in failed"
                else -> "Sign-in error: ${e.message}"
            }
            Pair(null, errorMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sign-in: ${e.message}", e)
            Pair(null, "Unexpected error: ${e.message}")
        }
    }

    /**
     * Обменивает authorization code на access и refresh токены.
     *
     * @param authCode Authorization code от Google Sign-In
     * @param email Email пользователя для идентификации
     */
    private suspend fun exchangeAuthCodeForTokens(authCode: String, email: String) {
        // Through the Cloud Function, not directly (SEC-1 §2). The web OAuth client needs a
        // client secret for this grant, and that secret used to be compiled into the APK —
        // where it was available to anybody who installed the app.
        val tokens = googleOAuthFunctions.exchangeAuthCode(authCode).getOrElse { e ->
            Log.e(TAG, "Error exchanging auth code for tokens: ${e.message}", e)
            throw e
        }

        encryptedPreferences.putAccessToken(tokens.accessToken)
        // Only when one came back. Google omits the refresh token when the account has already
        // granted consent, and blanking the stored one there would cost the parent a re-consent
        // for nothing.
        if (tokens.refreshToken.isNotEmpty()) {
            encryptedPreferences.putRefreshToken(tokens.refreshToken)
        }
        encryptedPreferences.putTokenExpiry(expiryFrom(tokens.expiresInSeconds))
        encryptedPreferences.putUserEmail(email)

        Log.d(TAG, "Tokens obtained and stored successfully")
    }

    /** When a token that lives [expiresInSeconds] from now runs out, as epoch millis. */
    private fun expiryFrom(expiresInSeconds: Long): Long =
        System.currentTimeMillis() +
            (expiresInSeconds.takeIf { it > 0L } ?: DEFAULT_TOKEN_LIFETIME_SECONDS) * 1000

    /**
     * Получает access token, проверяя срок действия и обновляя при необходимости.
     * Использует сохраненные токены и refresh токен для обновления.
     *
     * @return Pair of (accessToken?, errorMessage?)
     */
    suspend fun getAccessToken(): Pair<String?, String?> {
        return try {
            val storedToken = encryptedPreferences.getAccessToken()
            val expiryTime = encryptedPreferences.getTokenExpiry()
            val refreshToken = encryptedPreferences.getRefreshToken()

            // Проверяем, истек ли токен (с запасом в 5 минут)
            if (storedToken != null && expiryTime != null &&
                expiryTime > System.currentTimeMillis() + 300000) {
                Log.d(TAG, "Using stored access token")
                return Pair(storedToken, null)
            }

            // Если токен истек или отсутствует, обновляем
            if (refreshToken.isNullOrEmpty()) {
                Log.e(TAG, "No refresh token available")
                return Pair(null, "No refresh token available. Please sign in again.")
            }

            Log.d(TAG, "Refreshing access token")
            refreshAccessToken()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token: ${e.message}", e)
            Pair(null, "Error getting access token: ${e.message}")
        }
    }

    /**
     * Обновляет access token используя refresh токен.
     *
     * @return Pair of (accessToken?, errorMessage?)
     */
    private suspend fun refreshAccessToken(): Pair<String?, String?> {
        return try {
            val refreshToken = encryptedPreferences.getRefreshToken()
                ?: return Pair(null, "No refresh token available")

            // Through the Cloud Function, which holds the client secret this grant needs.
            // It refuses a refresh token it did not issue to this account, so a failure here
            // can mean "connect Google Calendar again" rather than only "no network" — which
            // is true of every account connected before SEC-1 §2 shipped.
            val tokens = googleOAuthFunctions.refreshAccessToken(refreshToken)
                .getOrElse { e ->
                    Log.e(TAG, "Error refreshing token: ${e.message}", e)
                    return Pair(null, "Error refreshing token: ${e.message}")
                }

            encryptedPreferences.putAccessToken(tokens.accessToken)
            encryptedPreferences.putTokenExpiry(expiryFrom(tokens.expiresInSeconds))

            Log.d(TAG, "Access token refreshed successfully")
            Pair(tokens.accessToken, null)
        } catch (e: IOException) {
            Log.e(TAG, "Error refreshing token: ${e.message}", e)
            Pair(null, "Network error refreshing token: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error refreshing token: ${e.message}", e)
            Pair(null, "Error refreshing token: ${e.message}")
        }
    }

    /**
     * Проверяет, есть ли активная сессия с валидными токенами.
     */
    fun isSignedIn(): Boolean {
        val token = encryptedPreferences.getAccessToken()
        val expiry = encryptedPreferences.getTokenExpiry()
        return token != null && expiry != null && expiry > System.currentTimeMillis() + 300000
    }

    /**
     * Выполняет выход из аккаунта, очищая все сохраненные токены и завершая Google Sign-In сессию.
     */
    suspend fun signOut(): Pair<Boolean, String?> {
        return try {
            _googleSignInClient?.signOut()?.await()
            _googleSignInClient?.revokeAccess()?.await()
            encryptedPreferences.clear()
            Log.d(TAG, "User signed out successfully")
            Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error during sign out: ${e.message}", e)
            Pair(false, "Error during sign out: ${e.message}")
        }
    }

    /**
     * Получает email текущего пользователя.
     */
    fun getCurrentUserEmail(): String? {
        return encryptedPreferences.getUserEmail()
    }

    /**
     * Получает Web Client ID из ресурсов или конфигурации.
     * Web Client ID должен быть настроен в Google Cloud Console как OAuth 2.0 Client ID для веб-приложения
     * и добавлен в strings.xml как default_web_client_id
     */
    private fun getWebClientId(): String {
        return try {
            // Попытка получить из strings.xml
            val clientId = context.getString(R.string.default_web_client_id)
            if (clientId.contains("YOUR_WEB_CLIENT_ID")) {
                throw IllegalStateException("Web Client ID not configured. Please set up OAuth 2.0 Client ID in Google Cloud Console and update default_web_client_id in strings.xml")
            }
            clientId
        } catch (e: Exception) {
            Log.e(TAG, "Web Client ID not configured: ${e.message}")
            throw IllegalStateException("Google OAuth not configured. Please:\n1. Go to Google Cloud Console\n2. Create OAuth 2.0 Client ID for Web application\n3. Add the Client ID to default_web_client_id in strings.xml\n4. Enable Google Calendar API")
        }
    }

}

