package com.satori.domain.repository

import com.satori.domain.model.SpeechModelId

/**
 * The speech models on this phone: which ones are there whole, and how big each one is. The files
 * and their sizes are those of the data layer's registry of speech models, which the downloads in
 * Advanced settings fetch.
 */
interface SpeechModelRepository {

    /**
     * Whether every file of [model] is on the phone and looks complete (its size and first bytes),
     * so the speech engine may load it. Reads the disk, on a background thread.
     */
    suspend fun isInstalled(model: SpeechModelId): Boolean

    /**
     * The bytes the files of [model] take together. It is also about what the model takes in
     * memory once loaded, which decides whether it may run next to other work.
     */
    fun sizeBytes(model: SpeechModelId): Long
}
