package com.satori.ui.screens

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.presentation.firstrun.FirstRunProblem
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.firstrun.SetupProgress
import com.satori.presentation.firstrun.SetupSizes
import com.satori.ui.R
import com.satori.ui.components.microphoneAskCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** What each state of the first run says, slot by slot, as the design's mockups have it (3.4-3.6). */
class FirstRunCopyTest {

    private val sizes = SetupSizes(speechBytes = 670_478_772L, malayalamBytes = 137_566_803L, aiModelBytes = 1_069_306_368L)
    private val getReady = FirstRunUiState(sizes = sizes)
    private val progress = SetupProgress(FirstRunPart.SPEECH, bytesDone = 276_000_000L, bytesTotal = 808_045_575L, minutesLeft = 2)
    private val downloading = getReady.copy(stage = FirstRunStage.DOWNLOADING, progress = progress)
    private val ready = downloading.copy(stage = FirstRunStage.READY, progress = progress.copy(bytesDone = 808_045_575L), microphone = MicrophoneState.ALLOWED)

    private fun copy(state: FirstRunUiState) = firstRunCopy(state) { modelSizeLabel(it, Locale.ENGLISH) }

    private fun ids(lines: List<CopyRes>) = lines.map { it.id }

    @Test
    fun `get ready on Wi-Fi lists the three parts and downloads 808 MB`() {
        val copy = copy(getReady)

        assertEquals(CopyRes(R.string.g_fr_intro), copy.intro)
        assertNull(copy.title)
        assertEquals(
            listOf(R.string.g_fr_part_speech, R.string.g_fr_part_malayalam, R.string.g_fr_part_notes),
            copy.rows.map { it.label.id }
        )
        assertEquals(listOf(null, true, null), copy.rows.map { it.switchedOn })
        assertEquals(CopyRes(R.string.g_fr_part_notes_when), copy.rows.last().caption)
        assertEquals(CopyRes(R.string.g_fr_wifi_only), copy.network)
        assertNull(copy.quiet)
        assertEquals(CopyRes(R.string.g_fr_download, listOf("808\u00A0MB")), copy.primary)
    }

    @Test
    fun `with the switch off the button downloads 670 MB, and the row stays with its switch off`() {
        val copy = copy(getReady.copy(malayalam = false))

        assertEquals(CopyRes(R.string.g_fr_download, listOf("670\u00A0MB")), copy.primary)
        assertEquals(false, copy.rows[1].switchedOn)
    }

    @Test
    fun `without an AI model to fetch its row is left out`() {
        assertEquals(2, copy(getReady.copy(sizes = sizes.copy(aiModelBytes = 0L))).rows.size)
    }

    @Test
    fun `the AI notes row counts the reader while Malayalam's switch is on, in its own words, and the button doesn't`() {
        val withReader = getReady.copy(sizes = sizes.copy(readerBytes = 241_989_173L))

        assertEquals(1_311_295_541L, copy(withReader).rows.last().bytes)
        assertEquals(R.string.g_fr_part_notes, copy(withReader).rows.last().label.id)
        assertEquals(CopyRes(R.string.g_fr_download, listOf("808\u00A0MB")), copy(withReader).primary)
        assertEquals(1_069_306_368L, copy(withReader.copy(malayalam = false)).rows.last().bytes)
        // With the AI model on the phone the row stays for the reader alone.
        val readerAlone = withReader.copy(sizes = withReader.sizes.copy(aiModelBytes = 0L))
        assertEquals(241_989_173L, copy(readerAlone).rows.last().bytes)
        assertEquals(2, copy(readerAlone.copy(malayalam = false)).rows.size)
    }

    @Test
    fun `the reader's download goes by the AI model's name, as no string names it`() {
        assertEquals(R.string.g_strip_ai_model, partLabel(FirstRunPart.READER))
        assertEquals(R.string.g_strip_ai_model, partLabel(FirstRunPart.AI_MODEL))
    }

    @Test
    fun `without Malayalam to fetch its row and switch are left out, and the button downloads speech recognition`() {
        val copy = copy(getReady.copy(sizes = sizes.copy(malayalamBytes = 0L)))

        assertEquals(listOf(R.string.g_fr_part_speech, R.string.g_fr_part_notes), copy.rows.map { it.label.id })
        assertTrue(copy.rows.all { it.switchedOn == null })
        assertEquals(CopyRes(R.string.g_fr_download, listOf("670\u00A0MB")), copy.primary)
    }

    @Test
    fun `on mobile data get ready says so and offers to wait for Wi-Fi`() {
        val copy = copy(getReady.copy(onMobileData = true))

        assertEquals(CopyRes(R.string.g_fr_mobile_now), copy.network)
        assertEquals(CopyRes(R.string.g_fr_wait_wifi), copy.quiet)
        assertEquals(R.string.g_fr_download, copy.primary.id)
    }

    @Test
    fun `short of space get ready says what it needs and what is free`() {
        val space = FirstRunProblem.NotEnoughSpace(SpaceShortfall(neededBytes = 1_108_045_575L, freeBytes = 600_000_000L))
        val copy = copy(getReady.copy(problem = space))

        assertEquals(CopyRes(R.string.g_fr_intro), copy.intro)
        assertEquals(CopyRes(R.string.g_fr_space_title), copy.title)
        assertEquals(CopyRes(R.string.g_fr_space_body, listOf("1.1\u00A0GB", "600\u00A0MB")), copy.body)
        assertTrue(copy.rows.isEmpty())
        assertNull(copy.network)
        assertEquals(CopyRes(R.string.g_fr_space_action), copy.primary)
    }

    @Test
    fun `getting ready asks for the microphone during the wait`() {
        val copy = copy(downloading)

        assertEquals(CopyRes(R.string.g_fr_getting_ready), copy.title)
        assertNull(copy.body)
        assertEquals(CopyRes(R.string.g_fr_part_speech), copy.part)
        assertEquals(CopyRes(R.string.g_fr_time_left, listOf(2)), copy.status)
        assertEquals(CopyRes(R.string.g_fr_pause), copy.quiet)
        assertEquals(listOf(R.string.g_fr_leave), ids(copy.notes))
        assertEquals(listOf(R.string.g_fr_mic_why, R.string.g_fr_mic_how), ids(copy.caption))
        assertEquals(CopyRes(R.string.g_fr_mic_action), copy.primary)
    }

    @Test
    fun `with the microphone allowed Record waits in its place, saying why`() {
        val copy = copy(downloading.copy(microphone = MicrophoneState.ALLOWED))

        assertEquals(listOf(R.string.g_fr_mic_ok, R.string.g_fr_leave), ids(copy.notes))
        assertEquals(listOf(R.string.g_fr_record_waits), ids(copy.caption))
        assertEquals(CopyRes(R.string.g_fr_record), copy.primary)
    }

    @Test
    fun `the line under the bar names the check, the last minute and a resume`() {
        assertEquals(CopyRes(R.string.g_fr_checking), copy(downloading.copy(progress = progress.copy(checking = true))).status)
        assertEquals(CopyRes(R.string.g_fr_time_under), copy(downloading.copy(progress = progress.copy(minutesLeft = 1))).status)
        assertEquals(
            CopyRes(R.string.g_onb_resuming, listOf(412)),
            copy(downloading.copy(progress = progress.copy(minutesLeft = null, resumedFromBytes = 412_400_000L))).status
        )
        assertNull(copy(downloading.copy(progress = progress.copy(minutesLeft = null))).status)
        assertEquals(CopyRes(R.string.g_fr_part_malayalam), copy(downloading.copy(progress = progress.copy(part = FirstRunPart.MALAYALAM))).part)
    }

    @Test
    fun `paused says so, and offers Continue`() {
        val copy = copy(downloading.copy(progress = progress.copy(paused = true)))

        assertEquals(CopyRes(R.string.g_fr_paused), copy.title)
        assertNull(copy.status)
        assertEquals(CopyRes(R.string.g_fr_resume), copy.quiet)
        assertTrue(copy.notes.isEmpty())
    }

    @Test
    fun `each problem has its title and one sentence`() {
        // problem, title, body
        val rows = listOf(
            Triple(FirstRunProblem.NoConnection, R.string.g_fr_err_conn_title, R.string.g_fr_err_conn_body),
            Triple(FirstRunProblem.WaitingForWifi, R.string.g_fr_err_wifi_title, R.string.g_fr_err_wifi_body),
            Triple(FirstRunProblem.Blocked, R.string.g_fr_err_blocked_title, R.string.g_fr_err_blocked_body),
            Triple(FirstRunProblem.ServerNotAnswering, R.string.g_fr_err_server_title, R.string.g_fr_err_server_body),
            Triple(FirstRunProblem.Damaged(again = false), R.string.g_fr_err_damaged_title, R.string.g_fr_err_damaged_body)
        )
        for ((problem, title, body) in rows) {
            val copy = copy(downloading.copy(problem = problem))
            assertEquals("$problem", CopyRes(title), copy.title)
            assertEquals("$problem", body, copy.body?.id)
            assertNull("$problem", copy.status)
        }
    }

    @Test
    fun `no connection keeps the leave line and offers Try now`() {
        val copy = copy(downloading.copy(problem = FirstRunProblem.NoConnection))

        assertEquals(CopyRes(R.string.g_fr_try_now), copy.quiet)
        assertEquals(listOf(R.string.g_fr_leave), ids(copy.notes))
        assertEquals(R.string.g_fr_mic_action, copy.primary.id)
    }

    @Test
    fun `waiting for Wi-Fi says what is left and offers mobile data as an outline pill`() {
        val copy = copy(downloading.copy(problem = FirstRunProblem.WaitingForWifi, progress = progress.copy(bytesDone = 380_000_000L)))

        assertEquals(CopyRes(R.string.g_fr_err_wifi_body, listOf("428\u00A0MB")), copy.body)
        assertEquals(CopyRes(R.string.g_fr_use_mobile), copy.outline)
        assertNull(copy.quiet)
        assertTrue(copy.notes.isEmpty())
    }

    @Test
    fun `blocked and a file damaged twice wait for Try again, before the microphone`() {
        for (problem in listOf(FirstRunProblem.Blocked, FirstRunProblem.Damaged(again = true))) {
            val copy = copy(downloading.copy(problem = problem))
            assertEquals("$problem", CopyRes(R.string.g_onb_retry), copy.primary)
            assertTrue("$problem", copy.caption.isEmpty())
            assertTrue("$problem", copy.notes.isEmpty())
        }
        assertNull(copy(downloading.copy(problem = FirstRunProblem.Damaged(again = true))).body)
    }

    @Test
    fun `a refused microphone is asked for again, then sent to the app's settings`() {
        val once = copy(downloading.copy(microphone = MicrophoneState.REFUSED_ONCE))
        val twice = copy(downloading.copy(microphone = MicrophoneState.REFUSED_FOR_GOOD))

        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_how), ids(once.caption))
        assertEquals(CopyRes(R.string.g_fr_mic_action), once.primary)
        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_settings), ids(twice.caption))
        assertEquals(CopyRes(R.string.g_rec_action_settings), twice.primary)
    }

    @Test
    fun `the microphone's words are the recorder's ask, in every state that asks`() {
        for (microphone in listOf(MicrophoneState.NOT_ALLOWED, MicrophoneState.REFUSED_ONCE, MicrophoneState.REFUSED_FOR_GOOD)) {
            for (state in listOf(downloading, ready, downloading.copy(problem = FirstRunProblem.NoConnection))) {
                val copy = copy(state.copy(microphone = microphone))
                val ask = microphoneAskCopy(microphone)
                assertEquals("$microphone", ask.caption, copy.caption)
                assertEquals("$microphone", ask.primary, copy.primary)
            }
        }
    }

    @Test
    fun `ready says one sentence, offers to look around, and records`() {
        val copy = copy(ready)

        assertEquals(CopyRes(R.string.g_fr_ready_title), copy.title)
        assertEquals(CopyRes(R.string.g_fr_ready_body), copy.body)
        assertNull(copy.part)
        assertNull(copy.status)
        assertEquals(CopyRes(R.string.g_fr_ready_look), copy.quiet)
        assertTrue(copy.notes.isEmpty())
        assertTrue(copy.caption.isEmpty())
        assertEquals(CopyRes(R.string.g_fr_record), copy.primary)
        assertEquals(
            listOf(R.string.g_fr_mic_why, R.string.g_fr_mic_how),
            ids(copy(ready.copy(microphone = MicrophoneState.NOT_ALLOWED)).caption)
        )
    }
}
