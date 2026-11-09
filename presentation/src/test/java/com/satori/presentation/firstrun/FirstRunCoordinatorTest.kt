package com.satori.presentation.firstrun

import com.satori.data.firstrun.FirstRunStore
import com.satori.data.firstrun.StoredFirstRun
import com.satori.domain.firstrun.FirstRunPart.AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.BIGGER_AI_MODEL
import com.satori.domain.firstrun.FirstRunPart.MALAYALAM
import com.satori.domain.firstrun.FirstRunPart.READER
import com.satori.domain.firstrun.FirstRunPart.SPEECH
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.firstrun.SpeechFiles
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first run's coordinator against fakes of the disk, the downloads and the network, on
 * virtual time: the stage and the plan stored before they show, the parts in order, the retries,
 * the holds and what lifts them, the recorder's gate, a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunCoordinatorTest {

    private val store = FirstRunStore(InMemoryPrefs())
    private val files = FakeFiles()
    private val downloads = FakeDownloads()
    private val network = FakeNetwork()
    private val recorder = ActiveRecorder()
    private val settings = FirstRunSettings()

    private val unvalidated = NetworkFacts(connected = true, validated = false, metered = false)

    // The field log's lines, under SatoriFirstRun in the app.
    private val lines = mutableListOf<String>()

    private fun TestScope.coordinator(
        downloads: FakeDownloads = this@FirstRunCoordinatorTest.downloads,
        scope: CoroutineScope = backgroundScope
    ) = FirstRunCoordinatorImpl(
        store, files, downloads, network, recorder, settings, scope,
        clock = { testScheduler.currentTime }, wallClock = { WALL + testScheduler.currentTime },
        logLine = { lines += it }
    )

    private fun TestScope.started(): FirstRunCoordinatorImpl = coordinator().also {
        it.start()
        runCurrent()
    }

    /** Started, Download tapped, the setup's parts finished: Ready. */
    private fun TestScope.ready(malayalam: Boolean = true): FirstRunCoordinatorImpl = started().also {
        it.download(malayalam, useMobileData = false)
        runCurrent()
        finish(SPEECH)
        if (malayalam) finish(MALAYALAM)
    }

    private fun TestScope.finish(part: FirstRunPart) {
        files.checked += part
        downloads.set(part, DownloadState.Completed)
        runCurrent()
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun stored() = store.read(PART_BYTES::getValue)

    private fun starts(part: FirstRunPart) = downloads.requests.count { it == "start $part" }

    // ── The stage ────────────────────────────────────────────────────────────

    @Test
    fun `the stored stage is there as the coordinator is made, before it starts`() = runTest {
        store.write(StoredFirstRun(stage = FirstRunStage.DOWNLOADING, consentAtMs = WALL, plan = FirstRunPlan.initial(1L, null, null)))

        assertEquals(FirstRunStage.DOWNLOADING, coordinator().state.value.stage)
    }

    @Test
    fun `speech files gone after the setup start it over at once`() = runTest {
        store.write(StoredFirstRun(stage = FirstRunStage.DONE, firstRecordingSaved = true, plan = FirstRunPlan.initial(1L, null, null)))
        files.speech = SpeechFiles.MISSING

        val coordinator = coordinator()

        assertEquals(FirstRunStage.GET_READY, coordinator.state.value.stage)
        assertEquals(StoredFirstRun(stage = FirstRunStage.GET_READY, stageAtMs = WALL), stored())
    }

    @Test
    fun `files put there by hand on a fresh install open at Ready, with the AI model after the first recording`() = runTest {
        files.speech = SpeechFiles.CHECKED
        files.checked += SPEECH

        val coordinator = started()

        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertEquals(listOf(SPEECH, AI_MODEL), coordinator.state.value.plan.items.map { it.part })
        assertTrue(coordinator.state.value.aiModelPending)
        assertEquals(emptyList<String>(), downloads.requests)
    }

    @Test
    fun `Download stores the consent, the switch and the plan before anything downloads`() = runTest {
        val coordinator = started()

        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()

        val stored = stored()
        assertEquals(FirstRunStage.DOWNLOADING, stored.stage)
        assertEquals(WALL, stored.consentAtMs)
        assertTrue(stored.malayalamWanted)
        // With Malayalam and AI notes, the reader after the AI model (0.8.0).
        assertEquals(listOf(SPEECH, MALAYALAM, AI_MODEL, READER), stored.plan?.items?.map { it.part })
        assertEquals(listOf("start SPEECH"), downloads.requests)
    }

    @Test
    fun `the stage is stored before any snapshot shows it`() = runTest {
        val coordinator = started()
        val seen = mutableListOf<FirstRunStage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.collect { snapshot ->
                assertEquals(snapshot.stage, stored().stage)
                seen += snapshot.stage
            }
        }

        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        finish(SPEECH)
        coordinator.recordingSaved()
        runCurrent()

        assertEquals(FirstRunStage.entries.toList(), seen.distinct())
    }

    @Test
    fun `the setup downloads in order and Ready follows the last part's check, with no AI model before the first recording`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()

        finish(SPEECH)
        assertEquals(listOf("start SPEECH", "start MALAYALAM"), downloads.requests)
        assertEquals(FirstRunStage.DOWNLOADING, coordinator.state.value.stage)

        finish(MALAYALAM)
        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertTrue(stored().stage == FirstRunStage.READY)
        assertEquals(listOf("start SPEECH", "start MALAYALAM"), downloads.requests)
    }

    @Test
    fun `the first recording ends the first run and starts the AI model, which recordings wait for until it is checked`() = runTest {
        val coordinator = ready()

        coordinator.recordingSaved()
        runCurrent()

        assertEquals(FirstRunStage.DONE, coordinator.state.value.stage)
        assertEquals("start AI_MODEL", downloads.requests.last())
        assertTrue(coordinator.state.value.aiModelPending)
        assertTrue(stored().aiDownloadStarted)

        finish(AI_MODEL)
        assertFalse(coordinator.state.value.aiModelPending)
        // With Malayalam the reader comes after it (0.8.0), and then nothing.
        assertEquals("start READER", downloads.requests.last())
        assertTrue(coordinator.state.value.readerPending)
        assertEquals(READER, coordinator.state.value.later?.part)
        finish(READER)
        assertFalse(coordinator.state.value.readerPending)
        assertNull(coordinator.state.value.later)
    }

    @Test
    fun `a recording said to be saved before Ready ends nothing and starts no AI model`() = runTest {
        // A capture the app's death cut off before a reset, finished at Get ready and while downloading.
        val coordinator = started()
        coordinator.recordingSaved()
        runCurrent()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        coordinator.recordingSaved()
        runCurrent()
        assertFalse(stored().firstRecordingSaved)

        finish(SPEECH)

        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertEquals(listOf("start SPEECH"), downloads.requests)
        assertTrue(coordinator.state.value.firstRecordingAhead)
    }

    @Test
    fun `Look around first goes home and keeps the stage at Ready`() = runTest {
        val coordinator = ready()

        coordinator.lookAround()
        runCurrent()

        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertTrue(coordinator.state.value.lookedAround)
        assertTrue(stored().lookedAround)
        assertTrue(coordinator.state.value.firstRecordingAhead)
    }

    // ── Networks ─────────────────────────────────────────────────────────────

    @Test
    fun `on mobile data the AI model waits for Wi-Fi though Download was tapped there, until Use mobile data`() = runTest {
        network.facts.value = NetworkFacts.MOBILE
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = true)
        runCurrent()
        assertEquals(listOf("start SPEECH"), downloads.requests)
        finish(SPEECH)

        coordinator.recordingSaved()
        runCurrent()
        assertEquals("hold AI_MODEL WIFI_ONLY", downloads.requests.last())

        coordinator.useMobileData(AI_MODEL)
        runCurrent()
        assertEquals("start AI_MODEL", downloads.requests.last())
        assertEquals(setOf(AI_MODEL), stored().mobileOkParts)
    }

    @Test
    fun `Wait for Wi-Fi on mobile data holds the setup until Wi-Fi comes`() = runTest {
        network.facts.value = NetworkFacts.MOBILE
        val coordinator = started()

        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        assertEquals(listOf("hold SPEECH WIFI_ONLY"), downloads.requests)
        assertEquals(PauseReason.WIFI_ONLY, (coordinator.state.value.setup?.state as? DownloadState.Paused)?.reason)

        network.facts.value = NetworkFacts.WIFI
        runCurrent()
        assertEquals("start SPEECH", downloads.requests.last())
    }

    @Test
    fun `a failed connect is tried again after 5, 10 and 30 s, and at once when a network comes`() = runTest {
        network.facts.value = unvalidated
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()
        assertEquals("hold SPEECH NO_NETWORK", downloads.requests.last())
        advance(4_999L)
        assertEquals(1, starts(SPEECH))
        advance(1L)
        assertEquals(2, starts(SPEECH))

        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()
        advance(9_999L)
        assertEquals(2, starts(SPEECH))
        advance(1L)
        assertEquals(3, starts(SPEECH))

        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()
        advance(10_000L)
        assertEquals(3, starts(SPEECH))
        // A network Android reports: at once, not after the rest of the 30 s.
        network.facts.value = NetworkFacts.WIFI
        runCurrent()
        assertEquals(4, starts(SPEECH))
    }

    @Test
    fun `two failed connects on a validated network read as blocked, which waits for Try again`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()
        assertEquals("hold SPEECH NO_NETWORK", downloads.requests.last())
        advance(5_000L)
        assertEquals(2, starts(SPEECH))
        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()

        assertEquals("hold SPEECH BLOCKED", downloads.requests.last())
        advance(300_000L)
        assertEquals(2, starts(SPEECH))

        coordinator.retry(SPEECH)
        runCurrent()
        assertEquals(3, starts(SPEECH))
    }

    @Test
    fun `a firewall that drops every packet shows as blocked within 30 s`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        downloads.progress(SPEECH, 300_000_000L)
        runCurrent()

        // The firewall goes on at 0 s: the read under way stalls for its 15 s.
        advance(15_000L)
        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()
        assertEquals(PauseReason.NO_NETWORK, (coordinator.state.value.setup?.state as? DownloadState.Paused)?.reason)
        // The retry at 20 s: its connect gives up after the downloads' 5 s.
        advance(5_000L)
        assertEquals(2, starts(SPEECH))
        advance(5_000L)
        downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
        runCurrent()

        assertEquals(25_000L, testScheduler.currentTime)
        assertEquals(PauseReason.BLOCKED, (coordinator.state.value.setup?.state as? DownloadState.Paused)?.reason)
    }

    @Test
    fun `bytes between failed connects mean the connection works, so it isn't blocked`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        repeat(4) { attempt ->
            downloads.progress(SPEECH, (attempt + 1) * 10_000_000L)
            runCurrent()
            downloads.fail(SPEECH, DownloadFailure.NO_CONNECTION)
            runCurrent()
            assertEquals("hold SPEECH NO_NETWORK", downloads.requests.last())
            advance(5_000L)
        }
        assertEquals(5, starts(SPEECH))
        assertFalse(coordinator.state.value.setup?.state is DownloadState.Paused)
    }

    @Test
    fun `a socket Android refuses is blocked at once, and a network that comes again tries at once`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.fail(SPEECH, DownloadFailure.BLOCKED)
        runCurrent()
        assertEquals("hold SPEECH BLOCKED", downloads.requests.last())
        assertEquals(PauseReason.BLOCKED, (coordinator.state.value.setup?.state as? DownloadState.Paused)?.reason)

        network.facts.value = NetworkFacts.NONE
        runCurrent()
        network.facts.value = NetworkFacts.WIFI
        runCurrent()
        assertEquals(2, starts(SPEECH))
    }

    @Test
    fun `a server error is tried again in a few minutes`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.fail(SPEECH, DownloadFailure.SERVER)
        runCurrent()
        assertEquals("hold SPEECH SERVER", downloads.requests.last())
        advance(179_999L)
        assertEquals(1, starts(SPEECH))
        advance(1L)
        assertEquals(2, starts(SPEECH))
        assertEquals(1, coordinator.state.value.plan.item(SPEECH)?.serverErrors)
    }

    // ── Damaged files ────────────────────────────────────────────────────────

    @Test
    fun `Malayalam is dropped after two damaged files, and Ready shows without it`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()
        finish(SPEECH)

        downloads.fail(MALAYALAM, DownloadFailure.OTHER)
        runCurrent()
        assertEquals(2, starts(MALAYALAM))
        downloads.fail(MALAYALAM, DownloadFailure.OTHER)
        runCurrent()

        assertEquals(FirstRunPlan.Status.DROPPED, coordinator.state.value.plan.item(MALAYALAM)?.status)
        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertEquals(2, starts(MALAYALAM))

        // The strip's Try again plans it again, and it downloads after the setup.
        coordinator.retry(MALAYALAM)
        runCurrent()
        assertEquals(3, starts(MALAYALAM))
        assertEquals(MALAYALAM, coordinator.state.value.later?.part)
    }

    @Test
    fun `speech recognition damaged twice waits for Try again`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.fail(SPEECH, DownloadFailure.OTHER)
        runCurrent()
        downloads.fail(SPEECH, DownloadFailure.OTHER)
        runCurrent()
        advance(120_000L)

        assertEquals(2, starts(SPEECH))
        assertTrue(coordinator.state.value.plan.item(SPEECH)?.waitsForTap == true)

        coordinator.retry(SPEECH)
        runCurrent()
        assertEquals(3, starts(SPEECH))
    }

    @Test
    fun `a finished download whose files fail their check is fetched again from nothing`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.set(SPEECH, DownloadState.Completed)
        runCurrent()

        assertEquals(listOf(SPEECH), files.discarded)
        assertEquals(listOf("start SPEECH", "stop SPEECH", "start SPEECH"), downloads.requests)
        assertEquals(1, coordinator.state.value.plan.item(SPEECH)?.damaged)
    }

    // ── Pause, space, the disk ───────────────────────────────────────────────

    @Test
    fun `Pause holds the setup, also after the app was closed, and Continue goes on`() = runTest {
        val first = CoroutineScope(backgroundScope.coroutineContext + Job())
        val before = coordinator(scope = first)
        before.start()
        runCurrent()
        before.download(malayalam = false, useMobileData = false)
        runCurrent()

        before.pause()
        runCurrent()
        assertEquals("hold SPEECH USER", downloads.requests.last())
        assertTrue(stored().userPaused)
        assertTrue(before.state.value.setup?.state.let { it is DownloadState.Paused && it.reason == PauseReason.USER })
        first.cancel()

        val afterRestart = FakeDownloads()
        val after = coordinator(downloads = afterRestart)
        after.start()
        runCurrent()
        assertEquals(listOf("hold SPEECH USER"), afterRestart.requests)

        after.resume()
        runCurrent()
        assertEquals("start SPEECH", afterRestart.requests.last())
        assertFalse(stored().userPaused)
    }

    @Test
    fun `a download that doesn't fit waits, says what it lacks, and goes on once there is room`() = runTest {
        files.free = 100_000_000L
        val coordinator = started()

        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        assertEquals(listOf("hold SPEECH NOT_ENOUGH_STORAGE"), downloads.requests)
        assertEquals(SpaceShortfall(PART_BYTES.getValue(SPEECH) + FirstRunPlan.HEADROOM_BYTES, 100_000_000L), coordinator.state.value.space)

        files.free = 10_000_000_000L
        advance(FirstRunCoordinatorImpl.POLL_MS)
        assertEquals("start SPEECH", downloads.requests.last())
        assertNull(coordinator.state.value.space)
    }

    @Test
    fun `files that appear while the download can't go on count as downloaded`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        downloads.fail(SPEECH, DownloadFailure.BLOCKED)
        runCurrent()

        // Copied into the models folder by hand.
        files.checked += SPEECH
        advance(FirstRunCoordinatorImpl.POLL_MS)

        assertEquals(FirstRunStage.READY, coordinator.state.value.stage)
        assertEquals(FirstRunPlan.Status.DONE, coordinator.state.value.plan.item(SPEECH)?.status)
        // The hold leaves its place, which would keep the download service.
        assertEquals("stop SPEECH", downloads.requests.last())
    }

    @Test
    fun `speech recognition downloaded again from Settings doesn't start the first run over`() = runTest {
        files.speech = SpeechFiles.CHECKED
        files.checked += SPEECH
        val coordinator = started()
        coordinator.recordingSaved()
        runCurrent()
        assertEquals(FirstRunStage.DONE, coordinator.state.value.stage)

        // Settings' Download again deletes the files and fetches them anew; the app comes back to the front.
        files.speech = SpeechFiles.MISSING
        files.checked -= SPEECH
        downloads.set(SPEECH, DownloadState.Downloading(DownloadProgress(10_000_000L, PART_BYTES.getValue(SPEECH), 1)))
        runCurrent()
        coordinator.start()
        runCurrent()
        assertEquals(FirstRunStage.DONE, coordinator.state.value.stage)
        assertTrue(stored().firstRecordingSaved)

        // Gone, with nothing on its way: the first run starts over, as the earlier onboarding did.
        downloads.set(SPEECH, DownloadState.Idle)
        coordinator.start()
        runCurrent()
        assertEquals(FirstRunStage.GET_READY, coordinator.state.value.stage)
    }

    @Test
    fun `nothing is decided before the settings and the disk are read`() = runTest {
        files.speech = SpeechFiles.CHECKED
        files.checked += SPEECH
        store.write(
            StoredFirstRun(
                stage = FirstRunStage.DONE, consentAtMs = WALL, firstRecordingSaved = true,
                plan = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), null, PART_BYTES.getValue(AI_MODEL)).done(SPEECH)
            )
        )
        // AI notes are off, which the app's settings say only after a moment.
        val slow = SlowSettings(AppSettings(llmEnabled = false), delayMs = 1_000L)
        val coordinator = FirstRunCoordinatorImpl(
            store, files, downloads, network, recorder, slow, backgroundScope,
            clock = { testScheduler.currentTime }, wallClock = { WALL + testScheduler.currentTime }
        )

        coordinator.start()
        runCurrent()
        assertEquals(0, starts(AI_MODEL))
        advance(2_000L)

        assertEquals(0, starts(AI_MODEL))
    }

    @Test
    fun `a download that went on after the app was closed says where from, until the time left shows`() = runTest {
        store.write(StoredFirstRun(stage = FirstRunStage.DOWNLOADING, consentAtMs = WALL, plan = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), PART_BYTES.getValue(MALAYALAM), null)))
        files.onPhone[SPEECH] = 412_000_000L

        val coordinator = started()

        assertEquals("start SPEECH", downloads.requests.last())
        val setup = coordinator.state.value.setup
        assertEquals(412_000_000L, setup?.resumedFromBytes)
        assertEquals(412_000_000L, setup?.bytesDone)
        assertEquals(PART_BYTES.getValue(SPEECH) + PART_BYTES.getValue(MALAYALAM), setup?.bytesTotal)
    }

    // ── Progress ─────────────────────────────────────────────────────────────

    @Test
    fun `Getting ready's one bar counts the setup's parts together, and its time left shows after 10 s`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()

        // 10 MB a second.
        for (second in 1..5) {
            advance(1_000L)
            downloads.progress(SPEECH, second * 10_000_000L)
            runCurrent()
        }
        assertNull(coordinator.state.value.setup?.minutesLeft)
        for (second in 6..12) {
            advance(1_000L)
            downloads.progress(SPEECH, second * 10_000_000L)
            runCurrent()
        }
        // 688 MB left of 808 MB at 10 MB/s: 69 s, rounded up.
        assertEquals(2, coordinator.state.value.setup?.minutesLeft)

        finish(SPEECH)
        downloads.progress(MALAYALAM, 50_000_000L)
        runCurrent()
        assertEquals(PART_BYTES.getValue(SPEECH) + 50_000_000L, coordinator.state.value.setup?.bytesDone)
        assertEquals(MALAYALAM, coordinator.state.value.setup?.part)
    }

    @Test
    fun `Getting ready's time left goes on from one part of the setup to the next, and starts over after a hold`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()
        for (second in 1..12) {
            advance(1_000L)
            downloads.progress(SPEECH, second * 10_000_000L)
            runCurrent()
        }
        val shown = coordinator.state.value.setup?.minutesLeft
        assertEquals(2, shown)

        finish(SPEECH)

        assertEquals(MALAYALAM, coordinator.state.value.setup?.part)
        assertEquals(shown, coordinator.state.value.setup?.minutesLeft)

        // A hold is a new run: its time left shows again once bytes come for 10 s.
        network.facts.value = NetworkFacts.NONE
        runCurrent()
        assertNull(coordinator.state.value.setup?.minutesLeft)
    }

    @Test
    fun `a file being checked shows as checked`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        downloads.set(SPEECH, DownloadState.Verifying(DownloadProgress(652_184_281L, PART_BYTES.getValue(SPEECH), 97)))
        runCurrent()

        assertTrue(coordinator.state.value.setup?.state is DownloadState.Verifying)
        assertEquals(652_184_281L, coordinator.state.value.setup?.bytesDone)
    }

    // ── The AI models ────────────────────────────────────────────────────────

    @Test
    fun `neither AI model downloads while AI notes are off, and the AI model does once they are on`() = runTest {
        val coordinator = ready(malayalam = false)
        settings.settings.value = AppSettings(llmEnabled = false)
        runCurrent()

        coordinator.recordingSaved()
        runCurrent()
        assertEquals(0, starts(AI_MODEL))

        settings.settings.value = AppSettings(llmEnabled = true)
        runCurrent()
        assertEquals(1, starts(AI_MODEL))
    }

    @Test
    fun `an AI model that writes the notes, put on the phone meanwhile, is not downloaded`() = runTest {
        val coordinator = ready(malayalam = false)
        // Downloaded in Advanced settings before the first recording.
        files.aiModel = true

        coordinator.recordingSaved()
        runCurrent()

        assertEquals(0, starts(AI_MODEL))
        assertFalse(coordinator.state.value.aiModelPending)
    }

    @Test
    fun `AI notes switched off while the AI model waits let its hold go, so nothing keeps the download service`() = runTest {
        val coordinator = ready(malayalam = false)
        coordinator.recordingSaved()
        runCurrent()
        network.facts.value = NetworkFacts.MOBILE
        runCurrent()
        assertEquals("hold AI_MODEL WIFI_ONLY", downloads.requests.last())

        settings.settings.value = AppSettings(llmEnabled = false)
        runCurrent()

        assertEquals("stop AI_MODEL", downloads.requests.last())
        assertEquals(DownloadState.Idle, downloads.state(AI_MODEL))
        assertNull(coordinator.state.value.later)
    }

    @Test
    fun `an AI model the registry knows only roughly takes its size from its download`() = runTest {
        val coordinator = ready(malayalam = false)
        coordinator.recordingSaved()
        runCurrent()

        downloads.set(AI_MODEL, DownloadState.Downloading(DownloadProgress(100_000_000L, 650_123_456L, 15)))
        runCurrent()

        assertEquals(650_123_456L, coordinator.state.value.plan.item(AI_MODEL)?.bytes)
        assertEquals(650_123_456L, coordinator.state.value.later?.bytesTotal)
        assertEquals(100_000_000L, coordinator.state.value.later?.bytesDone)
    }

    @Test
    fun `an AI model on the phone already is not planned`() = runTest {
        files.aiModel = true
        val coordinator = started()

        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        assertEquals(listOf(SPEECH), coordinator.state.value.plan.items.map { it.part })
        assertFalse(coordinator.state.value.aiModelPending)
    }

    @Test
    fun `the bigger AI model is planned on its offer and downloads after the AI model`() = runTest {
        val coordinator = ready(malayalam = false)
        coordinator.recordingSaved()
        runCurrent()

        coordinator.getBiggerModel()
        runCurrent()
        assertEquals(0, starts(BIGGER_AI_MODEL))

        finish(AI_MODEL)
        assertEquals("start BIGGER_AI_MODEL", downloads.requests.last())
        assertEquals(BIGGER_AI_MODEL, coordinator.state.value.later?.part)
    }

    @Test
    fun `a download of the AI model another screen started is left to run, on mobile data too`() = runTest {
        val coordinator = ready(malayalam = false)
        network.facts.value = NetworkFacts.MOBILE
        runCurrent()
        // Settings' "Get the AI model", tapped on a button that showed its size.
        downloads.set(AI_MODEL, DownloadState.Downloading(DownloadProgress(100_000_000L, PART_BYTES.getValue(AI_MODEL), 9)))
        runCurrent()

        coordinator.recordingSaved()
        runCurrent()

        assertEquals(listOf("start SPEECH"), downloads.requests)
        assertEquals(AI_MODEL, coordinator.state.value.later?.part)
        assertEquals(100_000_000L, coordinator.state.value.later?.bytesDone)
    }

    // ── The reader (0.8.0, design R7) ────────────────────────────────────────

    /**
     * An install whose first run is over: speech recognition, Malayalam (when [malayalam]) and the
     * AI model on the phone and done, with the plan [plan]; the reader's files as the test sets them.
     */
    private fun setUpInstall(malayalam: Boolean = true, plan: FirstRunPlan? = null) {
        val parts = listOfNotNull(SPEECH, MALAYALAM.takeIf { malayalam }, AI_MODEL)
        val done = plan ?: parts.fold(
            FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), PART_BYTES.getValue(MALAYALAM).takeIf { malayalam }, PART_BYTES.getValue(AI_MODEL))
        ) { found, part -> found.done(part) }
        store.write(StoredFirstRun(stage = FirstRunStage.DONE, consentAtMs = WALL, firstRecordingSaved = true, malayalamWanted = malayalam, plan = done))
        files.speech = SpeechFiles.CHECKED
        files.checked += parts
        files.aiModel = true
    }

    @Test
    fun `without Malayalam no reader is planned, and the AI model is the last download`() = runTest {
        val coordinator = ready(malayalam = false)
        coordinator.recordingSaved()
        runCurrent()
        finish(AI_MODEL)

        assertNull(coordinator.state.value.plan.item(READER))
        assertEquals(0, starts(READER))
        assertFalse(coordinator.state.value.readerPending)
    }

    @Test
    fun `an install with Malayalam and AI notes gets the reader at the start, and its files on the phone count after one read, nothing downloaded`() = runTest {
        setUpInstall()
        // The int8 build the debug tools fetched before 0.8.0-dev3: whole, its SHA-256 never read.
        files.checked += READER
        files.unread += READER

        val coordinator = started()

        assertEquals(FirstRunPlan.Status.DONE, coordinator.state.value.plan.item(READER)?.status)
        assertEquals(listOf(READER), files.reads)
        assertFalse(coordinator.state.value.readerPending)
        assertEquals(emptyList<String>(), downloads.requests)
        assertTrue(lines.contains("part=reader planned"))
        assertTrue(lines.contains("model_folder_changed part=reader ready=true"))
        assertEquals(FirstRunPlan.Status.DONE, stored().plan?.item(READER)?.status)
        // Found once: what the reader's availability follows besides its part's status.
        assertEquals(1, coordinator.state.value.readerFound)

        // The next start reads nothing again and plans nothing.
        coordinator().also { it.start() }
        runCurrent()
        assertEquals(listOf(READER), files.reads)
        assertEquals(emptyList<String>(), downloads.requests)
    }

    @Test
    fun `an install from before the first run's plans gets the reader too, and downloads it at DONE without the first recording's flag`() = runTest {
        store.write(StoredFirstRun(stage = FirstRunStage.DONE))
        files.speech = SpeechFiles.CHECKED
        files.checked += listOf(SPEECH, MALAYALAM)
        files.aiModel = true

        val coordinator = started()

        assertEquals(listOf(READER), coordinator.state.value.plan.items.map { it.part })
        assertTrue(coordinator.state.value.readerPending)
        assertEquals(listOf("start READER"), downloads.requests)
        assertEquals(READER, coordinator.state.value.later?.part)
    }

    @Test
    fun `the reader's SHA-256 is not read while a recorder is busy, and its download that ends then waits for its check`() = runTest {
        setUpInstall()
        files.checked += READER
        files.unread += READER
        val owner = Any()
        recorder.open(owner, "s1", ActiveRecorder.Activity.RECORDING)

        val coordinator = started()

        assertEquals(emptyList<FirstRunPart>(), files.reads)
        assertTrue(coordinator.state.value.readerPending)
        // The queue asks for its download, which the recorder's gate holds; whole, it fetches nothing.
        assertEquals("start READER", downloads.requests.last())
        downloads.set(READER, DownloadState.Completed)
        runCurrent()
        assertEquals(emptyList<FirstRunPart>(), files.reads)
        assertEquals(emptyList<FirstRunPart>(), files.discarded)
        assertTrue(coordinator.state.value.readerPending)
        assertTrue(lines.contains("part=reader state=VERIFYING reason=RECORDING"))

        recorder.report(owner, "s1", ActiveRecorder.Activity.IDLE)
        runCurrent()
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS)
        downloads.set(READER, DownloadState.Completed)
        runCurrent()

        assertEquals(listOf(READER), files.reads)
        assertEquals(FirstRunPlan.Status.DONE, coordinator.state.value.plan.item(READER)?.status)
        assertEquals(emptyList<FirstRunPart>(), files.discarded)
        // Its download's end found it.
        assertEquals(1, coordinator.state.value.readerFound)
    }

    @Test
    fun `the Malayalam model got through Settings plans the reader, which then downloads`() = runTest {
        setUpInstall(malayalam = false)
        val coordinator = started()
        assertNull(coordinator.state.value.plan.item(READER))

        downloads.set(MALAYALAM, DownloadState.Downloading(DownloadProgress(1L, PART_BYTES.getValue(MALAYALAM), 0)))
        runCurrent()
        assertNull(coordinator.state.value.plan.item(READER))
        files.checked += MALAYALAM
        downloads.set(MALAYALAM, DownloadState.Completed)
        runCurrent()

        assertEquals(FirstRunPlan.Status.PENDING, coordinator.state.value.plan.item(READER)?.status)
        assertEquals("start READER", downloads.requests.last())
    }

    @Test
    fun `a reader dropped after its Try again is planned anew, its failures forgotten, when the owner gets the Malayalam model through Settings`() = runTest {
        val bytes = PART_BYTES.getValue(READER)
        val base = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), PART_BYTES.getValue(MALAYALAM), PART_BYTES.getValue(AI_MODEL))
            .done(SPEECH).done(MALAYALAM).done(AI_MODEL)
        val dropped = base.withReader(bytes).failed(READER, DownloadFailure.OTHER).failed(READER, DownloadFailure.OTHER)
        setUpInstall(plan = dropped)
        val coordinator = started()
        assertEquals(FirstRunPlan.Status.DROPPED, coordinator.state.value.plan.item(READER)?.status)

        // Settings' Malayalam entry downloads the model again.
        downloads.set(MALAYALAM, DownloadState.Downloading(DownloadProgress(1L, PART_BYTES.getValue(MALAYALAM), 0)))
        runCurrent()
        downloads.set(MALAYALAM, DownloadState.Completed)
        runCurrent()

        val reader = coordinator.state.value.plan.item(READER)
        assertEquals(FirstRunPlan.Status.PENDING, reader?.status)
        assertEquals(0, reader?.damaged)
        assertTrue(lines.contains("part=reader planned again"))
        assertEquals("start READER", downloads.requests.last())
    }

    @Test
    fun `with AI notes off no reader is planned or downloaded, and once they are on it is`() = runTest {
        setUpInstall()
        settings.settings.value = AppSettings(llmEnabled = false)

        val coordinator = started()
        assertNull(coordinator.state.value.plan.item(READER))

        settings.settings.value = AppSettings(llmEnabled = true)
        runCurrent()
        assertTrue(coordinator.state.value.readerPending)
        assertEquals(listOf("start READER"), downloads.requests)

        // Off again while it downloads: it stops, and the plan keeps it for later.
        settings.settings.value = AppSettings(llmEnabled = false)
        runCurrent()
        assertEquals("stop READER", downloads.requests.last())
        assertTrue(coordinator.state.value.readerPending)
    }

    @Test
    fun `a reader whose files went since it was done is planned again, and a dropped one stays dropped`() = runTest {
        val bytes = PART_BYTES.getValue(READER)
        val base = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), PART_BYTES.getValue(MALAYALAM), PART_BYTES.getValue(AI_MODEL))
            .done(SPEECH).done(MALAYALAM).done(AI_MODEL)
        setUpInstall(plan = base.withReader(bytes).done(READER))

        val coordinator = started()
        assertTrue(coordinator.state.value.readerPending)
        assertTrue(lines.contains("part=reader planned again"))
        assertEquals(listOf("start READER"), downloads.requests)

        val dropped = base.withReader(bytes).failed(READER, DownloadFailure.OTHER).failed(READER, DownloadFailure.OTHER)
        store.write(stored().copy(plan = dropped))
        val restarted = coordinator()
        restarted.start()
        runCurrent()
        assertEquals(FirstRunPlan.Status.DROPPED, restarted.state.value.plan.item(READER)?.status)
        assertFalse(restarted.state.value.readerPending)
    }

    @Test
    fun `a reader damaged twice is dropped alone, Malayalam stays, and Try again plans it anew`() = runTest {
        val coordinator = ready()
        coordinator.recordingSaved()
        runCurrent()
        finish(AI_MODEL)
        assertEquals(1, starts(READER))

        downloads.fail(READER, DownloadFailure.OTHER)
        runCurrent()
        assertEquals(2, starts(READER))
        downloads.fail(READER, DownloadFailure.OTHER)
        runCurrent()

        assertEquals(FirstRunPlan.Status.DROPPED, coordinator.state.value.plan.item(READER)?.status)
        assertEquals(FirstRunPlan.Status.DONE, coordinator.state.value.plan.item(MALAYALAM)?.status)
        assertFalse(coordinator.state.value.readerPending)
        assertEquals(emptyList<FirstRunPart>(), files.discarded)

        coordinator.retry(READER)
        runCurrent()
        assertEquals(3, starts(READER))
        assertTrue(coordinator.state.value.readerPending)
    }

    // ── The recorder ─────────────────────────────────────────────────────────

    @Test
    fun `every download waits while a recorder starts, records or saves, and goes on 3 s after`() = runTest {
        started()
        val owner = Any()

        recorder.open(owner, "s1", ActiveRecorder.Activity.STARTING)
        runCurrent()
        assertEquals(listOf(true), downloads.gate)

        recorder.report(owner, "s1", ActiveRecorder.Activity.RECORDING)
        runCurrent()
        recorder.report(owner, "s1", ActiveRecorder.Activity.SAVING)
        runCurrent()
        // Saving takes its time: the downloads stay held all through it.
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS * 2)
        assertEquals(listOf(true), downloads.gate)

        recorder.report(owner, "s1", ActiveRecorder.Activity.IDLE)
        runCurrent()
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS - 1)
        assertEquals(listOf(true), downloads.gate)
        advance(1L)
        assertEquals(listOf(true, false), downloads.gate)
    }

    @Test
    fun `a quick key start right after Stop keeps the downloads held`() = runTest {
        started()
        val owner = Any()
        recorder.open(owner, "s1", ActiveRecorder.Activity.RECORDING)
        runCurrent()
        recorder.leave(owner)
        runCurrent()
        advance(1_000L)

        recorder.expect("s2")
        runCurrent()
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS)

        // Held again, which changes nothing; never let go in between.
        assertFalse(false in downloads.gate)
    }

    @Test
    fun `a part held by a recording is not started again under it`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()

        // The download manager's gate holds it.
        downloads.set(SPEECH, DownloadState.Paused(PauseReason.RECORDING, DownloadProgress(5L, 10L, 50)))
        runCurrent()
        advance(30_000L)

        assertEquals(listOf("start SPEECH"), downloads.requests)
    }

    // ── The field log ────────────────────────────────────────────────────────

    @Test
    fun `Continue after Pause says the owner resumed it, also after the app came back, and no Resuming from`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        downloads.progress(SPEECH, 110_000_000L)
        runCurrent()
        coordinator.pause()
        runCurrent()
        files.onPhone[SPEECH] = 110_000_000L
        // The app comes back to the front while paused.
        coordinator.start()
        runCurrent()

        coordinator.resume()
        runCurrent()

        assertEquals("start SPEECH", downloads.requests.last())
        assertTrue(lines.contains("resume part=speech bytes=110 MB source=user"))
        assertTrue(lines.none { it.contains("source=app_start") })
        assertNull(coordinator.state.value.setup?.resumedFromBytes)
    }

    @Test
    fun `a download that goes on as the app starts says so, with Resuming from`() = runTest {
        store.write(
            StoredFirstRun(stage = FirstRunStage.DOWNLOADING, consentAtMs = WALL, plan = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), null, null))
        )
        files.onPhone[SPEECH] = 412_000_000L

        val coordinator = started()

        assertTrue(lines.contains("resume part=speech bytes=412 MB source=app_start"))
        assertEquals(412_000_000L, coordinator.state.value.setup?.resumedFromBytes)
    }

    @Test
    fun `a part's last check ends its progress lines, with none for its last bytes`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = true, useMobileData = false)
        runCurrent()
        finish(SPEECH)
        val total = PART_BYTES.getValue(MALAYALAM)
        downloads.progress(MALAYALAM, total / 2)
        runCurrent()

        downloads.set(MALAYALAM, DownloadState.Verifying(DownloadProgress(total, total, 100)))
        runCurrent()
        downloads.progress(MALAYALAM, total)
        runCurrent()

        val malayalam = lines.filter { it.startsWith("part=malayalam") }
        assertEquals(
            listOf(
                "part=malayalam state=RUNNING bytes=0/137 MB",
                "part=malayalam state=RUNNING bytes=68/137 MB",
                "part=malayalam state=VERIFYING bytes=137/137 MB"
            ),
            malayalam
        )
        assertEquals(FirstRunStage.DOWNLOADING, coordinator.state.value.stage)
    }

    @Test
    fun `files found on the phone say how they came, a download's end or put there another way`() = runTest {
        store.write(
            StoredFirstRun(
                stage = FirstRunStage.DONE, consentAtMs = WALL, firstRecordingSaved = true, malayalamWanted = true,
                plan = FirstRunPlan.initial(PART_BYTES.getValue(SPEECH), PART_BYTES.getValue(MALAYALAM), PART_BYTES.getValue(AI_MODEL)).done(SPEECH)
            )
        )
        files.speech = SpeechFiles.CHECKED
        files.checked += setOf(SPEECH, MALAYALAM, AI_MODEL)
        // Malayalam's download ended as the app was closed; the AI model was copied in by hand.
        downloads.set(MALAYALAM, DownloadState.Completed)

        started()

        assertTrue(lines.contains("part=malayalam state=DONE"))
        assertTrue(lines.contains("model_folder_changed part=ai_model ready=true"))
        assertTrue(lines.none { it == "model_folder_changed part=malayalam ready=true" })
    }

    @Test
    fun `a recording that holds a download says so in the field log, and so does its going on`() = runTest {
        val coordinator = started()
        coordinator.download(malayalam = false, useMobileData = false)
        runCurrent()
        downloads.progress(SPEECH, 100_000_000L)
        runCurrent()
        // No recorder as the app starts: nothing was held, so nothing goes on.
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS)
        assertTrue(lines.none { it.endsWith("source=recording_saved") })
        val owner = Any()

        recorder.open(owner, "s1", ActiveRecorder.Activity.RECORDING)
        runCurrent()
        assertTrue(lines.contains("part=speech state=PAUSED reason=RECORDING"))

        recorder.report(owner, "s1", ActiveRecorder.Activity.IDLE)
        runCurrent()
        advance(FirstRunCoordinatorImpl.RESUME_AFTER_RECORDING_MS)
        assertEquals("resume part=speech source=recording_saved", lines.last())
    }

    private companion object {
        /** The wall clock at the start of every test, in epoch ms. */
        const val WALL = 1_760_000_000_000L
    }
}
