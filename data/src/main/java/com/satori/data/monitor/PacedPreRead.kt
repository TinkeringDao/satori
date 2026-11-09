package com.satori.data.monitor

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException

/**
 * Reads a model's files into the page cache before the model loads, [CHUNK_BYTES] at a time at
 * the [Pace] of the load, so the load then finds them cached: llama.cpp maps its whole GGUF with
 * MAP_POPULATE, which on 2026-10-13 read Gemma 4 E2B's 3.2 GB in 2.0-2.6 s and caused two thirds of
 * its kills before any weight was copied, and ONNX Runtime reads the speech models' files as it
 * parses or maps them. A load that maps its GGUF without MAP_POPULATE reads only some of it from
 * the mapping, and only those byte ranges are read in then ([Part.ranges]).
 *
 * Only what isn't cached yet costs memory, so a piece already in the page cache ([FileResidency])
 * is neither read nor paced: a file still cached from its last load takes about no time. A file
 * whose cached pages can't be seen is read and paced in full. Once the pace has waited for memory
 * as long as it may ([Pace.gaveUp]) reading stops: the caller then stops its load, or, for the
 * recorder's own load, lets the load go ahead unpaced.
 *
 * @param residency What of a file is cached already
 * @param chunkBytes The piece read and paced at a time
 */
internal class PacedPreRead(
    private val residency: FileResidency = OsFileResidency,
    private val chunkBytes: Int = CHUNK_BYTES
) {
    /**
     * A file to read in: all of it, or only [ranges] of it (byte offsets, first..last, in any
     * order), each widened to whole [ALIGN_BYTES] blocks, which page boundaries fall on.
     */
    class Part(val file: File, val ranges: List<LongRange>? = null)

    /**
     * What a pre-read did: [newBytes] of the [totalBytes] it was to read weren't cached and were
     * read in; [gaveUp] when the pace stopped waiting for memory (reading stopped there);
     * [unreadable] names the files it couldn't read, with the exception's class, and [unseen] those
     * whose cached pages it couldn't see, so read in full: the file's name to why.
     */
    class Outcome(
        val newBytes: Long,
        val totalBytes: Long,
        val gaveUp: Boolean,
        val unreadable: List<String>,
        val unseen: List<Pair<String, String>> = emptyList()
    )

    /**
     * Reads [parts] at [pace] until it has read them or the pace has given up waiting for memory;
     * [cancelled] ends it (and the load) with a [CancellationException] at its next piece or while
     * it waits.
     */
    fun read(parts: List<Part>, pace: Pace, cancelled: () -> Boolean = { false }): Outcome {
        val buffer = ByteArray(chunkBytes)
        val unreadable = mutableListOf<String>()
        val unseen = mutableListOf<Pair<String, String>>()
        var newBytes = 0L
        val spans = parts.map { it to spansOf(it) }
        val total = spans.sumOf { (_, list) -> list.sumOf { it.last - it.first + 1 } }
        for ((part, list) in spans) {
            val file = part.file
            try {
                RandomAccessFile(file, "r").use { input ->
                    val view = residency.open(file)
                    if (view is FileResidency.Unseen) unseen += file.name to view.why
                    try {
                        val channel = input.channel
                        for (span in list) {
                            var offset = span.first
                            while (offset <= span.last) {
                                if (cancelled()) throw CancellationException("The pre-read was cancelled")
                                val length = minOf(chunkBytes.toLong(), span.last - offset + 1)
                                val missing = (view as? FileResidency.Cached)?.missingBytes(offset, length) ?: length
                                if (missing > 0) {
                                    pace.awaitTurn(cancelled)
                                    if (pace.gaveUp) return Outcome(newBytes, total, gaveUp = true, unreadable, unseen)
                                    val n = readAt(channel, buffer, offset, length.toInt())
                                    if (n <= 0) break
                                    val taken = minOf(missing, n.toLong())
                                    newBytes += taken
                                    pace.took(taken)
                                }
                                offset += length
                            }
                        }
                    } finally {
                        (view as? FileResidency.Cached)?.close()
                    }
                }
            } catch (e: IOException) {
                unreadable += "${file.name} (${e.javaClass.simpleName})"
            }
        }
        return Outcome(newBytes, total, gaveUp = pace.gaveUp, unreadable, unseen)
    }

    // What of the part's file is read: all of it, or its ranges widened to whole blocks, sorted and joined.
    private fun spansOf(part: Part): List<LongRange> {
        val length = part.file.length()
        if (length <= 0) return emptyList()
        val ranges = part.ranges ?: return listOf(0L until length)
        val widened = ranges
            .filter { !it.isEmpty() && it.first < length }
            .map { (it.first / ALIGN_BYTES * ALIGN_BYTES)..(minOf(length, (it.last / ALIGN_BYTES + 1) * ALIGN_BYTES) - 1) }
            .sortedBy { it.first }
        val joined = mutableListOf<LongRange>()
        for (range in widened) {
            val last = joined.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                joined[joined.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                joined += range
            }
        }
        return joined
    }

    // Reads up to [length] bytes of the file at [offset] into [buffer]; how many, 0 at its end.
    private fun readAt(channel: FileChannel, buffer: ByteArray, offset: Long, length: Int): Int {
        val target = ByteBuffer.wrap(buffer, 0, length)
        while (target.hasRemaining()) {
            if (channel.read(target, offset + target.position()) < 0) break
        }
        return target.position()
    }

    companion object {
        /** 8 MiB: a few hundred reads for E2B's 3.2 GB, each paced on its own. */
        const val CHUNK_BYTES = 8 * 1024 * 1024

        /**
         * A range is read in whole blocks of 16 KiB, which the page boundaries of 4 and 16 KiB pages
         * fall on, so mincore(2) is asked about whole pages. Gemma 4 E2B's 265 tensors read in place
         * (most of them norms of a few KiB between the layers' matrices) come to 344 MB so instead
         * of 342.
         */
        const val ALIGN_BYTES = 16_384L
    }
}

/** Which pages of a file are in the page cache already. */
internal fun interface FileResidency {

    /** What of [file] is cached ([Cached]), or why that can't be seen ([Unseen]): then every piece counts as new. */
    fun open(file: File): View

    /** The page cache view of one file. */
    sealed interface View

    /** One file's pages, for as long as it is open. */
    interface Cached : View, Closeable {
        /** How many of the [length] bytes of the file from [offset] (a multiple of the page) aren't cached. */
        fun missingBytes(offset: Long, length: Long): Long
    }

    /** The file's cached pages can't be seen, for the reason given. */
    class Unseen(val why: String) : View
}

/**
 * Whether mincore(2) tells this process the truth about a file's pages in the page cache. Since
 * Linux 5.0 (can_do_mincore in mm/mincore.c) it does only for a file the process owns or could open
 * for writing; for any other it reports every page cached, whatever is, which would make the
 * pre-read of a cold file read nothing and the load then read all of it at full speed.
 *
 * @param fileUid The file's owner
 * @param processUid This process's user
 * @param writable Whether this process could open the file for writing
 */
internal fun mincoreSeesPages(fileUid: Int, processUid: Int, writable: () -> Boolean): Boolean =
    fileUid == processUid || writable()

/**
 * [FileResidency] from the kernel: the file mapped (no page is touched) and mincore(2) on each
 * piece, for a file whose pages mincore tells the truth about ([mincoreSeesPages]). Any other file,
 * and every failure, is [FileResidency.Unseen], so the pre-read reads and paces it in full.
 */
internal object OsFileResidency : FileResidency {

    override fun open(file: File): FileResidency.View = try {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            val stat = Os.fstat(fd)
            when {
                stat.st_size <= 0 -> Empty
                !mincoreSeesPages(stat.st_uid, Os.getuid(), writable = { writable(file) }) ->
                    FileResidency.Unseen("neither the app's nor writable by it, so mincore would call every page cached")
                else -> Mapped(Os.mmap(0, stat.st_size, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, 0), stat.st_size)
            }
        } finally {
            // The mapping stays valid without the descriptor.
            Os.close(fd)
        }
    } catch (e: ErrnoException) {
        FileResidency.Unseen(OsConstants.errnoName(e.errno) ?: "errno ${e.errno}")
    }

    private fun writable(file: File): Boolean = try {
        Os.access(file.path, OsConstants.W_OK)
    } catch (e: ErrnoException) {
        false
    }

    // A file with nothing in it has nothing to read.
    private object Empty : FileResidency.Cached {
        override fun missingBytes(offset: Long, length: Long): Long = 0
        override fun close() {}
    }

    private class Mapped(private val address: Long, private val size: Long) : FileResidency.Cached {
        private val page = Os.sysconf(OsConstants._SC_PAGESIZE).coerceAtLeast(4096L)

        override fun missingBytes(offset: Long, length: Long): Long {
            val bytes = minOf(length, size - offset)
            if (bytes <= 0) return 0
            val pages = ((bytes + page - 1) / page).toInt()
            val vector = ByteArray(pages)
            return try {
                Os.mincore(address + offset, bytes, vector)
                minOf(bytes, vector.count { it.toInt() and 1 == 0 } * page)
            } catch (e: ErrnoException) {
                bytes
            }
        }

        override fun close() {
            try {
                Os.munmap(address, size)
            } catch (e: ErrnoException) {
                // Only address space is left behind, until the process ends.
            }
        }
    }
}
