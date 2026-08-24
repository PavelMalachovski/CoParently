package com.coparently.app.data.local.preferences

import android.content.SharedPreferences

/**
 * A [SharedPreferences] that keeps everything in memory and writes nothing to disk.
 *
 * The last-resort store for [EncryptedPreferences] when the device cannot give it an encrypted
 * one. That class's KDoc explains why this exists rather than a plaintext file: it holds the
 * Google refresh token, and a credential that survives a reboot in clear text is worse than a
 * credential the user has to re-issue.
 *
 * The old code claimed to do this — its comment read "use in-memory SharedPreferences / This
 * will lose data on app restart" — while actually calling
 * `context.getSharedPreferences("encrypted_prefs_memory", MODE_PRIVATE)`, which is an ordinary
 * file on disk under a name that says otherwise.
 *
 * Synchronised on the backing map because `EncryptedPreferences` is a `@Singleton` reached from
 * several coroutines. Listeners are held and notified so the contract holds for any caller that
 * registers one; nothing in this app does today.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(values) { values[key] as? String ?: defValue }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) {
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(values) { values[key] as? Int ?: defValue }

    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(values) { values[key] as? Long ?: defValue }

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(values) { values[key] as? Float ?: defValue }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { synchronized(listeners) { listeners.add(it) } }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { synchronized(listeners) { listeners.remove(it) } }
    }

    private fun notifyChanged(keys: Collection<String?>) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        keys.forEach { key -> snapshot.forEach { it.onSharedPreferenceChanged(this, key) } }
    }

    /**
     * Accumulates changes and applies them on [commit]/[apply], the way the real editor does —
     * a caller that builds an edit and never commits it must change nothing.
     */
    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            val changed = mutableListOf<String?>()
            synchronized(values) {
                if (clearRequested) {
                    changed.addAll(values.keys)
                    values.clear()
                }
                pending.forEach { (key, value) ->
                    if (value === REMOVED || value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                    changed.add(key)
                }
            }
            notifyChanged(changed)
            return true
        }

        override fun apply() {
            commit()
        }
    }

    companion object {
        /** Distinguishes "remove this key" from "store null", which the map cannot. */
        private val REMOVED = Any()
    }
}
