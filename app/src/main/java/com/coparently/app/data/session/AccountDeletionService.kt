package com.coparently.app.data.session

import android.util.Log
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erases the signed-in account: everything the server holds, then everything this device does.
 *
 * **Why a callable rather than the client doing it.** `FirebaseAuthService.deleteCurrentUser()`
 * removes the Auth user and nothing else, and until this existed nothing called even that. The
 * documents an account leaves behind — its events, its child's medical profile, its half of the
 * chat — are spread across nine collections, several of which a departing user cannot read once
 * their `partnerId` clears, and none of which a client can delete in bulk. `deleteAccount` in
 * `functions/index.js` does the work on Admin credentials and deletes the Auth user last, so a
 * partial failure leaves an account that can still sign in and retry.
 *
 * **The local half is not optional.** Room deliberately survives sign-out so a returning parent
 * keeps their history, and [AccountSwitchGuard] only wipes when a *different* uid signs in — a
 * user who deletes their account and never signs in again would leave the whole database, chat
 * and medical profile included, sitting on the device. So the wipe is performed here,
 * explicitly, and only after the server reports success: clearing first would destroy the
 * user's data even on a run that failed and has to be retried.
 */
@Singleton
class AccountDeletionService @Inject constructor(
    private val functions: FirebaseFunctions,
    private val database: CoPlanlyDatabase,
    private val encryptedPreferences: EncryptedPreferences
) {

    /**
     * Deletes the account and wipes this device.
     *
     * @return success once the server has erased the account and the local database is clear.
     */
    suspend fun deleteAccount(): Result<Unit> = try {
        functions.getHttpsCallable(CALLABLE).call(emptyMap<String, Any>()).await()
        wipeLocalData()
        Result.success(Unit)
    } catch (e: CancellationException) {
        // Structured cancellation must keep unwinding: a config change mid-call is not a
        // failed deletion, and reporting it as one would offer a retry for work that may
        // have already succeeded.
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Log.e(TAG, "Account deletion failed", e)
        Result.failure(e)
    }

    /**
     * Clears the local database and the stored credentials.
     *
     * `clearAllTables` is blocking, so it runs on [Dispatchers.IO], matching
     * [AccountSwitchGuard]. The parent-slot markers `EncryptedPreferences.clear()` preserves
     * are keyed per-uid and belong to an account that no longer exists; they are harmless and
     * left alone rather than given a special path.
     */
    private suspend fun wipeLocalData() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        encryptedPreferences.clear()
    }

    private companion object {
        const val TAG = "AccountDeletionService"
        const val CALLABLE = "deleteAccount"
    }
}
