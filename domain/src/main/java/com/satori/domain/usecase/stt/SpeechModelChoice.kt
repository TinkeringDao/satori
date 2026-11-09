package com.satori.domain.usecase.stt

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment

/** Whether a speech model can transcribe a recording again now, or why not. */
enum class ModelReadiness {
    /** It can. */
    Ready,

    /** Its files are not (all) on the phone. */
    NotInstalled,

    /**
     * It doesn't know the recording's language, or the language isn't known and the model can't
     * find it itself ([SpeechModelId.understands]).
     */
    WrongLanguage,

    /** It is large ([SpeechModelChoice.isLarge]) and the AI model is in memory. */
    AiModelLoaded,

    /** It is large and less memory is free than its size and [SpeechModelChoice.MEMORY_MARGIN_BYTES]. */
    NotEnoughMemory
}

/**
 * One speech model as the Transcribe again sheet offers it.
 *
 * @property readiness [ModelReadiness.Ready], or why it can't run now
 * @property estimatedMs About how long it takes for the recording ([SpeechModelChoice.estimatedMs])
 * @property madeTranscript Whether the transcript the recording has now is this model's
 * @property changesLanguage Whether its transcript puts the recording in another language
 *   ([RecordingLanguage.transcribedBy]): the Malayalam model's for an English or German recording,
 *   the others' for a Malayalam one
 * @property makesTextAgain Whether choosing it has the recording's name, notes, to-dos and ideas
 *   made again from the new transcript, where the page can ([SpeechModelChoice.makesTextAgain])
 */
data class TranscribeOption(
    val model: SpeechModelId,
    val readiness: ModelReadiness,
    val estimatedMs: Long,
    val madeTranscript: Boolean,
    val changesLanguage: Boolean = false,
    val makesTextAgain: Boolean = false
)

/**
 * How long a speech model takes to transcribe a recording again from its kept audio: [fixedMs]
 * to load it and start, then [msPerAudioSecond] for each second of audio.
 */
internal data class TranscribeTime(val fixedMs: Long, val msPerAudioSecond: Long)

/**
 * Which speech model may transcribe a recording again, and how long it takes: for the Transcribe
 * again sheet and for the transcript after Stop. Pure, so the rules are tested without a phone.
 *
 * A large model needs the AI model out of memory: Cohere Transcribe's 2.9 GB next to Gemma 4
 * E2B's 3.3 GB is more than the 2.9-4.4 GB Satori has seen free on the razr, and Android ends an
 * app that runs out of memory instead of letting it fail.
 */
object SpeechModelChoice {

    /**
     * The memory a large model needs free beyond its own size: the app itself, the audio being
     * read and the decoder's working memory, which grows with the length of a stretch of speech.
     */
    const val MEMORY_MARGIN_BYTES = 600L * 1024 * 1024

    private const val MS_PER_SECOND = 1_000L

    /**
     * How long [model] takes to transcribe a recording again on the razr, in the foreground
     * scheduling group, the slowest one the app's own runs are in while a foreground service of
     * the app runs. Android puts the app's threads in a group by what the app does, and the group
     * decides the cores they may run on (the razr's pineapple has three little cores of capacity
     * 380, four of 873 and one of 1024):
     * - top-app, cores 0-7: Satori in front, as Transcribe again on the recording's page runs;
     * - foreground, cores 0-6: the screen off under a foreground service, as Transcribe again runs
     *   under the AI's service once the screen goes off, and the better transcript after Stop
     *   under the finisher's;
     * - background, cores 0-2, the little ones: the screen off and no service. The better
     *   transcript after Stop runs there when only the finisher's wake job keeps the app, as
     *   after a reboot: a JobScheduler job doesn't lift the app's group. Its cut then goes by
     *   [backgroundTranscribeTime].
     *
     * The phone's times on 2026-10-13, on a 49.1 s and a 43.6 s recording: the load (from the
     * speech model's line to its voice detector's) and the recognition per second of audio.
     * Parakeet v3 took 3.0 s + 0.054 s in top-app and 9.0 s + 0.29 s in background; Cohere
     * Transcribe 8.1 s + 0.20 s in top-app and 15.3 s + 0.28 s in foreground, after Stop with the
     * screen off. Cohere's numbers are those foreground times rounded up: 18 s + 0.33 s.
     * Parakeet wasn't timed in foreground: its top-app times scaled by Cohere's foreground to
     * top-app ratios (1.9 for the load, 1.4 for the recognition) and rounded up give 7 s + 0.09 s,
     * also for v2, which took 0.95 times v3's time on 2026-10-12. A little over is better than
     * under: the recordings queued behind a better transcript wait for it.
     *
     * A run in the background group takes 4 to 7 times as long as one in front, about 2 to 4.5
     * times this estimate. The debug runs of 2026-10-12, with the screen off and no service, took
     * 10.7 s + 0.566 s (v3), 10.6 s + 0.535 s (v2) and 37.4 s + 1.579 s (Cohere) on six
     * recordings of 7 to 146 s, timed from the run's start to the stored transcript, and the
     * estimate made from them was 4 to 6 times too high for the app's own runs. Measure these
     * again in the foreground group (the field log's summary line says `cpus=0-6`) after any
     * change to the speed of a whole-file transcription; a new model needs its own numbers,
     * measured the same way.
     *
     * The Malayalam model hasn't run in the app yet. sherpa-onnx's own tool on the razr
     * (2026-10-14, four threads) made its recognizer in 2.1 s and took 0.10 to 0.12 s per second
     * of audio, on a 30 s and a 29 s note and an 18-minute meeting. Its numbers are those to the
     * safe side, 3 s + 0.15 s, until the app's runs in the foreground group are timed.
     */
    internal fun transcribeTime(model: SpeechModelId): TranscribeTime = when (model) {
        SpeechModelId.PARAKEET_V3 -> TranscribeTime(fixedMs = 7_000L, msPerAudioSecond = 90L)
        SpeechModelId.PARAKEET_V2_EN -> TranscribeTime(fixedMs = 7_000L, msPerAudioSecond = 90L)
        SpeechModelId.COHERE_TRANSCRIBE -> TranscribeTime(fixedMs = 18_000L, msPerAudioSecond = 330L)
        SpeechModelId.INDICCONFORMER_ML -> TranscribeTime(fixedMs = 3_000L, msPerAudioSecond = 150L)
    }

    /**
     * How long [model] takes in the background group, on the three little cores
     * ([transcribeTime]): the debug runs of 2026-10-12, with the screen off and no service,
     * rounded up, as 0.5.0 estimated every run. Only the better transcript after Stop runs there,
     * when only the finisher's wake job keeps the app. Such a job runs for about ten minutes at a
     * time, and a step it cuts short twice is skipped, holding the recordings queued behind it
     * all that while; by these times a run within
     * [com.satori.domain.usecase.finish.TranscribeAfterStopUseCase.MAX_ESTIMATED_MS] ends
     * well inside one. Not yet seen on the phone under the wake job itself, where the run's summary
     * line would say `cpus=0-2`.
     *
     * The Malayalam model transcribes every recording in Malayalam again after Stop
     * ([afterStopModel]), so it runs in that group when only the wake job keeps the app. It hasn't
     * been timed on the little cores yet; until it is, it has Parakeet v3's times, whose encoder
     * does about twice its work per second of audio, as the safe side.
     */
    internal fun backgroundTranscribeTime(model: SpeechModelId): TranscribeTime = when (model) {
        SpeechModelId.PARAKEET_V3, SpeechModelId.INDICCONFORMER_ML ->
            TranscribeTime(fixedMs = 11_000L, msPerAudioSecond = 570L)
        SpeechModelId.PARAKEET_V2_EN -> TranscribeTime(fixedMs = 11_000L, msPerAudioSecond = 540L)
        SpeechModelId.COHERE_TRANSCRIBE -> TranscribeTime(fixedMs = 38_000L, msPerAudioSecond = 1_600L)
    }

    /**
     * Whether [model] is too big to run next to the AI model: one that may not run live. Those that
     * may are no bigger than Parakeet v3, which runs next to it while recording.
     */
    fun isLarge(model: SpeechModelId): Boolean = !model.runsLive

    /** The model that transcribes recordings in Malayalam, AI4Bharat's: the one of Malayalam alone. */
    private val MALAYALAM_MODEL: SpeechModelId = RecordingLanguage.MALAYALAM.liveModel

    /**
     * The speech model that transcribes a recording in [recording] again after Stop, with [set]
     * chosen for that in Advanced settings ([com.satori.domain.model.AppSettings.transcribeAfterStop]):
     * [set] for a recording in English or German, null when nothing is set, and for a recording in
     * Malayalam the Malayalam model, whatever is set. Its whole audio, read in one piece with the
     * voice detection for files, gets back what Parakeet v3 made of the Malayalam said before the
     * recording was switched to it (the first 13 s of a 36 s note on 2026-10-15), and a little more
     * of the rest; the notes, the name and the to-dos are then written from that transcript. None of
     * the models set for after Stop knows Malayalam.
     */
    fun afterStopModel(recording: RecordingLanguage, set: SpeechModelId?): SpeechModelId? =
        if (recording == RecordingLanguage.MALAYALAM) MALAYALAM_MODEL else set

    /**
     * Whether the recording of [lines] went back from Malayalam to English or German while it was
     * recorded: a complete line in Latin letters comes after one in Malayalam script. The Malayalam
     * model writes Malayalam script only and the others Latin letters only, so a line's script says
     * which model made it ([TranscriptLanguage]). Such a recording, though it ended in Malayalam
     * again, isn't transcribed again in Malayalam after Stop: the Malayalam model would write what
     * was said in English or German in Malayalam letters. One that only went to Malayalam is, with
     * the lines before the switch. Lines without a letter count for neither.
     */
    fun leftMalayalam(lines: List<TranscriptionSegment>): Boolean {
        var inMalayalam = false
        for (line in lines.filter { it.isComplete && it.text.any(Char::isLetter) }.sortedBy { it.timestamp }) {
            when {
                LanguageDetector.isMalayalam(line.text) -> inMalayalam = true
                inMalayalam -> return true
            }
        }
        return false
    }

    /**
     * The language [model] transcribes a recording in again, whose transcript there is now reads as
     * [spoken] ([com.satori.domain.language.SpokenLanguage]): the one a model told the
     * language is told, and the one [SpeechModelId.understands] judges.
     * - The Malayalam model transcribes any recording in Malayalam ("ml"): the owner picks it for
     *   one spoken in Malayalam, whatever its transcript reads as. Recorded in English or German,
     *   Malayalam speech gets a transcript from Parakeet v3 in no clear language, or in one of the
     *   European languages it knows.
     * - Any other model is told what [spoken] says, but for Malayalam: a transcript in Malayalam
     *   script says nothing of what an English or German model would hear, so the language is not
     *   known (null), and only one that finds it itself, Parakeet v3, can transcribe it then. That
     *   is the way back for a recording made in Malayalam by mistake.
     */
    fun languageFor(model: SpeechModelId, spoken: String?): String? = when {
        model == MALAYALAM_MODEL -> LanguageDetector.MALAYALAM
        spoken == LanguageDetector.MALAYALAM -> null
        else -> spoken
    }

    /**
     * Whether a recording in [mode] may be transcribed again by [model]: any model for any
     * recording, but the Malayalam model not for a Translate recording, which stays in English or
     * German ([com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase.offered]):
     * its translation is written live by the small AI model, which reads Malayalam poorly.
     */
    fun mayTranscribe(model: SpeechModelId, mode: RecordingMode?): Boolean =
        model != MALAYALAM_MODEL || mode != RecordingMode.REAL_TIME_TRANSLATION

    /**
     * Whether a transcript made again by [model] has the recording's text made again from it: its
     * name, its notes, and its to-dos and ideas but those the owner has changed
     * ([MakeTextAgainUseCase]). So it has when the new transcript changes
     * the language the recording is in, [recording] ([RecordingLanguage.transcribedBy]: the
     * Malayalam model makes an English or German recording Malayalam, and the others make a
     * Malayalam one English or German again), and when it replaces a transcript that wasn't in
     * that language ([transcriptInLanguage] false, [TranscriptLanguage.isIn]): lines Parakeet wrote
     * before the recording was switched to Malayalam, say. That text was written from what a model
     * made of speech in another language: on 2026-10-15 a Malayalam note Parakeet had made
     * gibberish of kept the name and the idea the gibberish gave it, and its notes were rewritten
     * with that name as their topic. A Malayalam recording's text is written in English (the
     * owner's decision, 2026-10-14). Any other transcript made again leaves the text as it is.
     */
    fun makesTextAgain(model: SpeechModelId, recording: RecordingLanguage, transcriptInLanguage: Boolean): Boolean =
        RecordingLanguage.transcribedBy(model) != recording || !transcriptInLanguage

    /**
     * Whether [model] can transcribe a recording in [language] now.
     *
     * @param language "en", "de", ... or null when it isn't known ([com.satori.domain.language.SpokenLanguage])
     * @param installed Whether its files are on the phone, whole
     * @param sizeBytes The size of its files
     * @param aiModelLoaded Whether the AI model is in memory (loaded or loading)
     * @param availableBytes The memory free now
     */
    fun readiness(
        model: SpeechModelId,
        language: String?,
        installed: Boolean,
        sizeBytes: Long,
        aiModelLoaded: Boolean,
        availableBytes: Long
    ): ModelReadiness = when {
        !installed -> ModelReadiness.NotInstalled
        !model.understands(language) -> ModelReadiness.WrongLanguage
        !isLarge(model) -> ModelReadiness.Ready
        aiModelLoaded -> ModelReadiness.AiModelLoaded
        availableBytes < sizeBytes + MEMORY_MARGIN_BYTES -> ModelReadiness.NotEnoughMemory
        else -> ModelReadiness.Ready
    }

    /**
     * About how long [model] takes to transcribe [audioMs] of audio again ([transcribeTime], or
     * [backgroundTranscribeTime] for a run in the [background] group), rounded up to the
     * millisecond.
     */
    fun estimatedMs(model: SpeechModelId, audioMs: Long, background: Boolean = false): Long {
        val time = if (background) backgroundTranscribeTime(model) else transcribeTime(model)
        val decodeMs = (time.msPerAudioSecond * audioMs.coerceAtLeast(0L) + MS_PER_SECOND - 1) / MS_PER_SECOND
        return time.fixedMs + decodeMs
    }

    /**
     * What the Transcribe again sheet lists: each model on the phone that knows the language it
     * would transcribe the recording in ([languageFor]), in the order of [SpeechModelId], with
     * whether it can run now, about how long it takes, whether it puts the recording in another
     * language, and whether the recording's text is then made again ([makesTextAgain]). The
     * Malayalam model is so listed for any recording but a Translate one ([mayTranscribe]). Models
     * that aren't on the phone or don't know the language aren't listed: there is nothing to do
     * about them on the page.
     *
     * @param language What the transcript there is now reads as
     *   ([com.satori.domain.language.SpokenLanguage]), null when it isn't clearly one
     * @param installed The models whose files are on the phone, whole
     * @param sizeBytes The size of each model's files
     * @param audioMs The length of the recording's audio
     * @param current The model that made the transcript the recording has now, or null if this
     *   version doesn't know it ([SpeechModelId.ofTranscript])
     * @param mode The recording's mode, null when it isn't known
     * @param recording The language the recording is in now
     * @param transcriptInLanguage Whether the transcript there is now is in [recording]
     *   ([TranscriptLanguage.isIn])
     */
    fun options(
        language: String?,
        installed: Set<SpeechModelId>,
        sizeBytes: (SpeechModelId) -> Long,
        aiModelLoaded: Boolean,
        availableBytes: Long,
        audioMs: Long,
        current: SpeechModelId?,
        mode: RecordingMode? = null,
        recording: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
        transcriptInLanguage: Boolean = true
    ): List<TranscribeOption> = SpeechModelId.entries
        .filter { it in installed && it.understands(languageFor(it, language)) && mayTranscribe(it, mode) }
        .map { model ->
            TranscribeOption(
                model = model,
                readiness = readiness(model, languageFor(model, language), installed = true, sizeBytes(model), aiModelLoaded, availableBytes),
                estimatedMs = estimatedMs(model, audioMs),
                madeTranscript = model == current,
                changesLanguage = RecordingLanguage.transcribedBy(model) != recording,
                makesTextAgain = makesTextAgain(model, recording, transcriptInLanguage)
            )
        }
}
