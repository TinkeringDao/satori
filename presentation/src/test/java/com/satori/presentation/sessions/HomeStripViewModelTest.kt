package com.satori.presentation.sessions

import androidx.lifecycle.SavedStateHandle
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.NudgeLedger
import com.satori.domain.firstrun.NudgeState
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import com.satori.presentation.firstrun.StripState
import com.satori.presentation.ideas.CountedReminders
import com.satori.presentation.ideas.IdeaTodos
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home's status strip as its view model puts it together: each offer once, across restarts too;
 * nothing within a minute of a Stop, and the offer by itself once that minute is over; the pills'
 * requests reach the first run's coordinator; the first notes' confirmation goes once a recording
 * is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStripViewModelTest {

    private val main = StandardTestDispatcher()
    private val base = 1_800_000_000_000L
    private val hourAgo = base - 3_600_000L
    private val recorder = ActiveRecorder()
    private val coordinator = FakeFirstRunCoordinator(FirstRunSnapshot(stage = FirstRunStage.DONE, network = NetworkFacts.WIFI))

    /** Notifications off, never asked: the ping can be offered. */
    private val notificationsOff = PhoneFacts(notificationsAskable = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recording(id: String, createdAt: Long = hourAgo, notes: Boolean = false) = SessionListItem(
        session = TranscriptionSession(id = id, name = null, createdAt = createdAt, lastModifiedAt = createdAt, durationMs = 60_000L),
        hasTranscript = true,
        hasNotes = notes
    )

    /** Two recordings with words, saved an hour ago: the ping is due. */
    private val twoSaved = listOf(recording("second"), recording("first"))

    /** Home over [recordings], the app started with [ledger] as stored. */
    private fun TestScope.openHome(
        recordings: ChangingRecordings = ChangingRecordings(twoSaved),
        ledger: StoredNudgeLedger = StoredNudgeLedger(),
        phone: TestPhone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff)
    ): SessionsViewModel {
        val todos = IdeaTodos(emptyList())
        val home = SessionsViewModel(
            getAllSessionsUseCase = GetAllSessionsUseCase(recordings),
            createSessionUseCase = CreateSessionUseCase(recordings),
            settingsRepository = FreshSettings(),
            categoryRepository = NoCategories(),
            todoRepository = todos,
            todoActions = TodoActions(todos, CountedReminders()),
            activeRecorder = recorder,
            finishingQueue = StatusQueue(),
            speechModels = SpeechModelsHere(),
            savedStateHandle = SavedStateHandle(),
            firstRun = coordinator,
            nudgeLedger = ledger,
            phone = phone
        )
        backgroundScope.launch { home.uiState.collect {} }
        home.homeStarted()
        runCurrent()
        return home
    }

    private val SessionsViewModel.strip: StripState? get() = uiState.value.strip

    @Test
    fun `the ping shows once, also across restarts, and Not now ends it for good`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val home = openHome(ledger = ledger)
        val ping = StripState.Nudge(NudgeId.NOTIFY_PING)
        assertEquals(ping, home.strip)

        home.stripSeen(ping, sinceMs = base)
        home.stripSeen(ping, sinceMs = base + 5_000L)
        assertEquals(listOf(NudgeId.NOTIFY_PING to NudgeState.SHOWN), ledger.logged)

        // The app starts again: the ping, ignored, is still on show, and not shown a second time.
        val restarted = StoredNudgeLedger(ledger.stored)
        val again = openHome(ledger = restarted)
        assertEquals(ping, again.strip)
        again.stripSeen(ping, sinceMs = base + 60_000L)
        assertEquals(emptyList<Pair<NudgeId, NudgeState>>(), restarted.logged)

        again.offerAnswered(NudgeId.NOTIFY_PING, accepted = false)
        runCurrent()
        assertNull(again.strip)
        assertNull(openHome(ledger = StoredNudgeLedger(restarted.stored)).strip)
    }

    @Test
    fun `the ping goes as soon as the to-do reminders' sheet is answered, not at the next start of home`() = runTest(main) {
        val phone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff)
        val home = openHome(phone = phone)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), home.strip)

        // The sheet's Not now marks the shared flag.
        phone.facts = notificationsOff.copy(notificationsAsked = true)
        phone.askChanges.tryEmit(Unit)
        runCurrent()

        assertNull(home.strip)
    }

    @Test
    fun `taking the ping marks Android's window as asked, so the to-do sheet doesn't ask too`() = runTest(main) {
        val phone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff)
        val home = openHome(phone = phone)

        home.offerAnswered(NudgeId.NOTIFY_PING, accepted = true)
        runCurrent()

        assertTrue(phone.asked)
        assertNull(home.strip)
    }

    @Test
    fun `no offer within a minute of a Stop, and it comes by itself once the minute is over`() = runTest(main) {
        val home = openHome()
        val owner = Any()
        recorder.open(owner, "third", ActiveRecorder.Activity.RECORDING)
        runCurrent()
        assertNull(home.strip)

        recorder.leave(owner)
        runCurrent()
        assertNull(home.strip)

        advanceTimeBy(59_000L)
        runCurrent()
        assertNull(home.strip)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), home.strip)
    }

    @Test
    fun `the first notes are confirmed, and go once a recording is opened`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first", notes = true))), ledger = ledger)
        val firstNotes = StripState.FirstNotes("first")
        assertEquals(firstNotes, home.strip)

        home.stripSeen(firstNotes, sinceMs = base)
        home.recordingOpened()
        runCurrent()

        assertNull(home.strip)
        assertEquals(NudgeState.GONE, ledger.ledger.value.state(NudgeId.FIRST_NOTES))
    }

    @Test
    fun `Open on the first notes ends the confirmation`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first", notes = true))), ledger = ledger)

        home.firstNotesOpened()
        runCurrent()

        assertNull(home.strip)
        assertEquals(NudgeState.ACCEPTED, ledger.ledger.value.state(NudgeId.FIRST_NOTES))
    }

    @Test
    fun `an offer left alone for a day goes at the next start, and the field log says so`() = runTest(main) {
        val ledger = StoredNudgeLedger(NudgeLedger().shown(NudgeId.NOTIFY_PING, atMs = base - NudgeLedger.SHOWN_FOR_MS).stored().toMutableMap())
        val home = openHome(ledger = ledger)

        assertNull(home.strip)
        assertEquals(listOf(NudgeId.NOTIFY_PING to NudgeState.GONE), ledger.logged)
    }

    @Test
    fun `the AI model's download after the first recording shows from the coordinator, with its numbers`() = runTest(main) {
        val bytes = 1_069_306_368L
        coordinator.snapshot.value = coordinator.snapshot.value.copy(
            plan = FirstRunPlan.initial(speechBytes = 670_478_772L, malayalamBytes = null, aiModelBytes = bytes).done(FirstRunPart.SPEECH),
            later = FirstRunProgress(FirstRunPart.AI_MODEL, DownloadState.Downloading(DownloadProgress(246_000_000L, bytes, 23)), 246_000_000L, bytes, minutesLeft = 3)
        )
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first"))))

        assertEquals(StripState.Download(FirstRunPart.AI_MODEL, 246_000_000L, bytes, minutesLeft = 3), home.strip)
    }

    @Test
    fun `the strip's pills ask the coordinator, and Malayalam's Try again is offered once`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val home = openHome(ledger = ledger)

        home.retryDownload(FirstRunPart.AI_MODEL)
        home.useMobileData(FirstRunPart.AI_MODEL)
        home.resumeDownload()
        home.retryMalayalam()
        assertTrue(home.offerAnswered(NudgeId.E2B_OFFER, accepted = true))
        // A second tap on the strip as it goes asks for nothing more.
        assertFalse(home.offerAnswered(NudgeId.E2B_OFFER, accepted = true))

        assertEquals(
            listOf("retry AI_MODEL", "useMobileData AI_MODEL", "resume", "retry MALAYALAM", "getBiggerModel"),
            coordinator.calls
        )
        assertTrue(ledger.ledger.value.malayalamRetried)
        assertEquals(NudgeState.ACCEPTED, ledger.ledger.value.state(NudgeId.E2B_OFFER))
    }

    @Test
    fun `the Malayalam strip's Try again plans whichever was dropped, the reader alone when only it was`() = runTest(main) {
        val bytes = 241_989_173L
        val readerDropped = FirstRunPlan(emptyList()).withReader(bytes)
            .failed(FirstRunPart.READER, DownloadFailure.OTHER).failed(FirstRunPart.READER, DownloadFailure.OTHER)
        coordinator.snapshot.value = coordinator.snapshot.value.copy(plan = readerDropped)
        val ledger = StoredNudgeLedger()
        val home = openHome(ledger = ledger)

        home.retryMalayalam()

        assertEquals(listOf("retry READER"), coordinator.calls)
        assertTrue(ledger.ledger.value.readerRetried)
        assertFalse(ledger.ledger.value.malayalamRetried)

        // Both dropped, the reader's Try again used: the strip is on show for Malayalam alone, which is planned again.
        val both = readerDropped.copy(
            items = readerDropped.items + FirstRunPlan.Item(FirstRunPart.MALAYALAM, 1L, FirstRunPlan.Status.DROPPED)
        )
        coordinator.snapshot.value = coordinator.snapshot.value.copy(plan = both)
        home.retryMalayalam()
        assertEquals(listOf("retry READER", "retry MALAYALAM"), coordinator.calls)
        assertTrue(ledger.ledger.value.malayalamRetried)
    }

    @Test
    fun `the strip on show for the reader alone retries the reader, not Malayalam once its own Try again is used`() = runTest(main) {
        val bytes = 241_989_173L
        val both = FirstRunPlan(listOf(FirstRunPlan.Item(FirstRunPart.MALAYALAM, 1L, FirstRunPlan.Status.DROPPED))).withReader(bytes)
            .failed(FirstRunPart.READER, DownloadFailure.OTHER).failed(FirstRunPart.READER, DownloadFailure.OTHER)
        coordinator.snapshot.value = coordinator.snapshot.value.copy(plan = both)
        val ledger = StoredNudgeLedger()
        ledger.update { it.malayalamRetryUsed() }
        val home = openHome(ledger = ledger)

        home.retryMalayalam()

        assertEquals(listOf("retry READER"), coordinator.calls)
        assertTrue(ledger.ledger.value.readerRetried)
    }

    @Test
    fun `notes missed while home is stopped make the ping the strip as it starts, and its first sighting says why, once`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val phone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff)
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first", notes = true))), ledger = ledger, phone = phone)
        assertEquals(StripState.FirstNotes("first"), home.strip)

        // Out of sight, the notes of a recording were ready and their notification wasn't allowed.
        home.homeStopped()
        phone.facts = notificationsOff.copy(notesMissed = 1)
        home.homeStarted()
        runCurrent()
        val missed = StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true)
        assertEquals(missed, home.strip)

        home.stripSeen(missed, sinceMs = base)
        home.stripSeen(missed, sinceMs = base + 5_000L)
        assertEquals(listOf("id=NOTIFY_PING trigger=notes_missed missed=1 recordings=1"), ledger.lines)
        assertEquals(listOf(NudgeId.NOTIFY_PING to NudgeState.SHOWN), ledger.logged)

        // Allow: the shared flag is set before Android's window opens, and the window's answer is logged.
        assertTrue(home.offerAnswered(NudgeId.NOTIFY_PING, accepted = true))
        assertTrue(phone.asked)
        home.notificationsAnswered(allowed = true)
        runCurrent()
        assertEquals("id=NOTIFY_PING window allowed=true", ledger.lines.last())
        // Then the confirmation it went ahead of.
        assertEquals(StripState.FirstNotes("first"), home.strip)
    }

    @Test
    fun `a miss counted while home shows makes the ping at once`() = runTest(main) {
        val phone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff)
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first", notes = true))), phone = phone)
        assertEquals(StripState.FirstNotes("first"), home.strip)

        phone.facts = notificationsOff.copy(notesMissed = 1)
        phone.askChanges.tryEmit(Unit)
        runCurrent()

        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true), home.strip)
    }

    @Test
    fun `the ping after two recordings says so on its first sighting, and Not now adds no line`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val home = openHome(ledger = ledger)
        val ping = StripState.Nudge(NudgeId.NOTIFY_PING)

        home.stripSeen(ping, sinceMs = base)
        home.offerAnswered(NudgeId.NOTIFY_PING, accepted = false)

        assertEquals(listOf("id=NOTIFY_PING trigger=recordings missed=0 recordings=2"), ledger.lines)
    }

    @Test
    fun `a recording opened while the confirmation waited behind the ping ends it, so it never comes late`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val phone = TestPhone(clock = { base + testScheduler.currentTime }, facts = notificationsOff.copy(notesMissed = 1))
        val home = openHome(recordings = ChangingRecordings(listOf(recording("first", notes = true))), ledger = ledger, phone = phone)
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true), home.strip)

        home.recordingOpened()
        home.offerAnswered(NudgeId.NOTIFY_PING, accepted = false)
        runCurrent()

        assertNull(home.strip)
        assertEquals(listOf(NudgeId.FIRST_NOTES to NudgeState.GONE, NudgeId.NOTIFY_PING to NudgeState.DECLINED), ledger.logged)
    }

    @Test
    fun `a recording opened before any notes are written leaves the first notes' confirmation to come`() = runTest(main) {
        val ledger = StoredNudgeLedger()
        val recordings = ChangingRecordings(listOf(recording("first")))
        val home = openHome(recordings = recordings, ledger = ledger)
        assertNull(home.strip)

        home.recordingOpened()
        recordings.list.value = listOf(recording("first", notes = true))
        runCurrent()

        assertEquals(StripState.FirstNotes("first"), home.strip)
        assertEquals(emptyList<Pair<NudgeId, NudgeState>>(), ledger.logged)
    }

    @Test
    fun `a new recording with words makes the ping due without leaving home`() = runTest(main) {
        val recordings = ChangingRecordings(listOf(recording("first")))
        val home = openHome(recordings = recordings)
        assertNull(home.strip)

        recordings.list.value = twoSaved
        runCurrent()
        assertEquals(StripState.Nudge(NudgeId.NOTIFY_PING), home.strip)
    }
}
