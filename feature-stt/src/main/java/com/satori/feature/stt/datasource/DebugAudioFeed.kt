package com.satori.feature.stt.datasource

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: lets a test replace the microphone with a WAV file, so the whole pipeline
 * (speech model, notes, name, to-dos, reminders) can be checked on the phone without anyone
 * speaking.
 *
 * Push a 16 kHz mono 16-bit PCM WAV to `<external files>/debug/feed.wav`, i.e.
 * `/sdcard/Android/data/com.satori/files/debug/feed.wav`. The next recording reads
 * it at real-time pace instead of the microphone, then silence until it is stopped, and the file
 * is renamed to `feed.wav.used` so it plays only once. A feed older than [MAX_AGE_MS] (by its
 * modification time; `adb shell touch` it after pushing) is deleted instead, so one left behind
 * by a test can't replace the microphone in a real recording later. In a release build, or with
 * no file, recording is unchanged.
 */
internal object DebugAudioFeed {

    private const val TAG = "SatoriDebugFeed"
    private const val SAMPLE_RATE_HZ = 16_000
    private const val MAX_AGE_MS = 10 * 60_000L

    /** The samples of a pending feed file, which is used up by this call, or null. */
    fun take(context: Context): ShortArray? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val dir = context.getExternalFilesDir("debug") ?: return null
        val file = File(dir, "feed.wav")
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
            file.delete()
            Log.w(TAG, "Deleted feed.wav: older than ten minutes, so not from this test; recording from the microphone")
            return null
        }
        val used = File(dir, "feed.wav.used")
        used.delete()
        if (!file.renameTo(used)) {
            Log.w(TAG, "Can't take feed.wav; recording from the microphone")
            return null
        }
        return parse(used.readBytes()).also { samples ->
            if (samples == null) {
                Log.w(TAG, "feed.wav is not 16 kHz mono 16-bit PCM; recording from the microphone")
            } else {
                Log.i(TAG, "Recording from feed.wav instead of the microphone: ${samples.size / SAMPLE_RATE_HZ}s of audio")
            }
        }
    }

    /** The samples of a RIFF/WAVE file with 16 kHz mono 16-bit PCM, or null for anything else. */
    fun parse(bytes: ByteArray): ShortArray? {
        if (bytes.size < 12) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        var formatOk = false
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    val format = buffer.getShort(body).toInt()
                    val channels = buffer.getShort(body + 2).toInt()
                    val rate = buffer.getInt(body + 4)
                    val bits = buffer.getShort(body + 14).toInt()
                    formatOk = format == 1 && channels == 1 && rate == SAMPLE_RATE_HZ && bits == 16
                    if (!formatOk) return null
                }
                "data" -> {
                    if (!formatOk) return null
                    return ShortArray(size / 2) { buffer.getShort(body + it * 2) }
                }
            }
            offset = body + size + (size and 1)
        }
        return null
    }
}
