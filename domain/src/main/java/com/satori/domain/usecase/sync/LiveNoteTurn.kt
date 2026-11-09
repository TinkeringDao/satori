package com.satori.domain.usecase.sync

/**
 * A live note's turn at the one AI engine, which the recorder shares with the finishing of older
 * recordings and the app's other AI work. A note takes the turn only when it is free at once and
 * never waits for it: while the engine is busy (an older recording is being finished), the
 * interval is skipped and what was said in it stays for the next note ([SyncSttLlmUseCase]).
 */
interface LiveNoteTurn {

    /** Takes the turn if it is free now, and says whether it did; never waits. */
    fun tryTake(): Boolean

    /**
     * Gets the engine ready for the note, inside the turn and before the model is loaded: whoever
     * used the engine since the last note may have put another model in place of the live one, or
     * none.
     */
    suspend fun prepare()

    /**
     * Gives the turn back: once for every turn taken, also when the note failed or was cancelled,
     * so it is called in a NonCancellable finally. It may still use the engine before it lets go:
     * a recorder that has closed meanwhile frees the model with it.
     */
    suspend fun release()

    companion object {
        /** Always free, with nothing to prepare: for a caller that shares the engine with no one. */
        val ALWAYS: LiveNoteTurn = object : LiveNoteTurn {
            override fun tryTake(): Boolean = true
            override suspend fun prepare() = Unit
            override suspend fun release() = Unit
        }
    }
}
