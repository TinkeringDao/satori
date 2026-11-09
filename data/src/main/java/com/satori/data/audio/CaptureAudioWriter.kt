package com.satori.data.audio

import android.util.Log
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes one capture of a recording into its audio files ([RecordingAudioFiles]): the samples the
 * speech recognizer is given, and where each transcript line was said ([AudioIndex]). The file
 * work runs on a coroutine of its own behind a bounded queue, so a slow or failing write never
 * holds up or breaks the capture: [write] and [markLine] return at once and never throw.
 *
 * A recording keeps all of its audio or none. When the queue overflows (the storage stalled for
 * the [capacity] reads it holds) or a write fails, a warning is logged, the audio files are
 * deleted and the rest of the capture is dropped, while the recording itself goes on; a
 * transcript is then never made again from audio that covers only part of it.
 *
 * A capture that continues a recording (started again after an error) appends to its audio and
 * adds a run entry, so the audio plays through both captures and each line is placed on its own
 * capture's clock; a file Satori didn't write is replaced. Audio compressed since its last capture
 * (an old recording continued in a recorder restored after the app was ended) can't be appended
 * to, so such a capture gives up as a failed write does: the compressed audio is deleted, and the
 * recording goes on without audio. A compressed copy next to the WAV, which a compression cut
 * short leaves, is not the audio yet ([RecordingAudioStore.find] reads the WAV), so the capture
 * deletes the copy and appends to the WAV. The header's sizes are brought up to date every
 * [HEADER_EVERY_BYTES] and at the end, so a capture cut short by a crash still leaves a file that
 * plays ([RecordingAudioStore] repairs the rest). Two writers of one recording never overlap: a
 * new one waits for the one before it to finish.
 *
 * @param startedAt Wall-clock time of the capture's first sample
 * @param capacity How many reads the queue holds; 100 ms each in a recording
 * @param warn Where problems are logged; never with what was said
 */
class CaptureAudioWriter(
    private val files: RecordingAudioFiles,
    private val startedAt: Long,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    capacity: Int = QUEUE_READS,
    private val warn: (String, Throwable?) -> Unit = { message, e -> Log.w(TAG, message, e) }
) {
    private sealed interface Item
    private class Samples(val data: ShortArray) : Item
    private class Line(val id: String, val startMs: Long, val endMs: Long) : Item

    private val queue = Channel<Item>(capacity)
    private val givenUp = AtomicBoolean(false)

    // The writer of the same recording that came before this one, which this one waits for.
    private var previous: Job? = null
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) { run() }

    init {
        val path = files.wav.path
        previous = synchronized(writers) { writers.put(path, job) }
        job.invokeOnCompletion { synchronized(writers) { writers.remove(path, job) } }
        job.start()
    }

    /** Hands over the first [count] of [samples], copied; the caller may reuse the array. */
    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        offer(Samples(samples.copyOf(count)))
    }

    /**
     * Notes that transcript line [id] was said from [startMs] to [endMs], counted from this
     * capture's first sample.
     */
    fun markLine(id: String, startMs: Long, endMs: Long) {
        offer(Line(id, startMs, endMs))
    }

    /** No more is coming: what is queued is written, then the header. Returns at once. */
    fun close() {
        queue.close()
    }

    /** Waits until everything is written and the files are closed, or deleted. */
    suspend fun join() {
        job.join()
    }

    private fun offer(item: Item) {
        if (givenUp.get()) return
        val sent = queue.trySend(item)
        // A closed queue drops what comes after the end; only a full one means the storage stalled.
        if (sent.isFailure && !sent.isClosed) giveUp("The storage fell behind the capture", null)
    }

    private fun giveUp(reason: String, e: Throwable?) {
        if (givenUp.compareAndSet(false, true)) warn("$reason; the recording goes on without its audio", e)
    }

    private suspend fun run() {
        val before = previous
        if (before != null && withTimeoutOrNull(PREVIOUS_WRITER_WAIT_MS) { before.join() } == null) {
            giveUp("An earlier capture of this recording is still writing its audio", null)
        }
        if (files.m4a.exists()) {
            if (files.wav.exists()) {
                // Left by a compression cut short before it deleted the WAV, and without this capture.
                files.m4a.delete()
            } else {
                // AAC can't be appended to, and audio for only part of the recording is no audio to keep.
                giveUp("This recording's audio was compressed, which a capture can't add to, so it is deleted", null)
            }
        }
        var output: Output? = null
        try {
            if (!givenUp.get()) output = open()
            for (item in queue) {
                val out = output ?: continue
                if (givenUp.get()) continue
                out.take(item)
            }
            if (!givenUp.get()) output?.writeHeader()
        } catch (e: Exception) {
            giveUp("Writing the audio failed", e)
            // Whatever is still queued goes nowhere; the capture keeps sending until it ends.
            for (ignored in queue) Unit
        } finally {
            try {
                output?.close()
            } catch (e: IOException) {
                giveUp("Closing the audio file failed", e)
            }
            if (givenUp.get() && !files.delete()) warn("The audio of a recording that couldn't keep it is still there", null)
        }
    }

    /** Opens the files, after the audio of an earlier capture if there is one Satori wrote. */
    private fun open(): Output {
        files.wav.parentFile?.mkdirs()
        val existing = if (files.wav.exists()) Wav.repair(files.wav) else null
        if (files.wav.exists() && existing == null) {
            warn("Replacing an audio file Satori didn't write", null)
            files.delete()
        }
        val wav = RandomAccessFile(files.wav, "rw")
        try {
            if (existing == null) {
                wav.setLength(0)
                wav.write(Wav.header(0))
            }
            val dataBytes = existing ?: 0L
            val index = FileOutputStream(files.index, existing != null)
            val run = AudioRun(startedAt = startedAt, startMs = Wav.durationMs(dataBytes))
            try {
                index.write(AudioIndex.run(run).toByteArray())
            } catch (e: IOException) {
                index.close()
                throw e
            }
            return Output(wav, index, dataBytes, run.startMs)
        } catch (e: IOException) {
            wav.close()
            throw e
        }
    }

    /** The open files of this capture. */
    private class Output(
        private val wav: RandomAccessFile,
        private val index: FileOutputStream,
        private var dataBytes: Long,
        private val runStartMs: Long
    ) {
        private var bytes = ByteArray(0)
        private var headerSays = dataBytes

        fun take(item: Item) {
            when (item) {
                is Samples -> append(item.data)
                is Line -> AudioIndex.line(item.id, AudioSpan(runStartMs + item.startMs, runStartMs + item.endMs))
                    ?.let { index.write(it.toByteArray()) }
            }
        }

        private fun append(samples: ShortArray) {
            val size = samples.size * 2
            if (dataBytes + size > Wav.MAX_DATA_BYTES) throw IOException("The recording is longer than a WAV file holds")
            if (bytes.size < size) bytes = ByteArray(size)
            Wav.toBytes(samples, samples.size, bytes)
            wav.seek(Wav.HEADER_BYTES + dataBytes)
            wav.write(bytes, 0, size)
            dataBytes += size
            if (dataBytes - headerSays >= HEADER_EVERY_BYTES) writeHeader()
        }

        fun writeHeader() {
            wav.seek(0)
            wav.write(Wav.header(dataBytes))
            headerSays = dataBytes
        }

        fun close() {
            try {
                index.close()
            } finally {
                wav.close()
            }
        }
    }

    companion object {
        private const val TAG = "SatoriAudio"

        /** 30 s of 100 ms reads: far longer than storage stalls, about 1 MB. */
        const val QUEUE_READS = 300

        /** How often the header's sizes are written while recording: every 5 s of audio. */
        const val HEADER_EVERY_BYTES = 5 * 1_000 * Wav.BYTES_PER_MS

        /** How long a writer waits for the one before it; it takes milliseconds when all is well. */
        const val PREVIOUS_WRITER_WAIT_MS = 5_000L

        // The running writer of each audio file.
        private val writers = HashMap<String, Job>()
    }
}
