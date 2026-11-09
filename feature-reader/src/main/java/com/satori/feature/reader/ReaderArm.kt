package com.satori.feature.reader

import com.satori.reader.ReaderOptions

/**
 * Which of the reader's graphs ONNX Runtime prepacks: copies their MatMul weights into its own
 * layout in anonymous memory as the session loads, so the per-token loop runs faster (D3).
 */
enum class Prepacking(val key: String) {
    /** D3, the default: the encoder and decoder_with_past, not decoder_model, which runs once per segment. */
    SESSION("session"),

    /** All three graphs: decoder_model's first step faster, about 0.09 GB more anonymous memory (a V1 arm). */
    ALL("all"),

    /** None: every weight stays in the mapped files, each step repacks them (a V1 arm). */
    OFF("off")
}

/**
 * How the reader's sessions are made: the design's defaults (D3, D4) or one of V1's arms, which
 * the debug reader selftest compares on the phone without a rebuild (`--es arm prepack=all,arena=off,threads=2`).
 *
 * @property arena ONNX Runtime's CPU arena (D3: on, shrunk after each segment's encoder and first step)
 * @property threads Intra-op threads per session (D4: 4; the reader never runs beside a capture)
 */
data class ReaderArm(
    val prepacking: Prepacking = Prepacking.SESSION,
    val arena: Boolean = true,
    val threads: Int = DEFAULT_THREADS
) {
    init {
        require(threads in THREADS) { "threads must be ${THREADS.joinToString(" or ")}" }
    }

    /** The arm as the selftest's lines and files name it: `prepack=session,arena=on,threads=4`. */
    val label: String get() = "prepack=${prepacking.key},arena=${if (arena) "on" else "off"},threads=$threads"

    /** The session settings of this arm over the model's own [limits]. */
    fun options(limits: ReaderLimits): ReaderOptions = ReaderOptions(
        threads = threads,
        prepackEncoder = prepacking != Prepacking.OFF,
        prepackDecoder = prepacking == Prepacking.ALL,
        prepackDecoderWithPast = prepacking != Prepacking.OFF,
        cpuArena = arena,
        shrinkArenaAfterSegment = arena,
        maxSourcePositions = limits.maxSourcePositions,
        maxTargetPositions = limits.maxTargetPositions,
        sourceVocabulary = limits.sourceVocabulary,
        decoderStartId = limits.decoderStartId,
        endId = limits.endId,
        padId = limits.padId
    )

    companion object {
        /** D4. */
        const val DEFAULT_THREADS = 4

        /** The thread counts V1 compares. */
        val THREADS = listOf(2, 4)

        val DEFAULT = ReaderArm()

        /**
         * The arm a debug tool's text names: comma-separated `key=value` pairs of `prepack`
         * (session, all, off), `arena` (on, off) and `threads` (2, 4), the defaults for the keys
         * left out; empty, null or `default` is [DEFAULT].
         *
         * @return The arm, or why the text names none
         */
        fun parse(text: String?): Parsed {
            val given = text?.trim().orEmpty()
            if (given.isEmpty() || given == "default") return Parsed.Arm(DEFAULT)
            var arm = DEFAULT
            for (pair in given.split(',')) {
                val key = pair.substringBefore('=', missingDelimiterValue = "").trim()
                val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                arm = when (key) {
                    "prepack" -> Prepacking.entries.firstOrNull { it.key == value }?.let { arm.copy(prepacking = it) }
                        ?: return Parsed.Wrong("prepack takes session, all or off, not $value")
                    "arena" -> when (value) {
                        "on" -> arm.copy(arena = true)
                        "off" -> arm.copy(arena = false)
                        else -> return Parsed.Wrong("arena takes on or off, not $value")
                    }
                    "threads" -> value.toIntOrNull()?.takeIf { it in THREADS }?.let { arm.copy(threads = it) }
                        ?: return Parsed.Wrong("threads takes ${THREADS.joinToString(" or ")}, not $value")
                    else -> return Parsed.Wrong("unknown key ${pair.trim()} (keys: prepack, arena, threads)")
                }
            }
            return Parsed.Arm(arm)
        }
    }

    /** What [parse] made of a text: an arm, or why there is none. */
    sealed interface Parsed {
        data class Arm(val arm: ReaderArm) : Parsed
        data class Wrong(val why: String) : Parsed
    }
}
