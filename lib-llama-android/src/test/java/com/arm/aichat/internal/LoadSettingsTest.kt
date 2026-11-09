package com.arm.aichat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings of a load: each model's own, which every load has in a release build and in a debug
 * build's own work; the debug properties over them, one by one, for a memtest's load; and how the
 * lines describe them.
 */
class LoadSettingsTest {

    private val warnings = mutableListOf<String>()

    private fun settings(defaults: LoadSettings, vararg properties: Pair<String, String>): LoadSettings {
        val set = properties.toMap()
        return LoadSettings.fromProperties(defaults, read = { name -> set[name].orEmpty() }) { warnings += it }
    }

    // The memory session's run 4, as memtest.sh's load line checks it.
    private val run4 = "llama.cpp repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped copypace=120 dropcopied=on"

    @Test
    fun `Gemma 3 1B loads with run 4's settings, the pre-read and the weight copy at 120 MB per second`() {
        val oneB = ModelDefaults.of("gemma-3-1b-it-Q8_0.gguf")

        assertEquals(ModelDefaults.GEMMA3_1B, oneB)
        assertEquals(LoadSettings.FAST, oneB.settings)
        with(LoadSettings.FAST) {
            assertEquals(120, preReadMbPerSecond)
            assertEquals(120, copyPaceMbPerSecond)
            assertTrue(repack)
            assertTrue(tiedOutputMapped)
            assertFalse(swaFull)
            assertTrue(kvQ8)
            assertEquals(1, nOutputsMax)
            assertEquals(4096, nCtx)
            assertTrue(mmap)
            assertFalse(directIo)
            assertTrue(populate)
            assertFalse(cold)
            assertTrue(dropCopied)
        }
        assertEquals(run4, LoadSettings.FAST.describe(directIoApplies = false))
    }

    @Test
    fun `Gemma 4 E2B loads with run 3's settings, the same as the 1B's`() {
        val e2b = ModelDefaults.of("gemma-4-E2B_q4_0-it.gguf")

        assertEquals(ModelDefaults.GEMMA4_E2B, e2b)
        assertEquals(LoadSettings.FAST, e2b.settings)
    }

    @Test
    fun `the lean rung is run 6's, repack off and no copy pace, with the fast rung's pre-read and context`() {
        // Nothing is copied, so nothing is dropped.
        assertEquals(
            LoadSettings.FAST.copy(repack = false, tiedOutputMapped = false, copyPaceMbPerSecond = LoadSettings.PACE_OFF, dropCopied = false),
            LoadSettings.LEAN
        )
        assertEquals(
            "llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1",
            LoadSettings.LEAN.describe(directIoApplies = false)
        )
    }

    @Test
    fun `any other model is paced, with llama_cpp's own settings otherwise and its tied output left to them`() {
        for (name in listOf("gemma-3-1b-it-IQ4_NL.gguf", "Qwen3.5-0.8B-Q8_0.gguf", "gemma-4-e2b.gguf")) {
            assertEquals(name, ModelDefaults.OTHER, ModelDefaults.of(name))
        }
        assertEquals(LoadSettings.UNPACED.copy(preReadMbPerSecond = 120, copyPaceMbPerSecond = 120, dropCopied = true), LoadSettings.PACED)
        assertEquals(
            "llama.cpp repack=on mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096 copypace=120 dropcopied=on",
            LoadSettings.PACED.describe(directIoApplies = false)
        )
    }

    @Test
    fun `0_6_0's settings are llama_cpp's own, nothing paced, and keep their line`() {
        assertEquals(LoadSettings(), LoadSettings.UNPACED)
        assertEquals(LoadSettings.PACE_OFF, LoadSettings.UNPACED.preReadMbPerSecond)
        assertEquals(
            "llama.cpp repack=on mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096",
            LoadSettings.UNPACED.describe(directIoApplies = false)
        )
    }

    @Test
    fun `with no property set a memtest's load has its model's settings`() {
        assertEquals(LoadSettings.FAST, settings(LoadSettings.FAST))
        assertEquals(LoadSettings.LEAN, settings(LoadSettings.LEAN))
        assertEquals(LoadSettings.PACED, settings(LoadSettings.PACED))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `each property overrides its own setting of the model's, and nothing else`() {
        val fast = LoadSettings.FAST
        assertEquals(fast.copy(repack = false), settings(fast, "debug.satori.llm.repack" to "off"))
        assertEquals(fast.copy(mmap = false), settings(fast, "debug.satori.llm.mmap" to "off"))
        assertEquals(fast.copy(directIo = true), settings(fast, "debug.satori.llm.directio" to "on"))
        assertEquals(fast.copy(swaFull = true), settings(fast, "debug.satori.llm.swa_full" to "on"))
        assertEquals(fast.copy(kvQ8 = false), settings(fast, "debug.satori.llm.kv" to "f16"))
        assertEquals(fast.copy(nCtx = 3072), settings(fast, "debug.satori.llm.ctx" to "3072"))
        assertEquals(fast.copy(nOutputsMax = 512), settings(fast, "debug.satori.llm.outputs" to "512"))
        assertEquals(fast.copy(copyPaceMbPerSecond = LoadSettings.PACE_OFF), settings(fast, "debug.satori.llm.copypace" to "off"))
        assertEquals(fast.copy(copyPaceMbPerSecond = 60), settings(fast, "debug.satori.llm.copypace" to "60"))
        assertEquals(fast.copy(tiedOutputMapped = false), settings(fast, "debug.satori.llm.tiedout" to "repack"))
        assertEquals(fast.copy(populate = false), settings(fast, "debug.satori.llm.populate" to "off"))
        assertEquals(fast.copy(cold = true), settings(fast, "debug.satori.llm.cold" to "on"))
        assertEquals(fast.copy(dropCopied = false), settings(fast, "debug.satori.llm.dropcopied" to "off"))
        assertEquals(fast.copy(preReadMbPerSecond = LoadSettings.PACE_OFF), settings(fast, "debug.satori.load.pace" to "off"))
        assertEquals(fast.copy(preReadMbPerSecond = 200), settings(fast, "debug.satori.load.pace" to "200"))
        assertEquals(fast.copy(preReadMbPerSecond = 60), settings(fast, "debug.satori.load.pace" to " 60 MB/s"))
        // Over the lean rung the other way round.
        val lean = LoadSettings.LEAN
        assertEquals(lean.copy(repack = true, tiedOutputMapped = true), settings(lean, "debug.satori.llm.repack" to "1", "debug.satori.llm.tiedout" to "mapped"))
        assertEquals(lean.copy(copyPaceMbPerSecond = 120), settings(lean, "debug.satori.llm.copypace" to "120"))
        assertEquals(lean.copy(dropCopied = true), settings(lean, "debug.satori.llm.dropcopied" to "on"))
        assertEquals(
            LoadSettings.UNPACED.copy(repack = false, kvQ8 = true),
            settings(LoadSettings.UNPACED, "debug.satori.llm.repack" to "0", "debug.satori.llm.kv" to "Q8_0 ")
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the properties can put a load back on 0_6_0's settings, with 512 outputs for llama_cpp's whole batch`() {
        val back = settings(
            LoadSettings.FAST,
            "debug.satori.load.pace" to "off",
            "debug.satori.llm.copypace" to "off",
            "debug.satori.llm.tiedout" to "repack",
            "debug.satori.llm.swa_full" to "on",
            "debug.satori.llm.kv" to "f16",
            "debug.satori.llm.outputs" to "512",
            "debug.satori.llm.dropcopied" to "off"
        )

        assertEquals(LoadSettings.UNPACED.copy(nOutputsMax = 512), back)
        assertEquals(
            "llama.cpp repack=on mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096 outputs=512",
            back.describe(directIoApplies = false)
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `only the memtest's load and an armed window of a debug build read the properties, every other load has its model's`() {
        val unread: (String) -> String = { name -> throw AssertionError("read $name") }
        val variant: (String) -> String = { name -> if (name == "debug.satori.llm.repack") "off" else "" }
        val fast = LoadSettings.FAST

        assertEquals(fast, LoadSettings.forLoad(fast, debuggable = false, armed = true, read = unread) { warnings += it })
        assertEquals(fast, LoadSettings.forLoad(fast, debuggable = false, armed = false, read = unread) { warnings += it })
        // A property left set on the phone changes no load of the app's own work.
        assertEquals(fast, LoadSettings.forLoad(fast, debuggable = true, armed = false, read = unread) { warnings += it })
        assertEquals(LoadSettings.LEAN, LoadSettings.forLoad(LoadSettings.LEAN, debuggable = true, armed = false, read = unread) { warnings += it })
        assertEquals(fast.copy(repack = false), LoadSettings.forLoad(fast, debuggable = true, armed = true, read = variant) { warnings += it })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a value it doesn't know is ignored with a warning, and that setting stays the model's`() {
        val read = settings(
            LoadSettings.FAST,
            "debug.satori.llm.repack" to "maybe",
            "debug.satori.llm.kv" to "q4_0",
            "debug.satori.llm.ctx" to "131072",
            "debug.satori.llm.tiedout" to "dropped",
            "debug.satori.load.pace" to "fast",
            "debug.satori.llm.swa_full" to "on"
        )

        assertEquals(LoadSettings.FAST.copy(swaFull = true), read)
        assertEquals(
            listOf(
                "Ignoring debug.satori.llm.kv=q4_0: not f16 or q8_0",
                "Ignoring debug.satori.llm.tiedout=dropped: not repack or mapped",
                "Ignoring debug.satori.load.pace=fast: not off or 10-1000 (MB/s)",
                "Ignoring debug.satori.llm.repack=maybe: not on or off",
                "Ignoring debug.satori.llm.ctx=131072: not 1024-8192"
            ),
            warnings
        )
    }

    @Test
    fun `a q8_0 KV cache sets flash attention on, as a quantized V cache needs`() {
        assertEquals(
            "llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=2048",
            LoadSettings(repack = false, swaFull = false, kvQ8 = true, nCtx = 2048).describe(directIoApplies = false)
        )
    }

    @Test
    fun `a cold load says so at the end, and only then`() {
        assertEquals("$run4 cold=on", LoadSettings.FAST.copy(cold = true).describe(directIoApplies = false))
        assertFalse(LoadSettings.FAST.describe(directIoApplies = false).contains("cold"))
    }

    @Test
    fun `direct IO that applies turns mmap off, and one the file doesn't allow leaves mmap`() {
        val direct = LoadSettings(directIo = true)

        assertEquals("llama.cpp repack=on mmap=off directio=on swa_full=on kv=f16 fa=auto ctx=4096", direct.describe(directIoApplies = true))
        assertEquals("llama.cpp repack=on mmap=on directio=unavailable swa_full=on kv=f16 fa=auto ctx=4096", direct.describe(directIoApplies = false))
        // Without being asked for, direct IO never applies, whatever the file allows.
        assertEquals(LoadSettings.FAST.describe(false), LoadSettings.FAST.describe(directIoApplies = true))
    }

    @Test
    fun `the outputs of a batch are taken from 1 to 512, and named only when they aren't llama_cpp's own`() {
        assertEquals(1, settings(LoadSettings.UNPACED, "debug.satori.llm.outputs" to "1").nOutputsMax)
        assertEquals(512, settings(LoadSettings.FAST, "debug.satori.llm.outputs" to "512").nOutputsMax)
        assertEquals(1, settings(LoadSettings.FAST, "debug.satori.llm.outputs" to "0").nOutputsMax)
        assertEquals(1, settings(LoadSettings.FAST, "debug.satori.llm.outputs" to "513").nOutputsMax)
        assertEquals(
            listOf("Ignoring debug.satori.llm.outputs=0: not 1-512", "Ignoring debug.satori.llm.outputs=513: not 1-512"),
            warnings
        )
        assertFalse(LoadSettings(nOutputsMax = LoadSettings.BATCH_OUTPUTS).describe(directIoApplies = false).contains("outputs"))
    }

    @Test
    fun `the weight copy is paced at 10 to 1000 MB per second or not at all, and the tied output repacked or mapped`() {
        assertEquals(LoadSettings.PACE_OFF, settings(LoadSettings.FAST, "debug.satori.llm.copypace" to "off").copyPaceMbPerSecond)
        assertEquals(10, settings(LoadSettings.FAST, "debug.satori.llm.copypace" to "10").copyPaceMbPerSecond)
        assertEquals(1000, settings(LoadSettings.FAST, "debug.satori.llm.copypace" to "1000").copyPaceMbPerSecond)
        assertEquals(120, settings(LoadSettings.FAST, "debug.satori.llm.copypace" to "5").copyPaceMbPerSecond)
        assertEquals(LoadSettings.PACE_OFF, settings(LoadSettings.LEAN, "debug.satori.llm.copypace" to "5").copyPaceMbPerSecond)
        assertEquals(10, settings(LoadSettings.FAST, "debug.satori.load.pace" to "10").preReadMbPerSecond)
        assertEquals(120, settings(LoadSettings.FAST, "debug.satori.load.pace" to "1001").preReadMbPerSecond)
        assertEquals(
            listOf(
                "Ignoring debug.satori.llm.copypace=5: not 10-1000",
                "Ignoring debug.satori.llm.copypace=5: not 10-1000",
                "Ignoring debug.satori.load.pace=1001: not off or 10-1000 (MB/s)"
            ),
            warnings
        )
    }

    @Test
    fun `the tied output is named mapped only where the layers are repacked at all`() {
        val noRepack = LoadSettings.FAST.copy(repack = false)

        assertEquals(
            "llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 copypace=120",
            noRepack.describe(directIoApplies = false)
        )
        assertTrue(LoadSettings.FAST.describe(directIoApplies = false).contains(" tiedout=mapped"))
    }

    @Test
    fun `a load maps its file with MAP_POPULATE unless populate is off, which the line then says`() {
        assertEquals(true, LoadSettings.FAST.populate)
        assertEquals(true, settings(LoadSettings.FAST, "debug.satori.llm.populate" to "on").populate)
        assertEquals(true, settings(LoadSettings.FAST, "debug.satori.llm.populate" to "sometimes").populate)
        assertEquals(listOf("Ignoring debug.satori.llm.populate=sometimes: not on or off"), warnings)

        assertEquals("$run4 populate=off cold=on", LoadSettings.FAST.copy(populate = false, cold = true).describe(directIoApplies = false))
        assertFalse(LoadSettings.FAST.describe(directIoApplies = false).contains("populate"))
    }

    @Test
    fun `the copied tensors' pages are given back where something is copied out of a mapping, and the line says so only then`() {
        assertTrue(LoadSettings.FAST.dropsCopied(direct = false))
        assertTrue(LoadSettings.PACED.dropsCopied(direct = false))
        assertFalse(LoadSettings.LEAN.dropsCopied(direct = false))
        assertFalse(LoadSettings.UNPACED.dropsCopied(direct = false))
        // Nothing copied without repack, no mapping without mmap or with direct IO that applies.
        assertFalse(LoadSettings.FAST.copy(repack = false).dropsCopied(direct = false))
        assertFalse(LoadSettings.FAST.copy(mmap = false).dropsCopied(direct = false))
        assertFalse(LoadSettings.FAST.copy(directIo = true).dropsCopied(direct = true))
        assertFalse(LoadSettings.FAST.copy(repack = false).describe(directIoApplies = false).contains("dropcopied"))
        assertFalse(LoadSettings.FAST.copy(mmap = false).describe(directIoApplies = false).contains("dropcopied"))
        assertFalse(LoadSettings.FAST.copy(directIo = true).describe(directIoApplies = true).contains("dropcopied"))
        assertTrue(LoadSettings.FAST.copy(directIo = true).describe(directIoApplies = false).contains(" dropcopied=on"))
        assertFalse(LoadSettings.FAST.copy(dropCopied = false).describe(directIoApplies = false).contains("dropcopied"))

        assertEquals(true, settings(LoadSettings.FAST, "debug.satori.llm.dropcopied" to "maybe").dropCopied)
        assertEquals(listOf("Ignoring debug.satori.llm.dropcopied=maybe: not on or off"), warnings)
        // A memtest's line names it among the properties it changed.
        val off = settings(LoadSettings.FAST, "debug.satori.llm.dropcopied" to "off")
        assertEquals(
            "Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's; debug properties: dropcopied): pre-read 120 MB/s, ${run4.removeSuffix(" dropcopied=on")}",
            LoadSettings.loadLine("gemma-3-1b-it-Q8_0.gguf", ModelDefaults.GEMMA3_1B, off, off.describe(directIoApplies = false))
        )
    }

    @Test
    fun `the context is taken from 1024 to 8192 cells, and nothing else`() {
        assertEquals(1024, settings(LoadSettings.FAST, "debug.satori.llm.ctx" to "1024").nCtx)
        assertEquals(8192, settings(LoadSettings.FAST, "debug.satori.llm.ctx" to "8192").nCtx)
        assertEquals(4096, settings(LoadSettings.FAST, "debug.satori.llm.ctx" to "1023").nCtx)
        assertEquals(4096, settings(LoadSettings.FAST, "debug.satori.llm.ctx" to "lots").nCtx)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `each load's line names its file, whose settings they are, the pre-read and llama_cpp's settings`() {
        assertEquals(
            "Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's): pre-read 120 MB/s, $run4",
            LoadSettings.loadLine("gemma-3-1b-it-Q8_0.gguf", ModelDefaults.GEMMA3_1B, LoadSettings.FAST, run4)
        )
        assertEquals(
            "Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's): pre-read 120 MB/s, $run4",
            LoadSettings.loadLine("gemma-4-E2B_q4_0-it.gguf", ModelDefaults.GEMMA4_E2B, LoadSettings.FAST, run4)
        )
        val paced = LoadSettings.PACED.describe(directIoApplies = false)
        assertEquals(
            "Load settings of Qwen3.5-0.8B-Q8_0.gguf (any other model's): pre-read 120 MB/s, $paced",
            LoadSettings.loadLine("Qwen3.5-0.8B-Q8_0.gguf", ModelDefaults.OTHER, LoadSettings.PACED, paced)
        )
    }

    @Test
    fun `a memtest's line names the settings the debug properties changed, and a pre-read that is off`() {
        val variant = LoadSettings.FAST.copy(repack = false, copyPaceMbPerSecond = LoadSettings.PACE_OFF, cold = true, preReadMbPerSecond = 0)
        val described = variant.describe(directIoApplies = false)

        assertEquals(
            "Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's; debug properties: load.pace, repack, copypace, cold): " +
                "pre-read off, llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 cold=on",
            LoadSettings.loadLine("gemma-4-E2B_q4_0-it.gguf", ModelDefaults.GEMMA4_E2B, variant, described)
        )
        // A property set to the model's own value changes nothing, and the line says nothing of it.
        assertEquals(
            "Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's): pre-read 120 MB/s, $run4",
            LoadSettings.loadLine("gemma-3-1b-it-Q8_0.gguf", ModelDefaults.GEMMA3_1B, settings(LoadSettings.FAST, "debug.satori.llm.copypace" to "120"), run4)
        )
    }
}
