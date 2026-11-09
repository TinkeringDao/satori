package com.satori.domain.service

import com.satori.domain.usecase.finish.FinishStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic interface for controlling the LLM processing foreground service.
 *
 * Implementations start a foreground service with a DATA_SYNC notification and keep the CPU
 * awake, so that long-running LLM inference can continue when the app is sent to the
 * background or the phone is locked.
 *
 * Every piece of AI work holds the service under a key of its own
 * ([com.satori.domain.finish.ProcessingHolders]): the first holder starts it and the last
 * one stops it, so the finishing of a recording, a rewrite and the mind map can overlap without
 * one stopping the service under another.
 */
interface LlmProcessingServiceController {

    /**
     * Takes the service for [holder], starting it if it is the first holder.
     * Call this before launching any LLM inference that should survive backgrounding.
     *
     * Never throws. The platform may refuse the service, for example when the app is in the
     * background and none of its other foreground services runs (so the recorder calls this
     * before it stops the recording service); the inference then runs anyway, only without that
     * protection.
     *
     * @return whether the service runs for it: the platform accepted the start, now or for an
     *   earlier holder
     */
    fun startProcessing(holder: String): Boolean

    /**
     * Gives the service up for [holder], stopping it if it was the last holder; a holder that
     * doesn't hold it is ignored. Call this when inference completes or is cancelled. Never throws.
     */
    fun stopProcessing(holder: String)

    /** Whether the service is in the foreground now, keeping the process alive. */
    val protected: StateFlow<Boolean>

    /**
     * What the service's notification says while recordings are being finished: how many, and
     * the step of the one under way (null between steps). Called on every change of the queue;
     * the default shows nothing.
     */
    fun showFinishing(recordings: Int, step: FinishStep?) {}

    /**
     * The holders of the service now ([com.satori.domain.finish.ProcessingHolders]): which AI
     * work and which Transcribe again runs, for a screen that must not remove a model under it
     * (Models and storage). None by default, for the fakes of tests.
     */
    val heldBy: StateFlow<Set<String>> get() = NO_HOLDERS

    companion object {
        /** No holder, for an implementation that doesn't tell ([heldBy]). */
        val NO_HOLDERS: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    }
}
