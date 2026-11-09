package com.satori.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/**
 * [ReaderEngine] and [ReaderTokenizer] through the real JNI: reader_jni.cpp and the native core
 * built for macOS against ONNX Runtime 1.28.2's macOS library (src/test/cpp, READER_JNI=ON), on the
 * toy graphs, reference and toy SentencePiece model of src/test/python. run_host_tests.sh makes
 * all of them and runs this with SATORI_READER_HOST_LIB_DIR (the folder of the .dylib, which the
 * Gradle test task puts on java.library.path) and SATORI_READER_TOYS (its work folder) set;
 * without them, as in an ordinary Gradle run, the tests are skipped.
 */
class ReaderEngineHostTest {

    companion object {
        private val library: String? = System.getenv("SATORI_READER_HOST_LIB_DIR")
        private val toys: File? = System.getenv("SATORI_READER_TOYS")?.let(::File)

        private fun graphs(variant: String): ReaderGraphs {
            val folder = File(toys, "models/$variant")
            return ReaderGraphs(
                File(folder, "encoder_model.onnx"),
                File(folder, "decoder_model.onnx"),
                File(folder, "decoder_with_past_model.onnx"),
            )
        }

        private val TOY = ReaderOptions(sourceVocabulary = 64)

        private fun source(pieces: Int): IntArray = IntArray(pieces + 3) { i ->
            when (i) {
                0 -> 4
                1 -> 5
                pieces + 2 -> 2
                else -> 6 + (i * 7) % 58
            }
        }

        private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun assumeHost() {
        assumeTrue("SATORI_READER_HOST_LIB_DIR and SATORI_READER_TOYS not set", library != null && toys != null)
    }

    private inline fun <reified T : ReaderException> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: ReaderException) {
            assertTrue("expected ${T::class.simpleName}, got $e", e is T)
            return e as T
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }

    @Test
    fun everyReferenceCaseComesThroughTheJniIdentical() {
        assumeHost()
        val engines = mutableMapOf<String, ReaderEngine>()
        var cases = 0
        try {
            File(toys, "expected.tsv").readLines().filter { it.isNotEmpty() }.forEach { line ->
                val field = line.split('\t')
                val engine = engines.getOrPut(field[1]) { ReaderEngine().also { it.load(graphs(field[1]), TOY) } }
                val ids = field[4].split(' ').map(String::toInt).toIntArray()
                val reading = engine.translate(ids, field[2].toInt(), field[3].toInt(), minTokens = field[9].toInt())
                val expected = if (field[5].isEmpty()) IntArray(0) else field[5].split(' ').map(String::toInt).toIntArray()
                assertArrayEquals("case ${field[0]}", expected, reading.ids)
                assertEquals("case ${field[0]} mean", field[6].toDouble(), reading.meanLogProb, 0.0)
                assertEquals("case ${field[0]} capped", field[7] == "1", reading.capped)
                assertEquals("case ${field[0]} runs", field[8].toInt(), reading.steps)
                cases++
            }
            assertEquals(ReaderDims(2, 4, 12), engines.getValue("toy").dims())
        } finally {
            engines.values.forEach(ReaderEngine::release)
        }
        assertTrue("only $cases cases", cases >= 60)
    }

    @Test
    fun statusesBecomeExceptions() {
        assumeHost()
        ReaderEngine().use { idle ->
            assertThrows<ReaderException.NotLoaded> { idle.translate(source(4)) }
            val missing = graphs("toy").copy(encoder = File(toys, "missing.onnx"))
            val failed = assertThrows<ReaderException.LoadFailed> { idle.load(missing, TOY) }
            assertTrue(failed.message!!.isNotEmpty())
            assertThrows<ReaderException.BadInput> { idle.load(graphs("toy"), TOY.copy(threads = 0)) }
        }
        ReaderEngine().use { wrong ->
            assertThrows<ReaderException.BadModel> { wrong.load(graphs("layers3"), TOY) }
        }
        ReaderEngine().use { broken ->
            broken.load(graphs("presenttwice"), TOY)
            assertThrows<ReaderException.RunFailed> { broken.translate(source(5)) }
        }
        ReaderEngine().use { engine ->
            engine.load(graphs("toy"), TOY)
            assertThrows<ReaderException.BadInput> { engine.translate(IntArray(0)) }
            assertThrows<ReaderException.BadInput> { engine.translate(source(4), beam = 9) }
            assertThrows<ReaderException.BadInput> { engine.translate(source(254)) }
            assertThrows<ReaderException.BadInput> { engine.translate(intArrayOf(4, 5, 64, 2)) }
            // The framing: </s> last, and only there.
            assertThrows<ReaderException.BadInput> { engine.translate(intArrayOf(4, 5, 7)) }
            assertThrows<ReaderException.BadInput> { engine.translate(source(2) + source(2)) }
            assertThrows<ReaderException.BadInput> { engine.translate(source(4), minTokens = -1) }
            assertTrue(engine.translate(source(4)).ids.isNotEmpty())
            engine.release()
            assertThrows<ReaderException.NotLoaded> { engine.translate(source(4)) }
            engine.release()
            engine.cancel()
        }
    }

    @Test
    fun cancelStopsARunFromAnotherThread() {
        assumeHost()
        ReaderEngine().use { engine ->
            engine.load(graphs("slowrun"), TOY)
            var outcome: Throwable? = null
            var returned = 0L
            val worker = thread {
                outcome = runCatching { engine.translate(source(253)) }.exceptionOrNull()
                returned = System.nanoTime()
            }
            Thread.sleep(200)
            val cancelled = System.nanoTime()
            engine.cancel()
            worker.join()
            assertTrue("got $outcome", outcome is ReaderException.Cancelled)
            val ms = (returned - cancelled) / 1e6
            assertTrue("returned $ms ms after cancel()", ms < 100)
            assertThrows<ReaderException.Cancelled> { engine.translate(source(3)) }
        }
    }

    @Test
    fun theTokenizerGivesTheWheelsPieces() {
        assumeHost()
        ReaderTokenizer().use { tokenizer ->
            assertThrows<ReaderException.NotLoaded> { tokenizer.pieces("x") }
            assertThrows<ReaderException.LoadFailed> { tokenizer.load(File(toys, "models/toy/decoder_model.onnx")) }
            tokenizer.load(File(toys, "spm/toy.model"))
            var lines = 0
            File(toys, "spm/pieces.tsv").readLines().filter { it.isNotEmpty() }.forEach { line ->
                val (text, pieces) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
                val expected = pieces.split(' ').filter { it.isNotEmpty() }.map { hex(it).decodeToString() }
                assertEquals("line $lines", expected, tokenizer.pieces(hex(text).decodeToString()))
                lines++
            }
            assertEquals(57, lines)
            // A lone surrogate becomes '?' in Kotlin's UTF-8, so the native side sees valid UTF-8.
            assertTrue(tokenizer.pieces("a\uD800b").isNotEmpty())
        }
    }
}
