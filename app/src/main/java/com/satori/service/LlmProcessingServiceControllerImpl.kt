package com.satori.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.finish.FinishStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [LlmProcessingServiceController].
 *
 * Starts and stops [LlmProcessingService] via explicit intents so that long-running
 * LLM inference (the finishing of recordings, Rewrite notes, Transcribe again, the mind map)
 * continues when the app is backgrounded or the phone is locked. The rules are those of
 * [ProcessingHolderSet]: the first holder starts the service and the last one stops it, so one
 * piece of AI work ending never stops the service under another. A START and its STOP carry a
 * generation, and the service ignores a STOP that a newer START has overtaken.
 *
 * [protected] follows what the service reports: it is in the foreground once its startForeground
 * went through, and no longer once it stops, Android refuses it or its time for the day is used up.
 */
@Singleton
class LlmProcessingServiceControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LlmProcessingServiceController {

    companion object {
        private const val TAG = "LlmProcessingController"
    }

    // The work that holds the service, the generations of its starts and whether it protects.
    private val holderSet = ProcessingHolderSet(ServiceCommands())

    /** True while the service is in the foreground for a holder, as the service reports it. */
    override val protected: StateFlow<Boolean> = holderSet.protected

    /** The holders now: the AI work and the Transcribe again that run. */
    override val heldBy: StateFlow<Set<String>> = holderSet.held

    private val _finishing = MutableStateFlow<FinishingNotificationText?>(null)

    /**
     * What the service's notification says about the recordings being finished
     * ([showFinishing]); null while none is, for the generic text of other AI work.
     */
    internal val finishing: StateFlow<FinishingNotificationText?> = _finishing.asStateFlow()

    /**
     * A holder that comes while the service runs for another only joins it. One that comes after
     * Android refused the start, or after the service lost the foreground, asks again: the app may
     * be in the foreground by now.
     */
    @Synchronized
    override fun startProcessing(holder: String): Boolean = holderSet.take(holder)

    @Synchronized
    override fun stopProcessing(holder: String) = holderSet.release(holder)

    override fun showFinishing(recordings: Int, step: FinishStep?) {
        _finishing.value = FinishingNotificationText.of(recordings, step)
    }

    /**
     * The service is in the foreground, as of the START of [generation]. Without a holder it
     * protects nothing: the STOP is on its way.
     */
    @Synchronized
    internal fun onForeground(generation: Long) {
        Log.d(TAG, "LLM processing service in the foreground (start $generation)")
        holderSet.foreground()
    }

    /**
     * The service left the foreground or never got there, as of the START of [generation]: it
     * stopped, Android refused it, or its time for the day is used up. The next holder starts it
     * again.
     */
    @Synchronized
    internal fun onLeftForeground(generation: Long) = holderSet.leftForeground(generation)

    /** Whether a STOP of [generation] still stops the service: no holder took it since. */
    @Synchronized
    internal fun stopHolds(generation: Long): Boolean = holderSet.stopHolds(generation)

    /** The START and STOP of [ProcessingHolderSet] as intents to [LlmProcessingService]. */
    private inner class ServiceCommands : ProcessingHolderSet.Commands {

        override fun start(holder: String, generation: Long): Boolean {
            Log.d(TAG, "Starting LLM processing service for $holder (start $generation)")
            return try {
                context.startForegroundService(command(LlmProcessingService.ACTION_START, generation))
                true
            } catch (e: Exception) {
                // Android refuses a foreground service start from the background
                // (ForegroundServiceStartNotAllowedException) unless the app may start one then, as
                // while its recording service runs, which is why the recorder hands a recording to
                // finishing before it stops that service. The inference still runs, just without the
                // protection from being killed; the finishing queue then keeps going under its job.
                Log.w(TAG, "LLM processing service not started", e)
                false
            }
        }

        override fun stop(generation: Long) {
            Log.d(TAG, "Stopping LLM processing service (start $generation)")
            // startService is sufficient here — the service handles ACTION_STOP itself. It throws
            // from the background when the service is not running, as after a refused start.
            try {
                context.startService(command(LlmProcessingService.ACTION_STOP, generation))
            } catch (e: Exception) {
                Log.w(TAG, "LLM processing service not stopped", e)
            }
        }

        private fun command(action: String, generation: Long): Intent =
            Intent(context, LlmProcessingService::class.java)
                .setAction(action)
                .putExtra(LlmProcessingService.EXTRA_GENERATION, generation)
    }
}
