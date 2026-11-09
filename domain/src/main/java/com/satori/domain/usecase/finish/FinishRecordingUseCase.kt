package com.satori.domain.usecase.finish

import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.todo.UntouchedFinds
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a finishing recording is waiting for; the screen words it as a status line. */
enum class FinishStep {
    /**
     * A better speech model transcribes the recording again before anything is written from it
     * ([TranscribeAfterStopUseCase]). The screen says "Making a better transcript".
     */
    Transcript,

    /**
     * Lines the speech recognizer cut mid-sentence are joined ([TranscriptLinesStep]), so
     * everything after it reads whole sentences. Runs with the AI off too.
     */
    Lines,

    /**
     * The reader reads the lines only it reads (a Malayalam recording's) into English
     * ([ReadingStep], 0.8.0), so the AI steps after it read English. Only with the AI on (or its
     * model on its way), and only for a recording with such lines. The screen says "Finishing up".
     */
    Reading,

    /** The microphone is off and the AI writes the last notes. */
    Notes,

    /** The AI writes the recording's name. */
    Name,

    /** The AI looks for to-dos in what was said. */
    Todos,

    /**
     * The AI links the recording's topics into the mind map and rewrites the summaries of the
     * themes they joined. The screen says "Linking topics".
     */
    Map
}

/**
 * Everything done for a recording once its capture has ended, in this order: a better transcript,
 * when a speech model is set for that ([BetterTranscriptStep]); the joining of lines the speech
 * recognizer cut mid-sentence ([TranscriptLinesStep]); then what the AI does: the last notes, the
 * name, the to-dos, the mind map. The finisher of recordings runs it in the background (the app's
 * RecordingFinisher), one recording after the other, while the owner may already record the next.
 * Each step is best effort: a step that fails or finds nothing leaves what the earlier ones saved,
 * and the next one still runs; one that throws is reported ([Progress.onStepFailed]) as well, also
 * when it throws an error of code that could not be loaded or set up, or of memory (since 0.7.4:
 * on 2026-10-19 the to-dos step threw ExceptionInInitializerError on the phone). Only cancellation
 * ends the run early, and it is passed on, and so is a failure to read the recording itself,
 * without which no step has anything to work on, and an error that is no step's alone.
 *
 * Every step reads what it works on from the repository, never from what a recorder held in
 * memory, and can run again: a run cut short by a process death goes on from the step it was at
 * ([Request.resumeFrom]), and a step run twice leaves what a single run leaves. Before each step
 * [Progress.beforeStep] may hold the run, or defer it until no capture is live
 * ([Outcome.Deferred]): the finisher waits there for a new recording's microphone or for a hot
 * phone to cool, and defers while a Translate capture needs the AI model.
 *
 * The silent rule: the recorder applies it at the hand-off, with the line it showed, so a recording
 * handed over is never deleted here; running the rule a second time, on less than the recorder
 * knew, could delete one it kept. A recording whose capture a process death cut off
 * ([Request.recovered]) had no recorder to see its end: it is deleted here, before any step, if
 * nothing was said in it ([DiscardSilentRecordingUseCase], as for a capture whose end no recorder
 * saw). One that can't be deleted gives the steps nothing to work on, and is left as it is.
 *
 * A recording whose live recognizer left audio untranscribed ([Request.recognizerFailed]: it
 * failed, or Stop came while its model was still read in) has all of its kept audio transcribed in
 * the Transcript step, by the live model when no better one does it
 * ([TranscribeAfterStopUseCase]); when that finds no speech in the audio and no line holds a word,
 * the recording is deleted then ([Outcome.Discarded]), as Stop deletes a silent one.
 *
 * The better transcript and the joining come first, so everything after them reads the final
 * transcript; both run also with the AI off. The notes then are:
 * - For an end-of-session recording, the whole map-reduce pass. Its notes replace any the
 *   recording has, in one transaction once they all exist
 *   ([TranscriptionRepository.replaceInsights]), so a pass run again leaves one set; the parts of
 *   a pass whose last step failed are kept the same way.
 * - For a real-time recording whose transcript the better model replaced, the same pass: the live
 *   notes were written from the first pass, and are replaced by notes of the final transcript. A
 *   pass that fails or writes nothing leaves the live notes.
 * - Otherwise a final note on the tail ([RecordingTail]), the lines no note cites, which then
 *   cites them, so a second run finds no tail. A tail longer than the final note reads
 *   ([GenerateFinalInsightUseCase.maxUserPromptChars]; the live notes waited while an older
 *   recording was finished) gets the whole pass, as after a better transcript. A Malayalam
 *   recording has no live notes, so its tail is its whole transcript, and as its characters cost
 *   more tokens a shorter one gets the pass ([com.satori.domain.usecase.llm.TextBudget]).
 *
 * A recording with enough words for notes is not left without a note or a name for one answer that
 * gave nothing usable (since 0.7.2): a notes call whose answer leaves no point is asked once more
 * ([GenerateFinalInsightUseCase], [GenerateBatchInsightUseCase]), and an answer that is no name once
 * more from the transcript, before a note's title or the default name stands in
 * ([AutoNameSessionUseCase]); both in their own step, on the model in memory.
 *
 * A Malayalam recording's notes, name, to-dos and topics are written in English (the owner's
 * decision, 2026-10-14), since 0.8.0 from its readings (design 3.A): after the language detection,
 * which reads the transcript as it was said (D8), the Reading step ([FinishStep.Reading],
 * [ReadingStep]) reads the lines only the reader reads into English, one by one, storing each, and
 * the AI steps then read the recording's readable lines
 * ([TranscriptionRepository.getReadableSegments]): its English lines as they are and each such line
 * as its reading, a line without one left out, so the AI model never reads Malayalam. Here the Notes
 * step reads them, and the bars the Name, To-dos and Map steps ask of the text before they begin;
 * their use cases read the same lines themselves (design R6: AutoNameSessionUseCase,
 * ExtractSessionTodosUseCase, MapRecordingUseCase), with no reader of their own, the step having read
 * them. Their prompts are those of an English recording (D6), and the language they write in is the
 * one the transcript as it was said gives ([NotesLanguageResolver]: English for Malayalam, whatever
 * the setting).
 * - The Reading step runs only with the AI on or its model on its way, and only beside no capture
 *   (O5): a live one defers the run, one that opens stops the reader at once (D14,
 *   [Progress.onReadingStart]) and defers it too, and the run goes on from the next line once the
 *   capture is over. A reader whose load the phone's memory stopped ends the run as a model's does
 *   ([Outcome.LoadStopped]); one that something else in memory keeps from loading ends it to try
 *   again later ([Outcome.ReaderHeldOff]).
 * - A reader on its way ([Request.readerPending]: the first run downloads it) holds the run at
 *   the step, before its waits, as the AI model on its way does ([Outcome.WaitsForModel] with
 *   [Outcome.WaitsForModel.reader]).
 * - Without the reader, and none coming, or with lines it could not read, the AI steps don't run
 *   at all (D7, [Outcome.Unread]): no notes rather than the AI model's of Malayalam. They come when
 *   the reader can read them (design R9).
 *
 * A Translate recording keeps its live translation, also after a better transcript or with a long
 * tail: the pass translates a part of 5,000 characters in at most 256 tokens, so it would put the
 * start of each part in their place. Its tail gets a final note on its last part, which cites all
 * of the tail.
 *
 * The mind map step links the recording's topics into the map ([MapRecordingUseCase]), then
 * rewrites up to [RefreshThemeSummariesUseCase.PER_FINISH] theme summaries: those of the themes
 * the recording joined first, then others that are stale. It runs only for a recording with
 * enough transcript to map ([MapRecordingUseCase.hasEnoughText]), so a microphone test doesn't
 * keep the user waiting for summaries; and when the model failed to map, the summaries wait for
 * the next recording.
 *
 * The Notes step first sees whether the text holds enough for the notes it would write, by the bar
 * those notes ask for ([GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT] for a final note,
 * [GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH] for the whole pass), before any model is
 * chosen: with too little it says so at once ([Progress.onTooLittleText]), so the screens can tell
 * the owner right after Stop, and goes on without one. On 2026-10-13 a 17-word voice note waited
 * 16 s for room for Gemma 4 E2B before the final note found its text too short.
 *
 * The AI steps run on the model for the work after Stop ([LoadFinishingModelUseCase]): Gemma 4 E2B
 * in place of the live model when it is switched on and on the phone. It is loaded once, as the
 * first AI step that has something to do begins (each step asks the bar of its own work first), so
 * a recording that needs none never loads it ([Progress.onNoModelNeeded]); the caller registers the
 * live model as the fallback before the run, and releases the model after. E2B loads only with
 * [LoadFinishingModelUseCase.E2B_MARGIN_BYTES] free beyond its file, as every load of it does,
 * whether a capture runs beside the finishing ([Progress.captureLive]) or not, and beside a capture
 * also with the room its speech model may still take ([Progress.captureGrowthBytes]). Beside a
 * capture, with less the run is deferred until the capture ends: the smaller live model would find
 * fewer of the to-dos. With no capture live, too little memory alone doesn't give the live model at once
 * either: the step waits for the speech model of the recorder that closed to be freed and tries E2B
 * again once a second for [IDLE_RETRY_MS] ([ModelWait]), and only then does the live model do the
 * work. A capture that goes live during those waits defers the run, and nothing loads beside it
 * while it opens.
 *
 * The live model, when the AI steps run on it, is put in memory as the first of them begins, as
 * Gemma 4 E2B is: every AI model load is paced since 0.6.1, and one the phone's memory stops (MemFree
 * under the pacing floor for 10 s in all, [LoadStoppedForMemoryException]) ends the run there
 * ([Outcome.LoadStopped]), for the finisher to go on from that step later, instead of every AI step
 * failing on it in turn and the recording being finished without notes, name or to-dos.
 *
 * A Malayalam recording finished before its lines were read (by an older version's AI model on
 * the lines as they were said, or without its AI steps for want of the reader) is made again once
 * the reader is on the phone ([Request.redo], design R9: the backfill of readings queues it, from
 * its Reading step): its lines are read, and its AI steps run again over the readings and replace
 * what the AI made of them before, as Transcribe again's text made again replaces what the old
 * transcript gave, but what the owner changed, which stays as he left it ([Redo]): a note he edited
 * keeps all the notes ([com.satori.domain.model.LlmInsight.editedAt]), a name he typed stays
 * ([TranscriptionSession.nameByOwner]), and so do the to-dos and ideas he touched
 * ([com.satori.domain.usecase.todo.UntouchedFinds]). What the recording has is kept first, once,
 * before anything of it is replaced ([TranscriptionRepository.keepBeforeRedo]): a name or notes the
 * owner set before version 13 recorded it can't be told from the app's, and can be given back. New
 * notes replace the old ones in one write once written, unless he saved a note meanwhile, and a
 * transcript with too little text for notes takes the old ones away; notes that fail, or leave
 * nothing, leave them, and the run ends [Outcome.NotesFailed], for the backfill to try again. The
 * name is made from the new notes, or the readings, never from the old notes, in
 * place of the app's name; the app's untouched finds give way to the new ones in the write that
 * stores them; the map is made again, without the model when nothing readable is left. With too
 * little readable text for a name or for to-dos, the old ones stay, as after Transcribe again.
 *
 * A recording saved while the AI model is on its way ([Request.modelPending]: the first run
 * downloads it after the first recording) is not finished without the AI either: its better
 * transcript, its lines and the Notes step's look at its text run as with the AI, and the run stops
 * as its first AI step with something to do would begin, before that step's waits and before any
 * model ([Outcome.WaitsForModel]). The finisher goes on from that step once the model is in. A
 * recording with too little text for notes says so first, so the screens tell the owner at once,
 * and it waits for the model only for its name, its to-dos or its map.
 *
 * @property transcriptionRepository Where the recording is read and its notes are stored
 * @property generateFinalInsight Writes the final note on the tail
 * @property generateBatchInsight Writes the notes of a whole transcript
 * @property autoNameSession Names a recording that has no name
 * @property extractSessionTodos Finds the to-dos
 * @property mapRecording Links the recording's topics into the mind map
 * @property refreshThemeSummaries Rewrites the summaries of the themes the recording joined
 * @property notesLanguage Chooses the language of the output
 * @property loadFinishingModel Puts the model for the work after Stop in memory
 * @property betterTranscript Transcribes the recording again with the model set for after Stop
 * @property transcriptLines Joins the lines cut mid-sentence
 * @property discardSilentRecording Deletes a recovered recording if nothing was said in it
 * @property readingStep Reads the lines only the reader reads into English
 * @property untouchedFinds The app's to-dos and ideas the owner hasn't touched, which a run made
 *   again replaces ([Request.redo]); without it such a run leaves the old ones beside the new
 */
class FinishRecordingUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val generateFinalInsight: GenerateFinalInsightUseCase,
    private val generateBatchInsight: GenerateBatchInsightUseCase,
    private val autoNameSession: AutoNameSessionUseCase,
    private val extractSessionTodos: ExtractSessionTodosUseCase,
    private val mapRecording: MapRecordingUseCase,
    private val refreshThemeSummaries: RefreshThemeSummariesUseCase,
    private val notesLanguage: NotesLanguageResolver,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val betterTranscript: BetterTranscriptStep,
    private val transcriptLines: TranscriptLinesStep,
    private val discardSilentRecording: DiscardSilentRecordingUseCase,
    private val readingStep: ReadingStep,
    private val untouchedFinds: UntouchedFinds? = null
) {
    /**
     * Which recording to finish, and how.
     *
     * @property sessionId The recording
     * @property aiAvailable The AI is on and its live model is on the phone; false skips every AI
     *   step
     * @property mayChangeModel Whether the model in memory may be swapped for the one for the work
     *   after Stop; false runs the AI steps on the model that is there, and makes no better
     *   transcript
     * @property resumeFrom The step a run cut short goes on from; the steps before it don't run
     *   again. Null starts at the first.
     * @property recovered Its capture ended without a hand-off (the process died while it
     *   recorded), so the silent rule runs here
     * @property recognizerFailed The live speech recognition failed during the capture, so part of
     *   what was recorded may never have been transcribed
     * @property modelPending With [aiAvailable] false: AI notes are on and the AI model is on its way
     *   (the first run downloads it), so the run waits for it at its first AI step with something
     *   to do ([Outcome.WaitsForModel]) instead of skipping the AI steps
     * @property readerPending The reader is on its way
     *   ([com.satori.domain.reading.ReaderStatus.ON_ITS_WAY]: the first run downloads it), so a
     *   recording with lines only it reads waits for it at its Reading step
     *   ([Outcome.WaitsForModel] with [Outcome.WaitsForModel.reader]) while its files aren't on the
     *   phone, instead of being finished without its AI steps (D7)
     * @property redo A finished recording made again over its readings (0.8.0, design R9: the
     *   backfill of readings, from its Reading step): its AI steps replace what the AI made before,
     *   but what the owner changed ([Redo])
     */
    data class Request(
        val sessionId: String,
        val aiAvailable: Boolean,
        val mayChangeModel: Boolean = true,
        val resumeFrom: FinishStep? = null,
        val recovered: Boolean = false,
        val recognizerFailed: Boolean = false,
        val modelPending: Boolean = false,
        val readerPending: Boolean = false,
        val redo: Boolean = false
    )

    /**
     * How the caller follows and steers the run.
     *
     * @property beforeStep Called as each step that is due begins, with the step: the better
     *   transcript always (it decides itself whether its model runs), the others when they have
     *   something to do. It may suspend, and false defers the run at that step
     *   ([Outcome.Deferred]).
     * @property onModel Called once, as the first AI step begins, with the model the AI steps run
     *   on ([FinishingModel.Live] when [Request.mayChangeModel] is false), for the log; not called
     *   for a run deferred for want of memory
     * @property onBatchProgress Called with the progress of the map-reduce pass
     * @property onTranscriptStart Called as the better transcript's model starts, with the run the
     *   owner can skip
     * @property onTranscriptProgress How much of the audio the better transcript has read, 0..1;
     *   called from a background thread
     * @property onBetterTranscript Called once with what the better transcript step did, for the
     *   log; not when [Request.resumeFrom] is past it
     * @property captureLive Whether a capture of another recording is live now; asked as Gemma 4
     *   E2B would load, and between its tries
     * @property awaitSpeechModelFreed Called before Gemma 4 E2B loads with no capture live: returns
     *   once the live speech model is out of memory, as the recorder that closed frees it, once a
     *   capture is live (the run is then deferred, [Outcome.Deferred.captureOpening]), or once the
     *   caller stops waiting
     * @property onModelWait Called as the model step waits for memory with no capture live, and as
     *   that wait ends, for the log ([ModelWait])
     * @property onTodos Called with what the search for to-dos came to, for the log; it holds
     *   counts only
     * @property onStepFailed Called when the work of a step threw what fails it alone (an
     *   exception, or since 0.7.4 an error of code that could not be loaded or set up, or of
     *   memory), for the log; the run goes on with the next step
     * @property onTooLittleText Called once the Notes step finds too little text for its notes,
     *   before any model is chosen and before the next step begins ([TooLittleText]); also for a
     *   run that goes on past the Notes step, when what it would have found still holds: the
     *   recording has no notes and too little text for them
     * @property onNoModelNeeded Called as a run with the AI ends in which no AI step had anything
     *   to do, so no model was chosen or loaded; for the log
     * @property awaitCaptureSettled Called before Gemma 4 E2B loads beside a live capture, right
     *   before its room is measured: returns true once that capture's speech model is in memory,
     *   false when it still isn't after the caller's wait. It isn't while the capture opens, nor
     *   while its live model is changed for another after a switch of its language (the one is
     *   freed before the other loads, for a second or three), and memory measured then would count
     *   the room that model is about to take. False defers the run without a load
     *   ([Outcome.Deferred.captureOpening])
     * @property onLanguages Called once the AI steps read the transcript, with the languages of the
     *   run ([Languages]), for the log
     * @property captureGrowthBytes Called as Gemma 4 E2B's room beside a live capture is measured,
     *   once its speech model is in ([awaitCaptureSettled]): the memory that model may still take
     *   on top of what it holds, which E2B leaves free as well. A recording live in Malayalam that
     *   is switched back to English or German loads Parakeet v3 in place of the smaller model, a
     *   load of its recorder's that never waits for memory (the recorder wins), so E2B is let in
     *   beside it only with that room too; 0 when its model can't grow
     * @property onReadingStart Called right before the reader loads in the Reading step, with the
     *   run, which a capture that starts stops at once ([ReadingRun.stop], D14); not called when the
     *   step loads nothing (no line to read, or fragments only)
     * @property onReading Called once the Reading step has read, with what it came to, counts only,
     *   for the log; also for a reading that was stopped or failed
     * @property onRedo Called once in a run made again over the readings ([Request.redo]), as its AI
     *   steps come, with what of the owner's stays ([Redo]), for the log
     */
    class Progress(
        val beforeStep: suspend (FinishStep) -> Boolean = { true },
        val onModel: (FinishingModel) -> Unit = {},
        val onBatchProgress: (BatchInsightProgress) -> Unit = {},
        val onTranscriptStart: (BetterTranscriptRun) -> Unit = {},
        val onTranscriptProgress: (Float) -> Unit = {},
        val onBetterTranscript: (BetterTranscript) -> Unit = {},
        val captureLive: () -> Boolean = { false },
        val awaitSpeechModelFreed: suspend () -> Unit = {},
        val onModelWait: (ModelWait) -> Unit = {},
        val onTodos: (ExtractSessionTodosUseCase.Outcome) -> Unit = {},
        val onStepFailed: (FinishStep, Throwable) -> Unit = { _, _ -> },
        val onTooLittleText: (TooLittleText) -> Unit = {},
        val onNoModelNeeded: () -> Unit = {},
        val awaitCaptureSettled: suspend () -> Boolean = { true },
        val onLanguages: (Languages) -> Unit = {},
        val captureGrowthBytes: () -> Long = { 0L },
        val onReadingStart: (ReadingRun) -> Unit = {},
        val onReading: (ReadingStep.Counts) -> Unit = {},
        val onRedo: (Redo) -> Unit = {}
    )

    /**
     * What a run made again over a recording's readings ([Request.redo]) leaves as the owner had it,
     * read from the database as the run's AI steps come, counts only:
     * - [notesKept]: a note of the recording was saved in the note editor
     *   ([com.satori.domain.model.LlmInsight.editedAt] set), so its notes all stay and none is
     *   written;
     * - [nameKept]: its name is one the owner typed ([TranscriptionSession.nameByOwner]), which stays;
     * - [todosKept]: its to-dos and ideas the owner changed (their last change more than 5 s after
     *   they were found, [com.satori.domain.usecase.todo.UntouchedFinds]), which stay;
     * - [todosReplaced]: the app's finds nobody touched, which give way to the new ones.
     */
    data class Redo(val notesKept: Boolean, val nameKept: Boolean, val todosKept: Int, val todosReplaced: Int)

    /**
     * The languages of a run, as the AI steps see them, for the log: what the transcript is in
     * ([spoken]: "ml", "en" or "de" as [LanguageDetector] tells it, null when it isn't sure), the
     * language the recording is made in ([recording]), and the one its notes are written in
     * ([notes]: "en" or "de", a Translate recording's target). A Malayalam recording's are "ml",
     * [RecordingLanguage.MALAYALAM] and "en" (the owner reads its notes in English).
     */
    data class Languages(val spoken: String?, val recording: RecordingLanguage, val notes: String?)

    /**
     * The Notes step had too little text for the notes it writes: [words] words where their bar
     * asks for [neededWords] ([GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT] for a final
     * note, [GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH] for the whole pass). It was found
     * before any model was chosen, so none loads for the notes. Counts only, for the log and the
     * screens.
     *
     * @property keepsNotes The recording has notes from before (the live ones), which stay; only
     *   the lines after them were too few for a final note
     * @property translation A Translate recording, whose notes are its translation
     */
    data class TooLittleText(val words: Int, val neededWords: Int, val keepsNotes: Boolean, val translation: Boolean) {
        /**
         * The recording is left without notes for want of text: its page and home say that there
         * isn't enough text to write notes from. Not for a Translate recording, whose page speaks
         * of its translation.
         */
        val leavesNoNotes: Boolean
            get() = !keepsNotes && !translation
    }

    /**
     * What the model step does when Gemma 4 E2B doesn't fit with no capture live, for the log: it
     * tries again once every [IDLE_RETRY_INTERVAL_MS] for [IDLE_RETRY_MS], and then the live
     * model does the work. A capture that goes live meanwhile defers the run, as one live at the
     * start does when E2B doesn't fit beside it.
     */
    sealed interface ModelWait {
        /** E2B didn't fit ([noRoom], the memory there was and the memory it needed); the step tries again. */
        data class Short(val noRoom: NotEnoughMemoryException) : ModelWait

        /**
         * Try number [tries] found room: Gemma 4 E2B is in memory. A try that gets past the memory
         * check and still doesn't load E2B (a damaged file, E2B switched off meanwhile) says
         * nothing here; [Progress.onModel] says what the steps run on.
         */
        data class Room(val tries: Int) : ModelWait

        /** No try found room ([noRoom] at the last); the live model does the work. */
        data class StillShort(val noRoom: NotEnoughMemoryException) : ModelWait

        /** A capture went live during the tries; the run is deferred until it ends. */
        data object CaptureStarted : ModelWait
    }

    /** How a run ended. */
    sealed interface Outcome {
        /** Every step that could run did. */
        data object Finished : Outcome

        /** The AI could not write any notes and the recording has none. The transcript is saved. */
        data object NotesFailed : Outcome

        /**
         * Nothing was said in the recording, so it was deleted: a recovered one before any step ran,
         * one whose live recognizer left audio untranscribed once the Transcript step found no
         * speech in all of it.
         */
        data object Discarded : Outcome

        /**
         * The model the AI steps run on couldn't be put in memory as [step], the first of them,
         * began: the phone stayed short of memory as the live model loaded
         * ([LoadStoppedForMemoryException]), after Gemma 4 E2B, if it was tried, gave way to it. No
         * AI step ran; the steps before it stay done, and the run goes on from [step] once the
         * phone has memory again.
         */
        data class LoadStopped(val step: FinishStep) : Outcome

        /**
         * The run stopped as [step] was to begin; it goes on from there once no capture is live.
         * [Progress.beforeStep] deferred it; or a capture runs and Gemma 4 E2B did not fit beside
         * it ([noRoom], with the memory there was and the memory it needed); or a capture went
         * live while the model step waited for the speech model to be freed ([captureOpening]),
         * before any memory was measured: it is still opening then, and nothing loads beside a
         * capture that opens.
         */
        data class Deferred(
            val step: FinishStep,
            val noRoom: NotEnoughMemoryException? = null,
            val captureOpening: Boolean = false
        ) : Outcome

        /**
         * The AI model is on its way ([Request.modelPending]): the run stopped as [step], its first
         * AI step with something to do, was to begin, before [Progress.beforeStep] and before any
         * model. The steps before it stay done; the run goes on from [step] once the model is in.
         *
         * With [reader], it is the reader that is on its way ([Request.readerPending]) and not on
         * the phone: the run stopped at the Reading step, before its waits, and goes on from there
         * once the reader is in or no longer coming, whether the AI model is in or not.
         */
        data class WaitsForModel(val step: FinishStep, val reader: Boolean = false) : Outcome

        /**
         * Lines of the recording only the reader reads have no reading, and none comes now, so no AI
         * step ran (D7): never the AI model on Malayalam. The steps before stay done, the transcript
         * is saved, and the AI steps come once the reader can read the lines (design R9), for [why].
         */
        data class Unread(val why: Why) : Outcome {
            enum class Why {
                /** The reader isn't on the phone and isn't on its way. */
                NO_READER,

                /** The reader failed as it loaded or read ([Progress.onStepFailed] says how). */
                NOT_READ,

                /** A run that went on past the Reading step found lines it never got to: the step was skipped after it began twice. */
                SKIPPED
            }
        }

        /**
         * The reader could not be loaded as [step] (the Reading step) began, for [why]: something it
         * never loads beside was still in memory (the speech model of a recorder, an AI model whose
         * load was under way), or a capture's state no recorder showed. Nothing was read for it; the
         * run goes on from [step] after a pause, as after [LoadStopped].
         */
        data class ReaderHeldOff(val step: FinishStep, val why: ReaderNotLoadedException.Why) : Outcome
    }

    /**
     * @return How it ended; throws only [CancellationException], what a read of the recording or
     *   its lines throws, and an error that is no step's alone (an AssertionError)
     */
    suspend operator fun invoke(request: Request, progress: Progress = Progress()): Outcome {
        if (request.recovered) {
            val discarded = discardSilentRecording(
                request.sessionId,
                captured = false,
                mayLackSpeech = request.recognizerFailed
            ).rethrowCancellation()
            if (discarded.getOrNull() == true) return Outcome.Discarded
            if (discarded.isFailure) return Outcome.Finished
        }
        val session = transcriptionRepository.getSession(request.sessionId).first() ?: return Outcome.Finished
        val run = Run(request, progress)

        val better = if (run.isDue(FinishStep.Transcript)) {
            if (!progress.beforeStep(FinishStep.Transcript)) return run.deferred(FinishStep.Transcript)
            // A step that threw leaves the transcript as the run found it.
            run.bestEffort(FinishStep.Transcript, earlierTranscript(session)) {
                betterTranscript.run(
                    sessionId = session.id,
                    mayChangeModel = request.mayChangeModel,
                    onStart = progress.onTranscriptStart,
                    onProgress = progress.onTranscriptProgress,
                    mayLackSpeech = request.recognizerFailed
                ).also(progress.onBetterTranscript)
            }
        } else {
            earlierTranscript(session)
        }
        currentCoroutineContext().ensureActive()
        if (request.recognizerFailed && heardNothing(better) && silentNow(session.id)) return Outcome.Discarded

        if (run.isDue(FinishStep.Lines)) {
            if (!progress.beforeStep(FinishStep.Lines)) return run.deferred(FinishStep.Lines)
            // Lines left as they are are lines the notes can be written from all the same.
            run.bestEffort(FinishStep.Lines, Unit) { transcriptLines.join(session.id) }
            currentCoroutineContext().ensureActive()
        }

        if (!request.aiAvailable && !request.modelPending) return Outcome.Finished
        // Read after the better transcript and the joining, which give the lines new ids.
        val said = transcriptionRepository.getSegmentsBySession(request.sessionId).first()
            .filter { it.isComplete }
            .sortedBy { it.timestamp }
        val transcript = transcriptOf(said)
        val spoken = LanguageDetector.detect(transcript)
        // Best effort, for the field log's reader: the steps take the language from the text they read.
        if (spoken != null) transcriptionRepository.setInputLanguage(session.id, spoken).rethrowCancellation()
        val notesIn = insightLanguage(session, transcript)
        progress.onLanguages(Languages(spoken, session.language, notesIn))

        // The lines only the reader reads go to the AI steps as their readings, never as they are.
        val segments = if (said.any { ReadableSegments.needsReader(it.text) }) {
            readLines(session, request, run)?.let { return it }
            currentCoroutineContext().ensureActive()
            // Their notes are in the language the transcript as it was said gives: English.
            run.readIn(notesIn)
            transcriptionRepository.getReadableSegments(session.id).filter { it.isComplete }.sortedBy { it.timestamp }
        } else {
            said
        }

        // Made again over its readings: what the owner changed stays as he left it (design R9), and
        // what it has now is kept first, once, so a name or notes of his from before version 13,
        // which recorded no edit, can be given back (TranscriptionRepository.keepBeforeRedo).
        val redo = if (request.redo) redoOf(session) else null
        redo?.let(progress.onRedo)
        if (redo != null) transcriptionRepository.keepBeforeRedo(session.id).rethrowCancellation().getOrThrow()

        val notes = if (run.isDue(FinishStep.Notes)) {
            if (redo != null && (redo.notesKept || session.mode == RecordingMode.REAL_TIME_TRANSLATION)) {
                // His notes, or a translation's: the run made again writes none.
                NotesResult.NONE
            } else {
                run.bestEffort(FinishStep.Notes, NotesResult.FAILED) {
                    when {
                        redo != null -> writeNotesAgain(session, segments, run)
                        // An end-of-session recording has no live notes: its notes are the whole pass.
                        session.insightStrategy == InsightStrategy.END_OF_SESSION -> writeWholeNotes(session, segments, run)
                        better is BetterTranscript.Replaced && session.mode != RecordingMode.REAL_TIME_TRANSLATION ->
                            replaceNotes(session, segments, run)
                        else -> writeTailNote(session, segments, run)
                    }
                } ?: return run.deferred(FinishStep.Notes)
            }
        } else {
            // The screens still hear it after a process death past the Notes step.
            NotesResult(wrote = false, failed = false, tooLittle = tooLittleBefore(session, segments, better))
        }
        notes.tooLittle?.let(progress.onTooLittleText)
        currentCoroutineContext().ensureActive()

        // Made again, the app's name is named anew; one the owner typed stays.
        val renames = redo != null && !redo.nameKept && !session.name.isNullOrBlank()
        if (run.isDue(FinishStep.Name) && (session.name.isNullOrBlank() || renames) && (notes.wrote || hasWordsToName(segments))) {
            if (!run.beginAiStep(FinishStep.Name)) return run.deferred(FinishStep.Name)
            run.bestEffort(FinishStep.Name, Unit) {
                // Made again, never from the notes of before: from the new ones, his own, or the readings.
                val from = if (redo != null && !notes.wrote && !redo.notesKept) emptyList<LlmInsight>() else null
                // A model that failed and left no name is reported as a step that threw is.
                autoNameSession(session.id, titleLanguage(session, transcript), notes = from, replacing = session.name.takeIf { renames })
                    .rethrowCancellation()
                    .exceptionOrNull()?.let { progress.onStepFailed(FinishStep.Name, it) }
            }
            currentCoroutineContext().ensureActive()
        }

        // The search reads the lines that hold text (ExtractSessionTodosUseCase), and with none it
        // asks the model nothing, so none is loaded for it.
        if (run.isDue(FinishStep.Todos) && segments.any { it.text.isNotBlank() }) {
            if (!run.beginAiStep(FinishStep.Todos)) return run.deferred(FinishStep.Todos)
            run.bestEffort(FinishStep.Todos, Unit) {
                progress.onTodos(if (redo != null) findTodosAgain(session.id) else extractSessionTodos(session.id))
            }
            currentCoroutineContext().ensureActive()
        }

        // Made again with too little readable text and no notes left, it is mapped again all the
        // same, without the model (MapRecordingUseCase reads it so then): the topics the old notes
        // gave it go. No summary is written without the model; the themes stay stale for the next
        // finishing that maps.
        val withoutModel = redo != null && run.isDue(FinishStep.Map) && !MapRecordingUseCase.hasEnoughText(segments) &&
            !hasStoredNotes(session.id)
        if (run.isDue(FinishStep.Map) && (MapRecordingUseCase.hasEnoughText(segments) || withoutModel)) {
            val begun = if (withoutModel) progress.beforeStep(FinishStep.Map) else run.beginAiStep(FinishStep.Map)
            if (!begun) return run.deferred(FinishStep.Map)
            run.bestEffort(FinishStep.Map, Unit) {
                val mapped = mapRecording(session.id)
                if (!withoutModel && mapped != MapRecordingUseCase.Outcome.ModelFailed) {
                    val joined = (mapped as? MapRecordingUseCase.Outcome.Mapped)?.themeIds.orEmpty()
                    refreshThemeSummaries(joined, RefreshThemeSummariesUseCase.PER_FINISH)
                }
            }
        }

        if (!run.modelChosen) progress.onNoModelNeeded()
        // Made again, notes that failed failed the run's purpose, though the old ones stay: the
        // backfill tries it again (design R9).
        val notesFailed = notes.failed && !notes.wrote && (redo != null || !hasStoredNotes(session.id))
        return if (notesFailed) Outcome.NotesFailed else Outcome.Finished
    }

    /**
     * The Reading step ([FinishStep.Reading]) of a recording with lines only the reader reads, as
     * the run comes to it; null once the lines all have their readings, so the AI steps go on with
     * them, else how the run ends here.
     *
     * - A run that went on past the step finds the readings it stored, unless the step was skipped
     *   after it began twice and left lines it never read: no AI step then (D7).
     * - With nothing left to read the step doesn't begin.
     * - A reader not on the phone holds the run while it is on its way, else no AI step runs (D7);
     *   lines that are all fragments need none, and get their empty readings without it.
     * - The reader never loads beside a capture (O5): one live as the step begins defers the run;
     *   it waits for the speech model of the recorder that closed to be freed first.
     * - The reading's own end: a capture that opened (D14) defers the run, the phone's memory ends
     *   it as a model load's does, something else in memory holds it off, and a reader that failed
     *   leaves no AI step to run (D7).
     */
    private suspend fun readLines(session: TranscriptionSession, request: Request, run: Run): Outcome? {
        val progress = run.progress
        if (!run.isDue(FinishStep.Reading)) {
            return Outcome.Unread(Outcome.Unread.Why.SKIPPED).takeIf { readingStep.hasNeverRead(session.id) }
        }
        when (readingStep.needs(session.id)) {
            ReadingStep.Needs.NOTHING -> return null
            ReadingStep.Needs.NO_MODEL -> Unit
            ReadingStep.Needs.READER -> if (!readingStep.isOnPhone()) return noReader(request)
        }
        if (!progress.beforeStep(FinishStep.Reading)) return run.deferred(FinishStep.Reading)
        if (!progress.captureLive()) progress.awaitSpeechModelFreed()
        if (progress.captureLive()) return Outcome.Deferred(FinishStep.Reading, captureOpening = true)
        val read = readingStep.read(session.id, progress.onReadingStart)
        progress.onReading(read.counts)
        return when (read) {
            is ReadingStep.Outcome.Read -> null
            is ReadingStep.Outcome.Stopped -> Outcome.Deferred(FinishStep.Reading, captureOpening = true)
            is ReadingStep.Outcome.LoadStopped -> Outcome.LoadStopped(FinishStep.Reading)
            is ReadingStep.Outcome.NotLoaded ->
                if (read.why == ReaderNotLoadedException.Why.NOT_ON_PHONE) noReader(request) else Outcome.ReaderHeldOff(FinishStep.Reading, read.why)
            is ReadingStep.Outcome.Failed -> {
                progress.onStepFailed(FinishStep.Reading, read.cause)
                Outcome.Unread(Outcome.Unread.Why.NOT_READ)
            }
        }
    }

    /** The reader isn't on the phone: the run waits for it while it is on its way, else no AI step runs (D7). */
    private fun noReader(request: Request): Outcome =
        if (request.readerPending) Outcome.WaitsForModel(FinishStep.Reading, reader = true) else Outcome.Unread(Outcome.Unread.Why.NO_READER)

    /**
     * What of the owner's a run made again leaves ([Redo]), read now: his notes, his name, his
     * to-dos. A read of the notes that fails keeps them, as his.
     */
    private suspend fun redoOf(session: TranscriptionSession): Redo {
        val notesKept = notesEdited(session.id)
        val finds = try {
            untouchedFinds?.of(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return Redo(
            notesKept = notesKept,
            nameKept = session.nameByOwner && !session.name.isNullOrBlank(),
            todosKept = finds?.touched ?: 0,
            todosReplaced = finds?.untouched?.size ?: 0
        )
    }

    /**
     * Whether a note of [sessionId] was saved in the note editor
     * ([com.satori.domain.model.LlmInsight.editedAt]): the notes are his then. A read that fails
     * says yes, so they stay.
     */
    private suspend fun notesEdited(sessionId: String): Boolean = try {
        transcriptionRepository.getInsightsBySession(sessionId).first().any { it.editedAt != null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    /**
     * The notes of a recording made again over its readings ([Request.redo]), as if it had none: the
     * whole pass for an end-of-session recording and for text longer than a final note reads, else a
     * final note on all of it, which cites every line it read. They replace the old notes in one
     * write once written. Text with too little in it for them gets none, and the old notes go, as
     * Transcribe again's do: they were written from the lines as they were said. A pass or a note
     * that fails, or that leaves nothing, leaves the old ones, a failure of the run made again; and
     * a note the owner saved in the editor while the notes were written keeps them all, looked for
     * in the very write that replaces them ([TranscriptionRepository.replaceInsightsMadeAgain]), so
     * none he saved is ever taken away.
     *
     * @return null when the run is deferred
     */
    private suspend fun writeNotesAgain(session: TranscriptionSession, segments: List<TranscriptionSegment>, run: Run): NotesResult? {
        val text = transcriptOf(segments)
        val whole = session.insightStrategy == InsightStrategy.END_OF_SESSION || text.length > GenerateFinalInsightUseCase.maxUserPromptChars(text)
        val needed = if (whole) GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH else GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT
        if (wordsIn(text) < needed) {
            // Once begun, the old notes' removal runs to its end; his stay, and one that fails leaves them.
            val removed = withContext(NonCancellable) {
                transcriptionRepository.replaceInsightsMadeAgain(session.id, emptyList(), session.insightStrategy)
            }.getOrElse { return NotesResult.FAILED }
            return tooLittle(text, needed, keepsNotes = !removed, session)
        }
        if (!run.beginAiStep(FinishStep.Notes)) return null
        val notes = if (whole) {
            val parts = mutableListOf<LlmInsight>()
            val summary = wholePass(session, segments, run) { parts += it }.getOrElse { return NotesResult.FAILED }
            parts + listOfNotNull(summary)
        } else {
            val note = generateFinalInsight(
                pendingText = text,
                sessionId = session.id,
                mode = session.mode,
                outputLanguage = run.notesLanguageOf(session, text),
                topic = session.topic,
                sourceSegmentIds = segments.map { it.id },
                earlierNotes = emptyList()
            ).rethrowCancellation().getOrElse { return NotesResult.FAILED }
            listOfNotNull(note)
        }
        // Nothing written leaves the old notes, as a failure does.
        if (notes.isEmpty()) return NotesResult.FAILED
        val strategy = if (whole) InsightStrategy.END_OF_SESSION else session.insightStrategy
        return when (transcriptionRepository.replaceInsightsMadeAgain(session.id, notes, strategy).rethrowCancellation().getOrNull()) {
            true -> NotesResult(wrote = true, failed = false)
            // His, saved meanwhile: they all stay, and none is written.
            false -> NotesResult.NONE
            null -> NotesResult.FAILED
        }
    }

    /**
     * The to-dos and ideas of a recording made again over its readings ([Request.redo]): the app's
     * finds of before that nobody has touched give way to what the readings give, in the write that
     * stores the new ones, as after Transcribe again ([UntouchedFinds]); those the owner changed
     * stay, and the search finds no repeat of them.
     */
    private suspend fun findTodosAgain(sessionId: String): ExtractSessionTodosUseCase.Outcome {
        val finds = untouchedFinds ?: return extractSessionTodos(sessionId)
        val old = finds.of(sessionId).untouched
        return extractSessionTodos(sessionId, replacing = old) { ids -> finds.takeAway(sessionId, ids, TodoSource.READ_AGAIN) }
    }

    /**
     * One run: which steps are due, and the model for the AI steps, loaded as the first of them
     * begins.
     */
    private inner class Run(private val request: Request, val progress: Progress) {
        private var modelReady = false
        private var noRoom: NotEnoughMemoryException? = null
        private var captureOpening = false
        private var loadStopped = false
        private var waitsForModel = false

        // Set once the Reading step has read the lines: the language the notes of the readings are in.
        private var read = false
        private var readLanguage: String? = null

        /**
         * The AI steps read readings in place of lines the reader reads: their notes are in
         * [language], the one the transcript as it was said gives, not the readings' own (English,
         * which a German setting would write German notes of).
         */
        fun readIn(language: String?) {
            read = true
            readLanguage = language
        }

        /** The language the notes of [text] are written in: the readings' language once [readIn], else [text]'s own. */
        suspend fun notesLanguageOf(session: TranscriptionSession, text: String): String? =
            if (read) readLanguage else insightLanguage(session, text)

        /** Whether an AI step began in this run, and with it the choice of the model. */
        val modelChosen: Boolean
            get() = modelReady

        /** Whether [step] runs in this run: it isn't before [Request.resumeFrom]. */
        fun isDue(step: FinishStep): Boolean {
            val from = request.resumeFrom ?: return true
            return step >= from
        }

        /**
         * An AI step begins: [Progress.beforeStep], then the model, the first time. False defers
         * the run, also when a capture runs and Gemma 4 E2B doesn't fit beside it, and ends it when
         * the phone's memory stopped the live model's load ([liveLoadStopped]); the next run tries
         * the model again. Without the AI the run gets here only while the model is on its way
         * ([Request.modelPending]), and false then stops it for the model ([Outcome.WaitsForModel]),
         * before the step's waits: there is nothing to wait for until the model is in.
         */
        suspend fun beginAiStep(step: FinishStep): Boolean {
            if (!request.aiAvailable) {
                waitsForModel = true
                return false
            }
            if (!progress.beforeStep(step)) return false
            if (modelReady) return true
            val model = if (request.mayChangeModel) chooseModel() ?: return false else FinishingModel.Live
            if (model != FinishingModel.Switched && liveLoadStopped()) return false
            modelReady = true
            progress.onModel(model)
            return true
        }

        /**
         * The live model, which each AI step would load at its first call, put in memory now:
         * whether the phone's memory stopped its load ([LoadStoppedForMemoryException]), which ends
         * the run ([Outcome.LoadStopped]). Any other failure is the steps' to meet, as it was.
         */
        private suspend fun liveLoadStopped(): Boolean {
            val failure = loadFinishingModel.loadLive().exceptionOrNull() ?: return false
            if (failure is CancellationException) throw failure
            loadStopped = failure is LoadStoppedForMemoryException
            return loadStopped
        }

        /**
         * Gemma 4 E2B when it can be loaded, else the live model, or null when the run is deferred
         * for want of room beside a capture ([noRoom]).
         *
         * E2B loads only with [LoadFinishingModelUseCase.E2B_MARGIN_BYTES] to spare, beside a live
         * capture or not. With none live, too little memory alone never gives the live model at
         * once: the step waits for the speech model of the recorder that closed to be freed, then
         * tries again once every [IDLE_RETRY_INTERVAL_MS] for [IDLE_RETRY_MS], and only then does
         * the live model do the work: E2B never loads with less to spare. A capture that goes live
         * during the wait for the speech model or during the tries defers the run without a load:
         * [Progress.beforeStep] waited for a capture to open before the step, not for one that
         * starts after, and nothing loads while a capture opens; during the tries E2B didn't fit by
         * the bar a capture asks for too, and beside a capture the live model never does the work.
         * Any other failure to load E2B gives the live model at once.
         */
        private suspend fun chooseModel(): FinishingModel? {
            if (progress.captureLive()) return besideCapture()
            progress.awaitSpeechModelFreed()
            if (progress.captureLive()) return deferredForOpening()
            var model = loadFinishingModel()
            var short = model.lackOfMemory() ?: return model
            progress.onModelWait(ModelWait.Short(short))
            for (tries in 1..(IDLE_RETRY_MS / IDLE_RETRY_INTERVAL_MS).toInt()) {
                delay(IDLE_RETRY_INTERVAL_MS)
                if (progress.captureLive()) return deferredForCapture(short)
                model = loadFinishingModel()
                short = model.lackOfMemory() ?: run {
                    // Room only once E2B is in; what else came of the try is onModel's to say.
                    if (model == FinishingModel.Switched) progress.onModelWait(ModelWait.Room(tries))
                    return model
                }
            }
            if (progress.captureLive()) return deferredForCapture(short)
            progress.onModelWait(ModelWait.StillShort(short))
            // The last try's fallback: the steps load the live model again.
            return model
        }

        /**
         * E2B with [LoadFinishingModelUseCase.E2B_MARGIN_BYTES] to spare beside a live capture, or
         * null, deferred, without the room. The room is measured only once the capture's speech
         * model is in ([Progress.awaitCaptureSettled]): the same for a capture that opens and for
         * one whose live model is being changed after a switch of its language, which has neither
         * model in memory meanwhile. Beside the Malayalam model it is measured with the room for
         * Parakeet v3 kept as well ([Progress.captureGrowthBytes]), which a switch back loads.
         */
        private suspend fun besideCapture(): FinishingModel? {
            if (!progress.awaitCaptureSettled()) return deferredForOpening()
            val model = loadFinishingModel(progress.captureGrowthBytes())
            noRoom = model.lackOfMemory() ?: return model
            return null
        }

        /**
         * Defers the run for a capture that went live while E2B didn't fit: [short], the last
         * try, was measured by the bar beside a capture as well, and says what was missing.
         */
        private fun deferredForCapture(short: NotEnoughMemoryException): FinishingModel? {
            progress.onModelWait(ModelWait.CaptureStarted)
            noRoom = short
            return null
        }

        /**
         * Defers the run for a capture whose speech model is not in yet: one that went live while
         * the step waited for the speech model to be freed, and one beside which E2B was to load
         * that hasn't its model in after the caller's wait ([Progress.awaitCaptureSettled]). Neither
         * a load nor a measure of the room beside it may come now ([Outcome.Deferred.captureOpening]).
         */
        private fun deferredForOpening(): FinishingModel? {
            captureOpening = true
            return null
        }

        /** The shortfall when E2B couldn't be loaded for want of memory alone, else null. */
        private fun FinishingModel.lackOfMemory(): NotEnoughMemoryException? =
            (this as? FinishingModel.FellBack)?.cause as? NotEnoughMemoryException

        fun deferred(step: FinishStep): Outcome = when {
            waitsForModel -> Outcome.WaitsForModel(step)
            loadStopped -> Outcome.LoadStopped(step)
            else -> Outcome.Deferred(step, noRoom, captureOpening)
        }

        /**
         * The work of [step], which is best effort: what fails the step alone ([failsTheStepAlone])
         * is reported ([Progress.onStepFailed]), and [fallback] stands for what the step would have
         * given, so the run goes on with the next step. A cancel is passed on, and so is an error
         * that is no step's alone.
         */
        suspend inline fun <T> bestEffort(step: FinishStep, fallback: T, work: () -> T): T = try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!failsTheStepAlone(e)) throw e
            progress.onStepFailed(step, e)
            fallback
        }
    }

    /**
     * Whether [e], thrown by the work of a step, fails that step alone: an exception, or an error
     * of code that could not be loaded or set up ([LinkageError]: ExceptionInInitializerError,
     * NoClassDefFoundError, UnsatisfiedLinkError) or of the limits of memory ([VirtualMachineError]:
     * OutOfMemoryError, StackOverflowError), which the next step's own code may well be clear of.
     * On 2026-10-19 0.7.3's PromptRules could not start on the phone, the to-dos step threw
     * ExceptionInInitializerError, and the error ended the finisher's worker with the recording in
     * "Finishing up" (0.7.4). Never a cancel, which ends the run as one; another error (an
     * AssertionError, a test's or an assert's) is a bug no fallback stands for, and ends the run.
     */
    private fun failsTheStepAlone(e: Throwable): Boolean =
        e !is CancellationException && (e is Exception || e is LinkageError || e is VirtualMachineError)

    /**
     * What the notes step did: [wrote] a note of its own, or [failed] to; [tooLittle] when it found
     * too little text for its notes, before any model was chosen.
     */
    private class NotesResult(val wrote: Boolean, val failed: Boolean, val tooLittle: TooLittleText? = null) {
        companion object {
            /** Nothing to write, or nothing written: no failure. */
            val NONE = NotesResult(wrote = false, failed = false)

            /** Nothing written, and that was a failure. */
            val FAILED = NotesResult(wrote = false, failed = true)
        }
    }

    /**
     * Nothing written, as [text] holds fewer words than the [neededWords] its notes ask for; null
     * when it holds enough. [keepsNotes]: the recording has notes from before, which stay.
     */
    private fun tooLittle(text: String, neededWords: Int, keepsNotes: Boolean, session: TranscriptionSession): NotesResult? {
        val words = wordsIn(text)
        if (words >= neededWords) return null
        val tooLittle = TooLittleText(words, neededWords, keepsNotes, translation = session.mode == RecordingMode.REAL_TIME_TRANSLATION)
        return NotesResult(wrote = false, failed = false, tooLittle = tooLittle)
    }

    /**
     * What the Notes step of a run that went on past it (a process death cut the one before short)
     * found, as far as the screens need it: too little text, when the recording has no notes and
     * its transcript holds fewer words than the notes it would have had ask for. Null otherwise.
     */
    private suspend fun tooLittleBefore(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        better: BetterTranscript
    ): TooLittleText? {
        if (hasStoredNotes(session.id)) return null
        val wholePass = session.insightStrategy == InsightStrategy.END_OF_SESSION ||
            (better is BetterTranscript.Replaced && session.mode != RecordingMode.REAL_TIME_TRANSLATION)
        val needed = if (wholePass) GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH else GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT
        return tooLittle(transcriptOf(segments), needed, keepsNotes = false, session)?.tooLittle
    }

    /**
     * Whether the recording has notes, from a run before this one or the live ones. A read that
     * fails says yes, so the run isn't reported as one that left the recording without notes.
     */
    private suspend fun hasStoredNotes(sessionId: String): Boolean = try {
        transcriptionRepository.getInsightsBySession(sessionId).first().isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    /**
     * Whether the Transcript step read all of the kept audio and found no speech in it: the live
     * recognizer of a recording handed off with [Request.recognizerFailed] left audio untranscribed,
     * which kept it at the hand-off however silent it was, and the whole of it has been heard now.
     */
    private fun heardNothing(better: BetterTranscript): Boolean =
        better is BetterTranscript.Skipped && better.reason == BetterTranscript.Reason.NoSpeech

    /**
     * The silent rule once more, now that all of the audio has been heard ([heardNothing]): the
     * recording is deleted when no line holds a word, as Stop deletes one in which nothing was said
     * ([DiscardSilentRecordingUseCase]). A quick-key press stopped before the speech model was in
     * kept its audio for this step. A delete that fails keeps it.
     */
    private suspend fun silentNow(sessionId: String): Boolean =
        discardSilentRecording(sessionId, captured = true, heard = false, mayLackSpeech = false).rethrowCancellation().getOrNull() == true

    /**
     * What the better transcript did in the run a process death cut short, which went on past it:
     * the transcript is a better model's when it isn't one a live model made, Parakeet v3's or, for
     * a Malayalam recording, the Malayalam model's ([RecordingLanguage.LIVE_MODELS]).
     */
    private fun earlierTranscript(session: TranscriptionSession): BetterTranscript {
        val model = SpeechModelId.ofTranscript(session.transcriptModel)
        return if (model != null && model !in RecordingLanguage.LIVE_MODELS) {
            BetterTranscript.Replaced(model)
        } else {
            BetterTranscript.Skipped(null, BetterTranscript.Reason.Off)
        }
    }

    /**
     * The notes of an end-of-session recording: the whole pass, whose notes replace all the
     * recording has once they exist, so a pass run again doesn't add a second set. Parts written
     * before a later step of the pass failed are kept the same way. A transcript with fewer words
     * than the pass reads ([GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH]) gets none, and no
     * model is chosen for it.
     *
     * @return null when the run is deferred
     */
    private suspend fun writeWholeNotes(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        run: Run
    ): NotesResult? {
        tooLittleForPass(session, segments)?.let { return it }
        if (!run.beginAiStep(FinishStep.Notes)) return null
        val parts = mutableListOf<LlmInsight>()
        val summary = wholePass(session, segments, run) { parts += it }
        val notes = parts + listOfNotNull(summary.getOrNull())
        if (notes.isEmpty()) return NotesResult(wrote = false, failed = summary.isFailure)
        val stored = transcriptionRepository.replaceInsights(session.id, notes, InsightStrategy.END_OF_SESSION)
            .rethrowCancellation().isSuccess
        return NotesResult(wrote = stored, failed = !stored || summary.isFailure)
    }

    /**
     * The notes of a real-time recording whose live notes don't cover what was said: its
     * transcript was replaced by a better one, or its tail is longer than a final note reads. The
     * whole pass writes them again from the final transcript and they replace all of them in one
     * transaction, once every one exists. A pass that fails, or has too little to write about,
     * leaves the live notes; one with fewer words than it reads
     * ([GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH]) is not begun, and no model is chosen
     * for it.
     *
     * @return null when the run is deferred
     */
    private suspend fun replaceNotes(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        run: Run
    ): NotesResult? {
        tooLittleForPass(session, segments)?.let { return it }
        if (!run.beginAiStep(FinishStep.Notes)) return null
        val parts = mutableListOf<LlmInsight>()
        val summary = wholePass(session, segments, run) { parts += it }
            .getOrElse { return NotesResult(wrote = false, failed = true) }
        val notes = parts + listOfNotNull(summary)
        if (notes.isEmpty()) return NotesResult.NONE
        val stored = transcriptionRepository.replaceInsights(session.id, notes, InsightStrategy.END_OF_SESSION)
            .rethrowCancellation().isSuccess
        return NotesResult(wrote = stored, failed = !stored)
    }

    /**
     * Nothing for the whole pass to write, before a model is chosen for it: [segments] hold fewer
     * words than it reads ([GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH]). Null when they hold
     * enough.
     */
    private suspend fun tooLittleForPass(session: TranscriptionSession, segments: List<TranscriptionSegment>): NotesResult? {
        val text = transcriptOf(segments)
        // Only a pass that won't run asks whether the recording has notes to keep.
        if (wordsIn(text) >= GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH) return null
        return tooLittle(text, GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH, keepsNotes = hasStoredNotes(session.id), session)
    }

    /**
     * The map-reduce pass over the whole transcript; its parts go to [onPart] as they are
     * written. Nothing is stored once cancelled, also when the cancel came back as the pass's
     * result.
     */
    private suspend fun wholePass(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        run: Run,
        onPart: (LlmInsight) -> Unit
    ): Result<LlmInsight?> {
        val transcript = transcriptOf(segments)
        val summary = generateBatchInsight(
            segments = segments,
            sessionId = session.id,
            mode = session.mode,
            outputLanguage = run.notesLanguageOf(session, transcript),
            topic = session.topic,
            onProgress = run.progress.onBatchProgress,
            onChunkInsight = { chunk -> onPart(chunk) }
        ).rethrowCancellation()
        currentCoroutineContext().ensureActive()
        return summary
    }

    /**
     * The note on the tail: the lines no note cites ([RecordingTail]), which it then cites. A tail
     * longer than the note reads gets the whole pass instead, except for a Translate recording,
     * whose tail gets a note on its last part. A tail with fewer words than the note asks for
     * ([GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT]) gets none, and no model is
     * chosen for it; without notes from before, the tail is the whole transcript.
     *
     * @return null when the run is deferred
     */
    private suspend fun writeTailNote(
        session: TranscriptionSession,
        segments: List<TranscriptionSegment>,
        run: Run
    ): NotesResult? {
        val insights = transcriptionRepository.getInsightsBySession(session.id).first()
        val tail = RecordingTail.of(segments, insights)
        val text = transcriptOf(tail)
        // The notes there are cite every line: nothing is left to write about.
        if (text.isEmpty() && insights.isNotEmpty()) return NotesResult.NONE
        // Fewer characters of a Malayalam tail fit, as its characters cost more tokens.
        if (text.length > GenerateFinalInsightUseCase.maxUserPromptChars(text) && session.mode != RecordingMode.REAL_TIME_TRANSLATION) {
            return replaceNotes(session, segments, run)
        }
        tooLittle(text, GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT, keepsNotes = insights.isNotEmpty(), session)
            ?.let { return it }
        if (!run.beginAiStep(FinishStep.Notes)) return null
        val result = generateFinalInsight(
            pendingText = text,
            sessionId = session.id,
            mode = session.mode,
            outputLanguage = run.notesLanguageOf(session, text),
            topic = session.topic,
            sourceSegmentIds = tail.map { it.id },
            // The live notes, which stay: the last note is none when it mostly repeats one of them.
            earlierNotes = insights
        )
        return storeNote(result)
    }

    /** Keeps the note a use case wrote. A null note is not a failure: there was too little to write about. */
    private suspend fun storeNote(result: Result<LlmInsight?>): NotesResult {
        val insight = result.rethrowCancellation().getOrElse { return NotesResult(wrote = false, failed = true) }
            ?: return NotesResult.NONE
        val stored = transcriptionRepository.saveInsight(insight).rethrowCancellation().isSuccess
        return NotesResult(wrote = stored, failed = !stored)
    }

    /**
     * The language the notes are written in: a Translate recording's target (null for the
     * Settings default), otherwise the language of [text].
     */
    private suspend fun insightLanguage(session: TranscriptionSession, text: String): String? =
        if (session.mode == RecordingMode.REAL_TIME_TRANSLATION) {
            session.outputLanguage
        } else {
            notesLanguage.resolve(session.id, text)
        }

    /** The name is written in the language the user reads the recording in ([readingLanguage]). */
    private suspend fun titleLanguage(session: TranscriptionSession, text: String): String =
        notesLanguage.readingLanguage(session, text)

    private fun hasWordsToName(segments: List<TranscriptionSegment>): Boolean =
        segments.sumOf { segment -> segment.text.split(WHITESPACE).count { it.isNotEmpty() } } >=
            GenerateSessionTitleUseCase.MIN_WORDS

    private fun transcriptOf(segments: List<TranscriptionSegment>): String =
        segments.joinToString(" ") { it.text.trim() }.trim()

    /** The words of [text], counted as the notes' use cases count them for their bars. */
    private fun wordsIn(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }

    /** A cancellation that a use case returned as a failed Result is thrown again, so it ends the run. */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> {
        val failure = exceptionOrNull()
        if (failure is CancellationException) throw failure
        return this
    }

    companion object {
        /**
         * With no capture live, how long the model step tries Gemma 4 E2B again when too little
         * memory is free for it ([LoadFinishingModelUseCase.E2B_MARGIN_BYTES]), once every
         * [IDLE_RETRY_INTERVAL_MS]; then the live model does the work. The tries only read the
         * memory, which a closing recorder or another app may free meanwhile: on 2026-10-13, 3 s
         * after a capture's Stop, 3096 MB were free, less than even E2B's 3194 MB file.
         */
        const val IDLE_RETRY_MS = 15_000L

        /** How often the model step tries Gemma 4 E2B again within [IDLE_RETRY_MS]. */
        const val IDLE_RETRY_INTERVAL_MS = 1_000L

        private val WHITESPACE = Regex("\\s+")
    }
}
