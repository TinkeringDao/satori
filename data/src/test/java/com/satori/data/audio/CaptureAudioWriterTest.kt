package com.satori.data.audio

import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CaptureAudioWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()
    private val stalled = Executors.newSingleThreadExecutor()

    @After
    fun shutDown() {
        stalled.shutdownNow()
    }

    private fun files() = RecordingAudioFiles(File(tmp.root, "audio/s1.wav"), File(tmp.root, "audio/s1.idx"))

    private fun writer(
        files: RecordingAudioFiles,
        startedAt: Long,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        capacity: Int = CaptureAudioWriter.QUEUE_READS
    ) = CaptureAudioWriter(files, startedAt, scope, capacity) { message, _ -> synchronized(warnings) { warnings += message } }

    /** [size] samples (100 ms by default) that name their place: first, first + 1, ... */
    private fun chunk(first: Int, size: Int = 1_600) = ShortArray(size) { (first + it).toShort() }

    private fun samplesIn(file: File): ShortArray {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes, Wav.HEADER_BYTES, bytes.size - Wav.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - Wav.HEADER_BYTES) / 2) { data.getShort() }
    }

    private fun headerDataSize(file: File): Int =
        ByteBuffer.wrap(file.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()

    @Test
    fun `a capture leaves a WAV with its sizes and an index of where its lines were said`() = runBlocking {
        val files = files()
        val writer = writer(files, startedAt = 1_000)

        writer.write(chunk(0))
        writer.write(chunk(1_600))
        writer.markLine("line-1", startMs = 20, endMs = 180)
        writer.close()
        writer.join()

        assertArrayEquals(chunk(0) + chunk(1_600), samplesIn(files.wav))
        assertTrue(Wav.isOurs(files.wav.readBytes().copyOf(Wav.HEADER_BYTES)))
        assertEquals(6_400, headerDataSize(files.wav))
        val index = AudioIndex.parse(files.index.readText())
        assertEquals(listOf(AudioRun(startedAt = 1_000, startMs = 0)), index.runs)
        assertEquals(mapOf("line-1" to AudioSpan(20, 180)), index.lines)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the caller may reuse its buffer at once`() = runBlocking {
        val files = files()
        val writer = writer(files, startedAt = 1_000)
        val buffer = chunk(0)

        writer.write(buffer, count = 800)
        buffer.fill(9)
        writer.close()
        writer.join()

        assertArrayEquals(chunk(0, size = 800), samplesIn(files.wav))
    }

    @Test
    fun `a second capture plays after the first and places its lines on its own clock`() = runBlocking {
        val files = files()
        writer(files, startedAt = 1_000).apply {
            write(chunk(0, size = 3_200)) // 200 ms
            markLine("a", startMs = 0, endMs = 150)
            close()
            join()
        }

        writer(files, startedAt = 50_000).apply {
            write(chunk(9_000))
            markLine("b", startMs = 10, endMs = 90)
            close()
            join()
        }

        assertArrayEquals(chunk(0, size = 3_200) + chunk(9_000), samplesIn(files.wav))
        assertEquals(9_600, headerDataSize(files.wav))
        val index = AudioIndex.parse(files.index.readText())
        assertEquals(listOf(AudioRun(1_000, 0), AudioRun(50_000, 200)), index.runs)
        assertEquals(mapOf("a" to AudioSpan(0, 150), "b" to AudioSpan(210, 290)), index.lines)
    }

    @Test
    fun `a writer of the same recording waits for the one before it`() = runBlocking {
        val files = files()
        val first = writer(files, startedAt = 1_000)
        first.write(chunk(0))
        val second = writer(files, startedAt = 2_000)
        second.write(chunk(5_000))
        first.write(chunk(1_600))
        first.close()
        second.close()
        second.join()

        assertArrayEquals(chunk(0) + chunk(1_600) + chunk(5_000), samplesIn(files.wav))
        assertEquals(listOf(AudioRun(1_000, 0), AudioRun(2_000, 200)), AudioIndex.parse(files.index.readText()).runs)
    }

    @Test
    fun `a storage that falls behind costs the recording its audio, earlier captures' too, never the capture`() = runBlocking {
        val files = files()
        writer(files, startedAt = 1_000).apply {
            write(chunk(0))
            close()
            join()
        }
        // The writer's only thread is busy until the gate opens, so nothing is written meanwhile.
        val gate = CountDownLatch(1)
        stalled.execute { gate.await() }
        val writer = writer(files, startedAt = 9_000, scope = CoroutineScope(stalled.asCoroutineDispatcher()), capacity = 2)

        // Never blocks: the third read is one too many for the queue.
        repeat(5) { writer.write(chunk(0)) }
        writer.markLine("late", startMs = 0, endMs = 10)
        gate.countDown()
        writer.close()
        writer.join()

        assertFalse(files.wav.exists())
        assertFalse(files.index.exists())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a file that can't be written costs the audio and is only logged`() = runBlocking {
        // A folder where the audio file belongs can't be opened as one.
        val files = RecordingAudioFiles(tmp.newFolder("audio", "s1.wav"), File(tmp.root, "audio/s1.idx"))
        val writer = writer(files, startedAt = 1_000)

        writer.write(chunk(0))
        writer.markLine("a", startMs = 0, endMs = 10)
        writer.close()
        writer.join()

        assertEquals(1, warnings.size)
        assertFalse(files.index.exists())
    }

    @Test
    fun `a file Satori didn't write is replaced, with its index`() = runBlocking {
        val files = files()
        files.wav.parentFile!!.mkdirs()
        files.wav.writeBytes("not a wave file at all".toByteArray())
        files.index.writeText(AudioIndex.line("old", AudioSpan(0, 10))!!)

        writer(files, startedAt = 7_000).apply {
            write(chunk(0))
            close()
            join()
        }

        assertArrayEquals(chunk(0), samplesIn(files.wav))
        val index = AudioIndex.parse(files.index.readText())
        assertEquals(listOf(AudioRun(7_000, 0)), index.runs)
        assertTrue(index.lines.isEmpty())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `the sizes are written while recording, so a capture cut short leaves a file that plays`() = runBlocking {
        val files = files()
        val writer = writer(files, startedAt = 1_000)

        // 5.1 s, and no close: the app is killed while recording.
        repeat(51) { writer.write(chunk(0)) }
        withTimeout(10_000) { while (files.wav.length() < Wav.HEADER_BYTES + 51 * 3_200L) delay(10) }

        assertEquals(CaptureAudioWriter.HEADER_EVERY_BYTES.toInt(), headerDataSize(files.wav))
        writer.close()
        writer.join()
    }

    @Test
    fun `a capture can't add to compressed audio, so the recording goes on without any`() = runBlocking {
        val files = files()
        files.m4a.parentFile?.mkdirs()
        files.m4a.writeText("compressed audio")
        files.index.writeText(AudioIndex.run(AudioRun(1_000, 0)))

        writer(files, startedAt = 90_000).apply {
            write(chunk(0))
            markLine("line-1", startMs = 0, endMs = 90)
            close()
            join()
        }

        assertFalse(files.wav.exists())
        assertFalse(files.m4a.exists())
        assertFalse(files.index.exists())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a capture after a compression cut short adds to the WAV and drops the copy next to it`() = runBlocking {
        val files = files()
        writer(files, startedAt = 1_000).apply {
            write(chunk(0, size = 3_200))
            close()
            join()
        }
        // Cut short between putting the copy in place and deleting the WAV: the WAV is the audio.
        files.m4a.writeText("compressed audio")

        writer(files, startedAt = 50_000).apply {
            write(chunk(9_000))
            close()
            join()
        }

        assertArrayEquals(chunk(0, size = 3_200) + chunk(9_000), samplesIn(files.wav))
        assertFalse(files.m4a.exists())
        assertEquals(listOf(AudioRun(1_000, 0), AudioRun(50_000, 200)), AudioIndex.parse(files.index.readText()).runs)
        assertTrue(warnings.isEmpty())
    }
}
