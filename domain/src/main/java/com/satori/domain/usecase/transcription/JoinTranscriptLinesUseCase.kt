package com.satori.domain.usecase.transcription

import com.satori.domain.language.MalayalamText
import com.satori.domain.language.SpokenLanguage
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.model.VadParameters
import com.satori.domain.model.VadProfile
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.TranscriptLinesStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The finishing step [com.satori.domain.usecase.finish.FinishStep.Lines]: joins the lines
 * of a recording the speech recognizer cut mid-sentence ([TranscriptJoiner]), right after Stop and
 * before the notes are written, with the AI off too.
 *
 * It reads the recording's complete lines, where each was said and in which capture (the audio's
 * index), which notes cite each line, and the language of the transcript ([SpokenLanguage]). A
 * joined line keeps its first line's id, its last line's stamp and the place from the first
 * line's start to the last one's end, so tapping it plays it from its first word. The notes,
 * to-dos and mind-map links that cite a joined-away line are pointed at the joined one, each id
 * once, and written with the lines in one transaction ([TranscriptionRepository.replaceSegments]).
 * Nothing is written when nothing joins.
 *
 * The places are written first ([RecordingAudioRepository.replaceLines], which keeps the "Keep
 * this audio" choice): each joined line's under its id, and every other entry of the index as it
 * was, so the lines joined away keep theirs under ids no line has any more. A process death
 * between the two writes then leaves the old lines with places that a run again joins the same
 * way, and a write of the lines that fails puts the old places back. Once the writes have begun
 * they run to their end, so a cancel doesn't part the lines from their places.
 *
 * Running it again joins nothing more where the lines have places: a joined line starts, ends
 * and is cited as its pieces were, and [TranscriptJoiner.Reason.FORCED] reads its last piece,
 * whose place the index kept (the latest entry within the joined line's place of an id no line
 * has). Without places the pause and the length are reckoned from the stamps and the time it
 * takes to say a line at a brisk pace, and a joined line has the stamp of its last piece and the
 * text of all of them: the pause before it comes out no shorter than the first run found (speech
 * is rarely that brisk), but a length can come out shorter, so a run again may join two lines
 * that came out just too long the first time. It runs only as a finishing step, never over older
 * recordings: many of those have no places for their lines, and a trial that joined them without
 * the pause between came out joined too often.
 *
 * Best effort, as every finishing step: a failure leaves the transcript as it was and is logged;
 * only cancellation is thrown.
 *
 * @property settingsRepository The voice detector's longest stretch of speech, for
 *   [TranscriptJoiner.Reason.FORCED]
 * @property log Where each run's line counts go ([TranscriptJoiner.Joined.summary])
 */
class JoinTranscriptLinesUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val todoRepository: TodoRepository,
    private val audioRepository: RecordingAudioRepository,
    private val settingsRepository: SettingsRepository,
    private val log: LinesLog = LinesLog.NONE
) : TranscriptLinesStep {

    override suspend fun join(sessionId: String): Int =
        try {
            joinLines(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.write("Lines of $sessionId not joined: ${e.javaClass.simpleName}", e)
            0
        }

    private suspend fun joinLines(sessionId: String): Int {
        val session = transcriptionRepository.getSession(sessionId).first() ?: return 0
        val segments = transcriptionRepository.getSegmentsBySession(sessionId).first()
        val complete = segments.filter { it.isComplete }.sortedBy { it.timestamp }
        val audio = audioRepository.find(sessionId)
        val notes = transcriptionRepository.getInsightsBySession(sessionId).first()
        val citedBy = citations(notes)
        val places = audio?.lines.orEmpty()
        val runs = audio?.runs.orEmpty()
        val lineIds = segments.mapTo(HashSet()) { it.id }
        val joinedAwayPlaces = places.filterKeys { it !in lineIds }.values
        val lines = complete.map { segment ->
            val span = places[segment.id]
            TranscriptJoiner.Line(
                id = segment.id,
                text = segment.text,
                span = span,
                stamp = segment.timestamp,
                citedBy = citedBy[segment.id].orEmpty(),
                lastPiece = span?.let { lastPieceOf(it, joinedAwayPlaces) },
                capture = span?.let { TranscriptJoiner.captureAt(runs, it.startMs) }
            )
        }
        val joined = TranscriptJoiner.join(lines, maxSpeechMs(session), SpokenLanguage.of(segments))
        if (joined.joinedAway == 0) {
            log.write("${joined.summary()} in $sessionId", null)
            return 0
        }

        val groupIds = joined.groupIds()
        // A word's end the recognizer put at the start of the next line goes back to its word
        // ([MalayalamText]), as the live lines are stored.
        val newSegments = joined.groups.map { group ->
            TranscriptionSegment(group.id, sessionId, MalayalamText.clean(group.text), group.stamp, isComplete = true)
        } + segments.filterNot { it.isComplete }
        val insightSources = notes.associate { it.id to it.sourceSegmentIds }.pointedAt(groupIds)
        val todoSources = todoRepository.observeForSession(sessionId).first()
            .associate { it.id to it.sourceSegmentIds }
            .pointedAt(groupIds)
        val linkSources = transcriptionRepository.getLinkSources(sessionId).pointedAt(groupIds)
        val joinedPlaces = joined.groups
            .filter { it.lines.size > 1 }
            .mapNotNull { group -> group.span?.let { group.id to it } }
        withContext(NonCancellable) {
            if (audio != null) audioRepository.replaceLines(sessionId, places + joinedPlaces)
            transcriptionRepository.replaceSegments(sessionId, newSegments, insightSources, todoSources, linkSources)
                .onFailure { failure ->
                    if (audio != null) audioRepository.replaceLines(sessionId, places)
                    throw failure
                }
        }
        log.write("${joined.summary()} in $sessionId", null)
        return joined.joinedAway
    }

    /**
     * The longest stretch of speech the voice detector let these lines run to: Advanced settings'
     * for the transcript the recording made, by the live model of its language (Parakeet v3, or the
     * Malayalam model: [RecordingLanguage.LIVE_MODELS]), the fixed one for files for a transcript
     * made again from its audio (a better transcript after Stop), whose lines a lower live maximum
     * would misread as cut.
     */
    private suspend fun maxSpeechMs(session: TranscriptionSession): Long {
        val live = SpeechModelId.ofTranscript(session.transcriptModel) in RecordingLanguage.LIVE_MODELS
        val profile = if (live) VadProfile.LIVE else VadProfile.FILE
        return TranscriptJoiner.maxSpeechMsOf(VadParameters.of(profile, settingsRepository.getSettings().first()))
    }

    private companion object {
        /**
         * Where the last piece of the line said at [span] was said: for a line an earlier run
         * joined, the latest of [joinedAwayPlaces] (places the index keeps for ids no line has)
         * within it; otherwise [span] itself.
         */
        fun lastPieceOf(span: AudioSpan, joinedAwayPlaces: Collection<AudioSpan>): AudioSpan =
            joinedAwayPlaces
                .filter { it.startMs >= span.startMs && it.endMs <= span.endMs }
                .maxByOrNull { it.startMs }
                ?: span

        /** The ids of the notes that cite each line, by line id. */
        fun citations(notes: List<LlmInsight>): Map<String, Set<String>> {
            val citedBy = HashMap<String, MutableSet<String>>()
            for (note in notes) {
                for (lineId in note.sourceSegmentIds) citedBy.getOrPut(lineId) { HashSet() } += note.id
            }
            return citedBy
        }

        /**
         * These sources pointed at the joined lines, each id once, keeping only the entries that
         * change; ids of no line (gone already) stay as they are.
         */
        fun Map<String, List<String>>.pointedAt(groupIds: Map<String, String>): Map<String, List<String>> =
            mapValues { (_, ids) -> ids.map { groupIds[it] ?: it }.distinct() }
                .filter { (key, ids) -> ids != getValue(key) }
    }
}
