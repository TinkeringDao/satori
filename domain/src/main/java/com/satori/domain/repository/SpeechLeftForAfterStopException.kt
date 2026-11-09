package com.satori.domain.repository

/**
 * Why the stream of [SttRepository.startStreaming] ends when Stop came while the live speech model
 * was still being read in and the recording keeps its audio: the load stopped there, and the
 * capture ended at once instead of waiting seconds for the model to transcribe what waited in its
 * queue. That audio is in the recording's audio file, untranscribed, so the recorder takes this as
 * a capture that may lack speech, never as a lost microphone, and hands the recording off saying
 * so: the finisher transcribes its kept audio after Stop
 * (com.satori.domain.usecase.finish.TranscribeAfterStopUseCase). Not an error the screen shows:
 * it comes only after Stop.
 */
class SpeechLeftForAfterStopException(message: String) : IllegalStateException(message)
