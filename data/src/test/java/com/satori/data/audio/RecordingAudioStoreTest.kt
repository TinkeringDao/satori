package com.satori.data.audio

import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioRun
import com.satori.domain.model.AudioSpan
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.SyncFailedException

class RecordingAudioStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()
    private val codec = FakeCodec()
    private lateinit var dir: File
    private lateinit var decodedDir: File
    private lateinit var store: RecordingAudioStore

    @Before
    fun setUp() {
        dir = File(tmp.root, "audio")
        decodedDir = File(tmp.root, "cache/transcribe")
        // Writes copies through to the disk as on the phone.
        store = RecordingAudioStore(
            dir = dir,
            decodedDir = decodedDir,
            compressor = codec,
            decoder = codec,
            info = {},
            warn = { message, _ -> synchronized(warnings) { warnings += message } }
        )
    }

    /** A store whose writing through to the storage is [syncToDisk]. */
    private fun storeSyncing(syncToDisk: (File) -> Unit) = RecordingAudioStore(
        dir = dir,
        decodedDir = decodedDir,
        compressor = codec,
        decoder = codec,
        info = {},
        warn = { message, _ -> synchronized(warnings) { warnings += message } },
        syncToDisk = syncToDisk
    )

    /** What a capture of 200 ms that started at [startedAt] and said one line leaves behind. */
    private suspend fun capture(sessionId: String, startedAt: Long = 1_000) {
        CaptureAudioWriter(checkNotNull(store.files(sessionId)), startedAt, warn = { message, _ -> warnings += message }).apply {
            write(ShortArray(3_200) { it.toShort() })
            markLine("$sessionId-line", startMs = 30, endMs = 170)
            close()
            join()
        }
    }

    @Test
    fun `finds the audio a capture kept, with its capture and its line`() = runBlocking {
        capture("rec-1")

        val audio = checkNotNull(store.find("rec-1"))

        assertEquals("rec-1", audio.sessionId)
        assertEquals(File(dir, "rec-1.wav").path, audio.path)
        assertEquals(200L, audio.durationMs)
        assertEquals(listOf(AudioRun(1_000, 0)), audio.runs)
        assertEquals(mapOf("rec-1-line" to AudioSpan(30, 170)), audio.lines)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `audio the app was killed while writing plays in full`() = runBlocking {
        dir.mkdirs()
        File(dir, "rec-2.wav").writeBytes(Wav.header(0) + ByteArray(6_400))

        val audio = checkNotNull(store.find("rec-2"))

        assertEquals(200L, audio.durationMs)
        assertEquals(6_400L, Wav.repair(File(audio.path)))
        assertTrue(audio.runs.isEmpty())
    }

    @Test
    fun `a recording without audio, with a file Satori didn't write, or with one stopped before it heard anything, has none`() = runBlocking {
        assertNull(store.find("rec-3"))

        dir.mkdirs()
        File(dir, "rec-4.wav").writeText("not audio")
        assertNull(store.find("rec-4"))
        assertEquals(1, warnings.size)

        File(dir, "rec-9.wav").writeBytes(Wav.header(0))
        assertNull(store.find("rec-9"))
    }

    @Test
    fun `audio is kept as long as its file holds more than a header, read or not`() = runBlocking {
        capture("rec-heard")
        dir.mkdirs()
        File(dir, "rec-other.wav").writeBytes(ByteArray(100) { 1 })
        val unread = File(dir, "rec-unread.wav").apply { writeBytes(Wav.header(32_000) + ByteArray(32_000)) }
        File(dir, "rec-header.wav").writeBytes(Wav.header(0))
        // As a read error on the phone would: the file is there but can't be opened.
        unread.setReadable(false)
        unread.setWritable(false)

        assertTrue(store.hasAudio("rec-heard"))
        assertTrue(store.hasAudio("rec-other"))
        // Unless the tests run as root, which reads anything.
        if (!unread.canRead()) assertNull(store.find("rec-unread"))
        assertTrue(store.hasAudio("rec-unread"))
        assertFalse(store.hasAudio("rec-header"))
        assertFalse(store.hasAudio("rec-none"))
        assertFalse(store.hasAudio("../outside"))
    }

    @Test
    fun `new lines replace the old ones and the captures stay`() = runBlocking {
        capture("rec-5")

        store.replaceLines("rec-5", mapOf("new-1" to AudioSpan(0, 90), "new-2" to AudioSpan(100, 200)))

        val audio = checkNotNull(store.find("rec-5"))
        assertEquals(listOf(AudioRun(1_000, 0)), audio.runs)
        assertEquals(mapOf("new-1" to AudioSpan(0, 90), "new-2" to AudioSpan(100, 200)), audio.lines)
        assertFalse(File(dir, "rec-5.idx.new").exists())
    }

    @Test
    fun `audio is kept as the owner chose, and a new transcript's lines keep the choice`() = runBlocking {
        capture("rec-10")
        assertFalse(checkNotNull(store.find("rec-10")).kept)

        store.setKept("rec-10", kept = true)
        assertTrue(checkNotNull(store.find("rec-10")).kept)

        store.replaceLines("rec-10", mapOf("new-1" to AudioSpan(0, 90)))
        val afterNewLines = checkNotNull(store.find("rec-10"))
        assertTrue(afterNewLines.kept)
        assertEquals(mapOf("new-1" to AudioSpan(0, 90)), afterNewLines.lines)
        assertEquals(listOf(AudioRun(1_000, 0)), afterNewLines.runs)

        store.setKept("rec-10", kept = false)
        assertFalse(checkNotNull(store.find("rec-10")).kept)
        store.replaceLines("rec-10", mapOf("new-2" to AudioSpan(0, 90)))
        assertFalse(checkNotNull(store.find("rec-10")).kept)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `keeping audio whose index was lost writes a new one, and a recording without audio gets none`() = runBlocking {
        dir.mkdirs()
        File(dir, "rec-11.wav").writeBytes(Wav.header(0) + ByteArray(6_400))

        store.setKept("rec-11", kept = true)
        store.setKept("rec-none", kept = true)

        assertTrue(checkNotNull(store.find("rec-11")).kept)
        assertFalse(File(dir, "rec-none.idx").exists())
        assertNull(store.find("rec-none"))
    }

    @Test
    fun `the audio's size is its file's and its index's together`() = runBlocking {
        capture("rec-12")

        val audio = checkNotNull(store.find("rec-12"))

        assertEquals(File(dir, "rec-12.wav").length() + File(dir, "rec-12.idx").length(), audio.sizeBytes)
        assertTrue(audio.sizeBytes > Wav.HEADER_BYTES + 6_400)
    }

    @Test
    fun `deleting a recording's audio takes its index too, and deleting all takes every recording's`() = runBlocking {
        capture("rec-6")
        capture("rec-7")
        capture("rec-8")

        store.delete("rec-6")

        assertNull(store.find("rec-6"))
        assertFalse(File(dir, "rec-6.idx").exists())
        assertNotNull(store.find("rec-7"))

        store.deleteAll()

        assertNull(store.find("rec-7"))
        assertNull(store.find("rec-8"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an id that is not a file name never reaches outside the folder`() = runBlocking {
        tmp.newFile("outside.wav").writeBytes(Wav.header(0))

        assertNull(store.files("../outside"))
        assertNull(store.find("../outside"))
        store.delete("../outside")

        assertTrue(File(tmp.root, "outside.wav").exists())
    }

    @Test
    fun `compressed audio takes the WAV's place, keeps its index, and is found as AAC`() = runBlocking {
        capture("rec-20")
        val index = File(dir, "rec-20.idx").readText()
        val wavBytes = File(dir, "rec-20.wav").length()

        val freed = store.compress("rec-20").getOrThrow()

        assertFalse(File(dir, "rec-20.wav").exists())
        val m4a = File(dir, "rec-20.m4a")
        assertEquals(wavBytes - m4a.length(), freed)
        assertEquals(index, File(dir, "rec-20.idx").readText())
        assertFalse(File(dir, "rec-20.m4a.new").exists())
        val audio = checkNotNull(store.find("rec-20"))
        assertEquals(AudioEncoding.AAC, audio.encoding)
        assertEquals(m4a.path, audio.path)
        assertEquals(200L, audio.durationMs)
        assertEquals(listOf(AudioRun(1_000, 0)), audio.runs)
        assertEquals(mapOf("rec-20-line" to AudioSpan(30, 170)), audio.lines)
        assertEquals(m4a.length() + File(dir, "rec-20.idx").length(), audio.sizeBytes)
        assertTrue(store.hasAudio("rec-20"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the WAV is the audio while a compressed copy is next to it`() = runBlocking {
        capture("rec-21")
        // As a compression cut short between putting the copy in place and deleting the WAV.
        File(dir, "rec-21.m4a").writeText("aac 200")

        val audio = checkNotNull(store.find("rec-21"))

        assertEquals(AudioEncoding.WAV, audio.encoding)
        assertEquals(File(dir, "rec-21.wav").path, audio.path)
        assertEquals(AudioEncoding.WAV, store.inventory().single().encoding)
    }

    @Test
    fun `a compression that fails leaves the WAV as it was and no copy`() = runBlocking {
        capture("rec-22")
        val wav = File(dir, "rec-22.wav").readBytes()
        codec.encodeFailure = IllegalStateException("The codec failed")

        val result = store.compress("rec-22")

        assertTrue(result.isFailure)
        assertArrayEquals(wav, File(dir, "rec-22.wav").readBytes())
        assertFalse(File(dir, "rec-22.m4a").exists())
        assertFalse(File(dir, "rec-22.m4a.new").exists())
        assertEquals(AudioEncoding.WAV, checkNotNull(store.find("rec-22")).encoding)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a copy that plays shorter than its WAV is not kept`() = runBlocking {
        capture("rec-23")
        codec.extraMs = -(CompressedLength.TOLERANCE_MS + 1)

        assertTrue(store.compress("rec-23").isFailure)

        assertTrue(File(dir, "rec-23.wav").exists())
        assertFalse(File(dir, "rec-23.m4a").exists())
        assertFalse(File(dir, "rec-23.m4a.new").exists())
    }

    @Test
    fun `a copy that plays longer by the codec's own frames is kept`() = runBlocking {
        capture("rec-24")
        codec.extraMs = CompressedLength.FRAMING_MS

        assertTrue(store.compress("rec-24").isSuccess)

        assertEquals(200L + CompressedLength.FRAMING_MS, checkNotNull(store.find("rec-24")).durationMs)
    }

    @Test
    fun `audio compressed already, or none, frees nothing`() = runBlocking {
        capture("rec-25")
        store.compress("rec-25").getOrThrow()

        assertEquals(0L, store.compress("rec-25").getOrThrow())
        assertEquals(0L, store.compress("rec-none").getOrThrow())
    }

    @Test
    fun `what a compression cut short left is removed, by the next one and by the inventory`() = runBlocking {
        capture("rec-26")
        capture("rec-27")
        File(dir, "rec-26.m4a.new").writeText("part of a file")
        File(dir, "rec-27.m4a.new").writeText("part of a file")

        store.compress("rec-26").getOrThrow()
        store.inventory()

        assertFalse(File(dir, "rec-26.m4a.new").exists())
        assertFalse(File(dir, "rec-27.m4a.new").exists())
        // The other temporary file of the folder belongs to a new transcript's lines.
        File(dir, "rec-27.idx.new").writeText("run 1 0\n")
        store.inventory()
        assertTrue(File(dir, "rec-27.idx.new").exists())
    }

    @Test
    fun `the inventory lists each recording's audio with its size, how it is stored, its age and whether it is kept`() = runBlocking {
        capture("rec-30", startedAt = 5_000)
        capture("rec-31", startedAt = 9_000)
        store.compress("rec-31").getOrThrow()
        store.setKept("rec-31", kept = true)
        // Left out: audio Satori didn't write, a capture stopped before it heard anything, and
        // files that don't name a recording.
        File(dir, "rec-32.wav").writeText("not audio")
        File(dir, "rec-33.wav").writeBytes(Wav.header(0))
        File(dir, "notes.txt").writeText("not audio")
        File(dir, "a b.wav").writeBytes(Wav.header(6_400) + ByteArray(6_400))

        val inventory = store.inventory().associateBy { it.sessionId }

        assertEquals(setOf("rec-30", "rec-31"), inventory.keys)
        val wav = checkNotNull(inventory["rec-30"])
        assertEquals(File(dir, "rec-30.wav").length() + File(dir, "rec-30.idx").length(), wav.bytes)
        assertEquals(AudioEncoding.WAV, wav.encoding)
        assertEquals(5_000L + 200L, wav.lastCaptureEndMs)
        assertFalse(wav.kept)
        val aac = checkNotNull(inventory["rec-31"])
        assertEquals(File(dir, "rec-31.m4a").length() + File(dir, "rec-31.idx").length(), aac.bytes)
        assertEquals(AudioEncoding.AAC, aac.encoding)
        assertEquals(9_000L + 200L, aac.lastCaptureEndMs)
        assertTrue(aac.kept)
    }

    @Test
    fun `the last capture ends where the audio after the last run ends, and without runs nothing tells`() = runBlocking {
        capture("rec-34", startedAt = 1_000)
        // Continued an hour later: the second capture starts 200 ms into the audio.
        capture("rec-34", startedAt = 3_601_000)
        dir.mkdirs()
        File(dir, "rec-35.wav").writeBytes(Wav.header(6_400) + ByteArray(6_400))

        val inventory = store.inventory().associateBy { it.sessionId }

        assertEquals(3_601_000L + 200L, inventory.getValue("rec-34").lastCaptureEndMs)
        assertNull(inventory.getValue("rec-35").lastCaptureEndMs)
    }

    @Test
    fun `deleting compressed audio takes the copy, its index and what a compression left`() = runBlocking {
        capture("rec-36")
        store.compress("rec-36").getOrThrow()
        File(dir, "rec-36.m4a.new").writeText("part of a file")

        store.delete("rec-36")

        assertTrue(dir.listFiles().isNullOrEmpty())
        assertFalse(store.hasAudio("rec-36"))
    }

    @Test
    fun `compressed audio keeps the owner's choice, also through a new transcript's lines`() = runBlocking {
        capture("rec-37")
        store.compress("rec-37").getOrThrow()
        File(dir, "rec-37.idx").delete()

        // Its index was lost: keeping it writes a new one.
        store.setKept("rec-37", kept = true)
        store.replaceLines("rec-37", mapOf("new-1" to AudioSpan(0, 90)))

        val audio = checkNotNull(store.find("rec-37"))
        assertTrue(audio.kept)
        assertEquals(mapOf("new-1" to AudioSpan(0, 90)), audio.lines)
    }

    @Test
    fun `compressed audio that can't be read is not found, and its age is the recording's`() = runBlocking {
        dir.mkdirs()
        File(dir, "rec-38.m4a").writeText("not a copy")
        File(dir, "rec-38.idx").writeText(AudioIndex.run(AudioRun(startedAt = 1_000, startMs = 0)))

        assertNull(store.find("rec-38"))
        assertTrue(store.hasAudio("rec-38"))
        assertNull(store.inventory().single().lastCaptureEndMs)
    }

    @Test
    fun `compressing and deleting are told, so a screen reads the audio again`() = runBlocking {
        capture("rec-39")

        val afterCompress = async(start = CoroutineStart.UNDISPATCHED) { store.observeChanges().first() }
        store.compress("rec-39").getOrThrow()
        withTimeout(5_000) { afterCompress.await() }

        val afterDelete = async(start = CoroutineStart.UNDISPATCHED) { store.observeChanges().first() }
        store.delete("rec-39")
        withTimeout(5_000) { afterDelete.await() }
    }

    @Test
    fun `compressed audio is decoded into the cache for the recognizer, and a failed decode leaves nothing`() = runBlocking {
        capture("rec-40")
        store.compress("rec-40").getOrThrow()
        val audio = checkNotNull(store.find("rec-40"))

        val copy = store.decodedCopy(audio)

        assertEquals(File(decodedDir, "rec-40.wav"), copy)
        assertEquals(200L, Wav.durationMs(checkNotNull(Wav.dataBytes(copy))))
        assertEquals(listOf(File(audio.path)), codec.decoded)
        copy.delete()

        codec.decodeFailure = IOException("The decoder failed")
        assertTrue(runCatching { store.decodedCopy(audio) }.isFailure)
        assertFalse(File(decodedDir, "rec-40.wav").exists())
    }

    @Test
    fun `the copy a compression is writing is not taken for a leftover by an inventory meanwhile`() = runBlocking {
        capture("rec-41")
        // Settings reads the inventory again after each file of a run.
        codec.onEncode = { runBlocking { store.inventory() } }

        assertTrue(store.compress("rec-41").isSuccess)

        assertTrue(File(dir, "rec-41.m4a").isFile)
        assertFalse(File(dir, "rec-41.wav").exists())
    }

    @Test
    fun `audio the owner keeps is not compressed`() = runBlocking {
        capture("rec-42")
        store.setKept("rec-42", kept = true)
        val wav = File(dir, "rec-42.wav").readBytes()

        assertEquals(0L, store.compress("rec-42").getOrThrow())

        assertArrayEquals(wav, File(dir, "rec-42.wav").readBytes())
        assertFalse(File(dir, "rec-42.m4a").exists())
    }

    @Test
    fun `audio kept while it is being compressed stays as it was`() = runBlocking {
        capture("rec-43")
        val wav = File(dir, "rec-43.wav").readBytes()
        // "Keep this audio" while the encoder runs.
        codec.onEncode = { runBlocking { store.setKept("rec-43", kept = true) } }

        assertEquals(0L, store.compress("rec-43").getOrThrow())

        assertArrayEquals(wav, File(dir, "rec-43.wav").readBytes())
        assertFalse(File(dir, "rec-43.m4a").exists())
        assertFalse(File(dir, "rec-43.m4a.new").exists())
        val audio = checkNotNull(store.find("rec-43"))
        assertEquals(AudioEncoding.WAV, audio.encoding)
        assertTrue(audio.kept)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a WAV a capture adds to while it is being compressed stays, with all it holds`() = runBlocking {
        capture("rec-44")
        codec.onEncode = { File(dir, "rec-44.wav").appendBytes(ByteArray(3_200)) }

        assertTrue(store.compress("rec-44").isFailure)

        assertEquals(Wav.HEADER_BYTES + 6_400L + 3_200L, File(dir, "rec-44.wav").length())
        assertFalse(File(dir, "rec-44.m4a").exists())
        assertFalse(File(dir, "rec-44.m4a.new").exists())
    }

    @Test
    fun `the copy is written through to the storage before it takes the WAV's place`() = runBlocking {
        val synced = mutableListOf<String>()
        store = storeSyncing { file -> synced += "${file.name}, WAV there: ${File(dir, "rec-45.wav").exists()}" }
        capture("rec-45")

        store.compress("rec-45").getOrThrow()

        assertEquals(listOf("rec-45.m4a.new, WAV there: true"), synced)
        assertTrue(File(dir, "rec-45.m4a").isFile)
    }

    @Test
    fun `a copy that can't be written through to the storage is not kept`() = runBlocking {
        store = storeSyncing { throw SyncFailedException("The storage failed") }
        capture("rec-46")
        val wav = File(dir, "rec-46.wav").readBytes()

        assertTrue(store.compress("rec-46").isFailure)

        assertArrayEquals(wav, File(dir, "rec-46.wav").readBytes())
        assertFalse(File(dir, "rec-46.m4a").exists())
        assertFalse(File(dir, "rec-46.m4a.new").exists())
    }

    @Test
    fun `one recording's audio is read again as the inventory lists it`() = runBlocking {
        capture("rec-47", startedAt = 5_000)
        capture("rec-48")
        store.compress("rec-48").getOrThrow()
        store.setKept("rec-48", kept = true)

        val inventory = store.inventory().associateBy { it.sessionId }

        assertEquals(inventory.getValue("rec-47"), store.inventoryOf("rec-47"))
        assertEquals(inventory.getValue("rec-48"), store.inventoryOf("rec-48"))
        assertNull(store.inventoryOf("rec-none"))
    }

    @Test
    fun `the storage each recording's audio takes is told by the files' sizes`() = runBlocking {
        capture("rec-49")
        capture("rec-50")
        store.compress("rec-50").getOrThrow()
        // Not audio: a capture stopped before it heard anything, an index alone, and what a
        // compression cut short left.
        File(dir, "rec-51.wav").writeBytes(Wav.header(0))
        File(dir, "rec-52.idx").writeText(AudioIndex.keep(true))
        File(dir, "rec-49.m4a.new").writeText("part of a file")

        assertEquals(
            mapOf(
                "rec-49" to File(dir, "rec-49.wav").length() + File(dir, "rec-49.idx").length(),
                "rec-50" to File(dir, "rec-50.m4a").length() + File(dir, "rec-50.idx").length()
            ),
            store.storedBytes()
        )
        assertEquals(store.inventory().associate { it.sessionId to it.bytes }, store.storedBytes())
    }

    @Test
    fun `a decoding cancelled half way leaves nothing behind`() = runBlocking {
        capture("rec-53")
        store.compress("rec-53").getOrThrow()
        val audio = checkNotNull(store.find("rec-53"))
        val decoding = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { store.decodedCopy(audio) }
        codec.onDecode = { decoding.cancel() }

        decoding.start()
        decoding.join()

        assertTrue(decoding.isCancelled)
        assertTrue(decodedDir.listFiles().isNullOrEmpty())
    }
}
