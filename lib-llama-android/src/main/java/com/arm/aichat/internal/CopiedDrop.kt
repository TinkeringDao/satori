package com.arm.aichat.internal

import android.system.OsConstants
import java.util.Locale

/**
 * What a load gave back of the pages of the tensors it copied out of the file's mapping
 * ([LoadSettings.dropCopied], ai_chat.cpp's on_mapped and lastDrop), and its line ([line]), which
 * every build logs (tag InferenceEngineImpl, the model's file and figures, no text):
 * ```
 * Copied pages of gemma-3-1b-it-Q8_0.gguf given back: 707 MB of the 707 MB copied, in 78 ranges in 96 ms; 0 MB still cached; RssFile 1158 -> 452 MB
 * W Copied pages of <file> given back only in part: <k> MB of the <d> MB still cached (another mapping holds them, or the kernel kept them); RssFile <a> -> <b> MB
 * W Copied pages of <file> given back only in part: <d> MB of the <c> MB copied unmapped in <r> ranges, but MADV_PAGEOUT may not act on them (the file is neither the app's nor writable by it); RssFile <a> -> <b> MB
 * W Copied pages of <file>: madvise or posix_fadvise failed (<errno name>); <d> MB of the <c> MB copied unmapped in <r> ranges, <k> MB still cached; RssFile <a> -> <b> MB
 * ```
 * Given back counts the whole pages only the copied tensors lie on, so a little less than the
 * tensors (706.72 MiB of the 1B's 707.03).
 *
 * @property cachedBytes Of the bytes given back, those still in the page cache afterwards; null when
 *   mincore can't tell (the file is neither the app's nor writable by it)
 * @property error The errno of the first call that failed, 0 for none
 * @property pageout Whether MADV_PAGEOUT may act on the file's pages
 */
internal data class CopiedDrop(
    val copiedBytes: Long,
    val droppedBytes: Long,
    val cachedBytes: Long?,
    val ranges: Long,
    val error: Long,
    val pageout: Boolean,
    val rssFileBeforeKb: Long?,
    val rssFileAfterKb: Long?,
    val micros: Long
) {
    /** The line, and whether it is a warning. */
    fun line(fileName: String): Pair<String, Boolean> {
        val rssFile = "RssFile ${mb(rssFileBeforeKb, KB)} -> ${mb(rssFileAfterKb, KB)} MB"
        val given = "${mb(droppedBytes)} MB of the ${mb(copiedBytes)} MB copied"
        val cached = cachedBytes?.let { "${mb(it)} MB still cached" } ?: "still cached unknown"
        return when {
            error != 0L -> "Copied pages of $fileName: madvise or posix_fadvise failed (${errnoName(error)}); $given unmapped in $ranges ranges, " +
                "$cached; $rssFile" to true
            !pageout -> "Copied pages of $fileName given back only in part: $given unmapped in $ranges ranges, but MADV_PAGEOUT may not act on them " +
                "(the file is neither the app's nor writable by it); $rssFile" to true
            cachedBytes != null && cachedBytes >= LEFT_CACHED_BYTES -> "Copied pages of $fileName given back only in part: ${mb(cachedBytes)} MB of the " +
                "${mb(droppedBytes)} MB still cached (another mapping holds them, or the kernel kept them); $rssFile" to true
            else -> "Copied pages of $fileName given back: $given, in $ranges ranges in ${micros / 1_000} ms; $cached; $rssFile" to false
        }
    }

    companion object {
        private const val KB = 1_024L
        private const val MIB = 1_048_576L

        /** Still cached from this on, the drop went only in part: 1 MB, against 707 MB given back. */
        private const val LEFT_CACHED_BYTES = MIB

        /** The figures as ai_chat.cpp's lastDrop gives them, in its order; null when the load gave none back. */
        fun of(values: LongArray?): CopiedDrop? {
            if (values == null || values.size < 9) return null
            return CopiedDrop(
                copiedBytes = values[0],
                droppedBytes = values[1],
                cachedBytes = values[2].takeIf { it >= 0 },
                ranges = values[3],
                error = values[4],
                pageout = values[5] != 0L,
                rssFileBeforeKb = values[6].takeIf { it >= 0 },
                rssFileAfterKb = values[7].takeIf { it >= 0 },
                micros = values[8]
            )
        }

        private fun mb(value: Long?, unit: Long = 1L): String =
            if (value == null) "n/a" else String.format(Locale.ROOT, "%d", value * unit / MIB)

        private fun errnoName(error: Long): String = try {
            OsConstants.errnoName(error.toInt())
        } catch (e: RuntimeException) {
            null
        } ?: "errno $error"
    }
}
