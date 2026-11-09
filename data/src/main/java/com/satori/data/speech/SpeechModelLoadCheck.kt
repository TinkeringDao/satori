package com.satori.data.speech

import com.satori.data.config.SpeechModelFile
import com.satori.domain.modelfile.ModelFileState
import java.io.File
import java.io.IOException

/**
 * What sherpa-onnx reads from a speech model's files as it loads them, checked before it does:
 * the metadata of an ONNX file ([SpeechModelFile.metadata], [OnnxMetadata]) and the tokens of a
 * tokens.txt ([SpeechModelFile.tokens], [SherpaTokens]), wherever the registry names what they
 * must be. sherpa-onnx ends the whole process on a key it needs and doesn't find, or on a tokens
 * line it can't parse, and no Kotlin code can catch that; a wrong value it takes silently. So a
 * file that fails this never reaches it, like one of the wrong size
 * ([com.satori.data.datasource.ModelFileInspector]), which is checked first.
 */
object SpeechModelLoadCheck {

    /**
     * The outcome: [ModelFileState.Intact] when every file holds what the registry says,
     * [ModelFileState.Damaged] when one doesn't (a download of the same commit gives the same
     * bytes again, so this is a file the registry is wrong about, or one put there by hand), and
     * [ModelFileState.Unreadable] when one couldn't be read. [reason] says which file and why, in
     * file names, keys, values and counts only.
     */
    data class Result(val state: ModelFileState, val reason: String? = null)

    private val PASSED = Result(ModelFileState.Intact)

    /** The check of [files], the registry entries of the files in [dir], which exist with their sizes. */
    fun check(dir: File, files: List<SpeechModelFile>): Result {
        for (entry in files) {
            val file = File(dir, entry.spec.name)
            val result = try {
                metadataCheck(file, entry) ?: tokensCheck(file, entry)
            } catch (e: MalformedModelFileException) {
                Result(ModelFileState.Damaged, "${entry.spec.name}: ${e.message}")
            } catch (e: IOException) {
                Result(ModelFileState.Unreadable, "${entry.spec.name} can't be read (${e.javaClass.simpleName})")
            }
            if (result != null) return result
        }
        return PASSED
    }

    private fun metadataCheck(file: File, entry: SpeechModelFile): Result? {
        if (entry.metadata.isEmpty()) return null
        val found = OnnxMetadata.read(file)
        val wrong = entry.metadata.filter { (key, value) -> found[key] != value }
        if (wrong.isEmpty()) return null
        val what = wrong.entries.joinToString { (key, value) -> "$key=${found[key] ?: "absent"}, expected $value" }
        return Result(ModelFileState.Damaged, "${entry.spec.name} metadata $what")
    }

    private fun tokensCheck(file: File, entry: SpeechModelFile): Result? {
        val count = entry.tokens ?: return null
        val problem = SherpaTokens.problem(file, count) ?: return null
        return Result(ModelFileState.Damaged, "${entry.spec.name}: $problem")
    }
}
