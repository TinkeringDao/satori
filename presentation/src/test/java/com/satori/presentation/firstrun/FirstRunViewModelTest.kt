package com.satori.presentation.firstrun

import com.satori.data.firstrun.FirstRunStore
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {

    private val main = StandardTestDispatcher()
    private val coordinator = FakeFirstRunCoordinator()
    private val files = FakeFiles()
    private val store = FirstRunStore(InMemoryPrefs())
    private val settings = FirstRunSettings()
    private var microphoneGranted = false
    private var now = 10_000L

    private val setupBytes = PART_BYTES.getValue(FirstRunPart.SPEECH) + PART_BYTES.getValue(FirstRunPart.MALAYALAM)
    private val plan = FirstRunPlan.initial(PART_BYTES.getValue(FirstRunPart.SPEECH), PART_BYTES.getValue(FirstRunPart.MALAYALAM), PART_BYTES.getValue(FirstRunPart.AI_MODEL))

    @Before
    fun setMain() {
        Dispatchers.setMain(main)
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = FirstRunViewModel(coordinator, files, store, settings, { microphoneGranted }, { now }, main)

    private fun downloading(state: DownloadState, part: FirstRunPart = FirstRunPart.SPEECH, plan: FirstRunPlan = this.plan) = FirstRunSnapshot(
        stage = FirstRunStage.DOWNLOADING,
        plan = plan,
        setup = FirstRunProgress(part, state, bytesDone = 276_000_000L, bytesTotal = setupBytes)
    )

    private fun stateOf(snapshot: FirstRunSnapshot, free: Long? = null) =
        FirstRunViewModel.stateOf(snapshot, switch = true, mic = MicrophoneState.NOT_ALLOWED, sizes = SetupSizes(1L, 2L, 3L), free = free)

    // ── What the screens draw ────────────────────────────────────────────────

    @Test
    fun `Get ready lists the registry's sizes, the AI model only when it downloads after the first recording`() = runTest(main) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            SetupSizes(
                PART_BYTES.getValue(FirstRunPart.SPEECH),
                PART_BYTES.getValue(FirstRunPart.MALAYALAM),
                PART_BYTES.getValue(FirstRunPart.AI_MODEL),
                PART_BYTES.getValue(FirstRunPart.READER)
            ),
            viewModel.uiState.value.sizes
        )

        files.aiModel = true
        val withModel = viewModel()
        advanceUntilIdle()
        assertEquals(0L, withModel.uiState.value.sizes.aiModelBytes)
        assertEquals(PART_BYTES.getValue(FirstRunPart.READER), withModel.uiState.value.sizes.readerBytes)

        settings.settings.value = AppSettings(llmEnabled = false)
        files.aiModel = false
        val notesOff = viewModel()
        advanceUntilIdle()
        assertEquals(0L, notesOff.uiState.value.sizes.aiModelBytes)
        assertEquals(0L, notesOff.uiState.value.sizes.readerBytes)
    }

    @Test
    fun `Get ready leaves the reader out of the AI notes row when it is on the phone, and counts it only with Malayalam's switch`() = runTest(main) {
        files.checked += FirstRunPart.READER
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(0L, viewModel.uiState.value.sizes.readerBytes)

        val sizes = SetupSizes(speechBytes = 1L, malayalamBytes = 2L, aiModelBytes = 3L, readerBytes = 4L)
        assertEquals(7L, sizes.aiNotesBytes(malayalam = true))
        assertEquals(3L, sizes.aiNotesBytes(malayalam = false))
        // The button itself downloads the setup alone, as before.
        assertEquals(3L, sizes.download(malayalam = true))
    }

    @Test
    fun `Get ready leaves Malayalam out when it is on the phone, as after speech recognition's files went`() = runTest(main) {
        files.checked += FirstRunPart.MALAYALAM

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.sizes.malayalamBytes)
        // The button then downloads speech recognition alone, whatever the switch.
        assertEquals(PART_BYTES.getValue(FirstRunPart.SPEECH), viewModel.uiState.value.downloadBytes)
    }

    @Test
    fun `Get ready says before anything starts that the download doesn't fit, and reads the space again on the way back`() = runTest(main) {
        files.free = 600_000_000L
        val viewModel = viewModel()
        advanceUntilIdle()

        val problem = viewModel.uiState.value.problem
        assertEquals(FirstRunProblem.NotEnoughSpace(SpaceShortfall(setupBytes + FirstRunPlan.HEADROOM_BYTES, 600_000_000L)), problem)
        assertEquals(FirstRunFooter.OPEN_STORAGE_SETTINGS, viewModel.uiState.value.footer)

        files.free = 10_000_000_000L
        viewModel.microphoneRechecked(granted = false)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.problem)
        assertEquals(FirstRunFooter.DOWNLOAD, viewModel.uiState.value.footer)
    }

    @Test
    fun `the switch decides what Get ready downloads`() {
        val on = FirstRunViewModel.stateOf(FirstRunSnapshot(), switch = true, mic = MicrophoneState.NOT_ALLOWED, sizes = SetupSizes(1L, 2L, 3L), free = null)
        val off = FirstRunViewModel.stateOf(FirstRunSnapshot(), switch = false, mic = MicrophoneState.NOT_ALLOWED, sizes = SetupSizes(1L, 2L, 3L), free = null)

        assertEquals(3L, on.downloadBytes)
        assertEquals(1L, off.downloadBytes)
        // Once Download was tapped, the switch that was stored with it.
        val after = FirstRunViewModel.stateOf(downloading(DownloadState.Idle).copy(malayalamWanted = false), switch = true, mic = MicrophoneState.NOT_ALLOWED, sizes = SetupSizes(), free = null)
        assertFalse(after.malayalam)
    }

    @Test
    fun `on mobile data Get ready says so`() {
        assertTrue(stateOf(FirstRunSnapshot(network = NetworkFacts.MOBILE)).onMobileData)
        assertFalse(stateOf(FirstRunSnapshot(network = NetworkFacts.WIFI)).onMobileData)
        assertFalse(stateOf(FirstRunSnapshot(network = NetworkFacts.NONE)).onMobileData)
    }

    @Test
    fun `each hold of the setup is the problem of its name`() {
        val progress = DownloadProgress(276_000_000L, setupBytes, 34)
        val space = SpaceShortfall(1_000L, 10L)
        val rows = mapOf(
            PauseReason.NO_NETWORK to FirstRunProblem.NoConnection,
            PauseReason.WIFI_ONLY to FirstRunProblem.WaitingForWifi,
            PauseReason.BLOCKED to FirstRunProblem.Blocked,
            PauseReason.SERVER to FirstRunProblem.ServerNotAnswering,
            PauseReason.NOT_ENOUGH_STORAGE to FirstRunProblem.NotEnoughSpace(space),
            PauseReason.USER to null,
            PauseReason.RECORDING to null
        )
        for ((reason, problem) in rows) {
            val snapshot = downloading(DownloadState.Paused(reason, progress)).copy(space = space)
            assertEquals(reason.name, problem, stateOf(snapshot).problem)
        }
        assertEquals(PauseReason.entries.toSet(), rows.keys)
    }

    @Test
    fun `a damaged file says it is fetched again, and after the second time asks for Try again`() {
        val once = plan.failed(FirstRunPart.SPEECH, DownloadFailure.OTHER)
        val twice = once.failed(FirstRunPart.SPEECH, DownloadFailure.OTHER)

        assertEquals(FirstRunProblem.Damaged(again = false), stateOf(downloading(DownloadState.Downloading(DownloadProgress(1L, 2L, 50)), plan = once)).problem)
        val waits = stateOf(downloading(DownloadState.Error("damaged", DownloadFailure.OTHER), plan = twice))
        assertEquals(FirstRunProblem.Damaged(again = true), waits.problem)
        assertEquals(FirstRunFooter.TRY_AGAIN, waits.footer)
    }

    @Test
    fun `a file being checked, and a paused download, say so`() {
        val checking = stateOf(downloading(DownloadState.Verifying(DownloadProgress(656_000_000L, setupBytes, 81))))
        assertTrue(checking.progress?.checking == true)
        assertNull(checking.quiet)

        val paused = stateOf(downloading(DownloadState.Paused(PauseReason.USER, null)))
        assertTrue(paused.paused)
        assertEquals(FirstRunQuiet.CONTINUE, paused.quiet)
    }

    @Test
    fun `Getting ready's numbers are the coordinator's`() {
        val snapshot = downloading(DownloadState.Downloading(DownloadProgress(276_000_000L, setupBytes, 34)))
            .let { it.copy(setup = it.setup?.copy(minutesLeft = 2, resumedFromBytes = 200_000_000L)) }

        val progress = stateOf(snapshot).progress

        assertEquals(SetupProgress(FirstRunPart.SPEECH, 276_000_000L, setupBytes, minutesLeft = 2, resumedFromBytes = 200_000_000L), progress)
    }

    // ── What the screens ask ─────────────────────────────────────────────────

    @Test
    fun `Download on mobile data is the consent to it, Wait for Wi-Fi isn't, and both take the switch`() = runTest(main) {
        val viewModel = viewModel()
        advanceUntilIdle()
        coordinator.snapshot.value = FirstRunSnapshot(network = NetworkFacts.MOBILE)

        viewModel.setMalayalam(false)
        viewModel.download()
        viewModel.waitForWifi()

        assertEquals(listOf("download malayalam=false mobile=true", "download malayalam=false mobile=false"), coordinator.calls)
    }

    @Test
    fun `Try again and Use mobile data are for the setup's part under way`() = runTest(main) {
        coordinator.snapshot.value = downloading(DownloadState.Paused(PauseReason.WIFI_ONLY), part = FirstRunPart.MALAYALAM)
        val viewModel = viewModel()

        viewModel.retry()
        viewModel.useMobileData()
        viewModel.pause()
        viewModel.resume()
        viewModel.lookAround()

        assertEquals(listOf("retry MALAYALAM", "useMobileData MALAYALAM", "pause", "resume", "lookAround"), coordinator.calls)
    }

    @Test
    fun `the switch can't change once Download was tapped`() = runTest(main) {
        coordinator.snapshot.value = downloading(DownloadState.Idle)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setMalayalam(false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.malayalam)
    }

    // ── The microphone ───────────────────────────────────────────────────────

    @Test
    fun `the microphone's refusals outlive the screen, and allowing it in Settings counts on the way back`() = runTest(main) {
        val viewModel = viewModel()
        viewModel.microphoneAnswered(granted = false, canAskAgain = true)
        advanceUntilIdle()
        assertEquals(MicrophoneState.REFUSED_ONCE, viewModel.uiState.value.microphone)
        viewModel.microphoneAnswered(granted = false, canAskAgain = false)
        advanceUntilIdle()
        assertEquals(MicrophoneState.REFUSED_FOR_GOOD, viewModel.uiState.value.microphone)

        // The app was closed: a new screen still knows it was refused for good.
        val again = viewModel()
        advanceUntilIdle()
        assertEquals(MicrophoneState.REFUSED_FOR_GOOD, again.uiState.value.microphone)
        coordinator.snapshot.value = downloading(DownloadState.Idle)
        advanceUntilIdle()
        assertEquals(FirstRunFooter.OPEN_APP_SETTINGS, again.uiState.value.footer)

        microphoneGranted = true
        again.microphoneRechecked(granted = true)
        advanceUntilIdle()
        assertEquals(MicrophoneState.ALLOWED, again.uiState.value.microphone)
        assertNull(store.microphone)
        assertEquals(MicrophoneState.ALLOWED, viewModel().uiState.value.microphone)
    }

    @Test
    fun `an allowance taken back is asked for again`() = runTest(main) {
        microphoneGranted = true
        val viewModel = viewModel()
        assertEquals(MicrophoneState.ALLOWED, viewModel.uiState.value.microphone)

        microphoneGranted = false
        viewModel.microphoneRechecked(granted = false)
        advanceUntilIdle()

        assertEquals(MicrophoneState.NOT_ALLOWED, viewModel.uiState.value.microphone)
    }

    // ── The tap shield ───────────────────────────────────────────────────────

    @Test
    fun `a primary that changed by itself ignores taps for a moment, one changed by a tap doesn't`() = runTest(main) {
        microphoneGranted = true
        coordinator.snapshot.value = downloading(DownloadState.Downloading(DownloadProgress(1L, 2L, 50)))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.acceptsTaps(now))

        // The files are checked: Record switches on by itself.
        now = 20_000L
        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.READY, plan = plan.done(FirstRunPart.SPEECH))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.acceptsTaps(now + 300L))
        assertTrue(viewModel.uiState.value.acceptsTaps(now + FirstRunFlow.TAP_SHIELD_MS))

    }

    @Test
    fun `a change a moment after a tap is the screen's own again, and is shielded`() = runTest(main) {
        microphoneGranted = true
        coordinator.snapshot.value = downloading(DownloadState.Downloading(DownloadProgress(1L, 2L, 50)))
        val viewModel = viewModel()
        advanceUntilIdle()

        // Pause, then the files checked two seconds later: Record switching on was no tap's.
        viewModel.pause()
        now += 2_000L
        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.READY, plan = plan.done(FirstRunPart.SPEECH))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.acceptsTaps(now + 300L))
    }

    @Test
    fun `a primary changed by his own answer isn't shielded`() = runTest(main) {
        coordinator.snapshot.value = downloading(DownloadState.Downloading(DownloadProgress(1L, 2L, 50)))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(FirstRunFooter.ALLOW_MICROPHONE, viewModel.uiState.value.footer)

        // Android's window answered: Allow microphone gives way to Record, by his tap.
        now = 30_000L
        viewModel.microphoneAnswered(granted = true, canAskAgain = true)
        advanceUntilIdle()

        assertEquals(FirstRunFooter.RECORD, viewModel.uiState.value.footer)
        assertTrue(viewModel.uiState.value.acceptsTaps(now))
    }

    // ── For MainActivity ─────────────────────────────────────────────────────

    @Test
    fun `the quick key records from Ready on with the microphone allowed, read as it is pressed`() = runTest(main) {
        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.READY)
        val viewModel = viewModel()

        assertFalse(viewModel.quickKeyReady())
        microphoneGranted = true
        assertTrue(viewModel.quickKeyReady())

        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.DOWNLOADING)
        assertFalse(viewModel.quickKeyReady())
        assertFalse(viewModel.setUp())
    }

    @Test
    fun `the screen follows the coordinator's`() = runTest(main) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(FirstRunScreen.GET_READY, viewModel.screen.value)

        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.READY, lookedAround = true)
        advanceUntilIdle()
        assertEquals(FirstRunScreen.HOME, viewModel.screen.value)
    }
}
