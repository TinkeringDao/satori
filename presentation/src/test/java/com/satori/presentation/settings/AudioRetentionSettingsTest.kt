package com.satori.presentation.settings

import com.satori.domain.model.AppSettings
import com.satori.domain.usecase.audio.AudioRetentionScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for older audio in Settings: when choosing a delete age asks first, when a change
 * starts a run, and the storage line.
 */
class AudioRetentionSettingsTest {

    private val asked = mutableListOf<Int>()

    /** A dry run in which [count] recordings would lose their audio, whatever the age. */
    private fun wouldDelete(count: Int): suspend (Int) -> Int = { age ->
        asked += age
        count
    }

    @Test
    fun `an age under which audio would go at once is asked about, with the count`() = runTest {
        val choice = AudioDeleteChoice.of(stored = null, chosen = 30, wouldDelete = wouldDelete(12))

        assertEquals(AudioDeleteChoice.Ask(AudioDeleteConfirm(days = 30, recordings = 12)), choice)
        assertEquals(listOf(30), asked)
    }

    @Test
    fun `an age that deletes nothing now is stored at once`() = runTest {
        assertEquals(AudioDeleteChoice.Apply, AudioDeleteChoice.of(stored = 90, chosen = 15, wouldDelete = wouldDelete(0)))
    }

    @Test
    fun `never is stored at once, with no dry run`() = runTest {
        assertEquals(AudioDeleteChoice.Apply, AudioDeleteChoice.of(stored = 30, chosen = null, wouldDelete = wouldDelete(5)))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the age that is stored already changes nothing and asks nothing`() = runTest {
        assertEquals(AudioDeleteChoice.Unchanged, AudioDeleteChoice.of(stored = 30, chosen = 30, wouldDelete = wouldDelete(5)))
        assertEquals(AudioDeleteChoice.Unchanged, AudioDeleteChoice.of(stored = null, chosen = null, wouldDelete = wouldDelete(5)))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the storage line adds up every recording's audio`() {
        assertEquals(
            AudioStorage(bytes = 129_000_000L, recordings = 2),
            AudioStorage.of(mapOf("a" to 115_000_000L, "b" to 14_000_000L))
        )
        assertEquals(AudioStorage(bytes = 0L, recordings = 0), AudioStorage.of(emptyMap()))
    }

    /** What the runs were told, in order, as [AudioRetentionScheduler.rulesChanged] tells them. */
    private class ToldRuns : AudioRetentionScheduler {
        val told = mutableListOf<String>()
        override fun scheduleDaily() { told += "daily" }
        override fun cancel() { told += "cancel" }
        override fun runNow() { told += "now" }
    }

    @Test
    fun `a change of either rule has the runs follow it, and any other change doesn't`() {
        val runs = ToldRuns()
        val never = AppSettings()
        val compressing = never.copy(audioCompressAfterDays = 7)

        runs.followRules(never, never.copy(vibrateOnStartStop = !never.vibrateOnStartStop))
        runs.followRules(compressing, compressing)
        assertTrue(runs.told.isEmpty())

        runs.followRules(never, compressing)
        assertEquals(listOf("daily", "now"), runs.told)

        runs.told.clear()
        runs.followRules(compressing, compressing.copy(audioDeleteAfterDays = 30))
        assertEquals(listOf("daily", "now"), runs.told)

        runs.told.clear()
        runs.followRules(compressing, never)
        assertEquals(listOf("cancel"), runs.told)
    }
}
