package com.satori.domain.repository

/**
 * Why the stream of [SttRepository.startStreaming] fails when the microphone stopped delivering
 * audio for seconds on end (a dead AudioRecord), or no microphone input opened at all. Everything
 * it delivered until then was transcribed and emitted first, so unlike any other failure of the
 * stream it leaves no audio untranscribed: a recording that ends this way with nothing said in it
 * holds no speech that Transcribe again could find (see DiscardSilentRecordingUseCase).
 */
class MicrophoneLostException(message: String) : IllegalStateException(message)
