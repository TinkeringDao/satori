package com.satori.data.datasource

import android.content.Context
import android.content.ContextWrapper
import com.satori.data.config.ReaderModelFile
import com.satori.data.config.ReaderModels
import com.satori.domain.firstrun.FilesCheck
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import com.satori.domain.modelfile.ModelFileState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The reader's files as a model kind of their own: each build in its own folder under
 * `models/reader`, downloaded by the speech models' download (each file's size and SHA-256 before
 * it takes its name, Range resume), checked before a load by its size and each graph's first
 * bytes, and hashed whole against its pin when a debug tool asks. Since 0.8.0's R7 the first run
 * counts them once each was found with its pin: by the download as it fetched it, or by one read of
 * files that came another way, which a recorder stops.
 */
class ReaderModelDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TestFileServer
    private lateinit var manager: ModelDownloadManager
    private val served = ConcurrentHashMap<String, ByteArray>()

    @Before
    fun start() {
        server = TestFileServer { request ->
            val body = served[request.path] ?: return@TestFileServer TestFileServer.Answer(404)
            val from = request.range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val headers = if (from > 0) mapOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}") else emptyMap()
            TestFileServer.Answer(if (from > 0) 206 else 200, body.copyOfRange(from, body.size), headers)
        }
        manager = ModelDownloadManager(ExternalFilesContext(tmp.root))
    }

    @After
    fun stop() {
        server.close()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun served(name: String, content: ByteArray, format: ModelFileFormat): ReaderModelFile {
        served["/r/$name"] = content
        return ReaderModelFile(ModelFileSpec(name, format, bytes = content.size.toLong()), "http://127.0.0.1:${server.port}/r/$name", sha256(content))
    }

    /** Every file of [model] with its registry size, an ONNX graph starting as one does: sparse, so nothing is written. */
    private fun placeWhole(model: ReaderModelId) {
        val dir = manager.readerModelDir(model).apply { mkdirs() }
        for (file in ReaderModels.of(model).files) {
            RandomAccessFile(File(dir, file.spec.name), "rw").use { raf ->
                raf.setLength(checkNotNull(file.spec.bytes))
                if (file.spec.format == ModelFileFormat.Onnx) raf.write(0x08)
            }
        }
    }

    @Test
    fun `each build has a folder of its own under models reader, as their graphs have the same names`() {
        val int8 = manager.readerModelDir(ReaderModelId.INT8)
        val fp16 = manager.readerModelDir(ReaderModelId.FP16)

        assertEquals(File(tmp.root, "models/reader/indictrans2-indic-en-dist-200m-int8"), int8)
        assertEquals(File(tmp.root, "models/reader/indictrans2-indic-en-dist-200m-fp16"), fp16)
        assertEquals(ReaderModels.INT8.files.map { it.spec.name }, ReaderModels.FP16.files.map { it.spec.name })
    }

    @Test
    fun `the reader's files download as a speech model's do, each checked before it takes its name`() {
        val encoder = Random(3).nextBytes(200_000).also { it[0] = 0x08 }
        val vocabulary = "{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2}".toByteArray()
        val dir = manager.readerModelDir(ReaderModelId.INT8)

        val progress = runBlocking {
            manager.downloadFiles(
                dir,
                listOf(served("encoder_model.onnx", encoder, ModelFileFormat.Onnx), served("dict.SRC.json", vocabulary, ModelFileFormat.Text)),
                ReaderModelId.INT8.id,
                kind = "Reader"
            ).toList()
        }

        assertArrayEquals(encoder, File(dir, "encoder_model.onnx").readBytes())
        assertArrayEquals(vocabulary, File(dir, "dict.SRC.json").readBytes())
        assertFalse(File(dir, "encoder_model.onnx.partial").exists())
        assertEquals(100, progress.last().percentage)
        assertTrue(progress.any { it.verifying })
    }

    @Test
    fun `a build whose every file has its size and its graphs the first bytes of one is intact`() {
        placeWhole(ReaderModelId.INT8)

        assertTrue(manager.isReaderModelDownloaded(ReaderModelId.INT8))
        assertEquals(ModelFileState.Intact, manager.checkReaderModel(ReaderModelId.INT8))
        assertFalse(manager.isReaderModelDownloaded(ReaderModelId.FP16))
        assertEquals(ModelFileState.Missing, manager.checkReaderModel(ReaderModelId.FP16))
    }

    @Test
    fun `a graph that doesn't start as one, a file of another size and a missing file are told apart`() {
        placeWhole(ReaderModelId.INT8)
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        RandomAccessFile(File(dir, ReaderModels.DECODER), "rw").use { it.write('<'.code) }
        assertEquals(ModelFileState.Damaged, manager.checkReaderModel(ReaderModelId.INT8))

        placeWhole(ReaderModelId.INT8)
        RandomAccessFile(File(dir, ReaderModels.SOURCE_VOCABULARY), "rw").use { it.setLength(it.length() - 1) }
        assertEquals(ModelFileState.Damaged, manager.checkReaderModel(ReaderModelId.INT8))

        placeWhole(ReaderModelId.INT8)
        File(dir, ReaderModels.SOURCE_PIECES).delete()
        assertEquals(ModelFileState.Missing, manager.checkReaderModel(ReaderModelId.INT8))
        assertFalse(manager.isReaderModelDownloaded(ReaderModelId.INT8))
    }

    @Test
    fun `the hashes say which files are the pinned ones, and which are missing`() = runBlocking {
        val dir = manager.readerModelDir(ReaderModelId.INT8).apply { mkdirs() }
        // The export's generation_config.json at commit a7ecdc0b, byte for byte (MIT, its card).
        File(dir, "generation_config.json").writeText(
            "{\n  \"_from_model_config\": true,\n  \"bos_token_id\": 0,\n  \"decoder_start_token_id\": 2,\n" +
                "  \"eos_token_id\": 2,\n  \"pad_token_id\": 1,\n  \"transformers_version\": \"4.32.1\"\n}\n"
        )
        RandomAccessFile(File(dir, "config.json"), "rw").use { it.setLength(1_370L) }

        val hashes = manager.hashReaderModel(ReaderModelId.INT8, listOf("generation_config.json", "config.json", "model.SRC"))
            .associateBy { it.name }

        assertEquals(3, hashes.size)
        assertTrue(hashes.getValue("generation_config.json").matches)
        assertEquals(ReaderModels.INT8.file("generation_config.json").sha256, hashes.getValue("generation_config.json").sha256)
        assertFalse(hashes.getValue("config.json").matches)
        assertNotEquals(null, hashes.getValue("config.json").sha256)
        assertNull(hashes.getValue("model.SRC").sha256)
        assertFalse(hashes.getValue("model.SRC").matches)
    }

    @Test
    fun `deleting a build takes its folder, and leaves the other build`() {
        placeWhole(ReaderModelId.INT8)
        val other = manager.readerModelDir(ReaderModelId.FP16).apply { mkdirs() }
        File(other, "config.json").writeText("{}")
        val changes = manager.modelFilesChanged.value

        manager.deleteReaderModel(ReaderModelId.INT8)

        assertFalse(manager.readerModelDir(ReaderModelId.INT8).exists())
        assertTrue(File(other, "config.json").exists())
        assertEquals(changes + 1, manager.modelFilesChanged.value)
    }

    @Test
    fun `the bytes on the phone count whole files and partial ones`() {
        val dir = manager.readerModelDir(ReaderModelId.INT8).apply { mkdirs() }
        File(dir, "config.json").writeBytes(ByteArray(100))
        File(dir, "encoder_model.onnx.data.partial").writeBytes(ByteArray(250))

        assertEquals(350L, manager.readerModelBytesOnPhone(ReaderModelId.INT8))
        assertEquals(0L, manager.readerModelBytesOnPhone(ReaderModelId.FP16))
    }

    // ── The pinned SHA-256, read once (0.8.0, design R7) ─────────────────────

    /** Two files of a reader as a test serves them: a graph and a vocabulary, with their sizes and hashes. */
    private fun twoFiles(): List<ReaderModelFile> = listOf(
        served("encoder_model.onnx", Random(5).nextBytes(150_000).also { it[0] = 0x08 }, ModelFileFormat.Onnx),
        served("dict.SRC.json", "{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"x\": 3}".toByteArray(), ModelFileFormat.Text)
    )

    /** [files] put into [dir] another way than the reader's download: whole, the debug tool's, or by hand. */
    private fun putThere(dir: File, files: List<ReaderModelFile>) {
        dir.mkdirs()
        for (file in files) File(dir, file.spec.name).writeBytes(checkNotNull(served["/r/${file.spec.name}"]))
    }

    @Test
    fun `the reader's download keeps each file it fetched as checked, so the build counts at once, nothing read again`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)

        runBlocking { manager.downloadReaderFiles(dir, files, ReaderModelId.INT8.id).toList() }

        assertTrue(manager.filesVerified(dir, files))
        var asked = 0
        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { asked++; false })
        assertEquals("no file was read again", 0, asked)
        assertTrue(File(dir, VerifiedFiles.NAME).isFile)
    }

    @Test
    fun `files put there another way count only once their SHA-256 was read, which happens once`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        putThere(dir, files)

        // Whole by their sizes and first bytes, but never read: not yet.
        assertFalse(manager.filesVerified(dir, files))
        var asked = 0
        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { asked++; false })
        assertTrue("both files were read", asked >= 2)
        assertTrue(manager.filesVerified(dir, files))

        asked = 0
        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { asked++; false })
        assertEquals("the second check reads nothing", 0, asked)
    }

    @Test
    fun `a recorder that starts stops the read, which goes on later from the file it stopped at`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        putThere(dir, files)

        assertEquals(FilesCheck.NOT_NOW, manager.verifyFiles(dir, files, "Reader test") { true })
        assertFalse(manager.filesVerified(dir, files))

        // The graph is read, then a recorder starts before the vocabulary.
        var reads = 0
        assertEquals(FilesCheck.NOT_NOW, manager.verifyFiles(dir, files, "Reader test") { reads++ > 0 && File(dir, VerifiedFiles.NAME).isFile })
        assertEquals(setOf("encoder_model.onnx"), VerifiedFiles.read(dir).keys)

        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { false })
        assertTrue(manager.filesVerified(dir, files))
    }

    @Test
    fun `a file that isn't the pinned one fails the check and is left for the first run to discard`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        putThere(dir, files)
        // Of its size, starting as a vocabulary does, but other bytes.
        File(dir, "dict.SRC.json").writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"y\": 3}")

        assertEquals(FilesCheck.FAILED, manager.verifyFiles(dir, files, "Reader test") { false })
        assertFalse(manager.filesVerified(dir, files))
        assertTrue(File(dir, "dict.SRC.json").isFile)
    }

    @Test
    fun `a file found not to be the pinned one is not read again until it changes`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        putThere(dir, files)
        val vocabulary = File(dir, "dict.SRC.json")
        val pinned = vocabulary.readBytes()
        vocabulary.writeText("{\"<s>\": 0, \"<pad>\": 1, \"</s>\": 2, \"y\": 3}")
        assertEquals(FilesCheck.FAILED, manager.verifyFiles(dir, files, "Reader test") { false })

        // Looked at again a few seconds later, as the first run does while the reader is pending.
        var asked = 0
        assertEquals(FilesCheck.FAILED, manager.verifyFiles(dir, files, "Reader test") { asked++; false })
        assertEquals("the file that isn't the pin is not read again", 0, asked)

        // Put right: read again, and found the pinned one.
        vocabulary.writeBytes(pinned)
        vocabulary.setLastModified(vocabulary.lastModified() + 10_000L)
        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { asked++; false })
        assertTrue(asked > 0)
    }

    @Test
    fun `a record of another pin counts for nothing, though the file's size and last change are those it holds`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        runBlocking { manager.downloadReaderFiles(dir, files, ReaderModelId.INT8.id).toList() }
        assertTrue(manager.filesVerified(dir, files))

        // A file re-pinned to other bytes of the same size: the record names the old pin.
        val record = File(dir, VerifiedFiles.NAME)
        val vocabulary = files.single { it.spec.name == "dict.SRC.json" }
        record.writeText(record.readText().replace(vocabulary.sha256, "0".repeat(64)))

        assertFalse(manager.filesVerified(dir, files))
    }

    @Test
    fun `a file changed since it was read is read again, and one that went counts as missing`() {
        val files = twoFiles()
        val dir = manager.readerModelDir(ReaderModelId.INT8)
        runBlocking { manager.downloadReaderFiles(dir, files, ReaderModelId.INT8.id).toList() }
        val vocabulary = File(dir, "dict.SRC.json")

        // The same bytes, written again later: its last change moved, so it is read once more.
        vocabulary.writeBytes(vocabulary.readBytes())
        vocabulary.setLastModified(vocabulary.lastModified() + 10_000L)
        assertFalse(manager.filesVerified(dir, files))
        assertEquals(FilesCheck.PASSED, manager.verifyFiles(dir, files, "Reader test") { false })
        assertTrue(manager.filesVerified(dir, files))

        vocabulary.delete()
        assertFalse(manager.filesVerified(dir, files))
        assertEquals(FilesCheck.FAILED, manager.verifyFiles(dir, files, "Reader test") { false })
    }

    @Test
    fun `the registry's build is checked against its own pins, so a build of other bytes never counts`() {
        placeWhole(ReaderModelId.INT8)

        assertFalse(manager.readerModelVerified(ReaderModelId.INT8))
        assertEquals(FilesCheck.FAILED, manager.verifyReaderModel(ReaderModelId.INT8))
        assertFalse(manager.readerModelVerified(ReaderModelId.FP16))
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
