package com.arm.aichat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tiedout=mapped` sends exactly the layers' quantized matrices to KleidiAI's buffer type and
 * leaves the rest in the mapping, checked against the tensors the two models' headers list. An
 * override skips llama.cpp's check that the buffer type takes a tensor, so one that caught a norm
 * or an embedding would end the load. The matched bytes are what ai-chat's `CPU_KLEIDIAI model
 * buffer size` line should read on the phone, less llama.cpp's padding between tensors: 999.44
 * MiB for Gemma 4 E2B and 707.03 MiB for Gemma 3 1B, as the vendored llama.cpp's loader gave them
 * on the two headers with KleidiAI's kernels.
 */
class TiedOutputLayersTest {

    private class Tensor(val name: String, val type: String, val bytes: Long)

    private val layers = Regex(LoadSettings.TIED_OUTPUT_LAYERS)

    private fun tensorsOf(model: String): List<Tensor> =
        checkNotNull(javaClass.getResourceAsStream("/gguf/$model.tensors.tsv")).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") }.map { it.split('\t') }.map { Tensor(it[0], it[1], it[2].toLong()) }.toList()
        }

    // std::regex_search as llama.cpp's loader runs it: a match anywhere in the name.
    private fun matched(tensors: List<Tensor>) = tensors.filter { layers.containsMatchIn(it.name) }

    private fun mib(tensors: List<Tensor>): String = String.format(java.util.Locale.ROOT, "%.2f", tensors.sumOf { it.bytes } / 1_048_576.0)

    @Test
    fun `in Gemma 4 E2B it catches the 275 Q4_0 matrices of the layers and nothing else`() {
        val tensors = tensorsOf("gemma-4-E2B_q4_0-it")
        val caught = matched(tensors)

        assertEquals(275, caught.size)
        assertTrue(caught.all { it.type == "Q4_0" && it.name.startsWith("blk.") })
        assertEquals("999.42", mib(caught))
        // Every Q4_0 tensor of the model, so nothing KleidiAI repacks today stays behind.
        assertEquals(tensors.filter { it.type == "Q4_0" }.map { it.name }, caught.map { it.name })
        // The tied output and the per-layer embeddings stay in the mapping.
        val left = tensors - caught.toSet()
        assertTrue(left.any { it.name == "token_embd.weight" && it.type == "Q6_K" })
        assertTrue(left.any { it.name == "per_layer_token_embd.weight" })
        assertEquals("315.00", mib(left.filter { it.name == "token_embd.weight" }))
    }

    @Test
    fun `in Gemma 3 1B it catches the 182 Q8_0 matrices of the layers, not the tied output`() {
        val tensors = tensorsOf("gemma-3-1b-it-Q8_0")
        val caught = matched(tensors)

        assertEquals(182, caught.size)
        assertTrue(caught.all { it.type == "Q8_0" && it.name.startsWith("blk.") })
        assertEquals("707.03", mib(caught))
        // token_embd is Q8_0 as well: only the name keeps it in the mapping.
        assertEquals(listOf("token_embd.weight"), (tensors.filter { it.type == "Q8_0" } - caught.toSet()).map { it.name })
    }

    @Test
    fun `a norm, a scale or an embedding is never caught`() {
        val names = listOf(
            "blk.3.attn_q_norm.weight", "blk.3.attn_k_norm.weight", "blk.3.ffn_norm.weight", "blk.3.post_norm.weight",
            "blk.3.layer_output_scale.weight", "per_layer_model_proj.weight", "per_layer_proj_norm.weight",
            "token_embd.weight", "per_layer_token_embd.weight", "output_norm.weight", "rope_freqs.weight"
        )

        assertTrue(names.none { layers.containsMatchIn(it) })
        assertTrue(layers.containsMatchIn("blk.34.inp_gate.weight"))
        assertTrue(layers.containsMatchIn("blk.0.proj.weight"))
    }
}
