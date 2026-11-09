package com.satori.domain.model

/** How a recording's audio is stored ([RecordingAudio.encoding]). */
enum class AudioEncoding {
    /** 16 kHz mono 16-bit PCM in a WAV file, as the capture writes it. */
    WAV,

    /** AAC in an MPEG-4 file, about eight times smaller, made from the WAV once it is older. */
    AAC
}
