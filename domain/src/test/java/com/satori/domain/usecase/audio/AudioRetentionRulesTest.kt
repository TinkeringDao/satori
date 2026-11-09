package com.satori.domain.usecase.audio

import com.satori.domain.model.AudioEncoding
import com.satori.domain.repository.AudioFileInfo
import com.satori.domain.usecase.audio.AudioRetentionRules.DAY_MS
import com.satori.domain.usecase.audio.AudioRetentionRules.Plan
import com.satori.domain.usecase.audio.AudioRetentionRules.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRetentionRulesTest {

    private val now = 400 * DAY_MS

    /** Audio whose last capture ended [endedAgo] before now; null: its index has no run. */
    private fun audio(
        id: String,
        endedAgo: Long?,
        encoding: AudioEncoding = AudioEncoding.WAV,
        kept: Boolean = false
    ) = AudioFileInfo(id, bytes = 1_000_000L, encoding = encoding, lastCaptureEndMs = endedAgo?.let { now - it }, kept = kept)

    /** A recording that started [startedAgo] before now and ran [durationMs]. */
    private fun recording(id: String, holdsContent: Boolean = true, startedAgo: Long = 500 * DAY_MS, durationMs: Long = 60_000L) =
        Recording(id, createdAt = now - startedAgo, durationMs = durationMs, holdsContent = holdsContent)

    private fun plan(
        files: List<AudioFileInfo>,
        compressAfterDays: Int? = null,
        deleteAfterDays: Int? = null,
        inUse: Set<String> = emptySet(),
        sessions: List<Recording> = files.map { recording(it.sessionId) }
    ) = AudioRetentionRules.plan(files, sessions, now, compressAfterDays, deleteAfterDays, inUse)

    @Test
    fun `with both rules on never nothing happens, however old the audio`() {
        assertEquals(Plan(), plan(listOf(audio("old", endedAgo = 300 * DAY_MS))))
    }

    @Test
    fun `audio is compressed from exactly the day the rule says on`() {
        val justYounger = audio("younger", endedAgo = 7 * DAY_MS - 1)
        val exactly = audio("seven", endedAgo = 7 * DAY_MS)

        val plan = plan(listOf(justYounger, exactly), compressAfterDays = 7)

        assertEquals(Plan(compress = listOf(exactly)), plan)
    }

    @Test
    fun `audio is deleted from exactly the day the rule says on, and compressed before that`() {
        val justYounger = audio("younger", endedAgo = 15 * DAY_MS - 1)
        val exactly = audio("fifteen", endedAgo = 15 * DAY_MS)

        assertEquals(Plan(deleteAudio = listOf(exactly)), plan(listOf(justYounger, exactly), deleteAfterDays = 15))
        assertEquals(
            Plan(compress = listOf(justYounger), deleteAudio = listOf(exactly)),
            plan(listOf(justYounger, exactly), compressAfterDays = 7, deleteAfterDays = 15)
        )
    }

    @Test
    fun `delete wins over compress`() {
        val old = audio("old", endedAgo = 40 * DAY_MS)

        assertEquals(Plan(deleteAudio = listOf(old)), plan(listOf(old), compressAfterDays = 7, deleteAfterDays = 30))
    }

    @Test
    fun `compressed audio isn't compressed again, and is deleted like any other`() {
        val compressed = audio("aac", endedAgo = 20 * DAY_MS, encoding = AudioEncoding.AAC)

        assertEquals(Plan(), plan(listOf(compressed), compressAfterDays = 7))
        assertEquals(Plan(deleteAudio = listOf(compressed)), plan(listOf(compressed), compressAfterDays = 7, deleteAfterDays = 15))
    }

    @Test
    fun `audio the owner keeps is left alone`() {
        val kept = audio("kept", endedAgo = 200 * DAY_MS, kept = true)

        assertEquals(Plan(), plan(listOf(kept), compressAfterDays = 7, deleteAfterDays = 15))
    }

    @Test
    fun `audio in use is left alone`() {
        val playing = audio("playing", endedAgo = 200 * DAY_MS)
        val other = audio("other", endedAgo = 200 * DAY_MS)

        assertEquals(Plan(deleteAudio = listOf(other)), plan(listOf(playing, other), deleteAfterDays = 15, inUse = setOf("playing")))
    }

    @Test
    fun `nothing younger than a day is touched, whatever the rules say, also with the clock set back`() {
        val young = audio("young", endedAgo = DAY_MS - 1)
        val aDay = audio("day", endedAgo = DAY_MS)
        val ahead = audio("ahead", endedAgo = -3 * DAY_MS)

        val plan = plan(listOf(young, aDay, ahead), compressAfterDays = 0, deleteAfterDays = null)

        assertEquals(Plan(compress = listOf(aDay)), plan)
    }

    @Test
    fun `without runs the age is the recording's start plus its length`() {
        val files = listOf(audio("long", endedAgo = null), audio("short", endedAgo = null))
        val sessions = listOf(
            // Started 8 days ago and ran a whole day: it ended 7 days ago less a millisecond.
            recording("long", startedAgo = 8 * DAY_MS, durationMs = DAY_MS + 1),
            recording("short", startedAgo = 8 * DAY_MS, durationMs = 60_000L)
        )

        val plan = plan(files, compressAfterDays = 7, sessions = sessions)

        assertEquals(listOf("short"), plan.compress.map { it.sessionId })
    }

    @Test
    fun `audio whose age can't be told is left alone`() {
        val noAge = audio("lost", endedAgo = null)

        assertEquals(Plan(), plan(listOf(noAge), compressAfterDays = 7, deleteAfterDays = 15, sessions = emptyList()))
    }

    @Test
    fun `a recording that holds nothing but its audio goes whole once its audio goes`() {
        val empty = audio("empty", endedAgo = 40 * DAY_MS)
        val spoken = audio("spoken", endedAgo = 40 * DAY_MS)
        val sessions = listOf(recording("empty", holdsContent = false), recording("spoken"))

        val deleting = plan(listOf(empty, spoken), deleteAfterDays = 30, sessions = sessions)
        val compressing = plan(listOf(empty, spoken), compressAfterDays = 7, sessions = sessions)

        assertEquals(Plan(deleteAudio = listOf(spoken), deleteRecording = listOf(empty)), deleting)
        assertEquals(2, deleting.audioDeleted)
        assertEquals(Plan(compress = listOf(empty, spoken)), compressing)
    }

    @Test
    fun `audio left without its recording goes with the delete rule and is never compressed`() {
        val orphan = audio("orphan", endedAgo = 40 * DAY_MS)

        assertEquals(Plan(), plan(listOf(orphan), compressAfterDays = 7, sessions = emptyList()))
        assertEquals(Plan(deleteAudio = listOf(orphan)), plan(listOf(orphan), deleteAfterDays = 30, sessions = emptyList()))
    }

    @Test
    fun `an empty plan says so`() {
        assertTrue(Plan().isEmpty)
        assertEquals(0, Plan().audioDeleted)
    }
}
