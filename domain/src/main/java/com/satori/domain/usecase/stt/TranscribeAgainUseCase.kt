package com.satori.domain.usecase.stt

import com.satori.domain.language.MalayalamText
import com.satori.domain.language.SpokenLanguage
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.VadParameters
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.transcription.JoinTranscriptLinesUseCase
import com.satori.domain.usecase.transcription.LinesLog
import com.satori.domain.usecase.transcription.TranscriptJoiner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/** How a Transcribe again that did not fail ended. */
enum class TranscribeAgainOutcome {
    /** The recording has the new transcript in place of the old one. */
    Replaced,

    /**
     * No speech was found in the audio: no line, or none with a word in it, only noise written as
     * a lone "." ([DiscardSilentRecordingUseCase.holdsSpeech]). The transcript is as it was.
     */
    NoSpeech,

    /** The recording has no audio (any more). Nothing was done. */
    NoAudio
}

/**
 * Transcribes a recording again from the audio it kept, with a speech model on the phone, and
 * swaps the new transcript in for the old one. Its notes, name and to-dos stay as they are here;
 * where the new transcript is in another language ([SpeechModelChoice.makesTextAgain]), the caller
 * has them made again.
 *
 * It is how another speech model, or other voice detection settings, can be tried on the
 * owner's own recordings: the audio stays the same and only the recognizer changes. The model
 * that made the transcript is stored with it ([TranscriptionRepository.setTranscriptModel]), so
 * the transcript says which one it is and comparisons stay traceable.
 *
 * The new lines are held in memory and replace the old ones in one transaction
 * ([TranscriptionRepository.replaceSegments]) only once all of them exist, so a failure, a
 * cancel, or audio in which no speech is found leaves the transcript exactly as it was. Once they
 * all exist, they are stored whole even if a cancel comes then: their places in the audio and the
 * model that made them go in with them. Each new line is stamped with the wall-clock time its
 * speech ended, as a live line is stamped when the recognizer gives it out at its end: the to-do
 * extraction reads the gaps between stamps that way.
 *
 * In the same transaction the notes, the to-dos and the mind-map links are pointed at the new
 * lines said when their old ones were ([SourceLines]), and where each new line was said is
 * recorded with the audio, so tapping a line plays it and the map's "open at line" lands. Before
 * that, the new lines the recognizer cut mid-sentence are joined ([TranscriptJoiner]), as the
 * finishing does for a live transcript ([JoinTranscriptLinesUseCase]): their places are known, so
 * a joined line plays from its first word. The lines join only where the same notes cite them, so
 * the notes stay exact, and only within one capture of the recording, so a recording started
 * again after an error doesn't run on across the time it stood still. Their Malayalam is written as
 * its script has it ([com.satori.domain.language.MalayalamText]), before and after the join, and a
 * line with no letter or digit left in it is left out before the join, so none is stored empty.
 *
 * The recording is then in the language of the model that made its transcript
 * ([RecordingLanguage.transcribedBy]): Malayalam after AI4Bharat's model, which may transcribe any
 * recording but a Translate one ([SpeechModelChoice.mayTranscribe]), and English or German after
 * the others. Where [SpeechModelChoice.makesTextAgain] says so, the caller has the recording's
 * name, notes, to-dos and ideas made from the new one ([MakeTextAgainUseCase]), each in place of
 * what the old transcript gave it, once it is ready.
 *
 * @property newId Makes the id of each new line
 * @property linesLog Where the line counts of the join go
 */
class TranscribeAgainUseCase(
    private val sttRepository: SttRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val todoRepository: TodoRepository,
    private val audioRepository: RecordingAudioRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val linesLog: LinesLog = LinesLog.NONE
) {
    /**
     * @param model The speech model to transcribe with. The language it is told, if it is told
     *   one, is the one spoken in the transcript there is now ([SpokenLanguage]), as
     *   [SpeechModelChoice.languageFor] reads it for [model]; a model that doesn't know it
     *   ([SpeechModelId.understands]), and the Malayalam model for a Translate recording, are
     *   refused before anything runs.
     * @param onProgress How much of the audio has been read, 0..1; called from a background thread
     * @return How it ended, or a failure if the model doesn't know the recording's language, the
     *   audio couldn't be read, the speech model failed or the new transcript couldn't be stored;
     *   the old one is unchanged then
     */
    suspend operator fun invoke(
        sessionId: String,
        model: SpeechModelId = SpeechModelId.LIVE,
        onProgress: (Float) -> Unit = {}
    ): Result<TranscribeAgainOutcome> =
        try {
            transcribe(sessionId, model, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun transcribe(sessionId: String, model: SpeechModelId, onProgress: (Float) -> Unit): Result<TranscribeAgainOutcome> {
        val audio = audioRepository.find(sessionId) ?: return Result.success(TranscribeAgainOutcome.NoAudio)
        val session = transcriptionRepository.getSession(sessionId).first()
            ?: return Result.failure(IllegalStateException("The recording no longer exists"))
        val spoken = SpokenLanguage.of(transcriptionRepository.getSegmentsBySession(sessionId).first())
        val language = SpeechModelChoice.languageFor(model, spoken)
        if (!model.understands(language)) {
            return Result.failure(IllegalArgumentException("${model.id} doesn't know the language of the recording"))
        }
        if (!SpeechModelChoice.mayTranscribe(model, session.mode)) {
            return Result.failure(IllegalArgumentException("A Translate recording stays in English or German"))
        }

        // Malayalam as its script writes it, as the live lines are stored ([MalayalamText]). A line
        // with no word left is none: the clean-up leaves out a sign that no letter carries, and the
        // start buzz came out as such signs, which on 2026-10-16 made the first line empty.
        val lines = sttRepository.transcribeAudio(audio, model, language, onProgress)
            .map { line -> line.copy(text = MalayalamText.clean(line.text)) }
            .filter { DiscardSilentRecordingUseCase.holdsSpeech(it.text) }
        // A result of lone "." lines is noise, and would leave a recording with words without them.
        if (lines.isEmpty()) {
            return Result.success(TranscribeAgainOutcome.NoSpeech)
        }

        val heard = lines.map { line ->
            TranscriptJoiner.Line(
                id = newId(),
                text = line.text,
                span = line.span,
                stamp = audio.wallClockAt(line.span.endMs, session.createdAt),
                capture = TranscriptJoiner.captureAt(audio.runs, line.span.startMs)
            )
        }
        val newPlaces = heard.zip(lines) { new, line -> new.id to line.span }
        // The minutes of the speech model are spent: a cancel from here on (Cancel on the page, Skip
        // after Stop) would only leave the new lines without their places or their model's name.
        return withContext(NonCancellable) { store(sessionId, model, audio, session.createdAt, heard, newPlaces) }
    }

    /**
     * Puts [heard], the lines just recognized, whose places in the audio are [newPlaces], in place
     * of the recording's transcript, joined where a sentence goes on, with the notes, to-dos and
     * links pointed at them, and names [model] as their maker.
     */
    private suspend fun store(
        sessionId: String,
        model: SpeechModelId,
        audio: RecordingAudio,
        recordingStart: Long,
        heard: List<TranscriptJoiner.Line>,
        newPlaces: List<Pair<String, AudioSpan>>
    ): Result<TranscribeAgainOutcome> {
        // Read now, not before the minutes the speech model took: a note edited meanwhile keeps
        // its edit, since the transaction only changes which lines it points at.
        val oldPlaces = placesOf(transcriptionRepository.getSegmentsBySession(sessionId).first(), audio, recordingStart)
        val insightSources = transcriptionRepository.getInsightsBySession(sessionId).first()
            .associate { note -> note.id to note.sourceSegmentIds }
            .foundIn(oldPlaces, newPlaces)
        val todoSources = todoRepository.observeForSession(sessionId).first()
            .associate { todo -> todo.id to todo.sourceSegmentIds }
            .foundIn(oldPlaces, newPlaces)
        val linkSources = transcriptionRepository.getLinkSources(sessionId).foundIn(oldPlaces, newPlaces)

        // The lines a note now cites are known only here, and the join keeps each note's lines apart.
        val citedBy = HashMap<String, MutableSet<String>>()
        insightSources.forEach { (noteId, lineIds) -> lineIds.forEach { citedBy.getOrPut(it) { HashSet() } += noteId } }
        val joined = TranscriptJoiner.join(
            heard.map { it.copy(citedBy = citedBy[it.id].orEmpty()) },
            // The detector's values for a file cut these lines, not those of Advanced settings.
            maxSpeechMs = TranscriptJoiner.maxSpeechMsOf(VadParameters.FILE),
            // The new lines' own: an old transcript made by a model of the wrong language tells another.
            language = SpokenLanguage.of(
                heard.map { TranscriptionSegment(it.id, sessionId, it.text, it.stamp, isComplete = true) }
            )
        )
        val groupIds = joined.groupIds()
        // Cleaned again once joined: a word's end the recognizer put at the start of the next line
        // goes back to its word.
        val segments = joined.groups.map { group ->
            TranscriptionSegment(id = group.id, sessionId = sessionId, text = MalayalamText.clean(group.text), timestamp = group.stamp, isComplete = true)
        }

        transcriptionRepository.replaceSegments(
            sessionId,
            segments,
            insightSources.inGroups(groupIds),
            todoSources.inGroups(groupIds),
            linkSources.inGroups(groupIds)
        ).onFailure { return Result.failure(it) }
        audioRepository.replaceLines(sessionId, joined.groups.mapNotNull { group -> group.span?.let { group.id to it } }.toMap())
        // Best effort, like the places above: the transcript is in, and a model that wasn't stored
        // only leaves the caption naming the one before.
        transcriptionRepository.setTranscriptModel(sessionId, model.id)
        // So is the language: one left as it was is the page's to change ("Change type").
        transcriptionRepository.setLanguage(sessionId, RecordingLanguage.transcribedBy(model))
        linesLog.write("${joined.summary()} in $sessionId, transcribed again", null)
        return Result.success(TranscribeAgainOutcome.Replaced)
    }

    /**
     * The new lines said when the old ones of each entry were ([SourceLines.find]); an entry none
     * of whose lines can be placed keeps its old ones and is left out.
     */
    private fun Map<String, List<String>>.foundIn(
        oldPlaces: Map<String, AudioSpan>,
        newPlaces: List<Pair<String, AudioSpan>>
    ): Map<String, List<String>> =
        mapNotNull { (key, ids) -> SourceLines.find(ids, oldPlaces, newPlaces)?.let { key to it } }.toMap()

    /** These new lines as the joined lines that hold them, each once. */
    private fun Map<String, List<String>>.inGroups(groupIds: Map<String, String>): Map<String, List<String>> =
        mapValues { (_, ids) -> ids.map { groupIds.getValue(it) }.distinct() }

    /**
     * Where each of the old [lines] was said: as the index has it, or for a line it lost, from a
     * few seconds before its stamp ([RecordingAudio.startOf]) to its stamp.
     */
    private fun placesOf(lines: List<TranscriptionSegment>, audio: RecordingAudio, recordingStart: Long): Map<String, AudioSpan> =
        lines.associate { line ->
            line.id to (audio.lines[line.id]
                ?: AudioSpan(audio.startOf(line, recordingStart), audio.positionAt(line.timestamp, recordingStart)))
        }
}
