package com.satori.data.monitor

import android.os.SharedMemory
import java.nio.ByteBuffer
import java.util.Random

/**
 * Memory a speech model's load holds for a moment before its native load ([ModelLoadPacing]), so
 * the native load finds it free. ONNX Runtime parses a model whose weights are in its ONNX file
 * onto the heap in about a second: on 2026-10-16 at 09:57:45-46 Parakeet v3's live load took
 * RssAnon from 54 to 714 MB in one second while MemFree was 352 MB, the phone went into direct
 * reclaim (pgsteal_direct +5,912 pages) and lmkd killed a cached app. Its files were cached, so no
 * pre-read of them could have helped: the burst is the parse's own memory.
 *
 * So, before the native load, the load takes that memory at its pace ([Pace], 120 MB/s, waiting
 * while MemFree is under the pacing floor, [MemoryFloor]), a piece at a time into a room it holds
 * ([Held.take]), until the room and MemFree together are what the parse needs ([needFor]) and the
 * floor more; then it gives the room back ([Held.close]) and the native load starts at once, with
 * that memory free. kswapd frees what the room takes at the pace it was asked for, instead of the
 * parse asking for it at once. With MemFree as high as that already, no room is held.
 *
 * The room is shared memory ([SharedMemoryRoom]): it counts against no Java heap, its pages are
 * taken from memory as they are written, and all of them are freed as it is closed. A seam, so the
 * pacing is tested without it.
 */
internal fun interface ParseRoom {

    /** A room of up to [bytes], none of it taken yet. */
    fun open(bytes: Long): Held

    /** A room in memory, taken a piece at a time and given back all at once. */
    interface Held : AutoCloseable {
        /** The bytes taken so far. */
        val bytes: Long

        /** Takes the next [bytes] of the room from memory, as far as it goes. */
        fun take(bytes: Long)

        /** Gives all of it back. Once is enough; a second call does nothing. */
        override fun close()
    }

    companion object {
        /**
         * The memory a model's parse takes: the bytes of its ONNX files that hold their weights
         * ([com.satori.domain.modelfile.ModelFileFormat.Onnx], not the external data ONNX Runtime
         * maps), and an eighth more for what ONNX Runtime adds as it parses. Parakeet v3's 670 MB
         * took 660 MB in the first second on 2026-10-16; with prepacking off the memory plan expects
         * its peak at about 0.75 GB.
         */
        fun needFor(parseBytes: Long): Long = parseBytes + parseBytes / 8
    }
}

/**
 * [ParseRoom] in an ashmem or memfd region ([SharedMemory]), mapped for writing: its pages are
 * taken as they are written and freed as the region is unmapped and closed. Each page is written
 * with bytes no compressor shrinks and its own number, so zram, which swaps pages out by
 * compressing them and stores a page of one repeated value for nothing, gains nothing from it.
 */
internal object SharedMemoryRoom : ParseRoom {

    override fun open(bytes: Long): ParseRoom.Held {
        val size = bytes.coerceIn(PAGE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val shared = SharedMemory.create(NAME, size)
        val buffer = try {
            shared.mapReadWrite()
        } catch (e: Throwable) {
            shared.close()
            throw e
        }
        return Room(shared, buffer)
    }

    private class Room(private val shared: SharedMemory, private val buffer: ByteBuffer) : ParseRoom.Held {
        @Volatile override var bytes: Long = 0L
            private set
        private var closed = false

        override fun take(bytes: Long) {
            check(!closed) { "The room was given back" }
            val end = minOf(buffer.capacity().toLong(), this.bytes + bytes).toInt()
            var at = this.bytes.toInt()
            while (at < end) {
                val n = minOf(FILL.size - at % FILL.size, end - at)
                buffer.position(at)
                buffer.put(FILL, at % FILL.size, n)
                at += n
            }
            // A page's number in its first bytes makes each page unlike every other.
            var page = (this.bytes.toInt() + PAGE - 1) / PAGE * PAGE
            while (page + Long.SIZE_BYTES <= end) {
                buffer.putLong(page, page.toLong() xor SEED)
                page += PAGE
            }
            this.bytes = end.toLong()
        }

        override fun close() {
            if (closed) return
            closed = true
            SharedMemory.unmap(buffer)
            shared.close()
        }
    }

    private const val NAME = "satori-parse-room"
    private const val PAGE = 4_096
    private const val SEED = 0x5A70_21C0_FFEEL

    // 64 KiB of bytes no compressor shrinks, written over and over.
    private val FILL: ByteArray = ByteArray(64 * 1024).also { Random(SEED).nextBytes(it) }
}
