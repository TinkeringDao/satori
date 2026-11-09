package com.satori.data.datasource

import com.satori.data.config.MIN_LLM_BYTES
import com.satori.data.config.SpeechModels
import com.satori.data.config.modelConfigForVariant
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.modelfile.ModelFileCheck
import com.satori.domain.modelfile.ModelFileFormat
import com.satori.domain.modelfile.ModelFileSpec
import com.satori.domain.modelfile.ModelFileState
import java.io.File
import java.io.IOException

/**
 * Looks at model files on disk and says whether a native loader may open them. sherpa-onnx and
 * llama.cpp abort the whole process on a bad file, and no Kotlin code can catch that, so this
 * runs before every load. Reading is cheap: the size of each file and its first four bytes.
 *
 * The rules are [ModelFileCheck]'s; the sizes are in [com.satori.data.config.ModelConfig]
 * and, for the speech models, in [SpeechModels].
 * Unlike [ModelDownloadManager.isSttModelDownloaded], which only asks whether the files are
 * there, this asks whether they are the right ones.
 */
object ModelFileInspector {

    /** The state of a speech model whose files are in [dir]; Parakeet v3 unless [specs] say otherwise. */
    fun speechModel(
        dir: File,
        specs: List<ModelFileSpec> = SpeechModels.PARAKEET_V3.specs
    ): ModelFileState = files(dir, specs)

    /**
     * One line for a log that says, for each speech model file in [dir], its size on disk, the
     * size it should have and what [speechModel] makes of it. File names and sizes only.
     */
    fun speechModelReport(
        dir: File,
        specs: List<ModelFileSpec> = SpeechModels.PARAKEET_V3.specs
    ): String = filesReport(dir, specs)

    /**
     * The state of a model made of the files [specs] in [dir], a speech model's or the reader's
     * ([com.satori.data.config.ReaderModels]): each file's size, and the first bytes of an ONNX file.
     */
    fun files(dir: File, specs: List<ModelFileSpec>): ModelFileState =
        ModelFileCheck.combine(specs.map { inspect(File(dir, it.name), it) })

    /** [speechModelReport] for any model's files [specs] in [dir]. */
    fun filesReport(dir: File, specs: List<ModelFileSpec>): String = specs.joinToString("; ") { spec ->
        val file = File(dir, spec.name)
        val size = if (file.isFile) file.length() else null
        "${spec.name} ${size ?: "absent"}/${spec.bytes ?: ">=${spec.minBytes}"} ${inspect(file, spec)}"
    }

    /**
     * The state of the AI model [file]. Its spec is the one of the model variant with that file
     * name; any other GGUF file only has to be a GGUF file of a plausible size.
     */
    fun aiModel(file: File): ModelFileState = inspect(file, aiModelSpec(file.name))

    /** The state of one [file] against its [spec]. */
    internal fun inspect(file: File, spec: ModelFileSpec): ModelFileState {
        val size = if (file.isFile) file.length() else null
        val header = if (size != null && size > 0L) readHeader(file) else ByteArray(0)
        return ModelFileCheck.check(spec, size, header)
    }

    private fun aiModelSpec(fileName: String): ModelFileSpec =
        LlmModelVariant.entries
            .map { modelConfigForVariant(it).llmSpec }
            .firstOrNull { it.name == fileName }
            ?: ModelFileSpec(fileName, ModelFileFormat.Gguf, minBytes = MIN_LLM_BYTES)

    /** The first bytes of [file]; fewer if it is shorter, null if it cannot be read. */
    private fun readHeader(file: File): ByteArray? = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(ModelFileCheck.HEADER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
