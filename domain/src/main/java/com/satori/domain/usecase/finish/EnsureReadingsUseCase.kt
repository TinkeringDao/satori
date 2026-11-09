package com.satori.domain.usecase.finish

import com.satori.domain.repository.ReaderNotLoadedException

/**
 * Makes sure the lines of a recording only the reader reads have their current readings before AI
 * work outside the finishing reads the recording (0.8.0, design R6): Rewrite notes and "Change type"'s
 * rewrite, Find to-dos and ideas again, the text made again after Transcribe again, the mind map's
 * work and the debug rerun. Their use cases read the recording's lines as the AI steps read them
 * (com.satori.domain.repository.TranscriptionRepository.getLinesWithReadings), each such line as its
 * reading and none without one, so the AI model never reads Malayalam; this makes the readings they
 * lack first, by the Reading step's own rules ([ReadingStep.read]): D5's empty readings, each stored
 * as it is made, the AI model let go before the reader's paced load, and the reader released and the
 * native heap purged once the lines are read. The finishing reads its lines in its own Reading step,
 * with its own waits, and never comes here.
 *
 * Call it holding the AI work gate, as every model load is made, with no capture live, and before
 * the work loads its AI model: the reader never loads beside one, so the step lets it go first, and
 * it would come back in the work's next call without the room Gemma 4 E2B's every load keeps
 * (LoadFinishingModelUseCase). The work that holds the gate stops for a capture that starts, and its
 * cancel stops the reader at once (D14), as the reader's own watch on the capture does.
 *
 * Its counts go to [log], with no text: `Readings of <id> for <work>: read <n> lines ...`, or why
 * the readings are missing.
 *
 * @property readingStep The Reading step whose rules make the readings
 * @property log Where each reading's counts, and each reason readings are missing, go
 */
class EnsureReadingsUseCase(
    private val readingStep: ReadingStep,
    private val log: ReadingsLog = ReadingsLog.NONE
) {
    /** The AI work the readings are made for, as the field log names it. */
    enum class Work(val label: String) {
        REWRITE("Rewrite"),
        FIND_AGAIN("Find again"),
        TEXT_AGAIN("Text again"),
        MAP("The map"),
        RERUN("Debug rerun")
    }

    /** Whether the AI work may read the recording now. */
    sealed interface Outcome {
        /**
         * Every complete line the reader reads has its current reading, kept or left empty by D5 (a
         * line edited while it was read may lack one: it is left out); the lines as the AI steps read
         * them are the recording's.
         */
        data object Ready : Outcome

        /**
         * Lines the reader reads lack their readings, for [why]: work that replaces what the
         * recording has (its notes, its to-dos) doesn't run, and never the AI model on the lines as
         * they were said (D7).
         */
        data class Missing(val why: Why) : Outcome
    }

    /** Why readings are missing. */
    enum class Why {
        /** The reader's files aren't on the phone, or aren't whole. */
        NO_READER,

        /** Something the reader never loads beside is in memory: a capture's state, a speech model, an AI model being loaded. */
        HELD_OFF,

        /** A capture opened, which stopped the reader (D14); what it read before is stored. */
        STOPPED,

        /** The phone's memory stopped the reader's paced load. */
        NO_MEMORY,

        /** The reader failed as it loaded or read; what it read before is stored. */
        FAILED
    }

    /**
     * Makes the readings [sessionId] lacks, for [work]: none to make is [Outcome.Ready] at once,
     * fragments alone are read without the model, and the reader loads only when a line needs it.
     *
     * @return Whether the work may read the recording; throws only a cancel of the caller, a read of
     *   the recording that fails, and an error
     */
    suspend operator fun invoke(sessionId: String, work: Work): Outcome {
        val needs = readingStep.needs(sessionId)
        if (needs == ReadingStep.Needs.NOTHING) return Outcome.Ready
        if (needs == ReadingStep.Needs.READER && !readingStep.isOnPhone()) return missing(sessionId, work, Why.NO_READER)
        val read = readingStep.read(sessionId)
        log.log(ReadingsLog.Event.Read(sessionId, work, read.counts))
        return when (read) {
            is ReadingStep.Outcome.Read -> Outcome.Ready
            is ReadingStep.Outcome.Stopped -> missing(sessionId, work, Why.STOPPED)
            is ReadingStep.Outcome.NotLoaded ->
                missing(sessionId, work, if (read.why == ReaderNotLoadedException.Why.NOT_ON_PHONE) Why.NO_READER else Why.HELD_OFF, read.why)
            is ReadingStep.Outcome.LoadStopped -> missing(sessionId, work, Why.NO_MEMORY)
            is ReadingStep.Outcome.Failed -> missing(sessionId, work, Why.FAILED, failure = read.cause)
        }
    }

    private fun missing(
        sessionId: String,
        work: Work,
        why: Why,
        besides: ReaderNotLoadedException.Why? = null,
        failure: Throwable? = null
    ): Outcome {
        log.log(ReadingsLog.Event.Missing(sessionId, work, why, besides, failure?.javaClass?.simpleName))
        return Outcome.Missing(why)
    }
}

/**
 * Work that replaces what a recording has (its notes, its name, its to-dos) was asked of one with
 * lines only the reader reads that were never read (com.satori.domain.reading.LinesWithReadings.hasNeverRead):
 * it changes nothing (D7), as its text would be written from the rest of what was said. The work's
 * caller makes the readings first ([EnsureReadingsUseCase]).
 */
class LinesNeverReadException : IllegalStateException("Lines only the reader reads were never read")

/**
 * The field log's lines of the readings made for AI work outside the finishing
 * ([EnsureReadingsUseCase]), under SatoriFinishing beside the finishing's own: counts, ids and an
 * exception's class, never a text.
 */
fun interface ReadingsLog {

    /** One line of the field log ([line]). */
    fun log(event: Event)

    /** What the line is about. */
    sealed interface Event {
        val sessionId: String
        val work: EnsureReadingsUseCase.Work

        /** Whether the line is a warning. */
        val warns: Boolean

        /** The reader read the lines [sessionId] lacked readings of, with [counts]. */
        data class Read(
            override val sessionId: String,
            override val work: EnsureReadingsUseCase.Work,
            val counts: ReadingStep.Counts
        ) : Event {
            override val warns: Boolean get() = false
        }

        /**
         * Readings of [sessionId] are missing, for [why]; [besides] says what the reader didn't load
         * beside, [failure] the class of what failed.
         */
        data class Missing(
            override val sessionId: String,
            override val work: EnsureReadingsUseCase.Work,
            val why: EnsureReadingsUseCase.Why,
            val besides: ReaderNotLoadedException.Why? = null,
            val failure: String? = null
        ) : Event {
            override val warns: Boolean get() = true
        }
    }

    companion object {
        /** Keeps nothing. */
        val NONE = ReadingsLog { }

        /**
         * `Readings of <id> for <work>: read <n> lines in <ms> ms, <k> kept, <e> empty (...)`, or
         * `Readings of <id> for <work> missing: <why>`.
         */
        fun line(event: Event): String = when (event) {
            is Event.Read -> "Readings of ${event.sessionId} for ${event.work.label}: ${event.counts.line()}"
            is Event.Missing -> "Readings of ${event.sessionId} for ${event.work.label} missing: " + when (event.why) {
                EnsureReadingsUseCase.Why.NO_READER -> "the reader isn't on the phone"
                EnsureReadingsUseCase.Why.HELD_OFF -> "the reader can't load beside ${besideOf(event.besides)} now"
                EnsureReadingsUseCase.Why.STOPPED -> "a capture opened, which stopped the reader"
                EnsureReadingsUseCase.Why.NO_MEMORY -> "the phone's memory stopped the reader's load"
                EnsureReadingsUseCase.Why.FAILED -> "the reader failed" + (event.failure?.let { " ($it)" } ?: "")
            }
        }

        /** What the reader didn't load beside, as the finishing's line names it. */
        private fun besideOf(why: ReaderNotLoadedException.Why?): String = when (why) {
            ReaderNotLoadedException.Why.SPEECH_MODEL -> "the speech model"
            ReaderNotLoadedException.Why.AI_MODEL -> "the AI model"
            ReaderNotLoadedException.Why.CAPTURE, null -> "a capture"
            ReaderNotLoadedException.Why.NOT_ON_PHONE -> "files that aren't whole"
        }
    }
}
