package com.satori.domain.usecase.audio

import com.satori.domain.model.SessionListItem
import com.satori.domain.repository.AudioFileInfo
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.audio.AudioRetentionRules.Plan
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase.Companion.holdsSpeech
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Compresses and deletes older audio as the rules of Settings say ([AudioRetentionRules]). Run by
 * AudioRetentionJob once a day while the phone charges, and once after a rule changed.
 *
 * It goes file by file, the deletes first: they free the most for the least work, so a run that
 * is stopped early has done them. A cancel (Android stops the job) takes effect between two files,
 * never inside one, so no WAV is left half compressed.
 *
 * A run can take minutes, and the owner may change things meanwhile, so each file is looked at
 * again as its turn comes, against the rules as they are stored then: audio that came into use (a
 * playback started, a recorder opened) is skipped, and so is audio that is no longer due (the
 * owner chose "Keep this audio" for it or deleted it, or turned a rule off). A file never gets
 * more than was planned for it, which is what Settings counted: a recording planned to go whole
 * that holds something by then only loses its audio, and audio planned to be compressed is not
 * deleted instead, as the run that follows a new rule does that. A compression, the step that
 * takes longest, is guarded in the store as well: audio kept while it is being encoded stays as
 * it was ([RecordingAudioRepository.compress]). A file that fails doesn't stop the run: its audio
 * stays as it was, and the next run tries it again.
 *
 * @property inUse The recordings whose audio is in use now, asked again before each file: read
 *   (played, transcribed again), recorded or waiting to be finished
 * @property now The wall clock, in milliseconds since the epoch
 * @property elapsedMs A monotonic clock in milliseconds, for how long each file took
 * @property deleteRecording Deletes a recording whole, its audio with it
 */
class ApplyAudioRetentionUseCase(
    private val audioRepository: RecordingAudioRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val todoRepository: TodoRepository,
    private val settingsRepository: SettingsRepository,
    private val inUse: suspend () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val deleteRecording: suspend (sessionId: String) -> Result<Unit> = transcriptionRepository::deleteSession
) {

    /** What is done to one recording's audio, in the order of how much of it is lost. */
    enum class Action { COMPRESS, DELETE_AUDIO, DELETE_RECORDING }

    /**
     * One file of a run, as it was done.
     *
     * @property action What was done: a recording planned to go whole that holds something by
     *   then only loses its audio
     * @property freedBytes What it freed; 0 when it failed
     * @property ms How long it took
     * @property error Why it failed, or null when it didn't
     */
    data class FileDone(
        val sessionId: String,
        val action: Action,
        val freedBytes: Long,
        val ms: Long,
        val error: Throwable? = null
    )

    /**
     * What a whole run did, in counts, so it can be logged.
     *
     * @property noLongerDue Planned, but by its turn kept, deleted, compressed already or no
     *   longer under the rules, so left as it was
     */
    data class Outcome(
        val compressed: Int = 0,
        val audioDeleted: Int = 0,
        val recordingsDeleted: Int = 0,
        val failed: Int = 0,
        val skippedInUse: Int = 0,
        val noLongerDue: Int = 0,
        val freedBytes: Long = 0
    )

    /**
     * What a run would do with these rules, without changing anything: a run plans with the stored
     * rules, Settings asks with the delete age about to be chosen, and the debug tools plan for a
     * later day.
     *
     * @param atMs The time to plan for; now by default
     */
    suspend fun plan(compressAfterDays: Int?, deleteAfterDays: Int?, atMs: Long = now()): Plan {
        if (compressAfterDays == null && deleteAfterDays == null) return Plan()
        val files = audioRepository.inventory()
        if (files.isEmpty()) return Plan()
        val items = transcriptionRepository.getAllSessions().first()
        val busy = inUse()
        fun planWith(recordings: List<AudioRetentionRules.Recording>) =
            AudioRetentionRules.plan(files, recordings, atMs, compressAfterDays, deleteAfterDays, busy)
        // Taking every recording to hold something tells whose audio goes; only those are read
        // for what else they hold, which costs a read or two each.
        val first = planWith(items.map { it.forRules(holdsContent = true) })
        if (first.deleteAudio.isEmpty()) return first
        val losingAudio = first.deleteAudio.mapTo(HashSet()) { it.sessionId }
        return planWith(
            items.map { it.forRules(holdsContent = it.session.id !in losingAudio || holdsContent(it.session.id, it.hasNotes)) }
        )
    }

    /**
     * Carries out the plan of the stored rules.
     *
     * @param onFile Told of each file once it is done, for the log; not of those skipped
     * @throws kotlinx.coroutines.CancellationException when cancelled, after the file that was
     *   being done
     */
    suspend operator fun invoke(onFile: (FileDone) -> Unit = {}): Outcome {
        val settings = settingsRepository.getSettings().first()
        val plan = plan(settings.audioCompressAfterDays, settings.audioDeleteAfterDays)
        val steps = plan.deleteRecording.map { it.sessionId to Action.DELETE_RECORDING } +
            plan.deleteAudio.map { it.sessionId to Action.DELETE_AUDIO } +
            plan.compress.map { it.sessionId to Action.COMPRESS }
        var outcome = Outcome()
        for ((sessionId, planned) in steps) {
            currentCoroutineContext().ensureActive()
            if (sessionId in inUse()) {
                outcome = outcome.copy(skippedInUse = outcome.skippedInUse + 1)
                continue
            }
            val due = dueNow(sessionId, planned)
            if (due == null) {
                outcome = outcome.copy(noLongerDue = outcome.noLongerDue + 1)
                continue
            }
            val (file, action) = due
            val started = elapsedMs()
            val result = withContext(NonCancellable) { apply(file, action) }
            if (action == Action.COMPRESS && result.getOrNull() == 0L) {
                // Nothing was compressed: the audio was kept, or its WAV gone, by the time the copy was made.
                outcome = outcome.copy(noLongerDue = outcome.noLongerDue + 1)
                continue
            }
            val done = FileDone(
                sessionId = sessionId,
                action = action,
                freedBytes = result.getOrDefault(0L),
                ms = elapsedMs() - started,
                error = result.exceptionOrNull()
            )
            outcome = outcome.counting(done)
            onFile(done)
        }
        return outcome
    }

    /**
     * What is done to the audio of [sessionId] as its turn comes, with the file as it is then:
     * what the stored rules say of it now, unless that is more than [planned]. Null when nothing
     * is: the audio is gone, kept, compressed already or no longer under a rule, or the rules now
     * ask for more than was planned.
     */
    private suspend fun dueNow(sessionId: String, planned: Action): Pair<AudioFileInfo, Action>? {
        val file = audioRepository.inventoryOf(sessionId) ?: return null
        val rules = settingsRepository.getSettings().first()
        val recording = transcriptionRepository.getSession(sessionId).first()?.let {
            AudioRetentionRules.Recording(it.id, it.createdAt, it.durationMs, holdsContent = true)
        }
        val plan = AudioRetentionRules.plan(
            files = listOf(file),
            sessions = listOfNotNull(recording),
            now = now(),
            compressAfterDays = rules.audioCompressAfterDays,
            deleteAfterDays = rules.audioDeleteAfterDays,
            inUse = emptySet()
        )
        val ruleSays = when {
            plan.deleteAudio.isNotEmpty() ->
                // Audio without its recording row only ever loses its audio.
                if (planned == Action.DELETE_RECORDING && recording != null && !holdsContentNow(sessionId)) {
                    Action.DELETE_RECORDING
                } else {
                    Action.DELETE_AUDIO
                }
            plan.compress.isNotEmpty() -> Action.COMPRESS
            else -> return null
        }
        return if (ruleSays <= planned) file to ruleSays else null
    }

    /** Does [action] to [file]. @return The bytes it freed, or why it failed */
    private suspend fun apply(file: AudioFileInfo, action: Action): Result<Long> = when (action) {
        Action.COMPRESS -> audioRepository.compress(file.sessionId)
        Action.DELETE_AUDIO -> {
            audioRepository.delete(file.sessionId)
            if (audioRepository.hasAudio(file.sessionId)) {
                Result.failure(IllegalStateException("The audio is still there"))
            } else {
                Result.success(file.bytes)
            }
        }
        Action.DELETE_RECORDING -> deleteRecording(file.sessionId).map { file.bytes }
    }

    /** Whether the recording of [sessionId], read again now, holds anything but its audio. */
    private suspend fun holdsContentNow(sessionId: String): Boolean =
        holdsContent(sessionId, hasNotes = transcriptionRepository.getInsightsBySession(sessionId).first().isNotEmpty())

    /** Whether the recording of [sessionId] holds anything that outlasts its audio. */
    private suspend fun holdsContent(sessionId: String, hasNotes: Boolean): Boolean =
        hasNotes ||
            transcriptionRepository.getSegmentsBySession(sessionId).first().any { holdsSpeech(it.text) } ||
            // Ideas share the to-dos' table, so they count here too.
            todoRepository.observeForSession(sessionId).first().isNotEmpty()

    private fun SessionListItem.forRules(holdsContent: Boolean) =
        AudioRetentionRules.Recording(session.id, session.createdAt, session.durationMs, holdsContent)

    private fun Outcome.counting(done: FileDone): Outcome = when {
        done.error != null -> copy(failed = failed + 1)
        done.action == Action.COMPRESS -> copy(compressed = compressed + 1, freedBytes = freedBytes + done.freedBytes)
        done.action == Action.DELETE_AUDIO -> copy(audioDeleted = audioDeleted + 1, freedBytes = freedBytes + done.freedBytes)
        else -> copy(recordingsDeleted = recordingsDeleted + 1, freedBytes = freedBytes + done.freedBytes)
    }
}
