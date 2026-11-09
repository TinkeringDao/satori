package com.satori.data.firstrun

import android.content.SharedPreferences

/**
 * SharedPreferences in memory, for the JVM tests: what is written is read back, and [commits]
 * counts the writes that went through commit, which the first run's store relies on.
 */
class MemoryPrefs : SharedPreferences {

    val values = mutableMapOf<String, Any?>()
    var commits = 0

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        private fun change(block: () -> Unit): SharedPreferences.Editor {
            block()
            return this
        }

        override fun putString(key: String, value: String?) = change { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = change { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = change { changes[key] = value }
        override fun putLong(key: String, value: Long) = change { changes[key] = value }
        override fun putFloat(key: String, value: Float) = change { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = change { changes[key] = value }
        override fun remove(key: String) = change { removed += key }
        override fun clear() = change { clear = true }

        override fun commit(): Boolean {
            commits++
            write()
            return true
        }

        override fun apply() = write()

        private fun write() {
            if (clear) values.clear()
            removed.forEach(values::remove)
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
