package com.satori.data.config

import android.content.Context
import android.util.Log
import com.satori.domain.model.ReaderModelId
import com.satori.domain.model.SpeechModelId
import java.io.File
import java.io.IOException

/**
 * Debug builds only: where the model files come from. Without a test server it is the registry
 * as it is. Pointed at one ([set]), every model file comes from that server, with the SHA-256 its
 * manifest gives, so an emulator's first run downloads synthetic files of the registry's exact
 * sizes at a chosen rate (~/satori-audit/tools/throttle_server.py) and never fetches a model. A
 * release build has none of this: its ModelSource gives the registry unchanged.
 *
 * Kept in the app's files, `files/debug/model_source.txt`: the server's address on the first line,
 * then the manifest's lines, so it holds after a restart too; read as the app starts
 * ([ModelSourceInstaller]).
 */
object ModelSource {

    private const val TAG = "SatoriFirstRun"
    private const val FILE = "debug/model_source.txt"

    @Volatile
    private var current: TestServer? = null

    /** The test server in use, null for the registry's addresses. */
    val server: TestServer? get() = current

    /** [spec] with the test server's addresses and hashes, or as it is without one. */
    internal fun speech(spec: SpeechModelSpec): SpeechModelSpec = current?.speech(spec) ?: spec

    /** [config] with the test server's address, or as it is without one. */
    internal fun llm(config: ModelConfig): ModelConfig = current?.llm(config) ?: config

    /** The reader build [spec] of [id] with the test server's addresses and hashes, or as it is without one. */
    internal fun reader(id: ReaderModelId, spec: ReaderModelSpec): ReaderModelSpec = current?.reader(id, spec) ?: spec

    /** Reads the stored test server, as the app starts; a file that can't be read leaves the registry. */
    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return
        current = try {
            val lines = file.readLines()
            TestServer.parse(lines.firstOrNull().orEmpty(), lines.drop(1).joinToString("\n"))
        } catch (e: IOException) {
            Log.w(TAG, "Model source not read: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Model source not used: ${e.message}")
            null
        }
        current?.let { Log.i(TAG, "Model source: ${it.base}, ${it.files.size} files") }
    }

    /**
     * Points every model file at the test server at [base], with the hashes of its [manifest]
     * (manifest.txt), and keeps it for the next starts.
     *
     * @throws IllegalArgumentException when [base] or [manifest] can't be used
     * @throws IOException when it can't be stored
     */
    fun set(context: Context, base: String, manifest: String) {
        val server = TestServer.parse(base, manifest)
        val file = File(context.filesDir, FILE)
        file.parentFile?.mkdirs()
        file.writeText(server.base + "\n" + manifest.trim() + "\n")
        current = server
    }

    /** Back to the registry's addresses. */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        current = null
    }

    /**
     * A test server: its address, and what its manifest says of each file, by the file's path on
     * the server: `stt/<speech model id>/<file name>`, `llm/<file name>` and `reader/<reader model
     * id>/<file name>`.
     *
     * @property base The address the paths are put after, ending in a slash
     * @property files The SHA-256 and size of each file, by path
     */
    data class TestServer(val base: String, val files: Map<String, ManifestEntry>) {

        internal fun speech(spec: SpeechModelSpec): SpeechModelSpec = spec.copy(
            files = spec.files.map { file(spec.id, it) },
            vocabulary = spec.vocabulary?.let { file(spec.id, it) }
        )

        internal fun llm(config: ModelConfig): ModelConfig = config.copy(llmUrl = base + llmPath(config.llmFilename))

        /**
         * The reader's files as [file]'s for a speech model: at the server, with its manifest's hash
         * where it lists one; and with its manifest's size too (0.8.0's first-run hook, design R7),
         * as a test server may serve a toy reader of the same files
         * (~/satori-audit/tools/reading/toy_reader.py), smaller than the registry's: the app's own
         * reader, the finishing's Reading step's, then reads with it on an emulator, its files
         * checked and their SHA-256 read by the manifest's numbers. A file the manifest doesn't list
         * keeps the registry's size and hash, which a toy's file then fails.
         */
        internal fun reader(id: ReaderModelId, spec: ReaderModelSpec): ReaderModelSpec = spec.copy(
            files = spec.files.map {
                val path = readerPath(id, it.spec.name)
                val listed = files[path]
                it.copy(
                    spec = listed?.let { entry -> it.spec.copy(bytes = entry.bytes, minBytes = 1L) } ?: it.spec,
                    url = base + path,
                    sha256 = listed?.sha256 ?: it.sha256
                )
            }
        )

        /**
         * One speech model file at the server: always its address there, so no file of a model is
         * fetched from the internet by mistake; the manifest's hash where the manifest lists it,
         * else the registry's, which the server's synthetic content then fails.
         */
        private fun file(model: SpeechModelId, file: SpeechModelFile): SpeechModelFile {
            val path = speechPath(model, file.spec.name)
            return file.copy(url = base + path, sha256 = files[path]?.sha256 ?: file.sha256)
        }

        companion object {

            /**
             * The server at [base] (http or https, a slash added at the end if it has none) with
             * [manifest]: one line per file, "<sha256> <bytes> <path>", blank lines and lines
             * starting with # left out.
             *
             * @throws IllegalArgumentException for an address that isn't one, or a line that
             *   isn't a hash, a size and a path
             */
            fun parse(base: String, manifest: String): TestServer {
                val address = base.trim()
                require(address.startsWith("http://") || address.startsWith("https://")) { "Not an http address: $address" }
                val files = manifest.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .associate { line ->
                        val fields = line.split(Regex("\\s+"))
                        require(fields.size == 3) { "A manifest line has ${fields.size} fields, not 3" }
                        val (sha256, bytes, path) = fields
                        require(sha256.matches(SHA256)) { "Not a SHA-256: $sha256" }
                        val size = requireNotNull(bytes.toLongOrNull()?.takeIf { it > 0 }) { "Not a size: $bytes" }
                        path to ManifestEntry(sha256.lowercase(), size)
                    }
                require(files.isNotEmpty()) { "The manifest lists no file" }
                return TestServer(if (address.endsWith("/")) address else "$address/", files)
            }

            private val SHA256 = Regex("[0-9a-fA-F]{64}")
        }
    }

    /** A manifest's line: the file's SHA-256, lower-case hex, and its size. */
    data class ManifestEntry(val sha256: String, val bytes: Long)

    /** The path of the speech model file [name] of [model] on a test server. */
    fun speechPath(model: SpeechModelId, name: String): String = "stt/${model.id}/$name"

    /** The path of the AI model file [filename] on a test server. */
    fun llmPath(filename: String): String = "llm/$filename"

    /** The path of the reader file [name] of the build [model] on a test server. */
    fun readerPath(model: ReaderModelId, name: String): String = "reader/${model.id}/$name"
}
