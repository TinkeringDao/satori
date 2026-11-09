package com.satori.domain.usecase.finish

import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.SpokenLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.stt.ModelReadiness
import com.satori.domain.usecase.stt.SpeechModelChoice
import com.satori.domain.usecase.stt.TranscribeAgainOutcome
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/** What the step that makes a better transcript after Stop did, for the log and for the notes. */
sealed interface BetterTranscript {

    /**
     * The recording's transcript is [model]'s now: made by this run, or by an earlier one whose
     * finishing a process death cut short.
     */
    data class Replaced(val model: SpeechModelId) : BetterTranscript

    /** The step didn't change the transcript, for [reason]; [model] is the one set, if any. */
    data class Skipped(val model: SpeechModelId?, val reason: Reason) : BetterTranscript

    /** [model] failed on the way ([cause]); the live transcript stays. */
    data class Failed(val model: SpeechModelId, val cause: Throwable) : BetterTranscript

    /** Why the live transcript stays. */
    enum class Reason {
        /** No model is set for after Stop ([com.satori.domain.model.AppSettings.transcribeAfterStop]). */
        Off,

        /** The model set is not on the phone, or not whole. */
        NotInstalled,

        /** The recording kept no audio, or it is gone. */
        NoAudio,

        /**
         * The model doesn't know the language the live transcript is in, or that isn't known: too
         * little was said, or the recording is in two languages ([SpokenLanguage]).
         */
        WrongLanguage,

        /**
         * A capture is live, as the step would start or since it started: a speech model beside
         * the live one would take the memory and the processor the new recording needs (the
         * app's RecorderAwareBetterTranscript), or the recorder owns the models
         * ([FinishRecordingUseCase.Request.mayChangeModel]).
         */
        NewerRecorder,

        /**
         * The model would take longer than [TranscribeAfterStopUseCase.MAX_ESTIMATED_MS] for the
         * recording: Transcribe again on the details page can make its better transcript.
         */
        TooLong,

        /** A large model, and the AI model stayed in memory: a load of it was still under way. */
        AiModelLoaded,

        /** A large model, and too little memory free for it even with the AI model out. */
        NotEnoughMemory,

        /** The model heard no speech in the audio. */
        NoSpeech,

        /** The owner skipped it while it ran ([BetterTranscriptRun.skip]). */
        SkippedByOwner,

        /**
         * A recording in Malayalam that went back to English or German while it was recorded
         * ([SpeechModelChoice.leftMalayalam]): the Malayalam model would write what was said in
         * those in Malayalam letters.
         */
        LeftMalayalam
    }
}

/**
 * A better transcript under way after Stop: [model] transcribes the recording again, and [skip]
 * stops it at the stretch of speech it is on, so the finishing goes on with the live transcript.
 * [skip] may be called from any thread, and more than once.
 */
class BetterTranscriptRun(val model: SpeechModelId, val skip: () -> Unit)

/**
 * The step of [FinishRecordingUseCase] that makes a better transcript after Stop; a seam, so the
 * finishing can be tested without a speech model. [TranscribeAfterStopUseCase] is the step.
 */
fun interface BetterTranscriptStep {

    /**
     * @param mayChangeModel Whether this recorder owns the models ([FinishRecordingUseCase.Request.mayChangeModel])
     * @param onStart Called once, right before the model runs, with the run, which the owner can
     *   skip; not called when the step is skipped
     * @param onProgress How much of the audio has been read, 0..1; called from a background thread
     * @return What it did; throws only [CancellationException]
     */
    suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript

    /**
     * [run] for a recording whose live transcript [mayLackSpeech]: its recognizer failed or was cut
     * off before it had transcribed all of the audio ([FinishRecordingUseCase.Request.recognizerFailed]),
     * so the step may transcribe all of the kept audio with the live model when no better model
     * does ([TranscribeAfterStopUseCase]). A step that knows nothing of that runs as [run].
     */
    suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit,
        mayLackSpeech: Boolean
    ): BetterTranscript = run(sessionId, mayChangeModel, onStart, onProgress)
}

/**
 * Transcribes a recording again right after Stop with the speech model set for that
 * ([com.satori.domain.model.AppSettings.transcribeAfterStop]), so the notes, the name and
 * the to-dos are written from the better transcript. Live recording always uses Parakeet v3,
 * which finds the language itself and runs next to the AI model; the better models know only
 * English (Parakeet v2), or have to be told the language and are slower and bigger (Cohere
 * Transcribe).
 *
 * It runs when the model is on the phone, the recording kept its audio, and the live transcript
 * is in one language the model knows ([SpokenLanguage], [SpeechModelId.understands]). The caller
 * has released the live speech model by then. It never runs while a recorder owns the models
 * ([BetterTranscript.Reason.NewerRecorder]). The recordings queued behind it wait for it, so it
 * runs only when the model is expected to take at most [MAX_ESTIMATED_MS], and the owner can skip
 * it ([BetterTranscriptRun.skip]). The estimate is that of the scheduling group it runs in
 * ([SpeechModelChoice.estimatedMs]): the foreground group while the AI's service is in the
 * foreground ([serviceInForeground]), otherwise the background group, on the three little cores,
 * as when only the finisher's wake job keeps the app after a reboot. A large model
 * ([SpeechModelChoice.isLarge]) runs only with the AI model out of memory: the AI model is
 * unloaded for it (the notes load it again), and if even then less memory is free than the speech
 * model's size and [SpeechModelChoice.MEMORY_MARGIN_BYTES], the step is skipped.
 *
 * A transcript the set model made already counts as made ([BetterTranscript.Replaced]), and the
 * model doesn't run again: a finishing cut short by a process death goes on from this step, and
 * its notes are then rewritten from that transcript as after a fresh one.
 *
 * Whatever goes wrong leaves the live transcript, which [TranscribeAgainUseCase] only replaces
 * once the new one is complete, and the finishing goes on: the step only ever throws a cancel.
 *
 * A recording stored in Malayalam is transcribed again by the Malayalam model, whatever is set for
 * after Stop ([SpeechModelChoice.afterStopModel]): its whole kept audio, with the voice detection
 * and the four threads for files, so what was said before it was switched to Malayalam, which
 * Parakeet v3 wrote as nonsense, comes out in Malayalam too, before its notes, name and to-dos are
 * written from it. Its live transcript is the same model's, so it doesn't count as made already.
 * Its load waits its turn behind every other model load (the app's ModelLoadPacing.otherLoad) and
 * never runs beside a live capture (as for any model), nor beside the AI model: the one in memory,
 * Gemma 4 E2B above all, which the finisher keeps loaded from one recording to the next, is
 * unloaded first, and the step is skipped if a load under way keeps it in memory. It is skipped for
 * a recording that went back to English or German while it was recorded
 * ([SpeechModelChoice.leftMalayalam]); one that ended in English or German is no Malayalam
 * recording.
 *
 * A recording whose live transcript may lack speech ([BetterTranscriptStep.run]'s mayLackSpeech: the
 * live recognizer failed, or Stop came while its model was still read in, so the audio that waited
 * was never transcribed) is transcribed again from its kept audio by the live model of its
 * language when no model above does it ([fill]): Parakeet v3 for one in English or German, with
 * the voice detection and the four threads for files, through the same load gate and pace as any
 * file load, never beside a live capture nor beside the AI model. Its transcript then holds what
 * the live one lacked, and the notes, the name and the to-dos are written from it. Not for one with
 * Malayalam lines from before it went back to English or German, which Parakeet v3 would write
 * over; and a model set for after Stop that did transcribe it, or that the owner skipped, leaves
 * nothing to fill.
 *
 * @property serviceInForeground Whether the AI's foreground service keeps the app now
 *   ([com.satori.domain.service.LlmProcessingServiceController.protected])
 */
class TranscribeAfterStopUseCase(
    private val settingsRepository: SettingsRepository,
    private val speechModels: SpeechModelRepository,
    private val audioRepository: RecordingAudioRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val llmRepository: LlmRepository,
    private val memory: DeviceMemory,
    private val transcribeAgain: TranscribeAgainUseCase,
    private val serviceInForeground: () -> Boolean
) : BetterTranscriptStep {

    companion object {
        /**
         * The longest the step may take by its model's estimate ([SpeechModelChoice.estimatedMs]):
         * the notes of this recording and every recording queued behind it wait until it ends.
         * Under the AI's service three minutes are Cohere Transcribe on recordings of up to about
         * 8 minutes and Parakeet v2 on up to about half an hour; with only the finisher's wake job
         * keeping the app, about 1.5 and 5 minutes. Transcribe again on the recording's page takes
         * any length. The owner decides the limit, and the help in Advanced settings names it
         * in minutes.
         */
        const val MAX_ESTIMATED_MS = 3 * 60_000L

        /** Why the set model's step left nothing for the live model to fill: see [leavesNothingToFill]. */
        private val NOTHING_TO_FILL = setOf(
            BetterTranscript.Reason.NoSpeech,
            BetterTranscript.Reason.NoAudio,
            BetterTranscript.Reason.NewerRecorder,
            BetterTranscript.Reason.SkippedByOwner
        )
    }

    override suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript = run(sessionId, mayChangeModel, onStart, onProgress, mayLackSpeech = false)

    override suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit,
        mayLackSpeech: Boolean
    ): BetterTranscript {
        val set = settingsRepository.getSettings().first().transcribeAfterStop
        var model = set
        return try {
            val recording = transcriptionRepository.getSession(sessionId).first()?.language
                ?: RecordingLanguage.ENGLISH_OR_GERMAN
            val chosen = SpeechModelChoice.afterStopModel(recording, set)
            if (recording == RecordingLanguage.MALAYALAM && chosen != null) {
                model = chosen
                return transcribeMalayalam(sessionId, chosen, mayChangeModel, onStart, onProgress)
            }
            val better = chosen?.let {
                model = it
                transcribe(sessionId, it, mayChangeModel, onStart, onProgress)
            }
            if (!mayLackSpeech || better?.leavesNothingToFill() == true) {
                return better ?: BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
            }
            val live = recording.liveModel
            model = live
            fill(sessionId, live, mayChangeModel, onStart, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unloading the AI model, say, or a read: the step is extra, and the notes still come.
            model?.let { BetterTranscript.Failed(it, e) } ?: BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
        }
    }

    /**
     * Whether the set model's step leaves no gap for the live model to fill ([fill]): it transcribed
     * all of the audio, which held speech or none, there is no audio, a capture is live (the fill
     * would wait for the same), or the owner skipped it.
     */
    private fun BetterTranscript.leavesNothingToFill(): Boolean = when (this) {
        is BetterTranscript.Replaced -> true
        is BetterTranscript.Failed -> false
        is BetterTranscript.Skipped -> reason in NOTHING_TO_FILL
    }

    /**
     * The live model of the recording's language, [model], on all of its kept audio, as the live
     * transcript may lack speech ([BetterTranscriptStep.run]'s mayLackSpeech). Skipped for a
     * recording that holds Malayalam lines, which [model] would write over, beside a live capture,
     * and for one longer than [MAX_ESTIMATED_MS] by its estimate; the AI model in memory is
     * unloaded first, as for the Malayalam model, and the notes load it again.
     */
    private suspend fun fill(
        sessionId: String,
        model: SpeechModelId,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript {
        fun skipped(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(model, reason)

        if (!speechModels.isInstalled(model)) return skipped(BetterTranscript.Reason.NotInstalled)
        val audio = audioRepository.find(sessionId) ?: return skipped(BetterTranscript.Reason.NoAudio)
        val lines = transcriptionRepository.getSegmentsBySession(sessionId).first()
        if (lines.any { it.isComplete && LanguageDetector.isMalayalam(it.text) }) return skipped(BetterTranscript.Reason.LeftMalayalam)
        if (!mayChangeModel) return skipped(BetterTranscript.Reason.NewerRecorder)
        val background = !serviceInForeground()
        if (SpeechModelChoice.estimatedMs(model, audio.durationMs, background) > MAX_ESTIMATED_MS) {
            return skipped(BetterTranscript.Reason.TooLong)
        }
        if (llmRepository.isModelInMemory) {
            llmRepository.cleanup()
            if (llmRepository.isModelInMemory) return skipped(BetterTranscript.Reason.AiModelLoaded)
        }
        return runModel(sessionId, model, onStart, onProgress)
    }

    private suspend fun transcribe(
        sessionId: String,
        model: SpeechModelId,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript {
        fun skipped(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(model, reason)

        if (transcriptionRepository.getSession(sessionId).first()?.transcriptModel == model.id) {
            return BetterTranscript.Replaced(model)
        }
        if (!speechModels.isInstalled(model)) return skipped(BetterTranscript.Reason.NotInstalled)
        val audio = audioRepository.find(sessionId) ?: return skipped(BetterTranscript.Reason.NoAudio)
        val language = SpokenLanguage.of(transcriptionRepository.getSegmentsBySession(sessionId).first())
        if (!model.understands(language)) return skipped(BetterTranscript.Reason.WrongLanguage)
        if (!mayChangeModel) return skipped(BetterTranscript.Reason.NewerRecorder)
        // Without the AI's service in the foreground (only the finisher's wake job keeps the app,
        // as after a reboot) Android runs the threads on the little cores.
        val background = !serviceInForeground()
        if (SpeechModelChoice.estimatedMs(model, audio.durationMs, background) > MAX_ESTIMATED_MS) {
            return skipped(BetterTranscript.Reason.TooLong)
        }

        if (SpeechModelChoice.isLarge(model)) {
            // This recorder's own model: the notes load it, or the model for the work after Stop, again.
            llmRepository.cleanup()
            val readiness = SpeechModelChoice.readiness(
                model = model,
                language = language,
                installed = true,
                sizeBytes = speechModels.sizeBytes(model),
                aiModelLoaded = llmRepository.isModelInMemory,
                availableBytes = memory.availableBytes()
            )
            when (readiness) {
                ModelReadiness.Ready -> Unit
                ModelReadiness.AiModelLoaded -> return skipped(BetterTranscript.Reason.AiModelLoaded)
                else -> return skipped(BetterTranscript.Reason.NotEnoughMemory)
            }
        }

        return runModel(sessionId, model, onStart, onProgress)
    }

    /**
     * The Malayalam model ([model]) on the whole kept audio of a recording stored in Malayalam,
     * whatever its live transcript is: the same model made the part after the switch to it, with the
     * live voice detection and two threads, and Parakeet v3 the part before. Skipped for a recording
     * that went back to English or German ([SpeechModelChoice.leftMalayalam]), beside a live capture,
     * and for one longer than [MAX_ESTIMATED_MS] by its estimate; the AI model in memory, whichever
     * it is, is unloaded first, and the notes load the model they run on again.
     */
    private suspend fun transcribeMalayalam(
        sessionId: String,
        model: SpeechModelId,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript {
        fun skipped(reason: BetterTranscript.Reason) = BetterTranscript.Skipped(model, reason)

        if (!speechModels.isInstalled(model)) return skipped(BetterTranscript.Reason.NotInstalled)
        val audio = audioRepository.find(sessionId) ?: return skipped(BetterTranscript.Reason.NoAudio)
        if (SpeechModelChoice.leftMalayalam(transcriptionRepository.getSegmentsBySession(sessionId).first())) {
            return skipped(BetterTranscript.Reason.LeftMalayalam)
        }
        if (!mayChangeModel) return skipped(BetterTranscript.Reason.NewerRecorder)
        val background = !serviceInForeground()
        if (SpeechModelChoice.estimatedMs(model, audio.durationMs, background) > MAX_ESTIMATED_MS) {
            return skipped(BetterTranscript.Reason.TooLong)
        }
        // Never beside the AI model: the finisher keeps Gemma 4 E2B loaded between recordings.
        if (llmRepository.isModelInMemory) {
            llmRepository.cleanup()
            if (llmRepository.isModelInMemory) return skipped(BetterTranscript.Reason.AiModelLoaded)
        }
        return runModel(sessionId, model, onStart, onProgress)
    }

    /** [model] transcribes the recording again, which the owner can skip ([transcribeSkippably]), and what came of it. */
    private suspend fun runModel(
        sessionId: String,
        model: SpeechModelId,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript {
        val returned = transcribeSkippably(sessionId, model, onStart, onProgress)
            ?: return BetterTranscript.Skipped(model, BetterTranscript.Reason.SkippedByOwner)
        val outcome = returned.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            return BetterTranscript.Failed(model, cause)
        }
        return when (outcome) {
            TranscribeAgainOutcome.Replaced -> BetterTranscript.Replaced(model)
            TranscribeAgainOutcome.NoSpeech -> BetterTranscript.Skipped(model, BetterTranscript.Reason.NoSpeech)
            TranscribeAgainOutcome.NoAudio -> BetterTranscript.Skipped(model, BetterTranscript.Reason.NoAudio)
        }
    }

    /**
     * Transcribe again with [model], which the owner can skip while it runs: [onStart] gets the
     * run, and its skip cancels the transcription, which stops the model at the stretch of
     * speech it is on and leaves the live transcript. Once the new transcript is complete it is
     * stored whole ([TranscribeAgainUseCase]), and a skip that comes then changes nothing.
     *
     * @return What Transcribe again returned, or null when the skip stopped it first
     */
    private suspend fun transcribeSkippably(
        sessionId: String,
        model: SpeechModelId,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): Result<TranscribeAgainOutcome>? = coroutineScope {
        val skipped = AtomicBoolean(false)
        var returned: Result<TranscribeAgainOutcome>? = null
        // Started once the run is handed out, so its progress never comes before it.
        val transcription = async(start = CoroutineStart.LAZY) {
            transcribeAgain(sessionId, model, onProgress).also { returned = it }
        }
        onStart(
            BetterTranscriptRun(model) {
                skipped.set(true)
                transcription.cancel()
            }
        )
        try {
            transcription.await()
        } catch (e: CancellationException) {
            // A cancel of the finishing itself, or one that was no skip, is passed on.
            currentCoroutineContext().ensureActive()
            if (!skipped.get()) throw e
            returned
        }
    }
}
