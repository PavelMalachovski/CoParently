package com.coparently.app.data.session

import android.content.Context
import android.util.Log
import com.coparently.app.data.local.CoPlanlyDatabase
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wipes the local database when a *different* account signs in on this device.
 *
 * Room deliberately survives sign-out so a returning parent's history is still there
 * (see `EncryptedPreferences.clear`). What must not survive is a *switch*: nothing in the
 * schema scopes rows to an account, so after A signs out and B signs in, B's Home, Calendar
 * and Expenses read A's rows — and worse, `SyncService.performFullSync` uploads A's
 * still-unsynced rows under B's uid and audience, turning a local privacy leak into a remote
 * write. Owner decision (Aug 2026 walkthrough): on a uid change the local data is cleared and
 * the new account sees only what the cloud holds for it; unsynced rows of the old account are
 * knowingly lost.
 *
 * The last-seen uid lives in its own **plain** `SharedPreferences` file, not in
 * [com.coparently.app.data.local.preferences.EncryptedPreferences]: it is not a secret — it
 * gates a wipe — and `EncryptedPreferences.clear()` runs on sign-out, which would erase the
 * very memory this check needs at the next sign-in.
 *
 * [ensureAccountConsistency] is called from two places, deliberately redundant:
 * [SessionProfileSynchronizer]'s auth-state collector (the session boundary every sign-in
 * passes through) and the top of `SyncService.performFullSync` (the choke point every upload
 * passes through). The second call is what closes the race — a periodic `SyncWorker` run can
 * fire before the collector has seen the new uid.
 */
@Singleton
class AccountSwitchGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: CoPlanlyDatabase,
    private val authService: FirebaseAuthService
) {
    private val mutex = Mutex()

    /**
     * Compares the signed-in uid to the last one seen on this device and wipes Room when they
     * differ. No-op while signed out — sign-out keeps data — and on the same account.
     *
     * Safe to call from any dispatcher; the wipe itself runs on [Dispatchers.IO] because
     * `clearAllTables` is blocking. The mutex keeps the two call sites from racing each other
     * into a double wipe.
     */
    suspend fun ensureAccountConsistency() {
        val uid = authService.getCurrentUser()?.uid ?: return
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastUid = prefs.getString(KEY_LAST_UID, null)
            if (lastUid != null && lastUid != uid) {
                Log.i(TAG, "Different account signed in; clearing local data")
                withContext(Dispatchers.IO) { database.clearAllTables() }
            }
            if (lastUid != uid) {
                prefs.edit().putString(KEY_LAST_UID, uid).apply()
            }
        }
    }

    private companion object {
        const val TAG = "AccountSwitchGuard"
        const val PREFS_NAME = "account_session"
        const val KEY_LAST_UID = "last_signed_in_uid"
    }
}
