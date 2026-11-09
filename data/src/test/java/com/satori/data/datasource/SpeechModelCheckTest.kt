package com.satori.data.datasource

import android.content.Context
import android.content.ContextWrapper
import com.satori.data.config.SpeechModels
import com.satori.data.speech.OnnxProto
import com.satori.domain.model.SpeechModelId
import com.satori.domain.modelfile.ModelFileState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The check every load of the Malayalam model passes ([ModelDownloadManager.checkSttModel]), on
 * files of the registry's exact sizes made in the test: the model file a hole with the metadata at
 * its end, the tokens made up. The size and first bytes come first, then what sherpa-onnx reads.
 */
class SpeechModelCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manager by lazy { ModelDownloadManager(ExternalFilesContext(tmp.root)) }

    private val model = SpeechModelId.INDICCONFORMER_ML
    private val registry = SpeechModels.INDICCONFORMER_ML.files

    /** The Malayalam model's files on the phone, with [metadata] and a list of [tokenCount] tokens. */
    private fun onPhone(metadata: Map<String, String> = registry[0].metadata, tokenCount: Int = 257) {
        val dir = manager.speechModelDir(model).apply { mkdirs() }
        OnnxProto.sparseModel(File(dir, "model.int8.onnx"), registry[0].spec.bytes!!, metadata)
        File(dir, "tokens.txt").writeText(tokens(tokenCount, registry[1].spec.bytes!!.toInt()))
    }

    /** [count] tokens, <unk> first and the blank last, as the Malayalam model's, padded to [bytes]. */
    private fun tokens(count: Int, bytes: Int): String {
        val rest = buildString {
            for (id in 1 until count - 1) append("p$id $id\n")
            append("<blk> ${count - 1}\n")
        }
        val first = "<unk> 0\n"
        val padding = bytes - first.length - rest.toByteArray().size
        return "<unk>${"x".repeat(padding)} 0\n$rest".also { check(it.toByteArray().size == bytes) }
    }

    @Test
    fun `the model with the metadata and tokens the registry names may load`() {
        onPhone()

        assertEquals(ModelFileState.Intact, manager.checkSttModel(model))
    }

    @Test
    fun `a model file stamped with a subsampling factor of 8 never reaches sherpa-onnx`() {
        onPhone(metadata = registry[0].metadata + ("subsampling_factor" to "8"))

        assertEquals(ModelFileState.Damaged, manager.checkSttModel(model))
    }

    @Test
    fun `a model file without its vocabulary size never reaches sherpa-onnx`() {
        onPhone(metadata = registry[0].metadata - "vocab_size")

        assertEquals(ModelFileState.Damaged, manager.checkSttModel(model))
    }

    @Test
    fun `tokens of another vocabulary are refused`() {
        onPhone(tokenCount = 256)

        assertEquals(ModelFileState.Damaged, manager.checkSttModel(model))
    }

    @Test
    fun `a model file of another size is damaged before its metadata is read`() {
        onPhone()
        File(manager.speechModelDir(model), "model.int8.onnx").appendBytes(byteArrayOf(0))

        assertEquals(ModelFileState.Damaged, manager.checkSttModel(model))
    }

    @Test
    fun `without its files the model is missing`() {
        assertEquals(ModelFileState.Missing, manager.checkSttModel(model))
    }

    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
