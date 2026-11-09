package com.satori.domain.model

import java.security.MessageDigest

/**
 * What the reader made of one transcript line (0.8.0, design 3.A): the line's text in English, as
 * IndicTrans2 read it, which the AI steps read in place of the line ([com.satori.domain.reading.ReadableSegments]).
 * Stored per line (table segment_readings), as the Reading step reads them one by one, so a
 * reading cut short by a capture or a process death goes on from the next line.
 *
 * A reading is of the text its line had when it was read ([sourceSha256]): a line edited since has
 * a reading of another text, which counts as none ([isCurrentFor]). A line replaced (Transcribe
 * again, the joining after Stop) takes its reading with it, and a recording deleted all of its.
 * It holds no Malayalam: the line keeps that.
 *
 * @property segmentId The line read ([TranscriptionSegment.id])
 * @property sessionId Its recording
 * @property sourceSha256 The SHA-256 of the line's text as it was read ([sourceHashOf])
 * @property text The English reading; empty for a line the reader's rules leave without one (D5:
 *   a fragment, a reading the model was too unsure of, of a length no reading of its source has, or
 *   one that repeats itself), which the AI steps then don't read at all
 * @property model The id of the reader's build that read it ([ReaderModelId.id])
 * @property score The reading's mean log-probability per token, its end counted; null when the
 *   model wasn't asked (a fragment, a line it could not read) or gave no finite one
 * @property windowId The window of lines read together (design D1); null for a line read alone,
 *   which is how every line is read today
 * @property createdAt When it was read (epoch ms)
 */
data class SegmentReading(
    val segmentId: String,
    val sessionId: String,
    val sourceSha256: String,
    val text: String,
    val model: String,
    val score: Double?,
    val windowId: String? = null,
    val createdAt: Long
) {
    /** Whether this is a reading of [segment] as its text is now: the same line, the same text. */
    fun isCurrentFor(segment: TranscriptionSegment): Boolean =
        segment.id == segmentId && sourceSha256 == sourceHashOf(segment.text)

    companion object {
        /**
         * The SHA-256 of [text] as UTF-8, in lower-case hex: what a reading keeps of the text it
         * read, so an edit of the line is seen without the text kept twice.
         */
        fun sourceHashOf(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
