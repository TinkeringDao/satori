package com.satori.presentation.mindmap

import org.junit.Assert.assertEquals
import org.junit.Test

class MapTrailTest {

    private val theme = MapKey.Node("0b6b9c1e-2f7a-4c55-9d1e-6a1f0c3b9e21")
    private val topic = MapKey.Node("5d2c7e40-8a1b-4f3e-b6c2-1e9d4a7f0b38")
    private val recording = MapKey.Recording("9f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b")

    @Test
    fun `a trail reads back as it was written`() {
        val keys = listOf(MapKey.Root, theme, recording, topic)

        assertEquals(keys, MapTrail.decode(MapTrail.encode(keys)))
    }

    @Test
    fun `a trail is plain enough to go into a route as it is`() {
        val text = MapTrail.encode(listOf(MapKey.Root, theme, recording))

        assertEquals("r~n${theme.id}~s${recording.sessionId}", text)
        assertEquals(true, text.all { it.isLetterOrDigit() || it == '-' || it == '~' })
    }

    @Test
    fun `no trail is an empty one`() {
        assertEquals(emptyList<MapKey>(), MapTrail.decode(null))
        assertEquals(emptyList<MapKey>(), MapTrail.decode(""))
        assertEquals(emptyList<MapKey>(), MapTrail.decode("  "))
        assertEquals("", MapTrail.encode(emptyList()))
    }

    @Test
    fun `a part that doesn't read is left out`() {
        assertEquals(listOf(MapKey.Root, theme), MapTrail.decode("r~n~x12~~n${theme.id}~s"))
    }

    @Test
    fun `the top level has no way up`() {
        assertEquals(emptyList<Crumb>(), MapTrail.crumbs(listOf(MapKey.Root, theme), MapKey.Root))
    }

    @Test
    fun `each crumb goes back as many screens as stand between`() {
        val crumbs = MapTrail.crumbs(listOf(MapKey.Root, theme, recording), topic)

        assertEquals(
            listOf(Crumb(MapKey.Root, back = 3), Crumb(theme, back = 2), Crumb(recording, back = 1)),
            crumbs
        )
    }

    @Test
    fun `a view opened from outside the map gets a top level that opens afresh`() {
        assertEquals(listOf(Crumb(MapKey.Root, back = null)), MapTrail.crumbs(emptyList(), recording))
        assertEquals(
            listOf(Crumb(MapKey.Root, back = null), Crumb(recording, back = 1)),
            MapTrail.crumbs(listOf(recording), topic)
        )
    }

    @Test
    fun `a recording's page right behind its map is gone back to`() {
        assertEquals(RecordingOpening.Back, RecordingOpening.of(behind = "s1", sessionId = "s1", segmentId = null))
    }

    @Test
    fun `another recording's page, a line to show, or no page behind opens a new one`() {
        assertEquals(RecordingOpening.Open(null), RecordingOpening.of(behind = "s2", sessionId = "s1", segmentId = null))
        assertEquals(RecordingOpening.Open("seg"), RecordingOpening.of(behind = "s1", sessionId = "s1", segmentId = "seg"))
        assertEquals(RecordingOpening.Open(null), RecordingOpening.of(behind = null, sessionId = "s1", segmentId = null))
    }

    @Test
    fun `a view opened from here has this view at the end of its trail`() {
        val child = MapTrail.decode(MapTrail.childTrail(listOf(MapKey.Root), theme))

        assertEquals(listOf(MapKey.Root, theme), child)
        assertEquals(listOf(MapKey.Root), MapTrail.decode(MapTrail.childTrail(emptyList(), MapKey.Root)))
    }
}
