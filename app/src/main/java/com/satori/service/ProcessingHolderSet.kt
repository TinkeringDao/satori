package com.satori.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The holders of [LlmProcessingService] (com.satori.domain.finish.ProcessingHolders), the
 * generations of its starts and whether it protects them, for [LlmProcessingServiceControllerImpl],
 * which only turns [Commands] into intents. The first holder starts the service and the last one
 * stops it, so one piece of AI work ending never stops the service under another. Each START
 * carries a new generation and each STOP the generation of the START before it, so the service can
 * tell a STOP that a newer START has overtaken (a recording handed to finishing just as a rewrite
 * ends) from one that still holds ([stopHolds]).
 *
 * [protected] is true only while the service reports being in the foreground for a holder
 * ([foreground]), not when a START is sent: Android may still refuse it as the service starts.
 *
 * Not thread-safe: the controller calls it under its lock, also while it sends a command, so a
 * STOP can't overtake the START before it.
 */
internal class ProcessingHolderSet(private val commands: Commands) {

    /** How the commands reach [LlmProcessingService]: intents in the app, a fake in tests. */
    interface Commands {

        /**
         * Sends the START of [generation], for [holder].
         *
         * @return false if Android refused it, as it may for an app in the background
         */
        fun start(holder: String, generation: Long): Boolean

        /** Sends the STOP of [generation], that of the last START sent. */
        fun stop(generation: Long)
    }

    private val holders = mutableSetOf<String>()

    private val _held = MutableStateFlow<Set<String>>(emptySet())

    /** The holders now, as [take] and [release] leave them. */
    val held: StateFlow<Set<String>> = _held.asStateFlow()

    // The generation of the last START sent; 0 before the first.
    private var generation = 0L

    // A START was sent and Android took it, and since then the service was not stopped and didn't
    // lose its foreground: a new holder only joins it.
    private var running = false

    private val _protected = MutableStateFlow(false)

    /** Whether the service is in the foreground for a holder, as the service reports it. */
    val protected: StateFlow<Boolean> = _protected.asStateFlow()

    /**
     * [holder] takes the service. It joins a service that runs for an earlier holder. Otherwise it
     * sends a START with a new generation: as the first holder, or after the last START was
     * refused or lost the foreground ([leftForeground]), as the app may be allowed to start the
     * service by now.
     *
     * @return whether the service runs for it: Android took the START, now or for an earlier holder
     */
    fun take(holder: String): Boolean {
        holders += holder
        _held.value = holders.toSet()
        if (running) return true
        running = commands.start(holder, ++generation)
        return running
    }

    /**
     * [holder] gives the service up. The last holder sends the STOP and ends the protection; a
     * holder that doesn't hold the service changes nothing.
     */
    fun release(holder: String) {
        if (!holders.remove(holder)) return
        _held.value = holders.toSet()
        if (holders.isNotEmpty()) return
        running = false
        _protected.value = false
        commands.stop(generation)
    }

    /**
     * The service is in the foreground. Without a holder it protects nothing: its STOP is on its
     * way.
     */
    fun foreground() {
        if (holders.isNotEmpty()) _protected.value = true
    }

    /**
     * The service left the foreground or never got there, as of the START of [generation]: it
     * stopped, Android refused it, or its time for the day is used up. The next holder starts it
     * again. The loss of an older generation keeps the newer START, which is on its way.
     */
    fun leftForeground(generation: Long) {
        _protected.value = false
        if (generation == this.generation) running = false
    }

    /** Whether a STOP of [stopGeneration] still stops the service: no START was sent after it. */
    fun stopHolds(stopGeneration: Long): Boolean = ServiceStops.endsProcessing(stopGeneration, generation)
}
