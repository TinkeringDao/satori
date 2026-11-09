package com.satori.data.place

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Reads the offline list of places, `assets/places.bin`, written by tools/gazetteer/build_places.py,
 * whose docstring gives the format: a header, then one column after another (latitudes,
 * longitudes, populations, countries, name lengths, names), big-endian.
 */
internal object PlaceFile {

    /** The asset, in the data module's assets. */
    const val ASSET = "places.bin"

    private const val MAGIC = 0x53504C43 // "SPLC"
    private const val VERSION = 1

    /**
     * The places in [bytes], ready to look up.
     *
     * @throws IllegalArgumentException when [bytes] are not a list of places of this version
     */
    fun read(bytes: ByteArray): PlaceIndex {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            require(buffer.int == MAGIC) { "Not a list of places" }
            val version = buffer.get().toInt() and 0xFF
            require(version == VERSION) { "List of places version $version, this app reads $VERSION" }
            val bandsPerDegree = buffer.get().toInt() and 0xFF
            val countryCodes = List(buffer.short.toInt() and 0xFFFF) {
                String(byteArrayOf(buffer.get(), buffer.get()), Charsets.US_ASCII)
            }
            val count = buffer.int
            require(count >= 0) { "A negative number of places" }
            val latitudes = ints(buffer, count)
            val longitudes = ints(buffer, count)
            val populations = ints(buffer, count)
            val countries = ByteArray(count).also { buffer.get(it) }
            val nameStarts = IntArray(count + 1)
            for (i in 0 until count) nameStarts[i + 1] = nameStarts[i] + (buffer.get().toInt() and 0xFF)
            val names = ByteArray(nameStarts[count]).also { buffer.get(it) }
            require(!buffer.hasRemaining()) { "${buffer.remaining()} bytes after the names" }
            return PlaceIndex(bandsPerDegree, latitudes, longitudes, populations, countries, countryCodes, nameStarts, names)
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("The list of places ends early", e)
        }
    }

    /** The next [count] ints of [buffer], which moves past them. */
    private fun ints(buffer: ByteBuffer, count: Int): IntArray {
        if (buffer.remaining() < count * 4L) throw BufferUnderflowException()
        val values = IntArray(count)
        buffer.asIntBuffer().get(values)
        buffer.position(buffer.position() + count * 4)
        return values
    }
}
