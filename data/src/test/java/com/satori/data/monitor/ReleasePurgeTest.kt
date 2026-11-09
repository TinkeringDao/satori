package com.satori.data.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purge of the native heap after a speech model's release: on in every build, a debug build's
 * `debug.satori.mem.purge` off turning it off, read at each release and never by a release build; a
 * purge that gives nothing back or can't be made warns and never throws.
 */
class ReleasePurgeTest {

    private val warnings = mutableListOf<String>()
    private val reads = mutableListOf<String>()

    private fun purge(property: String?, heap: NativeHeap = NativeHeap { Purge.ALL }, debuggable: Boolean = true) = ReleasePurge(
        debuggable = debuggable,
        read = { name ->
            if (!debuggable) throw AssertionError("a release build read $name")
            reads += name
            property
        },
        warn = { warnings += it },
        heap = heap
    )

    @Test
    fun `every build purges after a release, a release build without reading the property`() {
        assertTrue(purge(property = "off", debuggable = false).plan() is ReleasePurge.Plan.Purging)
        assertTrue(purge("").plan() is ReleasePurge.Plan.Purging)
        assertTrue(purge(" ON ").plan() is ReleasePurge.Plan.Purging)
        assertEquals(listOf(ReleasePurge.PROPERTY, ReleasePurge.PROPERTY), reads)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a debug build's property off purges nothing, read at each release`() {
        var value = "off"
        val purge = ReleasePurge(true, { value }, { warnings += it }, NativeHeap { throw AssertionError("purged") })

        assertSame(ReleasePurge.Plan.Off, purge.plan())
        value = ""
        assertTrue(purge.plan() is ReleasePurge.Plan.Purging)
    }

    @Test
    fun `a value it doesn't know, or a property it can't read, purges all the same, with a warning`() {
        assertTrue(purge("sometimes").plan() is ReleasePurge.Plan.Purging)
        assertTrue(purge(null).plan() is ReleasePurge.Plan.Purging)

        assertEquals(
            listOf("Ignoring debug.satori.mem.purge=sometimes: not on or off", "Ignoring debug.satori.mem.purge: the property can't be read"),
            warnings
        )
    }

    @Test
    fun `the purge says how far it went, and warns when nothing was given back or the call couldn't be made`() {
        val all = purge("", NativeHeap { Purge.ALL }).plan() as ReleasePurge.Plan.Purging
        val some = purge("", NativeHeap { Purge.SOME }).plan() as ReleasePurge.Plan.Purging
        val none = purge("", NativeHeap { Purge.NONE }).plan() as ReleasePurge.Plan.Purging
        val unlinked = purge("", NativeHeap { throw UnsatisfiedLinkError("no libai-chat.so") }).plan() as ReleasePurge.Plan.Purging
        val failing = purge("", NativeHeap { throw IllegalStateException("no heap") }).plan() as ReleasePurge.Plan.Purging

        assertEquals(Purge.ALL, all.purge("parakeet-tdt-0.6b-v3-int8"))
        assertEquals(Purge.SOME, some.purge("parakeet-tdt-0.6b-v3-int8"))
        assertTrue(warnings.isEmpty())
        assertEquals(Purge.NONE, none.purge("parakeet-tdt-0.6b-v3-int8"))
        assertEquals(Purge.NONE, unlinked.purge("indicconformer-ml-int8"))
        assertEquals(Purge.NONE, failing.purge("indicconformer-ml-int8"))

        assertEquals(
            listOf(
                "Native heap not purged after the release of parakeet-tdt-0.6b-v3-int8: mallopt knows neither M_PURGE_ALL nor M_PURGE",
                "Native heap not purged after the release of indicconformer-ml-int8: UnsatisfiedLinkError",
                "Native heap not purged after the release of indicconformer-ml-int8: IllegalStateException"
            ),
            warnings
        )
    }

    @Test
    fun `none has no heap, so a release is measured as through 0_7_1`() {
        assertSame(ReleasePurge.Plan.Skip, ReleasePurge.NONE.plan())
    }
}
