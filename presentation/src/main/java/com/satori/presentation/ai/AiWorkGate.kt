package com.satori.presentation.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides who may use the one llama.cpp engine: the finisher of recordings, a live note, the mind
 * map, a rewrite of notes, Transcribe again, a new search for to-dos, a recorder freeing the model.
 * Two of them at once would load or free the model under each other. Code that loads, swaps or
 * frees a model holds the gate while it does, and every holder exits in a NonCancellable finally,
 * also on a cancel.
 *
 * The gate is fair: [enter] waits in line (first come, first served), and [exit] hands the gate
 * straight to the first in line, so [holder] goes from one holder to the next without reading
 * free in between, and nobody else can [tryEnter] meanwhile. A waiter that is cancelled leaves the
 * line, or passes the gate on if it was handed the gate as it was cancelled. Holders are told
 * apart by name only, so each piece of work enters under a name of its own.
 */
@Singleton
class AiWorkGate @Inject constructor() {

    /** One coroutine in line, and the turn that [exit] gives it. */
    private class Waiter(val holder: String) {
        val turn = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val line = ArrayDeque<Waiter>()

    private val _holder = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)

    /** Who holds the gate; null while it is free. */
    val holder: StateFlow<String?> = _holder.asStateFlow()

    /** True while anyone holds the gate. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Takes the gate for [holder], waiting in line while someone else holds it. Cancellable. */
    suspend fun enter(holder: String) {
        val waiter = synchronized(lock) {
            if (_holder.value == null) {
                hold(holder)
                return
            }
            Waiter(holder).also { line.addLast(it) }
        }
        try {
            waiter.turn.await()
        } catch (e: CancellationException) {
            val next = synchronized(lock) {
                when {
                    // Still in line: it leaves it.
                    line.remove(waiter) -> null
                    // Handed the gate as it was cancelled: it passes it on.
                    _holder.value == waiter.holder -> handOver()
                    else -> null
                }
            }
            next?.turn?.complete(Unit)
            throw e
        }
    }

    /** Takes the gate for [holder] if it is free, and says whether it did; never waits. */
    fun tryEnter(holder: String): Boolean = synchronized(lock) {
        if (_holder.value != null) return false
        hold(holder)
        true
    }

    /**
     * Frees the gate, or hands it to the first in line. Only the current holder can: a call for
     * any other name is ignored, so a late or doubled exit can't free the gate under someone else.
     */
    fun exit(holder: String) {
        val next = synchronized(lock) {
            if (_holder.value != holder) return
            handOver()
        }
        next?.turn?.complete(Unit)
    }

    /** Call holding [lock]. */
    private fun hold(holder: String) {
        _holder.value = holder
        _busy.value = true
    }

    /**
     * Gives the gate to the first in line, whose turn the caller completes once it has let go of
     * [lock], or frees it when nobody waits. Call holding [lock].
     */
    private fun handOver(): Waiter? {
        val next = line.removeFirstOrNull()
        if (next == null) {
            _holder.value = null
            _busy.value = false
        } else {
            _holder.value = next.holder
        }
        return next
    }

    companion object {
        /** The finisher of recordings, across consecutive jobs. */
        const val FINISHER = "finisher"

        /** One live note of a recording; never waits. */
        const val LIVE_NOTE = "live-note"

        /** The mind map's AI work. */
        const val MAP = "map"

        /** Write or Rewrite notes. */
        const val REWRITE = "rewrite"

        /** Transcribe again. */
        const val TRANSCRIBE = "transcribe"

        /** Find to-dos and ideas again. */
        const val TODOS = "todos"

        /** A recorder that closes frees the model. */
        const val RECORDER_RELEASE = "recorder-release"

        /** A debug build's rerun of a finishing step (DebugToolsActivity's rerun); never waits. */
        const val DEBUG_RERUN = "debug-rerun"
    }
}
