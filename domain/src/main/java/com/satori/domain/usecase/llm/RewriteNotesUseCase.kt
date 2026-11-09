package com.satori.domain.usecase.llm

import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.NotesLanguageChoice
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.LinesNeverReadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** How a rewrite that did not fail ended. */
enum class RewriteOutcome {
    /** The recording has new notes in place of the old ones. */
    Replaced,

    /** There was too little text to write notes from. The old notes are as they were. */
    TooLittle
}

/**
 * Writes a recording's notes again from its transcript and swaps them in for the old ones.
 *
 * The notes come from [GenerateBatchInsightUseCase] over the whole transcript: one summary and,
 * for a long recording, a note per part. They are held in memory and replace the old notes in
 * one transaction ([TranscriptionRepository.replaceInsights]) only once all of them exist, so a
 * failure, a cancel, or a model that answers nothing leaves the old notes exactly as they were.
 * No copy of the recording is made. Once begun, the swap runs to its end also when the caller is
 * cancelled meanwhile (a recording that starts stops the rewrite) and is reported as
 * [RewriteOutcome.Replaced], so the caller never says the old notes stayed when they are gone.
 *
 * They are written by the same model as the work after Stop ([LoadFinishingModelUseCase]): Gemma 4
 * E2B when it is switched on and on the phone and [LoadFinishingModelUseCase.E2B_MARGIN_BYTES] are
 * free beyond its file, as for every load of it, else the live model. It is loaded only once the
 * transcript has proved long enough; the caller releases it.
 *
 * Since 0.8.0 they are written from the transcript the AI steps read (design R6,
 * [TranscriptionRepository.getLinesWithReadings]): a line only the reader reads (a Malayalam line)
 * as its English reading, none without one, with the prompts of an English recording (D6), in the
 * language the transcript as it was said gives (D8, [languageFor]). The caller makes the readings
 * first (EnsureReadingsUseCase), before the model loads; a recording with such a line never read
 * gets no notes of the rest ([LinesNeverReadException], D7), and its old notes stay.
 *
 * @property transcriptionRepository Reads the recording and swaps its notes.
 * @property settingsRepository Supplies [com.satori.domain.model.AppSettings.notesLanguage].
 * @property generateBatchInsightUseCase The map-reduce pipeline that writes the notes.
 * @property loadFinishingModel Puts the model for the work after Stop in memory.
 */
class RewriteNotesUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val generateBatchInsightUseCase: GenerateBatchInsightUseCase,
    private val loadFinishingModel: LoadFinishingModelUseCase
) {
    companion object {
        /** The complete lines together need this many characters to be worth a pass of the model. */
        const val MIN_TOTAL_CHARS = 200

        private val WHITESPACE = Regex("\\s+")

        /**
         * Whether [segments] hold enough text for [invoke] to write notes from: [MIN_TOTAL_CHARS]
         * characters, and the words ([GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH]) the
         * pipeline needs to run at all. The screens offer a rewrite on this answer, so it must not
         * promise what the pipeline would turn down.
         */
        fun hasEnoughText(segments: List<TranscriptionSegment>): Boolean {
            val lines = segments.filter { it.isComplete }.map { it.text.trim() }
            if (lines.sumOf { it.length } < MIN_TOTAL_CHARS) return false
            // The screens ask on every recomposition: stop counting at the floor, not at the end.
            var words = 0
            for (line in lines) {
                words += line.split(WHITESPACE).count { it.isNotEmpty() }
                if (words >= GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH) return true
            }
            return false
        }

        /**
         * The language to write the notes of [session] in, as "en" or "de"; null leaves the choice
         * to the settings, whose prompts are in the app's language.
         *
         * - A translation keeps its own target language, whatever [setting] says.
         * - A Malayalam transcript's notes are English, whatever [setting] says ([NotesLanguageChoice]).
         * - [NotesLanguage.EN] and [NotesLanguage.DE] are used as they are.
         * - [NotesLanguage.AUTO] takes the language [LanguageDetector] finds in the whole
         *   transcript, which is a better sample than any one part of it, English for a transcript
         *   clearly in another language ([NotesLanguageChoice.detect]). When the detector is
         *   unsure, the recording's stored language stands in if it is English or German.
         */
        internal fun languageFor(
            session: TranscriptionSession,
            segments: List<TranscriptionSegment>,
            setting: NotesLanguage
        ): String? {
            if (session.mode == RecordingMode.REAL_TIME_TRANSLATION) return session.outputLanguage
            val transcript = segments.joinToString(" ") { it.text }
            if (LanguageDetector.isMalayalam(transcript)) return LanguageDetector.ENGLISH
            setting.code?.let { return it }
            NotesLanguageChoice.detect(transcript)?.let { detected ->
                return NotesLanguageChoice.choose(setting, detected, stored = null, device = null)
            }
            return session.outputLanguage?.takeIf {
                it == LanguageDetector.ENGLISH || it == LanguageDetector.GERMAN
            }
        }
    }

    /**
     * Rewrite the notes of a recording.
     *
     * @param sessionId The recording whose notes are replaced.
     * @param mayChangeModel Whether the model in memory may be swapped for the one for the work
     *   after Stop; false while a recorder owns the loaded model (see ActiveRecorder.mayRelease).
     * @param onModel The model the notes are written with, once it is known, for the log.
     * @param onProgress Each step of the pipeline, for the screen's progress.
     * @param nameAsTopic Whether the recording's name steers the notes when it has no topic of
     *   its own. Not when its text is made again from a transcript in another language
     *   ([com.satori.domain.usecase.stt.MakeTextAgainUseCase]): its name was written from
     *   the old transcript, and the rewrite took it as its topic on 2026-10-15, writing notes of a
     *   gibberish name in place of what the new transcript said.
     * @return [RewriteOutcome.Replaced] or [RewriteOutcome.TooLittle], or a failure when the
     *   recording is gone, lines only the reader reads were never read ([LinesNeverReadException]),
     *   the model can't be loaded or run or writes no notes, or the notes can't be stored. A cancel
     *   is thrown, not returned, unless it comes as the notes are swapped in: that ends, and is
     *   [RewriteOutcome.Replaced].
     */
    suspend operator fun invoke(
        sessionId: String,
        mayChangeModel: Boolean = true,
        onModel: (FinishingModel) -> Unit = {},
        onProgress: (BatchInsightProgress) -> Unit = {},
        nameAsTopic: Boolean = true
    ): Result<RewriteOutcome> {
        return try {
            val session = transcriptionRepository.getSession(sessionId).first()
                ?: return Result.failure(NoSuchElementException("Session not found: $sessionId"))
            val lines = transcriptionRepository.getLinesWithReadings(sessionId)
            // D7: never notes of the rest of what was said in place of all of them.
            if (lines.hasNeverRead) return Result.failure(LinesNeverReadException())
            val said = lines.said.filter { it.isComplete }.sortedBy { it.timestamp }
            // What the AI reads: a line only the reader reads as its reading, none without one.
            val segments = lines.readable.filter { it.isComplete }.sortedBy { it.timestamp }
            if (!hasEnoughText(segments)) return Result.success(RewriteOutcome.TooLittle)

            onModel(if (mayChangeModel) loadFinishingModel() else FinishingModel.Live)
            val notesLanguage = settingsRepository.getSettings().first().notesLanguage
            val parts = mutableListOf<LlmInsight>()
            // Recordings are no longer given a topic, so the name (the AI's or the user's) steers
            // the notes when there is none, unless it was written from another transcript. It goes
            // with the text, never into the system prompt ([NotesTopic]).
            val summary = generateBatchInsightUseCase(
                segments = segments,
                sessionId = sessionId,
                mode = session.mode,
                // In the language of the transcript as it was said (D8): English for Malayalam.
                outputLanguage = languageFor(session, said, notesLanguage),
                topic = session.topic ?: session.name.takeIf { nameAsTopic },
                onProgress = onProgress,
                onChunkInsight = { parts += it }
            ).getOrElse { return Result.failure(it) }

            // A cancel can come back from the pipeline as a failed Result instead of an exception,
            // or arrive after its last token: either way, nothing may be written once cancelled.
            currentCoroutineContext().ensureActive()

            // The transcript passed hasEnoughText, so the pipeline only returns nothing when the
            // model answered nothing: a failed rewrite, not a transcript that is too short.
            val notes = parts + listOfNotNull(summary)
            if (notes.isEmpty()) return Result.failure(IllegalStateException("The model wrote no notes"))
            withContext(NonCancellable) {
                transcriptionRepository.replaceInsights(sessionId, notes, InsightStrategy.END_OF_SESSION)
            }.map { RewriteOutcome.Replaced }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
