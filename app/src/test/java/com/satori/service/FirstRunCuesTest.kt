package com.satori.service

import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.model.AppSettings
import com.satori.domain.repository.SettingsRepository
import com.satori.presentation.main.ActiveRecorder
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first run's cues: Ready's buzz as Getting ready turns into it while the app is out of sight,
 * the first notes' buzz once ever, "Your notes are ready" only out of sight and when allowed, none
 * of it while a recording is live or, for the buzzes, with "Vibrate on start and stop" off; and the
 * field log's line for each. The cues run unconfined, so each change is dealt with before the next
 * line of a test.
 */
class FirstRunCuesTest {

    private val snapshot = MutableStateFlow(FirstRunSnapshot(stage = FirstRunStage.DOWNLOADING))
    private val settings = MutableStateFlow(AppSettings())
    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private var inFront = false
    private var allowed = true
    private val buzzed = mutableListOf<RecordingCues.Cue>()
    // The recordings each notification was posted for.
    private val notified = mutableListOf<String>()
    private val lines = mutableListOf<String>()
    private var firstNotesTaken = false
    // Notes-ready notifications counted as not allowed, as NotificationAsk counts them.
    private var missed = 0
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopCues() {
        scopes.forEach { it.cancel() }
    }

    private fun cues(): FirstRunCues {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return FirstRunCues(
            firstRun = Lazy { FirstRunAt(snapshot) },
            settings = HeldSettings(settings),
            recorder = recorder,
            inFront = { inFront },
            notificationsAllowed = { allowed },
            buzz = { buzzed += it },
            notify = { notified += it },
            firstNotes = FirstRunCues.OnceFlag {
                val first = !firstNotesTaken
                firstNotesTaken = true
                first
            },
            scope = scope,
            log = { lines += it },
            markNotesMissed = { ++missed }
        )
    }

    private fun setupDone() {
        snapshot.value = snapshot.value.copy(stage = FirstRunStage.READY)
    }

    @Test
    fun `Ready that comes while the app is out of sight buzzes once, its own shape`() {
        cues()

        setupDone()
        // Another change of the first run, at the same stage: no second buzz.
        snapshot.value = snapshot.value.copy(lookedAround = true)

        assertEquals(listOf(RecordingCues.Cue.SETUP_DONE), buzzed)
        assertEquals(listOf("cue=SETUP_DONE shown=true"), lines)
    }

    @Test
    fun `Ready in sight, beside a live recording, or with vibration off buzzes not, and the log says why`() {
        inFront = true
        cues()
        setupDone()
        assertEquals(listOf("cue=SETUP_DONE shown=false reason=IN_FRONT"), lines)

        inFront = false
        settings.value = AppSettings(vibrateOnStartStop = false)
        snapshot.value = snapshot.value.copy(stage = FirstRunStage.DOWNLOADING)
        setupDone()

        recorder.value = ActiveRecorder.Snapshot("s1", ActiveRecorder.Activity.RECORDING)
        snapshot.value = snapshot.value.copy(stage = FirstRunStage.DOWNLOADING)
        setupDone()

        assertTrue(buzzed.isEmpty())
        assertEquals(
            listOf(
                "cue=SETUP_DONE shown=false reason=IN_FRONT",
                "cue=SETUP_DONE shown=false reason=VIBRATION_OFF",
                "cue=SETUP_DONE shown=false reason=RECORDING"
            ),
            lines
        )
    }

    @Test
    fun `Ready at the first launch, from speech files put there by hand, is no cue`() {
        snapshot.value = FirstRunSnapshot(stage = FirstRunStage.GET_READY)
        cues()

        setupDone()

        assertTrue(buzzed.isEmpty())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the first notes buzz once ever, in sight or not, and the notification comes only out of sight`() {
        val cues = cues()
        inFront = true

        cues.notesReady("A")
        inFront = false
        cues.notesReady("B")

        assertEquals(listOf(RecordingCues.Cue.NOTES_READY), buzzed)
        // For the recording whose notes it says are ready, whose page its tap opens.
        assertEquals(listOf("B"), notified)
        assertEquals(
            listOf(
                "cue=NOTES_READY shown=true",
                "notification=NOTES_READY shown=false reason=IN_FRONT",
                "notification=NOTES_READY shown=true"
            ),
            lines
        )
    }

    @Test
    fun `notes ready while a recording is live get neither buzz nor notification, and the first notes' buzz is spent`() {
        val cues = cues()
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.STARTING)

        cues.notesReady("A")
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.IDLE)
        cues.notesReady("B")

        assertTrue(buzzed.isEmpty())
        assertEquals(listOf("B"), notified)
        assertEquals(
            listOf(
                "cue=NOTES_READY shown=false reason=RECORDING",
                "notification=NOTES_READY shown=false reason=RECORDING",
                "notification=NOTES_READY shown=true"
            ),
            lines
        )
    }

    @Test
    fun `without notifications allowed the notes are only buzzed for, the first time`() {
        allowed = false
        val cues = cues()

        cues.notesReady("A")

        assertEquals(listOf(RecordingCues.Cue.NOTES_READY), buzzed)
        assertTrue(notified.isEmpty())
        assertEquals("notification=NOTES_READY shown=false reason=NOT_ALLOWED missed=1", lines.last())
    }

    @Test
    fun `each notes ready that notifications weren't allowed for is counted, which makes home offer the ping`() {
        allowed = false
        val cues = cues()

        cues.notesReady("A")
        cues.notesReady("B")

        assertEquals(2, missed)
        assertEquals(
            listOf("notification=NOTES_READY shown=false reason=NOT_ALLOWED missed=1", "notification=NOTES_READY shown=false reason=NOT_ALLOWED missed=2"),
            lines.filter { it.startsWith("notification=") }
        )
    }

    @Test
    fun `notes ready in front, beside a live recording or with the notification shown count no miss`() {
        val cues = cues()
        inFront = true
        cues.notesReady("A")
        inFront = false
        recorder.value = ActiveRecorder.Snapshot("s2", ActiveRecorder.Activity.RECORDING)
        allowed = false
        cues.notesReady("B")
        recorder.value = null
        allowed = true
        cues.notesReady("C")

        assertEquals(0, missed)
        assertEquals(
            listOf(
                "notification=NOTES_READY shown=false reason=IN_FRONT",
                "notification=NOTES_READY shown=false reason=RECORDING",
                "notification=NOTES_READY shown=true"
            ),
            lines.filter { it.startsWith("notification=") }
        )
    }

    @Test
    fun `the rules, one condition at a time`() {
        val setup = RecordingCues.Cue.SETUP_DONE
        val notes = RecordingCues.Cue.NOTES_READY
        assertNull(FirstRunCues.cueQuiet(setup, recordingLive = false, inFront = false, vibrate = true))
        assertEquals(FirstRunCues.Quiet.IN_FRONT, FirstRunCues.cueQuiet(setup, recordingLive = false, inFront = true, vibrate = true))
        assertNull(FirstRunCues.cueQuiet(notes, recordingLive = false, inFront = true, vibrate = true))
        assertEquals(FirstRunCues.Quiet.RECORDING, FirstRunCues.cueQuiet(notes, recordingLive = true, inFront = false, vibrate = true))
        assertEquals(FirstRunCues.Quiet.VIBRATION_OFF, FirstRunCues.cueQuiet(notes, recordingLive = false, inFront = false, vibrate = false))

        assertNull(FirstRunCues.notificationQuiet(recordingLive = false, inFront = false, allowed = true))
        assertEquals(FirstRunCues.Quiet.RECORDING, FirstRunCues.notificationQuiet(recordingLive = true, inFront = false, allowed = true))
        assertEquals(FirstRunCues.Quiet.IN_FRONT, FirstRunCues.notificationQuiet(recordingLive = false, inFront = true, allowed = true))
        assertEquals(FirstRunCues.Quiet.NOT_ALLOWED, FirstRunCues.notificationQuiet(recordingLive = false, inFront = false, allowed = false))

        assertTrue(FirstRunCues.setupDone(FirstRunStage.DOWNLOADING, FirstRunStage.READY))
        for (before in listOf(null, FirstRunStage.GET_READY, FirstRunStage.READY, FirstRunStage.DONE)) {
            assertFalse("$before", FirstRunCues.setupDone(before, FirstRunStage.READY))
        }
        assertFalse(FirstRunCues.setupDone(FirstRunStage.DOWNLOADING, FirstRunStage.GET_READY))
    }

    /** A first run as [state] says, which asks nothing of the cues. */
    private class FirstRunAt(override val state: StateFlow<FirstRunSnapshot>) : FirstRunCoordinator {
        override fun start() = Unit
        override fun download(malayalam: Boolean, useMobileData: Boolean) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun retry(part: FirstRunPart) = Unit
        override fun useMobileData(part: FirstRunPart) = Unit
        override fun lookAround() = Unit
        override fun recordingSaved() = Unit
        override fun getBiggerModel() = Unit
    }

    /** The settings as [held] has them. */
    private class HeldSettings(private val held: MutableStateFlow<AppSettings>) : SettingsRepository {
        override fun getSettings(): Flow<AppSettings> = held
        override suspend fun updateSettings(settings: AppSettings) {
            held.value = settings
        }
        override suspend fun setHomeCategoryId(id: String) = Unit
        override suspend fun setMemoryConstrainedDetected(detected: Boolean) = Unit
        override suspend fun lowerConservativeThreadsCharThreshold(chars: Int) = Unit
        override suspend fun clearLlmModelPath() = Unit
    }
}
