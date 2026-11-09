package com.satori.domain.usecase.audio

import com.satori.domain.model.AudioEncoding
import com.satori.domain.repository.AudioFileInfo

/**
 * Which recordings' audio the two rules of Settings compress and which they delete: "Compress
 * older audio" ([com.satori.domain.model.AppSettings.audioCompressAfterDays]) and "Delete
 * older audio" ([com.satori.domain.model.AppSettings.audioDeleteAfterDays]). Pure;
 * [ApplyAudioRetentionUseCase] carries the plan out.
 *
 * - Audio is as old as the time since its recording's last capture ended
 *   ([AudioFileInfo.lastCaptureEndMs], else the recording's start plus its length). A rule of N
 *   days applies once N times 24 hours have passed.
 * - Delete wins over compress, and compressed audio isn't compressed again.
 * - Left alone, whatever the rules say: audio the owner keeps ("Keep this audio"), audio in use
 *   now (played, transcribed again, recorded or being finished), audio younger than [MIN_AGE_MS],
 *   and audio whose age can't be told.
 * - A recording whose audio goes and that holds nothing else, no word, note, to-do or idea, goes
 *   whole. Since recordings without speech are deleted as they end, such a recording was kept only
 *   for its audio, in which Transcribe again might find speech the live model missed; once the
 *   audio is gone there is nothing left in it to find.
 * - Audio left without its recording (a delete cut short before the audio went) goes with the
 *   delete rule, by the age of its runs. It is never compressed: nothing can play it.
 */
object AudioRetentionRules {

    /** The rules count in days of 24 hours. */
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Nothing younger is touched, whatever the rules say: also not after the clock was set back,
     * or for a recording whose age is read before its length is saved.
     */
    const val MIN_AGE_MS = DAY_MS

    /**
     * A recording, as the rules need to know it.
     *
     * @property createdAt When it started, wall clock
     * @property durationMs How long it recorded
     * @property holdsContent Whether anything of it outlasts its audio: a transcript line with a
     *   word in it (a letter or a digit), a note, a to-do or an idea
     */
    data class Recording(
        val sessionId: String,
        val createdAt: Long,
        val durationMs: Long,
        val holdsContent: Boolean
    )

    /**
     * What a run does, each list in the order of the files it was planned from.
     *
     * @property compress Audio to be compressed
     * @property deleteAudio Audio to be deleted; its recording stays
     * @property deleteRecording Recordings to be deleted whole, their audio with them
     */
    data class Plan(
        val compress: List<AudioFileInfo> = emptyList(),
        val deleteAudio: List<AudioFileInfo> = emptyList(),
        val deleteRecording: List<AudioFileInfo> = emptyList()
    ) {
        /** How many recordings lose their audio: what Settings asks about before a delete age applies. */
        val audioDeleted: Int get() = deleteAudio.size + deleteRecording.size

        val isEmpty: Boolean get() = compress.isEmpty() && deleteAudio.isEmpty() && deleteRecording.isEmpty()
    }

    /**
     * @param files The kept audio, as RecordingAudioRepository.inventory lists it
     * @param sessions The recordings there are
     * @param now The wall clock, in milliseconds since the epoch
     * @param compressAfterDays After how many days audio is compressed; null for never
     * @param deleteAfterDays After how many days audio is deleted; null for never
     * @param inUse The recordings whose audio is in use now
     */
    fun plan(
        files: List<AudioFileInfo>,
        sessions: Collection<Recording>,
        now: Long,
        compressAfterDays: Int?,
        deleteAfterDays: Int?,
        inUse: Set<String>
    ): Plan {
        if (compressAfterDays == null && deleteAfterDays == null) return Plan()
        val recordings = sessions.associateBy { it.sessionId }
        val compress = mutableListOf<AudioFileInfo>()
        val deleteAudio = mutableListOf<AudioFileInfo>()
        val deleteRecording = mutableListOf<AudioFileInfo>()
        for (file in files) {
            if (file.kept || file.sessionId in inUse) continue
            val recording = recordings[file.sessionId]
            val endedAt = file.lastCaptureEndMs ?: recording?.let { it.createdAt + it.durationMs } ?: continue
            val age = now - endedAt
            if (age < MIN_AGE_MS) continue
            when {
                deleteAfterDays != null && age >= deleteAfterDays * DAY_MS ->
                    if (recording != null && !recording.holdsContent) deleteRecording += file else deleteAudio += file
                compressAfterDays != null && age >= compressAfterDays * DAY_MS &&
                    recording != null && file.encoding == AudioEncoding.WAV -> compress += file
            }
        }
        return Plan(compress, deleteAudio, deleteRecording)
    }
}
