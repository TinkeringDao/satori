package com.satori.feature.stt.datasource

import com.satori.domain.model.MicSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which microphone input a capture records from: the one set in Advanced settings
 * ([com.satori.domain.model.AppSettings.micSource]), or [MicSource.MIC] when that one
 * can't be opened. [MicSource.VOICE_RECOGNITION] is Android's input for speech recognition,
 * without noise reduction or automatic gain, and [MicSource.VOICE_COMMUNICATION] its input for
 * calls, with the phone's noise reduction and echo cancelling; a phone that lacks either, or
 * refuses it at that moment, still records from the general input instead of not at all.
 *
 * Only the first fallback after the app starts is to be reported: an input the phone can't open
 * stays that way, and every recording would repeat it. Each capture's summary line in the field
 * log names the input it used.
 */
internal class MicInput {
    private val fallbackReported = AtomicBoolean(false)

    /**
     * An opened input.
     *
     * @property input What opened it gave, for example a started AudioRecord
     * @property source The input it is
     * @property fellBack Whether it is [MicSource.MIC] in place of the one asked for
     * @property reportFallback Whether this is the first fallback since the app started
     */
    class Opened<T>(val input: T, val source: MicSource, val fellBack: Boolean, val reportFallback: Boolean)

    /**
     * Opens [requested] with [tryOpen], which returns null for an input that can't be opened;
     * then [MicSource.MIC], unless that was the one asked for. Null when neither opens.
     */
    fun <T : Any> open(requested: MicSource, tryOpen: (MicSource) -> T?): Opened<T>? {
        tryOpen(requested)?.let { return Opened(it, requested, fellBack = false, reportFallback = false) }
        if (requested == MicSource.MIC) return null
        val fallback = tryOpen(MicSource.MIC) ?: return null
        return Opened(fallback, MicSource.MIC, fellBack = true, reportFallback = fallbackReported.compareAndSet(false, true))
    }
}
