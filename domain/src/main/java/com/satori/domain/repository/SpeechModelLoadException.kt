package com.satori.domain.repository

/**
 * Why the stream of [SttRepository.startStreaming] fails when the live speech model could not be
 * loaded after the microphone had opened. The microphone and the audio writer are closed by then
 * and the audio recorded meanwhile is kept, so the recorder tells this from a lost microphone:
 * the recording may hold speech that was never transcribed.
 */
class SpeechModelLoadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
