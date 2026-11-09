package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

/**
 * The pre-read of a model's files: what isn't cached is read in at the pace, a piece already cached
 * costs nothing, a file whose cache can't be seen is read in full, a load without MAP_POPULATE reads
 * only its ranges, and once the pace has waited for memory as long as it may it stops, or reads on
 * at the rate for the recorder's own load.
 */
class PacedPreReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var nowNs = 0L

    // 1 MB/s, so 1 KiB takes 1/1024 s.
    private fun pace(memFree: () -> Long? = { 4_000L }) =
        Pace(1, { memFree()?.let { FreeMemory(it, cachedMb = null) } }, mustYield = { false }, clockNs = { nowNs }, sleepNs = { nowNs += it })

    private fun file(name: String, bytes: Int): File = tmp.newFile(name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun whole(vararg files: File) = files.map { PacedPreRead.Part(it) }

    /**
     * The pieces of each file (by name) that are cached: index of the 1 KiB piece -> bytes missing;
     * a file it doesn't know is [FileResidency.Unseen].
     */
    private class KnownCache(private val missing: Map<String, Map<Long, Long>>) : FileResidency {
        val opened = mutableListOf<String>()
        val asked = mutableListOf<Pair<Long, Long>>()
        override fun open(file: File): FileResidency.View {
            val pieces = missing[file.name] ?: return FileResidency.Unseen("not known")
            opened += file.name
            return object : FileResidency.Cached {
                override fun missingBytes(offset: Long, length: Long): Long {
                    asked += offset to length
                    return pieces[offset / CHUNK] ?: length
                }
                override fun close() {}
            }
        }
    }

    @Test
    fun `what isn't cached is read in at the pace, and a piece that is cached costs nothing`() {
        val model = file("model.gguf", 4 * CHUNK.toInt())
        val cache = KnownCache(mapOf("model.gguf" to mapOf(1L to 0L, 3L to CHUNK / 2)))

        val outcome = PacedPreRead(cache, chunkBytes = CHUNK.toInt()).read(whole(model), pace())

        // Pieces 0 and 2 whole, 1 cached, half of 3.
        assertEquals(CHUNK * 2 + CHUNK / 2, outcome.newBytes)
        assertEquals(4 * CHUNK, outcome.totalBytes)
        assertFalse(outcome.gaveUp)
        assertTrue(outcome.unreadable.isEmpty())
        assertTrue(outcome.unseen.isEmpty())
        // 2.5 KiB at 1 MB/s.
        assertEquals((2.5 / 1024 * 1e9).toLong(), nowNs)
        assertEquals(listOf("model.gguf"), cache.opened)
    }

    @Test
    fun `a file whose cached pages can't be seen is read and paced in full, file after file, and named`() {
        val encoder = file("encoder.int8.onnx", 3 * CHUNK.toInt())
        val tokens = file("tokens.txt", 100)

        val outcome = PacedPreRead(KnownCache(emptyMap()), chunkBytes = CHUNK.toInt()).read(whole(encoder, tokens), pace())

        assertEquals(3 * CHUNK + 100, outcome.newBytes)
        assertEquals(3 * CHUNK + 100, outcome.totalBytes)
        assertEquals(((3 * CHUNK + 100) / 1_048_576.0 * 1e9).toLong(), nowNs)
        assertEquals(listOf("encoder.int8.onnx" to "not known", "tokens.txt" to "not known"), outcome.unseen)
    }

    @Test
    fun `it stops once the pace has waited for memory as long as it may`() {
        val model = file("model.gguf", 4 * CHUNK.toInt())

        val outcome = PacedPreRead(KnownCache(emptyMap()), chunkBytes = CHUNK.toInt()).read(whole(model), pace(memFree = { 100L }))

        assertTrue(outcome.gaveUp)
        assertEquals(0L, outcome.newBytes)
        assertEquals(4 * CHUNK, outcome.totalBytes)
        assertEquals(Pace.GIVE_UP_MS * 1_000_000L, nowNs)
    }

    @Test
    fun `a load without MAP_POPULATE reads only its ranges, widened to whole blocks and joined`() {
        val block = PacedPreRead.ALIGN_BYTES
        val model = tmp.newFile("model.gguf").apply { writeBytes(ByteArray((10 * block).toInt())) }
        val cache = KnownCache(mapOf("model.gguf" to emptyMap()))
        // A norm of 1 KiB in block 1, a matrix over blocks 3-5 that touches the norm's neighbour in
        // block 4, and the end of the file.
        val ranges = listOf(
            (block + 100) until (block + 1_124),
            (3 * block + 10) until (5 * block + 20),
            (4 * block) until (4 * block + 50),
            (9 * block + 5) until (10 * block)
        )

        val outcome = PacedPreRead(cache, chunkBytes = (2 * block).toInt()).read(listOf(PacedPreRead.Part(model, ranges)), pace())

        // Block 1, blocks 3-5 and block 9.
        assertEquals(5 * block, outcome.totalBytes)
        assertEquals(5 * block, outcome.newBytes)
        assertEquals(
            listOf(block to block, 3 * block to 2 * block, 5 * block to block, 9 * block to block),
            cache.asked
        )
    }

    @Test
    fun `a file it can't read is named, and the others are read in all the same`() {
        val model = file("model.gguf", CHUNK.toInt())
        val gone = File(tmp.root, "decoder.int8.onnx")

        val outcome = PacedPreRead(KnownCache(mapOf("model.gguf" to emptyMap())), chunkBytes = CHUNK.toInt())
            .read(whole(gone, model), pace())

        assertEquals(listOf("decoder.int8.onnx (FileNotFoundException)"), outcome.unreadable)
        assertTrue(outcome.unseen.isEmpty())
        assertEquals(CHUNK, outcome.newBytes)
    }

    @Test
    fun `a pre-read whose load was cancelled stops at its next piece`() {
        val model = file("model.gguf", 4 * CHUNK.toInt())
        var pieces = 0

        try {
            PacedPreRead(KnownCache(emptyMap()), chunkBytes = CHUNK.toInt()).read(whole(model), pace(), cancelled = { pieces++ >= 2 })
            fail("not cancelled")
        } catch (e: CancellationException) {
            // Two pieces were read before it.
            assertEquals((2.0 / 1024 * 1e9).toLong(), nowNs)
        }
    }

    @Test
    fun `mincore is trusted only for a file the app owns or may write, as Linux tells only those the truth`() {
        assertTrue(mincoreSeesPages(fileUid = 10_123, processUid = 10_123, writable = { error("not asked") }))
        assertTrue(mincoreSeesPages(fileUid = 0, processUid = 10_123, writable = { true }))
        assertFalse(mincoreSeesPages(fileUid = 0, processUid = 10_123, writable = { false }))
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
