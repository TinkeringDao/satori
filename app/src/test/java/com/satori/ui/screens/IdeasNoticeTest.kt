package com.satori.ui.screens

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdeasNoticeTest {

    private val prefs = MemoryPreferences()

    @Test
    fun `the count left by the move is taken once`() {
        IdeasNotice.postMoved(prefs, 4)

        assertEquals(4, IdeasNotice.takeMoved(prefs))
        assertNull(IdeasNotice.takeMoved(prefs))
    }

    @Test
    fun `without a move there is no notice`() {
        assertNull(IdeasNotice.takeMoved(prefs))
    }

    @Test
    fun `taking the notice leaves the other startup marks alone`() {
        prefs.edit().putBoolean("ideas_v1", true).apply()
        IdeasNotice.postMoved(prefs, 1)

        IdeasNotice.takeMoved(prefs)

        assertEquals(mapOf("ideas_v1" to true), prefs.all)
    }

    /** SharedPreferences in memory, with what IdeasNotice and these tests use. */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun contains(key: String): Boolean = key in values
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getString(key: String, defValue: String?): String? = unused()
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = unused()
        override fun getLong(key: String, defValue: Long): Long = unused()
        override fun getFloat(key: String, defValue: Float): Float = unused()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = unused()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = unused()

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

            override fun putInt(key: String, value: Int) = also { changes += { it[key] = value } }
            override fun putBoolean(key: String, value: Boolean) = also { changes += { it[key] = value } }
            override fun remove(key: String) = also { changes += { it.remove(key) } }
            override fun apply() = changes.forEach { it(values) }
            override fun commit(): Boolean = true.also { apply() }
            override fun putString(key: String, value: String?): SharedPreferences.Editor = unused()
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = unused()
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = unused()
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = unused()
            override fun clear(): SharedPreferences.Editor = unused()
        }

        private fun unused(): Nothing = error("not used by these tests")
    }
}
