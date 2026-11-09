package com.satori.presentation.sessiondetails

import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs [work], a piece of the recording page's AI work (writing the notes, Transcribe again,
 * finding the to-dos and ideas again), under the AI work gate as [holder]. That is the engine
 * rule: whoever loads, swaps or frees a model holds the gate meanwhile, so no two of them load or
 * free the one engine under each other.
 *
 * The gate is taken only when [refused] says no and it is free, without waiting: the page offers
 * its work only while the AI is free, and a recording waiting to be finished goes first. It is
 * taken in the coroutine of the work, so a coroutine that never starts holds nothing. However
 * [work] ends (done, failed, or cancelled by Cancel or by leaving the page), [release] frees the
 * model, to the end also after a cancel, and only then does the gate open, even if [release]
 * fails: no one loads a model under the release, and the gate is never left held.
 *
 * @return Whether [work] ran; false, with nothing run or held, when it was refused or the gate
 *   was taken
 */
internal suspend fun AiWorkGate.runGated(
    holder: String,
    refused: () -> Boolean,
    release: suspend () -> Unit,
    work: suspend () -> Unit
): Boolean {
    if (refused() || !tryEnter(holder)) return false
    try {
        work()
    } finally {
        try {
            withContext(NonCancellable) { release() }
        } finally {
            exit(holder)
        }
    }
    return true
}
