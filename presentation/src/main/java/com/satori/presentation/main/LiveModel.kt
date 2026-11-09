package com.satori.presentation.main

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingMode
import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * One recorder's share of the AI engine for its live model. The engine is the app's one llama.cpp
 * engine, which the finisher of older recordings and the app's other AI work use as well
 * ([AiWorkGate]).
 *
 * Every piece of the recorder's work on the live model takes the live turn
 * ([AiWorkGate.LIVE_NOTE]) with [tryTake], which never waits, and gives it back with
 * [Turn.giveBack] in a NonCancellable finally: registering the model, loading it in the background,
 * each live note. A turn is given back once. A second give-back does nothing, so it can't free the
 * engine under work that has taken it since: the gate tells its holders apart by name only.
 *
 * As the recorder closes ([close]) the live model is freed, holding the engine as the engine rule
 * asks. While the engine is free, at once and off the main thread ([AiWorkGate.RECORDER_RELEASE] in
 * [closeScope]): a recorder closes in onCleared, on the main thread, and unloading takes 0.25 s
 * (Gemma 3 1B) to 0.9 s (Gemma 4 E2B). While the recorder's own work holds the turn, that work
 * frees the model as it gives the turn back. Otherwise whoever holds the engine frees it: the
 * finisher at the end of its queue, the map's and the rewrite's work as they end.
 *
 * Used on the main thread; only [freeModel] runs elsewhere.
 *
 * @property freeModel Frees the model in memory (LlmRepository.cleanup); never throws but a cancel
 * @property closeScope Outlives the recorder, whose own scope is cancelled by the time it closes
 */
internal class LiveModel(
    private val gate: AiWorkGate,
    private val freeModel: suspend () -> Unit,
    private val closeScope: CoroutineScope
) {
    /** How the live model is freed as the recorder closes ([close]). */
    sealed interface Closing {
        /** Now, holding the engine, off the main thread. */
        data object Now : Closing

        /** By the recorder's own work, which holds the turn, as it gives the turn back. */
        data object ByOwnWork : Closing

        /** By [holder], which holds the engine. */
        data class LeftTo(val holder: String?) : Closing
    }

    /** The live turn, taken by one piece of the recorder's work ([tryTake]). */
    inner class Turn internal constructor() {
        /**
         * Gives the turn back; once the recorder has closed, it frees the model first. Call it in a
         * NonCancellable finally. A turn given back already does nothing.
         */
        suspend fun giveBack() {
            if (current !== this) return
            try {
                if (closed) freeModel()
            } finally {
                current = null
                gate.exit(AiWorkGate.LIVE_NOTE)
            }
        }
    }

    // The turn the recorder's work holds now; the gate has one holder at a time.
    private var current: Turn? = null

    // The recorder has closed: no more turns, and the one held frees the model as it ends.
    private var closed = false

    /** The live turn, if the engine is free now and the recorder hasn't closed; never waits. */
    fun tryTake(): Turn? {
        if (closed || !gate.tryEnter(AiWorkGate.LIVE_NOTE)) return null
        return Turn().also { current = it }
    }

    /**
     * The recorder closes, and the live model is to be freed: only while no newer recorder uses
     * it (ActiveRecorder.mayRelease). Says how it is freed; returns at once.
     */
    fun close(): Closing {
        closed = true
        if (current != null) return Closing.ByOwnWork
        if (!gate.tryEnter(AiWorkGate.RECORDER_RELEASE)) return Closing.LeftTo(gate.holder.value)
        // Started at once rather than dispatched: a coroutine cancelled before it runs never
        // reaches its finally, which frees the engine. Unloading leaves the main thread at once.
        closeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                freeModel()
            } finally {
                gate.exit(AiWorkGate.RECORDER_RELEASE)
            }
        }
        return Closing.Now
    }

    companion object {
        /**
         * Whether the live model is loaded in the background as the recorder gets ready, so the
         * first note doesn't wait for the load: only for a meeting or a translation that writes
         * live notes. A voice note (what the quick key records) and a long meeting load it at the
         * first note, and a recording whose notes come after Stop never does, nor one in a
         * language that gets no live notes ([writesLiveNotes] false: Malayalam), whose memory the
         * speech model and the finishing's model are better off with.
         *
         * Not while older recordings wait to be finished ([finishing]): the finisher takes the
         * engine for Gemma 4 E2B in a moment, and a load of the live model next to it only delays
         * the finishing, and is unloaded again for E2B. The first note loads the model then.
         *
         * @param mayLoadNow False for a model that arrives while the recorder records
         * @param writesLiveNotes Whether the recording's language gets live notes
         *   ([com.satori.domain.model.RecordingLanguage.writesLiveNotes])
         */
        fun loadsInBackground(
            mayLoadNow: Boolean,
            strategy: InsightStrategy,
            mode: RecordingMode,
            finishing: Boolean,
            writesLiveNotes: Boolean
        ): Boolean = mayLoadNow &&
            writesLiveNotes &&
            strategy == InsightStrategy.REAL_TIME &&
            mode != RecordingMode.SIMPLE_LISTENING &&
            mode != RecordingMode.LONG_MEETING &&
            !finishing

        /**
         * Whether the live model is registered with the engine again as [holder] takes over from
         * [before]: once the engine is free after anyone's work but a live note's. The finisher
         * forgets the model as it is done, and other work may have put another one in its place;
         * a live note leaves the live model as it was.
         */
        fun registersAgain(before: String?, holder: String?): Boolean =
            holder == null && before != null && before != AiWorkGate.LIVE_NOTE
    }
}
