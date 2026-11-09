package com.satori.data.config

import com.satori.domain.modelfile.ModelFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's pins: which files, how big, with which SHA-256, from which commit. A change here
 * means the reader changed; the numbers are those of the Hugging Face listings of the commits.
 */
class ReaderModelsTest {

    private val builds = listOf(ReaderModels.INT8, ReaderModels.FP16)

    @Test
    fun `the int8 build is the 11 files of commit a7ecdc0b, 241,989,173 bytes`() {
        assertEquals("a7ecdc0ba73148c6e1d051ca372332471d8aff4f", ReaderModels.INT8.commit)
        assertEquals(
            listOf(
                "encoder_model.onnx", "encoder_model.onnx.data", "decoder_model.onnx", "decoder_with_past_model.onnx",
                "decoder_shared.onnx.data", "model.SRC", "model.TGT", "dict.SRC.json", "dict.TGT.json", "config.json",
                "generation_config.json"
            ),
            ReaderModels.INT8.files.map { it.spec.name }
        )
        assertEquals(241_989_173L, ReaderModels.INT8.bytes)
    }

    @Test
    fun `the fp16 fallback has its own graphs and weights and the same tokenizers, vocabularies and configurations`() {
        val int8 = ReaderModels.INT8.files.associateBy { it.spec.name }
        val fp16 = ReaderModels.FP16.files.associateBy { it.spec.name }

        assertEquals("b6169410862d56cdace9a5e26298b1243c7c1265", ReaderModels.FP16.commit)
        assertEquals(int8.keys, fp16.keys)
        val graphs = fp16.values.filter { it.spec.name.endsWith(".onnx") || it.spec.name.endsWith(".onnx.data") }
        assertEquals(458_439_968L, graphs.sumOf { it.spec.bytes ?: 0L })
        for (name in listOf("model.SRC", "model.TGT", "dict.SRC.json", "dict.TGT.json", "config.json", "generation_config.json")) {
            assertEquals(name, int8.getValue(name).sha256, fp16.getValue(name).sha256)
            assertEquals(name, int8.getValue(name).spec, fp16.getValue(name).spec)
        }
        for (graph in graphs) assertTrue(graph.spec.name, graph.sha256 != int8.getValue(graph.spec.name).sha256)
    }

    @Test
    fun `each file has an exact size, a SHA-256 and the format its name says`() {
        builds.flatMap { it.files }.forEach { file ->
            assertTrue(file.spec.name, (file.spec.bytes ?: 0L) > 0L)
            assertTrue(file.spec.name, Regex("[0-9a-f]{64}").matches(file.sha256))
            val format = when {
                file.spec.name.endsWith(".onnx") -> ModelFileFormat.Onnx
                file.spec.name.endsWith(".json") -> ModelFileFormat.Text
                else -> ModelFileFormat.Data
            }
            assertEquals(file.spec.name, format, file.spec.format)
        }
    }

    @Test
    fun `each file comes from its build's commit, under its own name`() {
        for (build in builds) {
            assertTrue(Regex("[0-9a-f]{40}").matches(build.commit))
            build.files.forEach { file ->
                assertEquals("https://huggingface.co/${build.repo}/resolve/${build.commit}/${file.spec.name}", file.url)
            }
        }
    }
}
