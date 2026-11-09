package com.satori.service

/**
 * Which stops the two services act on. A stop sent for one piece of work can arrive after the next
 * one has begun: the next recording can start 1-2 s after Stop, before the stop of the last one has
 * come, and AI work can end just as other work takes the AI's service. Pure, so the rules are
 * tested without Android.
 */
internal object ServiceStops {

    /** What [LlmProcessingService] does with a STOP ([processingStop]). */
    enum class ProcessingStop {

        /** The STOP holds: the service leaves the foreground and stops. */
        LEAVE,

        /**
         * Work took the service after the STOP was sent, and the service is in the foreground:
         * it stays for that work, whose own STOP ends it, also when Android refused its START.
         */
        STAY,

        /**
         * Work took the service after the STOP was sent, but this instance has run no START: it
         * ends unless that START is on its way. Android refused it, or this instance was created
         * by the STOP itself, and nothing else would end it.
         */
        END_UNLESS_STARTED
    }

    /**
     * Whether a stop of [RecordingService] that names the registration [requested] ends the
     * recording the service runs, which started with the registration [current]
     * ([RecordingSessionManager.registration]). A stop for another registration belongs to an
     * older recording whose stop came late; the newer recording goes on.
     */
    fun endsRecording(requested: Long, current: Long): Boolean = requested == current

    /**
     * Whether a STOP of [LlmProcessingService] that carries [stopGeneration] still stops it, the
     * last START sent having carried [lastStartGeneration]. A START sent after the STOP (a holder
     * came in between) makes the STOP stale: the service stays for the new holder.
     */
    fun endsProcessing(stopGeneration: Long, lastStartGeneration: Long): Boolean =
        stopGeneration >= lastStartGeneration

    /**
     * What [LlmProcessingService] does with a STOP: [stopHolds] is [endsProcessing] for it, and
     * [inForeground] whether the instance runs a START that went into the foreground.
     */
    fun processingStop(stopHolds: Boolean, inForeground: Boolean): ProcessingStop = when {
        stopHolds -> ProcessingStop.LEAVE
        inForeground -> ProcessingStop.STAY
        else -> ProcessingStop.END_UNLESS_STARTED
    }
}
