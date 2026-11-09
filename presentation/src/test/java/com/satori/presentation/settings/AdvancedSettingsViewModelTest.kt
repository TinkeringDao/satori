package com.satori.presentation.settings

import com.satori.domain.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the AI is told to write is the app's own: Advanced settings neither shows nor changes it,
 * per kind of recording only how often and when notes are written.
 *
 * The view model can't be built in a JVM test (its download services need Android and its scope
 * the main dispatcher), so this reads what it offers. It fails when a prompt editor comes back.
 */
class AdvancedSettingsViewModelTest {

    private val offered: Set<String> = AdvancedSettingsViewModel::class.java.methods.map { it.name }.toSet()

    @Test
    fun `each kind of recording keeps its interval and its timing`() {
        assertTrue("updateModeInterval" in offered)
        assertTrue("updateModeDefaultStrategy" in offered)
    }

    @Test
    fun `nothing reads or writes the AI's instructions`() {
        assertEquals(emptyList<String>(), offered.filter { it.contains("prompt", ignoreCase = true) })
        assertEquals(
            emptyList<String>(),
            SettingsRepository::class.java.methods.map { it.name }.filter { it.contains("prompt", ignoreCase = true) }
        )
    }
}
