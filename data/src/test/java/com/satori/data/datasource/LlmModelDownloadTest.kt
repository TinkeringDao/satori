package com.satori.data.datasource

import android.content.Context
import android.content.ContextWrapper
import com.satori.domain.model.DownloadException
import com.satori.domain.model.DownloadFailure
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The AI model's download: checked against the registry's size, so a login page in its place
 * neither empties what the earlier tries fetched nor passes for the model; continued with a Range
 * request after a cut; started over when the server ignores the Range.
 */
class LlmModelDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TestFileServer
    private lateinit var manager: ModelDownloadManager

    private val model = Random(2).nextBytes(400_000)
    private val spec = ModelFileSpec("model.gguf", ModelFileFormat.Gguf, bytes = model.size.toLong())
    private val requests = CopyOnWriteArrayList<String>()

    // What the server sends instead of the file, how much of it, and whether it honours a Range.
    private var page: ByteArray? = null
    private var cutAfter: Int? = null
    private var ignoreRange = false

    @Before
    fun startServer() {
        server = TestFileServer { request ->
            requests += "${request.method} ${request.range ?: "whole"}"
            page?.let { return@TestFileServer TestFileServer.Answer(200, it) }
            val from = request.range?.takeUnless { ignoreRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val part = model.copyOfRange(from, model.size)
            val status = if (from > 0) 206 else 200
            val headers = if (from > 0) mapOf("Content-Range" to "bytes $from-${model.size - 1}/${model.size}") else emptyMap()
            val cut = cutAfter
            if (cut != null) {
                TestFileServer.Answer(status, part.copyOf(minOf(cut, part.size)), headers, declaresLength = false)
            } else {
                TestFileServer.Answer(status, part, headers)
            }
        }
        manager = ModelDownloadManager(ExternalFilesContext(tmp.root))
    }

    @After
    fun stopServer() {
        server.close()
    }

    private val file get() = File(tmp.root, "models/model.gguf")
    private val partial get() = File(tmp.root, "models/model.gguf.partial")

    private fun download() = runBlocking {
        manager.downloadLlmModel("http://127.0.0.1:${server.port}/model.gguf", "model.gguf", spec).toList()
    }

    @Test
    fun `the model is downloaded whole, and only then given its name`() {
        val progress = download()

        assertArrayEquals(model, file.readBytes())
        assertFalse(partial.exists())
        assertEquals(100, progress.last().percentage)
        val bytes = progress.map { it.bytesDownloaded }
        assertEquals(bytes.sorted(), bytes)
        assertTrue(progress.dropLast(1).all { it.percentage < 100 })
    }

    @Test
    fun `a login page in its place keeps what the earlier tries fetched`() {
        partial.parentFile?.mkdirs()
        val kept = model.copyOf(150_000)
        partial.writeBytes(kept)
        // A network that wants a login first answers every address with 200 and its own page.
        page = "<html>Log in to use this network</html>".toByteArray()

        val failure = runCatching { download() }.exceptionOrNull()

        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        assertArrayEquals(kept, partial.readBytes())
        assertFalse(file.exists())
    }

    @Test
    fun `a page in its place on a first try is no model either`() {
        page = "<html>Log in to use this network</html>".toByteArray()

        val failure = runCatching { download() }.exceptionOrNull()

        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        assertFalse(file.exists())
    }

    @Test
    fun `a download that ends early keeps what it got, and the next goes on from there`() {
        cutAfter = 120_000

        val failure = runCatching { download() }.exceptionOrNull()

        assertEquals(DownloadFailure.NO_CONNECTION, (failure as? DownloadException)?.failure)
        assertEquals(120_000L, partial.length())
        assertFalse(file.exists())

        cutAfter = null
        requests.clear()
        download()
        // The registry knows the size: no HEAD request is needed for it.
        assertEquals(listOf("GET bytes=120000-"), requests)
        assertArrayEquals(model, file.readBytes())
    }

    @Test
    fun `a server that ignores the Range sends the whole file again, which replaces the part`() {
        partial.parentFile?.mkdirs()
        partial.writeBytes(model.copyOf(100_000))
        ignoreRange = true

        val progress = download()

        assertArrayEquals(model, file.readBytes())
        // Counted from the start again, not on top of the part.
        assertTrue(progress.all { it.bytesDownloaded <= model.size })
    }

    @Test
    fun `a part longer than the file is no start for it`() {
        partial.parentFile?.mkdirs()
        partial.writeBytes(model + ByteArray(10))

        download()

        assertEquals(listOf("GET whole"), requests)
        assertArrayEquals(model, file.readBytes())
    }

    @Test
    fun `a part that holds the whole file, as a download cut off before its rename leaves it, is only given its name`() {
        partial.parentFile?.mkdirs()
        partial.writeBytes(model)

        val progress = download()

        // A Range from its end would be refused (416), forever.
        assertEquals(emptyList<String>(), requests)
        assertArrayEquals(model, file.readBytes())
        assertFalse(partial.exists())
        assertEquals(100, progress.last().percentage)
    }

    @Test
    fun `without the registry's size the HEAD request gives it`() {
        val rough = ModelFileSpec("model.gguf", ModelFileFormat.Gguf, minBytes = 1_000L)

        val progress = runBlocking { manager.downloadLlmModel("http://127.0.0.1:${server.port}/model.gguf", "model.gguf", rough).toList() }

        assertEquals(listOf("HEAD whole", "GET whole"), requests)
        assertArrayEquals(model, file.readBytes())
        assertTrue(progress.dropLast(1).all { it.totalBytes == model.size.toLong() })
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
