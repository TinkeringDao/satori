package com.satori.data.datasource

import android.content.Context
import android.content.ContextWrapper
import com.satori.data.config.SpeechModelFile
import com.satori.data.config.SpeechModels
import com.satori.domain.model.DownloadException
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The speech model downloads: every file checked against its size and SHA-256 before it takes its
 * name, and said to be checked while it is; a stopped download continued with a Range request,
 * also after a connection that hung or was reset, which are no connection, not a damaged file;
 * files already whole left alone; and the folders of the models, which Parakeet v3's deletion
 * leaves to the others.
 */
class SpeechModelDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TestFileServer
    private lateinit var manager: ModelDownloadManager

    // What the server serves, by path; what it was asked, in order. The server answers on a
    // thread of its own.
    private val served = ConcurrentHashMap<String, ByteArray>()
    private val requests = CopyOnWriteArrayList<String>()
    private var ignoreRange = false
    private var cutAfter: Int? = null

    // A connection that hangs, or is reset, after this many bytes of its answer.
    private var stallAfter: Int? = null
    private var resetAfter: Int? = null

    private val encoder = Random(1).nextBytes(300_000).also { it[0] = 0x08 }
    private val tokens = "<blk> 0\n▁the 1\n".toByteArray()

    @Before
    fun startServer() {
        server = TestFileServer { request ->
            requests += "${request.path} ${request.range ?: "whole"}"
            val body = served[request.path] ?: return@TestFileServer TestFileServer.Answer(404)
            val from = request.range?.takeUnless { ignoreRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val part = body.copyOfRange(from, body.size)
            val status = if (from > 0) 206 else 200
            val headers = if (from > 0) mapOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}") else emptyMap()
            val cut = cutAfter
            val stall = stallAfter
            val reset = resetAfter
            when {
                // A cut-off answer is sent without a length, so it ends cleanly, short of the file.
                cut != null -> TestFileServer.Answer(status, part.copyOf(minOf(cut, part.size)), headers, declaresLength = false)
                // These promise the whole rest of the file and send less.
                stall != null -> TestFileServer.Answer(
                    status, part.copyOf(minOf(stall, part.size)), headers, length = part.size.toLong(),
                    end = TestFileServer.End.STALL, stallMs = 2_000L
                )
                reset != null -> TestFileServer.Answer(
                    status, part.copyOf(minOf(reset, part.size)), headers, length = part.size.toLong(),
                    end = TestFileServer.End.RESET
                )
                else -> TestFileServer.Answer(status, part, headers)
            }
        }
        manager = ModelDownloadManager(ExternalFilesContext(tmp.root))
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun url(name: String) = "http://127.0.0.1:${server.port}/m/$name"

    private fun fileOf(name: String, content: ByteArray, format: ModelFileFormat = ModelFileFormat.Text): SpeechModelFile {
        served["/m/$name"] = content
        return SpeechModelFile(ModelFileSpec(name, format, bytes = content.size.toLong()), url(name), sha256(content))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val dir get() = File(tmp.root, "models/stt/test-model")

    private fun download(vararg files: SpeechModelFile) = runBlocking {
        manager.downloadFiles(dir, files.toList(), "test-model").toList()
    }

    @Test
    fun `every file is downloaded, checked and given its name`() {
        val progress = download(fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx), fileOf("tokens.txt", tokens))

        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
        assertArrayEquals(tokens, File(dir, "tokens.txt").readBytes())
        assertFalse(File(dir, "encoder.int8.onnx.partial").exists())
        assertEquals(100, progress.last().percentage)
        assertEquals((encoder.size + tokens.size).toLong(), progress.last().bytesDownloaded)
        // Never backwards, and done only at the end.
        val bytes = progress.map { it.bytesDownloaded }
        assertEquals(bytes.sorted(), bytes)
        assertEquals(listOf(100), progress.map { it.percentage }.filter { it == 100 })
    }

    @Test
    fun `each file says it is being checked once its bytes are in, and the next numbers say it is done`() {
        val progress = download(fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx), fileOf("tokens.txt", tokens))

        val checking = progress.filter { it.verifying }
        assertEquals(listOf(encoder.size.toLong(), (encoder.size + tokens.size).toLong()), checking.map { it.bytesDownloaded })
        // Each check is followed by numbers that aren't one, and the download doesn't end on one.
        progress.forEachIndexed { index, step ->
            if (step.verifying) assertFalse("after $index", progress[index + 1].verifying)
        }
        assertFalse(progress.last().verifying)
    }

    @Test
    fun `a connect is given up after 5 s and a stalled read after 15 s, so a firewall reads as blocked within 30 s`() {
        // The first run's two failures in a row (NetworkPolicy.BLOCKED_AFTER): the read under way
        // stalls, then the retry 5 s later can't connect (design C4).
        assertEquals(5_000, manager.connectTimeoutMs)
        assertEquals(15_000, manager.readTimeoutMs)
    }

    @Test
    fun `a connection that hangs fails as no connection and keeps what it got`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        manager.readTimeoutMs = 300
        stallAfter = 120_000

        val failure = runCatching { download(file) }.exceptionOrNull()

        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        assertEquals(120_000L, File(dir, "encoder.int8.onnx.partial").length())
        assertFalse(File(dir, "encoder.int8.onnx").exists())

        // The next try goes on from there.
        stallAfter = null
        requests.clear()
        download(file)
        assertEquals(listOf("/m/encoder.int8.onnx bytes=120000-"), requests)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a connection that is reset fails as no connection, not as a damaged file, and goes on from where it was`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        resetAfter = 100_000

        val failure = runCatching { download(file) }.exceptionOrNull()

        // Malayalam is dropped after two damaged files; a cut connection must never count as one.
        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        // A reset may throw away what hadn't been read yet; what was read stays.
        val kept = File(dir, "encoder.int8.onnx.partial").length()
        assertTrue("kept $kept bytes", kept in 0L..100_000L)

        resetAfter = null
        requests.clear()
        download(file)
        assertEquals(listOf("/m/encoder.int8.onnx " + if (kept > 0) "bytes=$kept-" else "whole"), requests)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a download goes on from what an earlier one left`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        dir.mkdirs()
        File(dir, "encoder.int8.onnx.partial").writeBytes(encoder.copyOf(100_000))

        download(file)

        assertEquals(listOf("/m/encoder.int8.onnx bytes=100000-"), requests)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a server that ignores the range sends the file from the start, which replaces the part`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        dir.mkdirs()
        File(dir, "encoder.int8.onnx.partial").writeBytes(encoder.copyOf(100_000))
        ignoreRange = true

        download(file)

        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a part longer than the file is no start for it, so it goes and the file is fetched whole`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        dir.mkdirs()
        File(dir, "encoder.int8.onnx.partial").writeBytes(encoder + ByteArray(10))

        download(file)

        assertEquals(listOf("/m/encoder.int8.onnx whole"), requests)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `an answer of another length, a login page say, keeps the part for the next try`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        dir.mkdirs()
        val part = encoder.copyOf(100_000)
        File(dir, "encoder.int8.onnx.partial").writeBytes(part)
        // A network that wants a login first answers every address with 200 and its own page.
        served["/m/encoder.int8.onnx"] = "<html>Log in to use this network</html>".toByteArray()
        ignoreRange = true

        val failure = runCatching { download(file) }.exceptionOrNull()

        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        assertArrayEquals(part, File(dir, "encoder.int8.onnx.partial").readBytes())
        assertFalse(File(dir, "encoder.int8.onnx").exists())
    }

    @Test
    fun `a file whose SHA-256 is not the registry's is deleted, and the download fails`() {
        val file = fileOf("tokens.txt", tokens).copy(sha256 = sha256("other".toByteArray()))

        val failure = runCatching { download(file) }.exceptionOrNull()

        assertEquals(DownloadFailure.OTHER, (failure as? DownloadException)?.failure)
        assertFalse(File(dir, "tokens.txt").exists())
        assertFalse(File(dir, "tokens.txt.partial").exists())
    }

    @Test
    fun `a download that ends early keeps what it got for the next try`() {
        val file = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        cutAfter = 120_000

        try {
            download(file)
            fail("a cut-off download finished")
        } catch (e: DownloadException) {
            assertEquals(DownloadFailure.NO_CONNECTION, e.failure)
        }
        assertFalse(File(dir, "encoder.int8.onnx").exists())
        assertTrue(File(dir, "encoder.int8.onnx.partial").length() > 0)

        cutAfter = null
        download(file)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a file already there whole is not fetched again, one of another size is`() {
        val whole = fileOf("tokens.txt", tokens)
        val wrong = fileOf("encoder.int8.onnx", encoder, ModelFileFormat.Onnx)
        dir.mkdirs()
        File(dir, "tokens.txt").writeBytes(tokens)
        File(dir, "encoder.int8.onnx").writeBytes(encoder.copyOf(1_000))

        download(whole, wrong)

        assertEquals(listOf("/m/encoder.int8.onnx whole"), requests)
        assertArrayEquals(encoder, File(dir, "encoder.int8.onnx").readBytes())
    }

    @Test
    fun `a server error is the server's failure`() {
        val missing = SpeechModelFile(ModelFileSpec("gone.txt", ModelFileFormat.Text, bytes = 10L), url("gone.txt"), sha256(ByteArray(10)))

        val failure = runCatching { download(missing) }.exceptionOrNull()

        assertEquals(DownloadFailure.SERVER, (failure as? DownloadException)?.failure)
    }

    // ── Folders ──────────────────────────────────────────────────────────────

    @Test
    fun `Parakeet v3 is in the speech folder itself, the others in folders of their own`() {
        val stt = File(tmp.root, "models/stt")

        assertEquals(stt, manager.speechModelDir(SpeechModelId.PARAKEET_V3))
        assertEquals(File(stt, "parakeet-tdt-0.6b-v2-int8"), manager.speechModelDir(SpeechModelId.PARAKEET_V2_EN))
        assertEquals(File(stt, "cohere-transcribe-14-lang-int8"), manager.speechModelDir(SpeechModelId.COHERE_TRANSCRIBE))
    }

    @Test
    fun `deleting Parakeet v3 leaves the other models and the vocabulary`() {
        placeFiles(SpeechModelId.PARAKEET_V3)
        placeFiles(SpeechModelId.PARAKEET_V2_EN)
        val vocabulary = File(manager.speechModelDir(SpeechModelId.PARAKEET_V3), "bpe.vocab").apply { writeText("▁the\t-1.0\n") }

        manager.deleteSttModel(SpeechModelId.PARAKEET_V3)

        assertFalse(manager.isSttModelDownloaded(SpeechModelId.PARAKEET_V3))
        assertTrue(manager.isSttModelDownloaded(SpeechModelId.PARAKEET_V2_EN))
        assertTrue(vocabulary.exists())
    }

    @Test
    fun `deleting another model takes its folder, partial downloads too`() {
        placeFiles(SpeechModelId.COHERE_TRANSCRIBE)
        val partial = File(manager.speechModelDir(SpeechModelId.COHERE_TRANSCRIBE), "encoder.int8.onnx.data.partial").apply { writeText("part") }

        manager.deleteSttModel(SpeechModelId.COHERE_TRANSCRIBE)

        assertFalse(partial.exists())
        assertFalse(manager.speechModelDir(SpeechModelId.COHERE_TRANSCRIBE).exists())
        assertTrue(File(tmp.root, "models/stt").exists())
    }

    @Test
    fun `the bytes on the phone count whole files and partial ones`() {
        val cohere = manager.speechModelDir(SpeechModelId.COHERE_TRANSCRIBE).apply { mkdirs() }
        File(cohere, "tokens.txt").writeBytes(ByteArray(207))
        File(cohere, "encoder.int8.onnx.data.partial").writeBytes(ByteArray(1_000))

        assertEquals(1_207L, manager.speechModelBytesOnPhone(SpeechModelId.COHERE_TRANSCRIBE))
        assertEquals(0L, manager.speechModelBytesOnPhone(SpeechModelId.PARAKEET_V2_EN))
    }

    @Test
    fun `the vocabulary counts only when it is whole`() {
        val file = File(manager.speechModelDir(SpeechModelId.PARAKEET_V3).apply { mkdirs() }, "bpe.vocab")
        assertNull(manager.speechVocabularyFile())

        file.writeText("too short")
        assertNull(manager.speechVocabularyFile())

        file.writeBytes(ByteArray(117_408))
        assertEquals(file, manager.speechVocabularyFile())
    }

    /** Files of [model]'s names, not empty, as a download would leave them as far as existence goes. */
    private fun placeFiles(model: SpeechModelId) {
        val folder = manager.speechModelDir(model).apply { mkdirs() }
        SpeechModels.of(model).specs.forEach { File(folder, it.name).writeText("x") }
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
