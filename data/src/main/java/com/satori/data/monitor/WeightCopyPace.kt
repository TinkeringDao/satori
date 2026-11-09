package com.satori.data.monitor

import java.util.Locale

/** One load's weight copy as the app paces it ([PacedAiLoad.weightCopy]). */
interface WeightCopy {
    /**
     * Before llama.cpp's next tensor, with [doneBytes] of the model's weights behind it; returns
     * when it may go: true to go on, false to stop the load, which then loads nothing.
     */
    fun beforeTensor(doneBytes: Long): Boolean

    /** The load's native part has ended. */
    fun ended()
}

/**
 * Paces llama.cpp's copy of a model's weights into its repacked buffers, at the load's copy pace
 * (its model's, 120 MB/s, or a debug build's memtest's `debug.satori.llm.copypace`), from the
 * loader's progress before each tensor: on 2026-10-13 Gemma 4 E2B copied 1.3 GB at about 1 GiB/s
 * and 32 of its 97 kills came then, while the 1B's copy at about 156 MB/s caused no direct reclaim.
 *
 * The progress counts every tensor, also the ones left in the mapping, which cost no memory and
 * take microseconds whatever their size (E2B's 1.8 GB of per-layer embeddings among them). So a
 * tensor that went faster than [MAPPED_BYTES_PER_NS] (100 GB/s) counts as mapped and isn't paced;
 * every other one is, at the [Pace] of the load, which also waits while MemFree is low and while the
 * recorder's own load runs. A run of the vendored llama.cpp on the two models' headers (KleidiAI's
 * i8mm kernels, as on the phone) put every mapped tensor of a MB or more above 50 GB/s and every
 * copy below 14 GB/s, from cached zero pages on a laptop; the phone copied at about 1 GB/s. The
 * counts it gave, the copied bytes, matched the repacked buffers to the MB.
 *
 * The rate counts from the first copied tensor ([Pace.restartAt]): what llama.cpp does before it
 * (the vocabulary, the tensors' setup, the file's mapping) gives no credit. Once the load has waited
 * for memory as long as it may (10 s in all of MemFree under the floor, its pre-read's waits among
 * them), [beforeTensor] stops the load, as a paced load's pre-read does
 * ([PacedLoadStoppedException]). [ended] logs one line:
 * ```
 * Weight copy of <model> at <r> MB/s: <n> MB copied, <m> MB left in the mapping, in <ms> ms; waited <ms> ms for memory (lowest MemFree <n> MB), <ms> ms for the recorder's load
 * Weight copy of <model> at <r> MB/s: ...; waited <ms> ms for memory in <k> waits (longest <ms> ms: <a> until MemFree came back, <b> stood still; floor <f> MB, lowest MemFree <n> MB), <ms> ms for the recorder's load
 * ```
 * with `; the load is stopped: MemFree under <f> MB for 10 s in all` added, as a warning, when it
 * stopped it (the copy's own waits are the line's; the pre-read's line gives the rest), <f> the
 * floor in effect ([MemoryFloor]).
 *
 * @param clockNs Nanoseconds that never jump
 */
class WeightCopyPace internal constructor(
    private val name: String,
    private val mbPerSecond: Int,
    private val pace: Pace,
    private val clockNs: () -> Long,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit = log
) : WeightCopy {
    private val startNs = clockNs()
    private var lastDoneBytes = 0L
    private var lastEndNs: Long? = null
    private var copiedBytes = 0L
    private var mappedBytes = 0L

    /**
     * Before llama.cpp's next tensor, with [doneBytes] of the weights behind it: the tensor before
     * is paced if it was copied, then the pace may hold the next one back, or stop the load.
     */
    override fun beforeTensor(doneBytes: Long): Boolean {
        val previousEnd = lastEndNs
        val bytes = doneBytes - lastDoneBytes
        if (previousEnd != null && bytes > 0) {
            val tookNs = clockNs() - previousEnd
            if (tookNs * MAPPED_BYTES_PER_NS < bytes) {
                mappedBytes += bytes
            } else {
                // The first copied tensor began where the one before it ended.
                if (copiedBytes == 0L) pace.restartAt(previousEnd)
                copiedBytes += bytes
                pace.took(bytes)
            }
        }
        pace.awaitTurn()
        lastDoneBytes = doneBytes
        lastEndNs = clockNs()
        return !pace.gaveUp
    }

    /** The load's native part has ended; logs the copy's line. */
    override fun ended() {
        val line = "Weight copy of $name at $mbPerSecond MB/s: ${mb(copiedBytes)} MB copied, ${mb(mappedBytes)} MB left in the mapping, " +
            "in ${(clockNs() - startNs) / NS_PER_MS} ms; ${pace.waitSummary()}, ${pace.yieldNs / NS_PER_MS} ms for the recorder's load"
        if (pace.gaveUp) {
            warn("$line; the load is stopped: MemFree under ${pace.floorMb} MB for ${Pace.GIVE_UP_MS / 1_000} s in all")
        } else {
            log(line)
        }
    }

    private companion object {
        /** Faster than this, a tensor wasn't copied: 100 bytes a nanosecond, 100 GB/s. */
        const val MAPPED_BYTES_PER_NS = 100L
        const val NS_PER_MS = 1_000_000L

        fun mb(bytes: Long): String = String.format(Locale.ROOT, "%d", bytes / 1_048_576L)
    }
}
