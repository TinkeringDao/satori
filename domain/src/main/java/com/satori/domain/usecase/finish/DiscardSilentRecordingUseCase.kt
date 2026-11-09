package com.satori.domain.usecase.finish

import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.flow.first

/**
 * Deletes a recording in which nothing was said, as it ends, so the list only holds recordings
 * with something in them to read. The owner asked for this: a recording without speech need not
 * be kept, and the list showed such recordings (a "0:54 Voice note" without a word in it), each
 * of which cost a delete by hand. It goes the way every deleted recording goes
 * ([TranscriptionRepository.deleteSession]): its row, transcript, notes, mind-map links and audio.
 * A to-do would outlive it, as to-dos outlive their recordings, but none comes from a recording in
 * which nothing was said.
 *
 * Nothing was said when no saved transcript line holds a letter or a digit ([holdsSpeech]): for a
 * breath or a noise the recognizer writes nothing or a lone ".", while a line such as "10" is
 * speech. How long the recording ran doesn't matter. Words the recorder heard count as said even
 * if no saved line has them (`heard`): a line can be lost on its way to the database, and a
 * capture cut off as its recorder closes never gets the line of what was being said.
 *
 * Kept though no line holds a word when something was recorded in it (a length, or kept audio)
 * and the transcript may not cover all of it, as the part it lacks may hold speech:
 * - `mayLackSpeech`: the speech recognizer failed and ended a capture, so part of the audio may
 *   never have been transcribed (Transcribe again can find it there), or the recording held a
 *   capture from before this recorder, whose end no recorder saw (Android ended the app). A
 *   microphone that stopped delivering audio is no such failure
 *   ([com.satori.domain.repository.MicrophoneLostException]): all it delivered was
 *   transcribed.
 * - `captured` false: a recorder that never recorded closes on a recording made before Android
 *   ended the app, whose last words may be missing from the transcript.
 *
 * A recording in which nothing was recorded goes either way: its row is created before the
 * recorder opens, so a recorder that was backed out of while it got ready, was refused the
 * microphone, or couldn't start one, leaves one behind. A recording that is continued keeps the
 * lines of its earlier captures, so one with speech in any of them stays.
 *
 * @property transcriptionRepository Where the recording is read and deleted
 * @property audioRepository Whether audio is kept of the recording
 */
class DiscardSilentRecordingUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val audioRepository: RecordingAudioRepository
) {
    /**
     * @param sessionId The recording that ends
     * @param captured Whether this recorder recorded it, and so knows how its captures ended
     * @param heard Whether words were heard that the saved lines may lack: the speech recognizer
     *   gave out a result with a letter or a digit, or may have
     *   ([com.satori.domain.repository.SttRepository.mayHaveHeardSpeech]), or the recorder
     *   still held such words as the capture stopped
     * @param mayLackSpeech Whether the transcript may lack what was said for another reason: the
     *   speech recognizer failed, or a capture from before this recorder is in the recording
     * @return Whether the recording is gone (false: it is kept); a failure if nothing was said in
     *   it but it could not be deleted
     */
    suspend operator fun invoke(
        sessionId: String,
        captured: Boolean,
        heard: Boolean = false,
        mayLackSpeech: Boolean = false
    ): Result<Boolean> {
        // What the recorder knows comes first: it costs no read.
        if (heard) return Result.success(false)
        val session = transcriptionRepository.getSession(sessionId).first() ?: return Result.success(false)
        val transcriptMayLack = mayLackSpeech || !captured
        if (transcriptMayLack && session.durationMs > 0L) return Result.success(false)
        if (transcriptionRepository.getSegmentsBySession(sessionId).first().any { holdsSpeech(it.text) }) {
            return Result.success(false)
        }
        // A length is saved only every 20 s while recording; audio is kept from the first read.
        if (transcriptMayLack && audioRepository.hasAudio(sessionId)) return Result.success(false)
        return transcriptionRepository.deleteSession(sessionId).map { true }
    }

    companion object {
        /** Whether [text] holds speech: at least one letter or digit, in any script. */
        fun holdsSpeech(text: String): Boolean = text.codePoints().anyMatch { Character.isLetterOrDigit(it) }
    }
}
