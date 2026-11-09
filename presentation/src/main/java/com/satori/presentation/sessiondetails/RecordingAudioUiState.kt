package com.satori.presentation.sessiondetails

import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.stt.TranscribeOption

/**
 * What the details page knows about a recording's kept audio.
 *
 * @property audio The audio, or null when none was kept (or it is still being looked for)
 * @property speechModel Whether a speech model that loads is on the phone, any of them
 * @property recorderBusy Whether a recorder is starting, recording or saving: it needs the
 *   microphone and the processor, so nothing here plays or transcribes meanwhile
 * @property aiBusy Whether the AI is in use (AiWorkGate), by the finishing of a recording say, or a
 *   recording waits to be finished: Transcribe again waits for it, playing doesn't
 * @property transcribeChoice While the sheet that picks the model for Transcribe again is open,
 *   what it lists ([com.satori.domain.usecase.stt.SpeechModelChoice.options]); null otherwise
 * @property transcription The Transcribe again that runs, or null
 * @property error What went wrong; null if nothing did
 */
data class RecordingAudioUiState(
    val audio: RecordingAudio? = null,
    val speechModel: Boolean = false,
    val recorderBusy: Boolean = false,
    val aiBusy: Boolean = false,
    val transcribeChoice: List<TranscribeOption>? = null,
    val transcription: TranscriptionProgress? = null,
    val error: AudioError? = null
) {
    /**
     * Whether Transcribe again is offered: there is audio, a speech model, no recorder needs
     * them, and none runs already. Whether it can start now is [canTranscribe].
     */
    val offersTranscribe: Boolean
        get() = audio != null && speechModel && !recorderBusy && transcription == null

    /**
     * Whether Transcribe again can start now: it is offered and the AI is free. While a recording
     * is being finished, its steps change the transcript and load models of their own.
     */
    val canTranscribe: Boolean
        get() = offersTranscribe && !aiBusy

    /** How much room the audio takes on the phone, its index included; null while that isn't known. */
    val sizeBytes: Long?
        get() = audio?.sizeBytes?.takeIf { it > 0 }

    /** Whether the audio is stored compressed ([AudioEncoding.AAC]), as older audio is once the setting says so. */
    val compressed: Boolean
        get() = audio?.encoding == AudioEncoding.AAC
}

/**
 * A Transcribe again that runs. The old transcript stays until the new one is complete.
 *
 * @property model The speech model it transcribes with
 * @property fraction How much of the audio has been read, 0..1
 * @property stopping Cancel was pressed and it is shutting down
 */
data class TranscriptionProgress(
    val model: SpeechModelId = SpeechModelId.LIVE,
    val fraction: Float = 0f,
    val stopping: Boolean = false
)

/** What went wrong with the audio. The screen words it, in the app's language. */
enum class AudioError {
    /** The audio couldn't be played. */
    Playback,

    /** Transcribe again failed on the way; the transcript is unchanged. */
    TranscribeFailed,

    /** No speech was found in the audio; the transcript is unchanged. */
    NoSpeech,

    /**
     * The AI was taken, or a recording was handed over to be finished, between the offer and the
     * tap.
     */
    AiBusy,

    /** Whether to keep the audio wasn't stored; it stays as it was. */
    Keep,

    /** The audio couldn't be deleted. */
    Delete
}

/** Short confirmations, shown as a notice at the bottom of the page. */
enum class AudioNotice {
    /** The transcript was made again from the audio. */
    Transcribed,

    /** The audio is kept as it is ("Keep this audio"). */
    Kept,

    /** The audio is no longer kept: it goes with older audio again. */
    Unkept,

    /** The audio was deleted; the transcript, notes and to-dos stay. */
    Deleted,

    /** A recording that started stopped Transcribe again; the transcript stayed as it was. */
    TranscribeStopped,

    /**
     * The transcript was made again in another language than the old one, after [Transcribed]
     * (com.satori.domain.usecase.stt.SpeechModelChoice.makesTextAgain): what the old one
     * gave the recording went with it, and its name, notes, to-dos and ideas are to be made again
     * from the new one, a Malayalam recording's in English. Not shown: the page makes them again
     * as it offers it.
     */
    TextToMakeAgain
}
