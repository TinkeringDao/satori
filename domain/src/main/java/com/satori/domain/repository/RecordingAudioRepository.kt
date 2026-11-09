package com.satori.domain.repository

import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The audio Satori keeps of its recordings ([com.satori.domain.model.AppSettings.keepAudio]),
 * in the app's own storage. The capture writes it (see [SttRepository.startStreaming]); deleting a
 * recording through [TranscriptionRepository] deletes its audio as well.
 *
 * A recording keeps all of its audio or none: a capture that can't keep its part (the setting is
 * off, the storage failed) leaves the recording without audio, so a transcript is only ever made
 * again from audio that covers all of it.
 */
interface RecordingAudioRepository {

    /**
     * The audio of [sessionId], or null if none was kept, it holds no sound at all, or the file is
     * not one Satori wrote. Reads the file, and brings up to date the header of one whose capture
     * never stopped (the app was killed while recording), so it plays and can be shared.
     */
    suspend fun find(sessionId: String): RecordingAudio?

    /**
     * Whether audio of [sessionId] is kept: its file is there and holds more than a header. The
     * file is not read, so one that can't be read now, or that Satori didn't write, still counts,
     * unlike for [find]. What deletes a recording only when it has no audio asks this, so a read
     * error never makes it delete audio.
     */
    suspend fun hasAudio(sessionId: String): Boolean

    /**
     * Records where the lines of a new transcript of [sessionId] were said, in place of the old
     * ones; the captures stay. Best effort: a failure is logged, and the lines are then found by
     * their stamps (see [RecordingAudio.startOf]).
     *
     * @param lines By segment id
     */
    suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>)

    /**
     * Keeps the audio of [sessionId] as it is, or stops doing so ([RecordingAudio.kept]): kept
     * audio is neither compressed nor deleted with older audio. The choice outlasts a new
     * transcript's lines. Best effort, like [replaceLines]: a failure is logged, and the audio
     * then keeps the choice it had. Does nothing for a recording without audio.
     */
    suspend fun setKept(sessionId: String, kept: Boolean)

    /** Deletes the audio of [sessionId], if there is any. Runs to the end once started. */
    suspend fun delete(sessionId: String)

    /** Deletes the audio of every recording. Runs to the end once started. */
    suspend fun deleteAll()

    /**
     * The kept audio of every recording, with what the rules for older audio decide on
     * ([com.satori.domain.usecase.audio.AudioRetentionRules]). A WAV that holds no sound
     * or that Satori didn't write is left out, as [find] leaves it out. Compressed audio that
     * can't be read is listed, unlike for [find]: its storage counts, and the rule that deletes
     * older audio can remove it, by its recording's age as its own can't be told. What a
     * compression cut short left behind ([compress]) is removed on the way. Empty where audio
     * isn't stored in files.
     */
    suspend fun inventory(): List<AudioFileInfo> = emptyList()

    /**
     * The kept audio of [sessionId] as [inventory] lists it, read again now, or null if it has
     * none: what the rules for older audio go by as its turn in a run comes, since the owner may
     * have kept or deleted it after the run's [inventory].
     */
    suspend fun inventoryOf(sessionId: String): AudioFileInfo? = inventory().firstOrNull { it.sessionId == sessionId }

    /**
     * The storage the kept audio of each recording takes, its index included, by recording id:
     * what Settings adds up. Only the files' sizes are read, never the files, so it stays quick
     * however much audio there is; a recording counts when [hasAudio] says it has audio.
     */
    suspend fun storedBytes(): Map<String, Long> = inventory().associate { it.sessionId to it.bytes }

    /**
     * Compresses the audio of [sessionId] in place: an AAC copy, about eight times smaller, which
     * still plays and can be transcribed again, takes the place of the WAV once it is complete and
     * plays as long. Where each line was said stays as it was, so a line still plays from its
     * start. AAC is lossy, so this can't be undone, and audio the owner keeps ([setKept]) is left
     * as it is, also when that is chosen while it is being compressed. Runs to the end once
     * started; one compression at a time.
     *
     * @return How many bytes it freed; 0 when nothing was compressed: there is no WAV (compressed
     *   already, or no audio) or the owner keeps it. A failure leaves the WAV as it was.
     */
    suspend fun compress(sessionId: String): Result<Long> =
        Result.failure(UnsupportedOperationException("Audio isn't compressed here"))

    /**
     * Tells each time audio was compressed or deleted, so a screen that says how much audio is
     * kept reads it again. Captures are not told: no such screen shows while one runs.
     */
    fun observeChanges(): Flow<Unit> = emptyFlow()
}

/**
 * One recording's kept audio, as [RecordingAudioRepository.inventory] lists it.
 *
 * @property sessionId The recording
 * @property bytes The storage its audio takes, its index included
 * @property encoding How the audio is stored: WAV until it is compressed
 * @property lastCaptureEndMs When its last capture ended, wall clock: the last run's start plus
 *   the audio after it. Null when the index has no run (it was lost) or the length can't be read;
 *   the recording's start and length then tell its age. Never the file's time, which a header
 *   repair or a copy changes.
 * @property kept The owner keeps this audio as it is ([RecordingAudio.kept])
 */
data class AudioFileInfo(
    val sessionId: String,
    val bytes: Long,
    val encoding: AudioEncoding,
    val lastCaptureEndMs: Long?,
    val kept: Boolean
)
