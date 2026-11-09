package com.arm.aichat.internal

import java.util.Locale

/**
 * How a model load reads its file in and how llama.cpp loads it and sets up its context (ai_chat.cpp's
 * load and prepare). Since 0.6.1 every load of the app, a release build's too, has its model's settings
 * ([ModelDefaults]): on 2026-10-15 a Rewrite after Transcribe again loaded Gemma 3 1B with 0.6.0's
 * ([UNPACED]) and the low-memory killer ended 14 cached apps in 0.6 s ("device is in direct
 * reclaim", MemAvailable 2,861 -> 2,325 MB), while the same model loaded with [FAST] that morning
 * (the memory session's run 4, from 3,361 MB) ended none and caused no direct reclaim. The owner's
 * rule: Satori must not push the phone into memory pressure.
 *
 * A debug build lets the memtest's load (com.arm.aichat.InferenceEngine.withDebugLoadSettings) and
 * the loads of an armed window (armDebugLoadSettings) take any setting from a system property
 * instead ([forLoad]), to measure on the phone what each does to a load's memory. Set one with
 * `adb shell setprop <name> <value>`; an empty value is the model's own setting:
 *
 * - `debug.satori.load.pace` off|10-1000 (MB/s): [preReadMbPerSecond], the pace at which the app reads
 *   the model's file into the page cache before llama.cpp maps it (com.arm.aichat.InferenceEngine.LoadHooks):
 *   llama.cpp maps the whole GGUF with MAP_POPULATE, which on 2026-10-13 read Gemma 4 E2B's 3.2 GB in
 *   2.0-2.6 s, and two thirds of its kills came then, before any weight was copied. Off reads nothing
 *   in first; the load still waits for the one-load gate. The speech models' loads read this property
 *   on their own (the app's ModelLoadPacing), where empty is their 120 MB/s since 0.7.1.
 * - `debug.satori.llm.repack` on|off: llama.cpp's extra buffer types (`use_extra_bufts`), with which
 *   the Q4_0 and Q8_0 matrix weights are copied at load into the layouts of the KleidiAI and
 *   repack kernels, in anonymous memory beside the file's mapping. On the phone (2026-10-13) that
 *   was 1,314 MiB of Gemma 4 E2B's 3,194 (999 KleidiAI, 315 repack): the other 1,880 MiB, read in
 *   place, are mostly its embeddings, the per-layer ones too large to be among the copied; and
 *   1,013 MiB of Gemma 3 1B (Q8_0), the whole model. Off, every weight is read from the mapping,
 *   with the plain kernels, which are slower.
 * - `debug.satori.llm.mmap` on|off: `use_mmap`. Off, every weight is read into anonymous memory,
 *   the embeddings too, through the page cache.
 * - `debug.satori.llm.directio` on|off: `use_direct_io`, reads with O_DIRECT past the page cache.
 *   llama.cpp then turns mmap off, so every weight is in anonymous memory, and each tensor is read
 *   through a buffer of its own size. Where the file doesn't open that way it keeps mmap (the
 *   description says `directio=unavailable`).
 * - `debug.satori.llm.swa_full` on|off: `swa_full`. Off, the KV cache of the sliding-window layers
 *   holds the window and a batch (n_swa 512 + 512 cells) instead of the whole context: 12 of
 *   Gemma 4 E2B's 15 cache layers, 22 of Gemma 3 1B's 26. Such a cache can't be rewound to the end
 *   of a system prompt, so each prompt starts from a full reset (ai_chat.cpp's processSystemPrompt).
 * - `debug.satori.llm.kv` f16|q8_0: `type_k` and `type_v`. q8_0 halves the KV cache; a quantized V
 *   cache needs flash attention, which q8_0 then sets on (`flash_attn_type`) where f16 leaves it to
 *   llama.cpp (auto, which turns it on for the CPU).
 * - `debug.satori.llm.ctx` 1024-8192: `n_ctx`, the context's cells (4,096).
 * - `debug.satori.llm.outputs` 1-512: `n_outputs_max`, how many tokens of a batch may ask for
 *   logits. llama.cpp's own is the whole batch, 512, and the compute buffer is reserved for
 *   that many: 512 of its 521 MiB for Gemma 4 E2B (262,144 F32 logits a token), never touched but
 *   counted in the native heap. The app asks for one logit at most per batch (the prompt's last
 *   token, then each token it generates), so 1 is enough, and the reservation falls by about
 *   400-460 MiB with RSS about the same. A batch that asked for more would end the process
 *   (GGML_ASSERT in llama_context::output_reserve); none of the app's does, and the benchmark makes
 *   its own context with llama.cpp's.
 * - `debug.satori.llm.copypace` off|10-1000 (MB/s): paces llama.cpp's copy of the weights into the
 *   repacked buffers, tensor by tensor, through its progress callback (the app's pacer,
 *   com.arm.aichat.InferenceEngine.WeightPacer): on 2026-10-13 E2B copied 1.3 GB at about 1 GiB/s
 *   and 32 of its 97 kills came then. The tensors left in the mapping cost nothing and aren't
 *   paced. A single tensor isn't split: with the tied output repacked, E2B's 315 MiB token_embd is
 *   one step, and the 1B's output copy needs a 288 MiB zero-filled temporary.
 * - `debug.satori.llm.tiedout` repack|mapped: mapped turns the extra buffer types off and sends
 *   only the layers' quantized matrices ([TIED_OUTPUT_LAYERS]) to KleidiAI's, so the tied output,
 *   token_embd, serves the output from the mapping as it serves the lookup: E2B's 315 MiB
 *   CPU_REPACK copy and the 1B's 306 MiB second copy (and its temporary) go, and the largest step
 *   is 10 MiB. ai-chat's `CPU_KLEIDIAI model buffer size` then reads 999.44 MiB for E2B and 707.03
 *   MiB for the 1B, with no CPU_REPACK buffer (run 4 on the phone read 707.03 MiB and no
 *   CPU_REPACK); the output's matrix product runs on the plain kernels (generation estimated 2-7%
 *   slower). With repack off nothing is repacked either way.
 * - `debug.satori.llm.populate` on|off: off maps the GGUF without MAP_POPULATE
 *   (lib-llama-android/patches/0002-map-populate-switch.patch, llama_satori_set_map_populate),
 *   so llama.cpp no longer reads the whole file into the page cache before it uses a weight. The
 *   pages come in as they are used instead: the copied tensors' as they are copied (at the copy's
 *   pace), the mapped ones as inference reads them, and the pre-read reads in only the tensors read
 *   in place ([InPlaceTensors]). E2B's 1.8 GB of per-layer embeddings, of which a prompt reads a few
 *   rows, mostly never come in, so its page cache at load falls from 3.2 to about 1.3 GB. The 1B
 *   reads every weight anyway.
 * - `debug.satori.llm.cold` on|off: no llama.cpp setting but a measuring aid. On, the model file's
 *   pages in the page cache are dropped before the load, so the load reads it from flash, as after a
 *   long pause, and memtests compare like with like. Off for every model. Since 0.7.2 the drop
 *   pages them out with MADV_PAGEOUT (ai_chat.cpp's drop_cached_pages): posix_fadvise DONTNEED, the
 *   drop through 0.7.1, does nothing for a file under Android/data, which the app reaches through
 *   FUSE with a BPF backing, so cold memtests on the phone started partly warm.
 * - `debug.satori.llm.dropcopied` on|off: [dropCopied], whether the pages of the tensors a load copies
 *   out of the file's mapping (the repacked ones) go back to the kernel once the last tensor is
 *   loaded (lib-llama-android/patches/0003-mapped-hook.patch, ai_chat.cpp's on_mapped):
 *   MADV_PAGEOUT takes them out of the page cache, MADV_DONTNEED out of the mapping, on the copied
 *   tensors' whole pages only, so the tensors read in place stay mapped and cached. Through 0.7.1
 *   they stayed mapped as long as the model was loaded: 707 MB of Gemma 3 1B's 1,019 MB (its RssFile
 *   1,151 MB during its first answer, r071-en-3-warm), 999 MB of Gemma 4 E2B's. On for the fast rung
 *   and for any other model, off where nothing is copied ([LEAN]) and in llama.cpp's own ([UNPACED]).
 *   The next load of the same model reads those pages from flash again (about 707 MB for the 1B).
 *
 * A value it doesn't know is ignored with a warning, and that setting stays the model's.
 */
internal data class LoadSettings(
    val repack: Boolean = true,
    val mmap: Boolean = true,
    val directIo: Boolean = false,
    val swaFull: Boolean = true,
    val kvQ8: Boolean = false,
    val nCtx: Int = DEFAULT_CONTEXT,
    val nOutputsMax: Int = BATCH_OUTPUTS,
    val copyPaceMbPerSecond: Int = PACE_OFF,
    val tiedOutputMapped: Boolean = false,
    val populate: Boolean = true,
    val cold: Boolean = false,
    val preReadMbPerSecond: Int = PACE_OFF,
    val dropCopied: Boolean = false
) {
    /**
     * llama.cpp's settings as a load's lines give them, after "llama.cpp", e.g. `llama.cpp
     * repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped
     * copypace=120`: what llama.cpp does with them, so [directIoApplies], whether the model's file
     * opens with O_DIRECT, decides mmap and directio when direct IO is asked for, and the tied output
     * is named mapped only where the layers are repacked at all. The settings added since 0.5.2 are
     * named only when they aren't llama.cpp's own (outputs, tiedout, copypace, dropcopied, populate,
     * cold), so 0.6.0's line stays as it was; dropcopied only where something is copied out of a
     * mapping (repack on, mmap on). The pre-read is the app's, not llama.cpp's: the load's own line
     * ([loadLine]) gives it.
     */
    fun describe(directIoApplies: Boolean): String {
        val direct = directIo && directIoApplies
        val directText = when {
            !directIo -> "off"
            direct -> "on"
            else -> "unavailable"
        }
        return "llama.cpp repack=${onOff(repack)} mmap=${onOff(mmap && !direct)} directio=$directText " +
            "swa_full=${onOff(swaFull)} kv=${if (kvQ8) "q8_0" else "f16"} fa=${if (kvQ8) "on" else "auto"} ctx=$nCtx" +
            (if (nOutputsMax != BATCH_OUTPUTS) " outputs=$nOutputsMax" else "") +
            (if (tiedOutputMapped && repack) " tiedout=mapped" else "") +
            (if (copyPaceMbPerSecond != PACE_OFF) " copypace=$copyPaceMbPerSecond" else "") +
            (if (dropsCopied(direct)) " dropcopied=on" else "") +
            (if (!populate) " populate=off" else "") +
            if (cold) " cold=on" else ""
    }

    /** Whether a load with these settings gives the copied tensors' pages back: something is copied out of a mapping. */
    fun dropsCopied(direct: Boolean): Boolean = dropCopied && repack && mmap && !direct

    companion object {
        /** ai_chat.cpp's DEFAULT_CONTEXT_SIZE, the context of every load. */
        const val DEFAULT_CONTEXT = 4096
        const val MIN_CONTEXT = 1024
        const val MAX_CONTEXT = 8192

        /** `n_outputs_max` 0, llama.cpp's own: as many as the batch (ai_chat.cpp's BATCH_SIZE, 512). */
        const val BATCH_OUTPUTS = 0
        const val MIN_OUTPUTS = 1
        const val MAX_OUTPUTS = 512

        /** No pace: a pre-read that reads nothing in first, or a weight copy at llama.cpp's own speed. */
        const val PACE_OFF = 0
        const val MIN_PACE = 10
        const val MAX_PACE = 1000

        /**
         * The pace of the pre-read and of the weight copy, R of MEMORY_RESEARCH_PLAN: the probe at
         * 120 MB/s on 2026-10-15 (run 1) read 2,994 MB with no direct reclaim, and its lowest MemFree
         * of 239 MB ruled out the faster probe. The one phase that took memory at about 156 MB/s on
         * 2026-10-13 caused no direct reclaim either; every one at about 270 MB/s or more did.
         */
        const val PACE_MB_PER_S = 120

        /**
         * The tensors `tiedout=mapped` sends to KleidiAI's buffer type (llama.cpp's
         * tensor_buft_overrides, an ECMAScript regex searched in each tensor's name): the layers'
         * quantized matrices, attention, feed-forward and E2B's per-layer input gate and
         * projection. Nothing else: an override skips llama.cpp's check that the buffer type takes
         * the tensor, and one that caught a norm or an embedding would end the load in the repack
         * code. In Gemma 4 E2B it catches the 275 Q4_0 matrices (999.4 MiB), in Gemma 3 1B the 182
         * Q8_0 ones (707.0 MiB). For that reason only these two models map their tied output by
         * default ([ModelDefaults]): another file's matrices may be of a type KleidiAI doesn't take.
         */
        const val TIED_OUTPUT_LAYERS = "blk\\.[0-9]+\\.(attn_q|attn_k|attn_v|attn_output|ffn_gate|ffn_up|ffn_down|inp_gate|proj)\\.weight"

        /**
         * 0.6.0's settings, llama.cpp's own with nothing paced: every load's until 0.6.1, no model's
         * since. The debug properties can still set a load back to any of them.
         */
        val UNPACED = LoadSettings()

        /**
         * The fast rung, the memory session's runs 3 and 4: the file read in at [PACE_MB_PER_S]
         * first, the layers' matrices repacked by KleidiAI at the same pace with the tied output left
         * in the mapping, a windowed SWA cache, a q8_0 KV cache and one output a batch. Run 4 on
         * 2026-10-15, Gemma 3 1B: 0 kills, 0 MB of direct reclaim, lowest MemFree 226 MB, CPU_KLEIDIAI
         * 707.03 MiB and no CPU_REPACK, the copy of 707 MB in 6.7 s, the load in 14.7 s, 19.0
         * tokens/s. Since 0.7.2 the copied matrices' pages go back after the copy ([dropCopied]).
         */
        val FAST = LoadSettings(
            swaFull = false,
            kvQ8 = true,
            nOutputsMax = 1,
            copyPaceMbPerSecond = PACE_MB_PER_S,
            tiedOutputMapped = true,
            preReadMbPerSecond = PACE_MB_PER_S,
            dropCopied = true
        )

        /**
         * The lean rung, the memory session's runs 6 and 8: [FAST]'s pre-read and context with
         * nothing repacked, so nothing is copied and no copy is paced. Every weight is read from the
         * mapping (about 0.15-0.2 GB of anonymous memory for Gemma 4 E2B instead of about 1.1 GB),
         * with the plain kernels: the prompt an estimated 1.5-3.5 times slower, generation 5-50%.
         */
        val LEAN = LoadSettings(
            repack = false,
            swaFull = false,
            kvQ8 = true,
            nOutputsMax = 1,
            preReadMbPerSecond = PACE_MB_PER_S
        )

        /**
         * A model without settings of its own ([ModelDefaults.OTHER]): llama.cpp's own settings, as
         * through 0.6.0, with the file read in and the weights copied at [PACE_MB_PER_S]. The pace
         * only spreads the same memory over time, whatever the model; the rest of [FAST] changes what
         * llama.cpp builds and was measured on Gemma 3 1B only.
         */
        val PACED = LoadSettings(copyPaceMbPerSecond = PACE_MB_PER_S, preReadMbPerSecond = PACE_MB_PER_S, dropCopied = true)

        const val PROPERTY_PREFIX = "debug.satori.llm."

        /** The pre-read's property, which the speech models' loads read too (the app's LoadPace). */
        const val PRE_READ_PROPERTY = "debug.satori.load.pace"

        /**
         * The settings of one load of a model whose own are [defaults]: those, with no property read,
         * unless the build is [debuggable] and the load is the memtest's or in an armed window
         * ([armed]: com.arm.aichat.InferenceEngine.withDebugLoadSettings, armDebugLoadSettings); then
         * what the properties ask for ([fromProperties]). The phone runs a debug build every day, and
         * a property stays set until it is cleared or the phone restarts: one left behind by a
         * memtest.sh that lost the phone (Wi-Fi ADB gone, the Mac asleep) would otherwise change
         * every load of the app's own work, say turn its pre-read off or load Gemma 4 E2B cold.
         */
        fun forLoad(
            defaults: LoadSettings,
            debuggable: Boolean,
            armed: Boolean,
            read: (String) -> String,
            warn: (String) -> Unit
        ): LoadSettings = if (debuggable && armed) fromProperties(defaults, read, warn) else defaults

        /**
         * [defaults] with what the debug properties ask for, each read with [read] ("" for a property
         * that isn't set, which keeps the setting of [defaults]); [warn] hears of a value that is
         * ignored.
         */
        fun fromProperties(defaults: LoadSettings, read: (String) -> String, warn: (String) -> Unit): LoadSettings {
            fun text(property: String): String = read(property).trim().lowercase(Locale.ROOT)
            fun value(name: String): String = text(PROPERTY_PREFIX + name)

            fun switch(name: String, default: Boolean): Boolean = when (val text = value(name)) {
                "" -> default
                "on", "1", "true" -> true
                "off", "0", "false" -> false
                else -> default.also { warn("Ignoring $PROPERTY_PREFIX$name=$text: not on or off") }
            }

            fun number(name: String, range: IntRange, default: Int): Int {
                val text = value(name)
                if (text.isEmpty()) return default
                return text.toIntOrNull()?.takeIf { it in range }
                    ?: default.also { warn("Ignoring $PROPERTY_PREFIX$name=$text: not ${range.first}-${range.last}") }
            }

            val kv = when (val text = value("kv")) {
                "" -> defaults.kvQ8
                "f16" -> false
                "q8_0" -> true
                else -> defaults.kvQ8.also { warn("Ignoring ${PROPERTY_PREFIX}kv=$text: not f16 or q8_0") }
            }
            val copyPace = if (value("copypace") == "off") PACE_OFF else number("copypace", MIN_PACE..MAX_PACE, defaults.copyPaceMbPerSecond)
            val tiedOut = when (val text = value("tiedout")) {
                "" -> defaults.tiedOutputMapped
                "repack" -> false
                "mapped" -> true
                else -> defaults.tiedOutputMapped.also { warn("Ignoring ${PROPERTY_PREFIX}tiedout=$text: not repack or mapped") }
            }
            // As the speech models' loads read it (LoadPace): off, or MB/s with or without the unit.
            val preRead = when (val text = text(PRE_READ_PROPERTY)) {
                "" -> defaults.preReadMbPerSecond
                "off" -> PACE_OFF
                else -> text.removeSuffix("mb/s").trim().toIntOrNull()?.takeIf { it in MIN_PACE..MAX_PACE }
                    ?: defaults.preReadMbPerSecond.also { warn("Ignoring $PRE_READ_PROPERTY=$text: not off or $MIN_PACE-$MAX_PACE (MB/s)") }
            }
            return LoadSettings(
                repack = switch("repack", defaults.repack),
                mmap = switch("mmap", defaults.mmap),
                directIo = switch("directio", defaults.directIo),
                swaFull = switch("swa_full", defaults.swaFull),
                kvQ8 = kv,
                nCtx = number("ctx", MIN_CONTEXT..MAX_CONTEXT, defaults.nCtx),
                nOutputsMax = number("outputs", MIN_OUTPUTS..MAX_OUTPUTS, defaults.nOutputsMax),
                copyPaceMbPerSecond = copyPace,
                tiedOutputMapped = tiedOut,
                populate = switch("populate", defaults.populate),
                cold = switch("cold", defaults.cold),
                preReadMbPerSecond = preRead,
                dropCopied = switch("dropcopied", defaults.dropCopied)
            )
        }

        /**
         * The line every load logs as it begins, a release build's too: the model's file, whose
         * settings it has and which of them the debug properties changed, then the pre-read's pace
         * and llama.cpp's settings ([described], from [describe]). Nothing of a prompt or a recording.
         * E.g. `Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's): pre-read 120 MB/s,
         * llama.cpp repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1
         * tiedout=mapped copypace=120`, or for a memtest's variant `Load settings of
         * gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's; debug properties: repack, cold): pre-read 120
         * MB/s, llama.cpp repack=off ...`.
         */
        fun loadLine(fileName: String, model: ModelDefaults, chosen: LoadSettings, described: String): String {
            val changed = changedFrom(model.settings, chosen)
            val whose = if (changed.isEmpty()) model.whose else "${model.whose}; debug properties: ${changed.joinToString(", ")}"
            val preRead = if (chosen.preReadMbPerSecond == PACE_OFF) "off" else "${chosen.preReadMbPerSecond} MB/s"
            return "Load settings of $fileName ($whose): pre-read $preRead, $described"
        }

        // The properties whose settings [chosen] has other than [defaults], by the names memtest.sh gives.
        private fun changedFrom(defaults: LoadSettings, chosen: LoadSettings): List<String> = listOfNotNull(
            "load.pace".takeIf { chosen.preReadMbPerSecond != defaults.preReadMbPerSecond },
            "repack".takeIf { chosen.repack != defaults.repack },
            "mmap".takeIf { chosen.mmap != defaults.mmap },
            "directio".takeIf { chosen.directIo != defaults.directIo },
            "swa_full".takeIf { chosen.swaFull != defaults.swaFull },
            "kv".takeIf { chosen.kvQ8 != defaults.kvQ8 },
            "ctx".takeIf { chosen.nCtx != defaults.nCtx },
            "outputs".takeIf { chosen.nOutputsMax != defaults.nOutputsMax },
            "copypace".takeIf { chosen.copyPaceMbPerSecond != defaults.copyPaceMbPerSecond },
            "tiedout".takeIf { chosen.tiedOutputMapped != defaults.tiedOutputMapped },
            "populate".takeIf { chosen.populate != defaults.populate },
            "dropcopied".takeIf { chosen.dropCopied != defaults.dropCopied },
            "cold".takeIf { chosen.cold != defaults.cold }
        )

        private fun onOff(value: Boolean) = if (value) "on" else "off"
    }
}

/**
 * The settings each model loads with ([LoadSettings.forLoad]'s defaults), by the name of its file
 * (as the app's ModelConfig names them, which data's AiModelFilesTest holds to these): the one place
 * where a model's are chosen.
 *
 * @property whose How the load's line names them ([LoadSettings.loadLine])
 */
internal enum class ModelDefaults(private val fileName: String?, val whose: String, val settings: LoadSettings) {
    /**
     * Gemma 3 1B (Q8_0), the model of the live notes and the fallback of the work after Stop
     * (the finisher, Rewrite notes, Find to-dos and ideas again, the map, Translate): the fast rung,
     * measured on the phone (run 4).
     */
    GEMMA3_1B("gemma-3-1b-it-Q8_0.gguf", "Gemma 3 1B's", LoadSettings.FAST),

    /**
     * Gemma 4 E2B, the model of the work after Stop: the fast rung, the 1B's settings (run 3),
     * which the phone hasn't measured on E2B yet. Should run 3 end apps or reclaim directly, E2B
     * loads with [LoadSettings.LEAN] instead (run 6: repack off, no copy pace): change it here, and
     * the expectation in LoadSettingsTest with it.
     */
    GEMMA4_E2B("gemma-4-E2B_q4_0-it.gguf", "Gemma 4 E2B's", LoadSettings.FAST),

    /**
     * Any other model file (Gemma 3 1B IQ4_NL, Qwen 3.5 0.8B, a file renamed by hand): paced,
     * with llama.cpp's own settings otherwise, since no other one was measured.
     */
    OTHER(null, "any other model's", LoadSettings.PACED);

    companion object {
        /** The settings of the model whose file is called [fileName]. */
        fun of(fileName: String): ModelDefaults = entries.firstOrNull { it.fileName == fileName } ?: OTHER
    }
}
