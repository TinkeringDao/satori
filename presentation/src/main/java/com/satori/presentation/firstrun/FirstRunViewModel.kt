package com.satori.presentation.firstrun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.firstrun.FirstRunStore
import com.satori.data.firstrun.StorageCheck
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The first run's view model (design 3.4-3.6, 6.2): makes the one [FirstRunUiState] that Get
 * ready and Getting ready draw, from the coordinator's snapshot, the microphone and the free
 * space, and passes the screens' requests on ([FirstRunActions]). It writes no first-run state
 * itself: the coordinator is the one writer. What it keeps is the screens' own: Get ready's
 * switch until Download is tapped, and the microphone's answers, which outlive the screen.
 *
 * A primary that the screen changed by itself ignores taps for a moment
 * ([FirstRunUiState.withTapShield]); its times are [SystemClock.uptimeMillis]'s, the clock of
 * Android's touch events, which the screens pass to [FirstRunUiState.acceptsTaps].
 */
@HiltViewModel
class FirstRunViewModel internal constructor(
    private val coordinator: FirstRunCoordinator,
    private val files: FirstRunFiles,
    private val store: FirstRunStore,
    private val settings: SettingsRepository,
    private val microphoneGranted: () -> Boolean,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher
) : ViewModel(), FirstRunActions {

    @Inject
    constructor(
        coordinator: FirstRunCoordinator,
        files: FirstRunFiles,
        store: FirstRunStore,
        settings: SettingsRepository,
        @ApplicationContext context: Context
    ) : this(
        coordinator, files, store, settings,
        { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        SystemClock::uptimeMillis,
        Dispatchers.IO
    )

    private val malayalam = MutableStateFlow(coordinator.state.value.malayalamWanted)
    private val microphone = MutableStateFlow(storedMicrophone())
    private val sizes = MutableStateFlow(SetupSizes())

    // The free space as Get ready last read it, null before it has; and when a control was last
    // tapped, so the change that follows it isn't shielded.
    private val freeBytes = MutableStateFlow<Long?>(null)
    private var tappedAtMs = Long.MIN_VALUE / 2
    private var shown: FirstRunUiState? = null

    /** What Get ready and Getting ready draw. */
    val uiState: StateFlow<FirstRunUiState> = combine(coordinator.state, malayalam, microphone, sizes, freeBytes) { snapshot, switch, mic, sizes, free ->
        val now = clock()
        stateOf(snapshot, switch, mic, sizes, free).withTapShield(shown, now, byTap = now - tappedAtMs < TAP_WINDOW_MS).also { shown = it }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        stateOf(coordinator.state.value, malayalam.value, microphone.value, sizes.value, null).also { shown = it }
    )

    /** The screen the app shows for the first run: Get ready, Getting ready, or home. */
    val screen: StateFlow<FirstRunScreen> = coordinator.state.map { it.screen }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, coordinator.state.value.screen)

    init {
        viewModelScope.launch { sizes.value = withContext(io) { readSizes() } }
        // Get ready says before anything starts whether the download fits.
        viewModelScope.launch {
            coordinator.state.map { it.stage == FirstRunStage.GET_READY }.distinctUntilChanged().collect { atGetReady ->
                if (atGetReady) readFreeSpace()
            }
        }
    }

    /** Whether a quick key press may record now ([FirstRunFlow.quickKeyReady]), the microphone read now. */
    fun quickKeyReady(): Boolean = FirstRunFlow.quickKeyReady(coordinator.state.value.stage, microphoneGranted())

    /** Whether the speech files are in and checked: home and its to-dos can open ([FirstRunFlow.onboardingComplete]). */
    fun setUp(): Boolean = FirstRunFlow.onboardingComplete(coordinator.state.value.stage)

    // ── FirstRunActions ──────────────────────────────────────────────────────

    override fun setMalayalam(wanted: Boolean) {
        if (coordinator.state.value.stage != FirstRunStage.GET_READY) return
        tap()
        malayalam.value = wanted
    }

    /** On mobile data too: the button showed the size, which makes the tap the consent to it. */
    override fun download() {
        tap()
        coordinator.download(malayalam.value, useMobileData = coordinator.state.value.network.metered)
    }

    override fun waitForWifi() {
        tap()
        coordinator.download(malayalam.value, useMobileData = false)
    }

    override fun pause() {
        tap()
        coordinator.pause()
    }

    override fun resume() {
        tap()
        coordinator.resume()
    }

    override fun retry() {
        tap()
        coordinator.retry(setupPart())
    }

    override fun useMobileData() {
        tap()
        coordinator.useMobileData(setupPart())
    }

    override fun microphoneAnswered(granted: Boolean, canAskAgain: Boolean) {
        tap()
        remember(MicrophoneState.answered(microphone.value, granted, canAskAgain))
    }

    /**
     * Also reads the free space again while Get ready shows: the screen calls this on every return
     * to it, so space made in Android's storage settings counts on the way back.
     */
    override fun microphoneRechecked(granted: Boolean) {
        remember(MicrophoneState.reread(microphone.value, granted))
        if (coordinator.state.value.stage == FirstRunStage.GET_READY) readFreeSpace()
    }

    override fun lookAround() {
        tap()
        coordinator.lookAround()
    }

    // ── The state ────────────────────────────────────────────────────────────

    private fun tap() {
        tappedAtMs = clock()
    }

    private fun readFreeSpace() {
        viewModelScope.launch { freeBytes.value = withContext(io) { files.freeBytes() } }
    }

    /** The setup's part under way, which Try again and Use mobile data are for. */
    private fun setupPart(): FirstRunPart = coordinator.state.value.setup?.part ?: FirstRunPart.SPEECH

    private fun remember(state: MicrophoneState) {
        microphone.value = state
        store.microphone = state.name.takeIf { state == MicrophoneState.REFUSED_ONCE || state == MicrophoneState.REFUSED_FOR_GOOD }
    }

    /** The microphone as it was left: allowed when it is now, else the refusal stored, if any. */
    private fun storedMicrophone(): MicrophoneState = when {
        microphoneGranted() -> MicrophoneState.ALLOWED
        else -> MicrophoneState.entries.firstOrNull { it.name == store.microphone } ?: MicrophoneState.NOT_ALLOWED
    }

    /**
     * What Get ready lists: the AI model only when it will download after the first recording, and
     * Malayalam only while it isn't on the phone, checked (speech recognition's files gone after
     * the setup start it over, and Malayalam's stay): a part with 0 bytes has no row. The reader,
     * which AI notes use for Malayalam, counts in the AI notes row unless it is on the phone.
     */
    private suspend fun readSizes(): SetupSizes {
        val aiNotes = settings.getSettings().first().llmEnabled
        val aiModel = aiNotes && !files.aiModelOnPhone()
        return SetupSizes(
            speechBytes = files.bytes(FirstRunPart.SPEECH),
            malayalamBytes = if (files.checked(FirstRunPart.MALAYALAM)) 0L else files.bytes(FirstRunPart.MALAYALAM),
            aiModelBytes = if (aiModel) files.bytes(FirstRunPart.AI_MODEL) else 0L,
            readerBytes = if (aiNotes && !files.checked(FirstRunPart.READER)) files.bytes(FirstRunPart.READER) else 0L
        )
    }

    companion object {
        /**
         * A change of the primary this soon after a tap came from it, so it isn't shielded: the
         * coordinator answers a tap within milliseconds, and the progress published meanwhile
         * changes no primary.
         */
        const val TAP_WINDOW_MS = 1_000L

        /**
         * The state the screens draw from [snapshot], Get ready's [switch], the microphone [mic],
         * the registry's [sizes] and the [free] space Get ready read.
         */
        internal fun stateOf(
            snapshot: FirstRunSnapshot,
            switch: Boolean,
            mic: MicrophoneState,
            sizes: SetupSizes,
            free: Long?
        ): FirstRunUiState {
            val getReady = snapshot.stage == FirstRunStage.GET_READY
            val chosen = if (getReady) switch else snapshot.malayalamWanted
            val setup = snapshot.setup
            return FirstRunUiState(
                stage = snapshot.stage,
                sizes = sizes,
                malayalam = chosen,
                onMobileData = snapshot.network.connected && snapshot.network.metered,
                progress = setup?.let {
                    SetupProgress(
                        part = it.part,
                        bytesDone = it.bytesDone,
                        bytesTotal = it.bytesTotal,
                        minutesLeft = it.minutesLeft,
                        checking = it.state is DownloadState.Verifying,
                        paused = (it.state as? DownloadState.Paused)?.reason == PauseReason.USER,
                        resumedFromBytes = it.resumedFromBytes
                    )
                },
                problem = if (getReady) {
                    free?.let { StorageCheck.shortfall(it, sizes.download(chosen)) }?.let(FirstRunProblem::NotEnoughSpace)
                } else {
                    problemOf(snapshot)
                },
                microphone = mic
            )
        }

        /** What keeps the setup's download from going on, from the state of its part under way. */
        private fun problemOf(snapshot: FirstRunSnapshot): FirstRunProblem? {
            val setup = snapshot.setup ?: return null
            val held = (setup.state as? DownloadState.Paused)?.reason
            val item = snapshot.plan.item(setup.part)
            return when (held) {
                PauseReason.NO_NETWORK -> FirstRunProblem.NoConnection
                PauseReason.WIFI_ONLY -> FirstRunProblem.WaitingForWifi
                PauseReason.BLOCKED -> FirstRunProblem.Blocked
                PauseReason.SERVER -> FirstRunProblem.ServerNotAnswering
                PauseReason.NOT_ENOUGH_STORAGE -> snapshot.space?.let(FirstRunProblem::NotEnoughSpace)
                PauseReason.USER, PauseReason.RECORDING -> null
                null -> when {
                    item?.waitsForTap == true -> FirstRunProblem.Damaged(again = true)
                    (item?.damaged ?: 0) > 0 -> FirstRunProblem.Damaged(again = false)
                    else -> null
                }
            }
        }
    }
}
