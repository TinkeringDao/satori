package com.satori.domain.repository

import java.io.IOException

/**
 * A model load the app stopped so as not to push the phone into memory pressure: it stayed short
 * of memory while the model loaded, MemFree under the pacing floor for 10 s in all as the model's
 * file was read in or its weights were copied (every AI model load is paced since 0.6.1; the floor
 * is 150 MB on the razr, lower once a wait finds the kernel not reclaiming, since 0.7.2), or the file
 * couldn't be read in first at the pace, which the load would then have read in one burst. Nothing
 * of the model is in memory then. Unlike a damaged file or a native error, it says nothing about
 * the model: the same load is worth trying again later, so work after Stop waits and tries again
 * instead of ending without notes
 * ([com.satori.domain.usecase.finish.FinishRecordingUseCase.Outcome.LoadStopped]).
 */
open class LoadStoppedForMemoryException(message: String, cause: Throwable? = null) : IOException(message, cause)
