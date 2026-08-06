package com.coparently.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit test for [EncryptedPreferences.clear], the storage-level half of the round-2 fix: a
 * marker under [PreferenceKeys.PARENT_SLOT_MARKER_PREFIX] must survive whatever calls `clear()`
 * — the app's own Sign out and disconnecting Google Calendar both do — because a device that
 * loses it while holding a full local history stamped in the old slot would take
 * `ParentSlotMigrator.reslotIfSlotChanged`'s "nothing to do" branch on the very next sync: the
 * exact damage the marker exists to prevent, reintroduced through where it lives.
 *
 * This exercises the real [EncryptedPreferences], not a mock of it — the property under test
 * is `clear()`'s own clear-then-restore ordering, which a mocked collaborator would not run at
 * all. `MasterKey`/`EncryptedSharedPreferences` cannot be constructed against a real Android
 * Keystore in a plain JVM unit test, so this relies on [EncryptedPreferences]'s own documented
 * fallback to a plain [SharedPreferences] when that construction fails — the same path a real
 * device without hardware-backed Keystore access takes in production, so this is exercising a
 * real, reachable branch of the class under test, not a test-only shim.
 */
class EncryptedPreferencesClearTest {

    @Test
    fun `clear preserves parent-slot markers but wipes everything else`() {
        val backing = FakeSharedPreferences()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns backing

        val preferences = EncryptedPreferences(context)
        val markerKey = "${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}u1"
        preferences.putString(markerKey, "dad")
        preferences.putUserEmail("alice@example.test")
        preferences.putAccessToken("token-1")

        preferences.clear()

        assertEquals("dad", preferences.getString(markerKey))
        assertNull(preferences.getUserEmail())
        assertNull(preferences.getAccessToken())
    }

    @Test
    fun `clear preserves markers for every uid that has one`() {
        // Two accounts have signed into this device over time (Room's own `users` rows survive
        // the same way — see PreferenceKeys.PARENT_SLOT_MARKER_PREFIX's KDoc) - a sign-out
        // triggered by either must not cost the other one its history.
        val backing = FakeSharedPreferences()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns backing

        val preferences = EncryptedPreferences(context)
        preferences.putString("${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}u1", "dad")
        preferences.putString("${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}u2", "mom")

        preferences.clear()

        assertEquals("dad", preferences.getString("${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}u1"))
        assertEquals("mom", preferences.getString("${PreferenceKeys.PARENT_SLOT_MARKER_PREFIX}u2"))
    }

    /**
     * A minimal in-memory [SharedPreferences], enough for [EncryptedPreferences]'s documented
     * fallback path (plain `SharedPreferences` when `EncryptedSharedPreferences`/`MasterKey`
     * cannot be constructed). Real `Editor.clear()`-then-`put()` ordering: a queued `clear()`
     * takes effect before any values queued in the same `apply()`, matching the Android
     * contract `EncryptedPreferences.clear()`'s exemption depends on.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = store.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            store[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            store[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            store[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = store.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var shouldClear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[requireNotNull(key)] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                pending[requireNotNull(key)] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[requireNotNull(key)] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[requireNotNull(key)] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[requireNotNull(key)] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[requireNotNull(key)] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                pending[requireNotNull(key)] = REMOVED
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                shouldClear = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (shouldClear) store.clear()
                pending.forEach { (key, value) ->
                    if (value === REMOVED) store.remove(key) else store[key] = value
                }
                pending.clear()
                shouldClear = false
            }
        }

        private companion object {
            val REMOVED = Any()
        }
    }
}
