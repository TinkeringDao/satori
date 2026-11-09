package com.satori.ui.debug

import android.util.Log
import com.satori.data.config.DefaultModelConfig
import com.satori.data.config.SpeechModels
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.finish.FinishStep
import com.satori.presentation.firstrun.FirstRunActions
import com.satori.presentation.firstrun.FirstRunProblem
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.firstrun.SetupProgress
import com.satori.presentation.firstrun.SetupSizes
import com.satori.presentation.firstrun.StripHold
import com.satori.presentation.firstrun.StripState
import com.satori.presentation.sessions.SessionsUiState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The first run's states for the debug gallery (design 5.9), `--es screen firstrun_<state>`, with
 * made-up numbers on the registry's sizes, as in the design's mockups. Get ready, Getting ready and
 * Ready are [FirstRunUiState]s ([setup]); home's are [SessionsUiState]s with their status strip
 * ([home]). The first recording's coach line is a recorder state, firstrun_s4_coach in
 * [GalleryStates].
 */
internal object FirstRunGallery {

    private const val MB = 1_000_000L

    /** The registry's bytes, as Get ready lists them: 670 MB, 138 MB and 1.1 GB. */
    val sizes = SetupSizes(
        speechBytes = SpeechModels.PARAKEET_V3.bytes,
        malayalamBytes = SpeechModels.INDICCONFORMER_ML.bytes,
        aiModelBytes = DefaultModelConfig.INSTANCE.llmSpec.bytes ?: 0L
    )

    private val setupBytes = sizes.download(malayalam = true)
    private val getReady = FirstRunUiState(sizes = sizes)

    // 34 %, 276 of 808 MB, about 2 min left, as in the mockups.
    private val running = SetupProgress(FirstRunPart.SPEECH, bytesDone = 276 * MB, bytesTotal = setupBytes, minutesLeft = 2)
    private val held = running.copy(minutesLeft = null)
    private val downloading = getReady.copy(stage = FirstRunStage.DOWNLOADING, progress = running)
    private val ready = downloading.copy(
        stage = FirstRunStage.READY,
        progress = held.copy(bytesDone = setupBytes),
        microphone = MicrophoneState.ALLOWED
    )

    /** Get ready, Getting ready and Ready, each in the states the design draws. */
    val setup: Map<String, FirstRunUiState> = linkedMapOf(
        "firstrun_s1_wifi" to getReady,
        "firstrun_s1_mobile" to getReady.copy(onMobileData = true),
        "firstrun_s1_space" to getReady.copy(
            problem = FirstRunProblem.NotEnoughSpace(SpaceShortfall(FirstRunPlan.neededSpace(setupBytes), freeBytes = 600 * MB))
        ),
        "firstrun_s2_mic" to downloading,
        "firstrun_s2_mic_ok" to downloading.copy(microphone = MicrophoneState.ALLOWED),
        // 81 %: the encoder's 652 MB are in and being checked.
        "firstrun_s2_checking" to downloading.copy(
            progress = held.copy(bytesDone = 656 * MB, checking = true),
            microphone = MicrophoneState.ALLOWED
        ),
        "firstrun_s2_paused" to downloading.copy(progress = held.copy(paused = true)),
        "firstrun_s2_noconn" to downloading.copy(progress = held, problem = FirstRunProblem.NoConnection),
        // 47 %: 428 MB left.
        "firstrun_s2_wifi" to downloading.copy(progress = held.copy(bytesDone = 380 * MB), problem = FirstRunProblem.WaitingForWifi),
        "firstrun_s2_blocked" to downloading.copy(progress = held.copy(bytesDone = 0L), problem = FirstRunProblem.Blocked),
        "firstrun_s2_server" to downloading.copy(progress = held, problem = FirstRunProblem.ServerNotAnswering),
        // The encoder arrived damaged and is fetched again from its start.
        "firstrun_s2_damaged" to downloading.copy(progress = held.copy(bytesDone = 12 * MB), problem = FirstRunProblem.Damaged(again = false)),
        "firstrun_s2_mic_refused" to downloading.copy(microphone = MicrophoneState.REFUSED_ONCE),
        "firstrun_s2_mic_settings" to downloading.copy(microphone = MicrophoneState.REFUSED_FOR_GOOD),
        "firstrun_s3_ready" to ready,
        "firstrun_s3_ready_mic" to ready.copy(microphone = MicrophoneState.NOT_ALLOWED)
    )

    /** The AI model downloading after the first recording: 23 %, about 3 min left. */
    private val aiModel = StripState.Download(
        FirstRunPart.AI_MODEL,
        bytesDone = 246 * MB,
        bytesTotal = sizes.aiModelBytes,
        minutesLeft = 3
    )

    /** Home's states, the strip and the recordings under it; [category] names a starter category by its id. */
    fun home(name: String, category: (String) -> String): SessionsUiState? {
        val voiceNote = recording("first", null, durationMs = 65_000L, categoryId = "voice-note", category = category)
        val named = voiceNote.copy(session = voiceNote.session.copy(name = "Planning the week"), hasNotes = true)
        val second = recording("second", "Shopping for the weekend", durationMs = 48_000L, categoryId = "voice-note", category = category, hasNotes = true)
        val third = recording("third", "Garden shed repair", durationMs = 131_000L, categoryId = "idea", category = category, hasNotes = true)
        val waits = mapOf("first" to FinishingStatus("first", FinishingStatus.Phase.WAITS_FOR_MODEL))
        return when (name) {
            "firstrun_home_waits" -> homeState(listOf(voiceNote), waits, aiModel)
            "firstrun_home_paused" -> homeState(listOf(voiceNote), waits, aiModel.copy(minutesLeft = null, hold = StripHold.RECORDING))
            // Under 20 words: its notes are skipped while the steps after them run.
            "firstrun_home_short" -> homeState(
                listOf(recording("first", null, durationMs = 9_000L, categoryId = "voice-note", category = category)),
                mapOf("first" to FinishingStatus("first", FinishingStatus.Phase.RUNNING, step = FinishStep.Name, noNotes = true)),
                aiModel
            )
            "firstrun_home_first_notes" -> homeState(listOf(named), emptyMap(), StripState.FirstNotes("first"))
            "firstrun_nudge_ping" -> homeState(listOf(second, named), emptyMap(), StripState.Nudge(NudgeId.NOTIFY_PING))
            // The first recording's notes were ready out of sight, notifications not allowed: the
            // ping says why, ahead of the first notes' confirmation (0.7.2).
            "firstrun_nudge_ping_missed" -> homeState(listOf(named), emptyMap(), StripState.Nudge(NudgeId.NOTIFY_PING, missedNotes = true))
            "firstrun_nudge_e2b" -> homeState(listOf(third, second, named), emptyMap(), StripState.Nudge(NudgeId.E2B_OFFER))
            else -> null
        }
    }

    /** Every state's name, in the design's order, with 0.7.2's ping for missed notes after the ping. */
    val names: List<String> = setup.keys.toList() + listOf(
        "firstrun_home_waits", "firstrun_home_paused", "firstrun_home_short", "firstrun_home_first_notes",
        "firstrun_nudge_ping", "firstrun_nudge_ping_missed", "firstrun_nudge_e2b"
    )

    private fun homeState(recordings: List<SessionListItem>, finishing: Map<String, FinishingStatus>, strip: StripState) =
        SessionsUiState(recordings = recordings, finishing = finishing, isLoading = false, strip = strip)

    /** A recording made today at 09:12, made up, as the starter category [categoryId]. */
    private fun recording(
        id: String,
        name: String?,
        durationMs: Long,
        categoryId: String,
        category: (String) -> String,
        hasNotes: Boolean = false
    ): SessionListItem {
        val at = LocalDate.now().atTime(LocalTime.of(9, 12)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SessionListItem(
            session = TranscriptionSession(
                id = id, name = name, createdAt = at, lastModifiedAt = at, mode = RecordingMode.SIMPLE_LISTENING,
                durationMs = durationMs, categoryId = categoryId
            ),
            categoryName = category(categoryId),
            hasTranscript = true,
            hasNotes = hasNotes
        )
    }
}

/** The first-run screens' requests in the gallery: each is only logged, so a tap can be seen to land. */
internal object GalleryFirstRunActions : FirstRunActions {
    private fun tapped(what: String) {
        Log.i(FirstRunDebug.TAG, "Gallery: $what")
    }

    override fun setMalayalam(wanted: Boolean) = tapped("Malayalam $wanted")
    override fun download() = tapped("download")
    override fun waitForWifi() = tapped("wait for Wi-Fi")
    override fun pause() = tapped("pause")
    override fun resume() = tapped("continue")
    override fun retry() = tapped("try again")
    override fun useMobileData() = tapped("use mobile data")
    override fun microphoneAnswered(granted: Boolean, canAskAgain: Boolean) = tapped("microphone answered $granted $canAskAgain")
    override fun microphoneRechecked(granted: Boolean) = tapped("microphone rechecked $granted")
    override fun lookAround() = tapped("look around first")
}
