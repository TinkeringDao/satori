package com.satori.presentation.mindmap

import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.MindMapRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun theme(id: String, label: String = id, key: String = label.lowercase(), pinned: Boolean = false) =
        MindNode(id = id, kind = MindNodeKind.THEME, label = label, matchKey = key, pinned = pinned, createdAt = 0, updatedAt = 0)

    private fun topic(id: String, parent: String, label: String = id, key: String = label.lowercase()) =
        MindNode(id = id, kind = MindNodeKind.TOPIC, label = label, matchKey = key, parentId = parent, createdAt = 0, updatedAt = 0)

    private fun item(node: MindNode, count: Int = 1, daysAgo: Long = 0) = MindNodeItem(node, count, now - daysAgo * day)

    private fun link(topicId: String, sessionId: String, at: Long, vararg points: String, segments: List<String> = emptyList()) =
        MindLink(topicId = topicId, sessionId = sessionId, points = points.toList(), segmentIds = segments, createdAt = at, recordedAt = at)

    private fun segment(text: String, complete: Boolean = true) =
        TranscriptionSegment(id = text, sessionId = "s", text = text, timestamp = 0, isComplete = complete)

    // ─── Chips ───────────────────────────────────────────────────────────────

    @Test
    fun `Core is the seven with most recordings and every pinned theme, in their order`() {
        val themes = (1..10).map { item(theme("t$it", pinned = it == 9), count = 20 - it) }

        val core = MindMapRules.selectThemes(themes, ThemeFilter.CORE, now)

        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t9"), core.map { it.node.id })
    }

    @Test
    fun `a pinned theme among the seven is listed once`() {
        val themes = (1..8).map { item(theme("t$it", pinned = it == 2)) }

        assertEquals(7, MindMapRules.selectThemes(themes, ThemeFilter.CORE, now).size)
    }

    @Test
    fun `Recent is the last week's themes, the newest first`() {
        val themes = listOf(
            item(theme("old"), count = 9, daysAgo = 30),
            item(theme("week"), count = 5, daysAgo = 7),
            item(theme("today"), count = 1, daysAgo = 0),
            item(theme("tuesday"), count = 3, daysAgo = 2),
            item(theme("eight"), count = 2, daysAgo = 8)
        )

        val recent = MindMapRules.selectThemes(themes, ThemeFilter.RECENT, now)

        assertEquals(listOf("today", "tuesday", "week"), recent.map { it.node.id })
    }

    @Test
    fun `Recent keeps the map's order for themes of the same moment`() {
        val themes = listOf(item(theme("b"), count = 4), item(theme("a"), count = 2))

        assertEquals(listOf("b", "a"), MindMapRules.selectThemes(themes, ThemeFilter.RECENT, now).map { it.node.id })
    }

    @Test
    fun `All is every theme as the map orders them`() {
        val themes = (1..12).map { item(theme("t$it")) }

        assertEquals(themes, MindMapRules.selectThemes(themes, ThemeFilter.ALL, now))
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    private val heating = item(theme("h", "Heating"))
    private val garden = item(theme("g", "Garden shed"))
    private val mueller = item(theme("m", "Herr Müller"))
    private val cafe = item(theme("c", "Café"))
    private val all = listOf(heating, garden, mueller, cafe)

    @Test
    fun `search finds a theme by part of its name, whatever the case`() {
        assertEquals(listOf(garden), MindMapRules.searchThemes(all, emptyMap(), "SHED"))
    }

    @Test
    fun `search finds a theme by one of its topics`() {
        val topics = mapOf("h" to listOf("Boiler repair"))

        assertEquals(listOf(heating), MindMapRules.searchThemes(all, topics, "boiler"))
    }

    @Test
    fun `umlauts match written either way, and accents don't matter`() {
        assertEquals(listOf(mueller), MindMapRules.searchThemes(all, emptyMap(), "mueller"))
        assertEquals(listOf(mueller), MindMapRules.searchThemes(all, emptyMap(), "muller"))
        assertEquals(listOf(cafe), MindMapRules.searchThemes(all, emptyMap(), "cafe"))
        val written = listOf(item(theme("w", "Mueller")))
        assertEquals(written, MindMapRules.searchThemes(written, emptyMap(), "Müller"))
    }

    @Test
    fun `a blank search keeps every theme`() {
        assertEquals(all, MindMapRules.searchThemes(all, emptyMap(), "  "))
    }

    @Test
    fun `nothing found is an empty list`() {
        assertEquals(emptyList<MindNodeItem>(), MindMapRules.searchThemes(all, emptyMap(), "rent"))
    }

    @Test
    fun `topic labels are gathered under their themes`() {
        val nodes = listOf(theme("h"), topic("a", "h", "Boiler"), topic("b", "g", "Roof"), topic("c", "h", "Radiators"))

        assertEquals(mapOf("h" to listOf("Boiler", "Radiators"), "g" to listOf("Roof")), MindMapRules.topicLabelsByTheme(nodes))
    }

    // ─── Size and brightness ─────────────────────────────────────────────────

    @Test
    fun `a theme touched this week is at full brightness`() {
        assertEquals(1f, MindMapRules.recency(now, now))
        assertEquals(1f, MindMapRules.recency(now - 7 * day, now))
        // A recording dated ahead of the clock counts as recent.
        assertEquals(1f, MindMapRules.recency(now + day, now))
    }

    @Test
    fun `brightness fades until three months and then stays at its least`() {
        assertTrue(MindMapRules.recency(now - 8 * day, now) < 1f)
        assertEquals(0.5f, MindMapRules.recency(now - ((7 + 90) / 2.0 * day).toLong(), now), 0.01f)
        assertEquals(0f, MindMapRules.recency(now - 90 * day, now), 0.0001f)
        assertEquals(0f, MindMapRules.recency(now - 400 * day, now))
    }

    @Test
    fun `size grows with the square root of the number of recordings`() {
        assertEquals(1f, MindMapRules.weight(16, 16))
        assertEquals(0.5f, MindMapRules.weight(4, 16), 0.0001f)
        assertEquals(1f, MindMapRules.weight(20, 16))
        assertEquals(0f, MindMapRules.weight(0, 16))
        assertEquals(0f, MindMapRules.weight(3, 0))
    }

    @Test
    fun `labels are cut at nineteen characters on the map`() {
        assertEquals("Heating", MindMapRules.cutLabel("  Heating "))
        assertEquals("Exactly nineteen ch", MindMapRules.cutLabel("Exactly nineteen ch"))
        val cut = MindMapRules.cutLabel("Garden shed roof repair before winter")
        assertEquals("Garden shed roof r…", cut)
        assertEquals(19, cut.length)
    }

    @Test
    fun `a cut never ends in a space or half a character`() {
        assertEquals("Garden shed roof…", MindMapRules.cutLabel("Garden shed roof  repair"))
        // The party popper takes the 18th and 19th chars; it goes whole.
        assertEquals("Party planning 12…", MindMapRules.cutLabel("Party planning 12🎉 next"))
    }

    // ─── Pages ───────────────────────────────────────────────────────────────

    @Test
    fun `a summary is missing, stale or current`() {
        val base = theme("h")

        assertEquals(SummaryState.NONE, MindMapRules.summaryState(base))
        assertEquals(SummaryState.NONE, MindMapRules.summaryState(base.copy(summary = " ", summaryStale = true)))
        assertEquals(SummaryState.STALE, MindMapRules.summaryState(base.copy(summary = "It leaks.", summaryStale = true)))
        assertEquals(SummaryState.CURRENT, MindMapRules.summaryState(base.copy(summary = "It leaks.")))
    }

    @Test
    fun `the topic that only repeats its theme's name isn't shown`() {
        val heatingTheme = theme("h", "Heating", key = "heat")
        val topics = listOf(
            item(topic("a", "h", "Heating", key = "heat")),
            item(topic("b", "h", "Boiler repair", key = "boiler repair")),
            item(topic("c", "h", "heating ", key = "other"))
        )

        assertEquals(listOf("b"), MindMapRules.visibleTopics(heatingTheme, topics).map { it.node.id })
    }

    @Test
    fun `points are grouped by recording, newest first, with the labels asked for`() {
        val links = listOf(
            link("a", "new", 30, "It still leaks."),
            link("b", "new", 30, "The boiler is old.", segments = listOf("seg1")),
            link("a", "old", 10, "The roof leaks.")
        )

        val groups = MindMapRules.byRecording(links) { topicId -> if (topicId == "b") "Boiler" else null }

        assertEquals(listOf("new", "old"), groups.map { it.sessionId })
        assertEquals(listOf(30L, 10L), groups.map { it.recordedAt })
        assertEquals(listOf(null, "Boiler"), groups[0].topics.map { it.label })
        assertEquals(listOf("The boiler is old."), groups[0].topics[1].points)
        assertEquals(listOf("seg1"), groups[0].topics[1].segmentIds)
    }

    @Test
    fun `a recording's links stay together however they arrive`() {
        val links = listOf(link("a", "x", 5), link("b", "y", 5), link("c", "x", 5))

        val groups = MindMapRules.byRecording(links) { it }

        assertEquals(listOf("x", "y"), groups.map { it.sessionId })
        assertEquals(listOf("a", "c"), groups[0].topics.map { it.topicId })
    }

    @Test
    fun `each recording is counted once, the newest first`() {
        val links = listOf(link("a", "x", 20), link("b", "y", 50), link("c", "x", 20))

        assertEquals(listOf("y" to 50L, "x" to 20L), MindMapRules.recordingsOf(links))
    }

    @Test
    fun `only enough complete transcript can be mapped, counted like the repository`() {
        val limit = MindMapRepository.MIN_TRANSCRIPT_CHARS
        val half = "a".repeat(limit / 2)

        assertTrue(MindMapRules.hasEnoughTranscript(listOf(segment(half), segment("  $half  "))))
        assertFalse(MindMapRules.hasEnoughTranscript(listOf(segment(half), segment("  ${half.drop(1)}  "))))
        assertFalse(MindMapRules.hasEnoughTranscript(listOf(segment(half), segment(half, complete = false))))
        assertFalse(MindMapRules.hasEnoughTranscript(emptyList()))
    }

    // ─── Drawings ────────────────────────────────────────────────────────────

    @Test
    fun `the top level has You in the centre with every recording and the themes around`() {
        val themes = listOf(item(theme("a"), count = 4, daysAgo = 1), item(theme("b", pinned = true), count = 1, daysAgo = 200))

        val scene = MindMapRules.rootScene(themes, recordings = 12, now = now)

        assertEquals(MapNodeKind.YOU, scene.centre.kind)
        assertEquals(MapLabel.You, scene.centre.label)
        assertEquals(12, scene.centre.recordings)
        assertEquals(listOf("a", "b"), scene.children.map { it.key })
        assertEquals(listOf(1f, 0.5f), scene.children.map { it.weight })
        assertEquals(listOf(1f, 0f), scene.children.map { it.recency })
        assertEquals(MapTarget.Node("b"), scene.children[1].target)
        assertTrue(scene.children[1].pinned)
        assertEquals(0, scene.hidden)
    }

    @Test
    fun `a drawing shows at most forty children and counts the rest`() {
        val themes = (1..45).map { item(theme("t$it")) }

        val scene = MindMapRules.rootScene(themes, recordings = 50, now = now)

        assertEquals(MindMapRules.CANVAS_NODES, scene.children.size)
        assertEquals(5, scene.hidden)
    }

    @Test
    fun `a theme has its topics at the top and its recordings after them`() {
        val heatingTheme = theme("h", "Heating")
        val topics = listOf(item(topic("a", "h", "Boiler"), count = 2), item(topic("b", "h", "Radiators"), count = 1))
        val titles = mapOf("x" to RecordingTitle.of("Plumber call", null, RecordingMode.SIMPLE_LISTENING))

        val scene = MindMapRules.themeScene(heatingTheme, topics, listOf("x" to now, "y" to now - day), titles, now)

        assertEquals(MapNodeKind.THEME, scene.centre.kind)
        assertNull(scene.centre.target)
        assertEquals(2, scene.leadCount)
        assertEquals(
            listOf(MapNodeKind.TOPIC, MapNodeKind.TOPIC, MapNodeKind.RECORDING, MapNodeKind.RECORDING),
            scene.children.map { it.kind }
        )
        assertEquals(MapTarget.RecordingMap("x"), scene.children[2].target)
        assertEquals(MapLabel.Recording(titles.getValue("x")), scene.children[2].label)
        // Keys of topics and recordings can't clash.
        assertEquals(4, scene.children.map { it.key }.toSet().size)
    }

    @Test
    fun `a theme without topics leads with its first recording`() {
        val scene = MindMapRules.themeScene(theme("h"), emptyList(), listOf("x" to now), emptyMap(), now)

        assertEquals(1, scene.leadCount)
        assertEquals(1, scene.centre.recordings)
    }

    @Test
    fun `a theme's drawing keeps to the budget, topics first`() {
        val topics = (1..30).map { item(topic("t$it", "h")) }
        val recordings = (1..20).map { "r$it" to now - it }

        val scene = MindMapRules.themeScene(theme("h"), topics, recordings, emptyMap(), now)

        assertEquals(MindMapRules.CANVAS_NODES, scene.children.size)
        assertEquals(30, scene.children.count { it.kind == MapNodeKind.TOPIC })
        assertEquals(10, scene.hidden)
    }

    @Test
    fun `a topic has its recordings around it`() {
        val scene = MindMapRules.topicScene(topic("a", "h"), listOf("x" to now, "y" to now - day), emptyMap(), now)

        assertEquals(MapNodeKind.TOPIC, scene.centre.kind)
        assertEquals(listOf(MapTarget.RecordingMap("x"), MapTarget.RecordingMap("y")), scene.children.map { it.target })
    }

    @Test
    fun `a recording's map has its topics and their points, which open where they were said`() {
        val heatingTheme = theme("h", "Heating")
        val topics = listOf(
            MindSessionTopic(topic("a", "h", "Boiler"), heatingTheme, link("a", "s1", now, "It is old.", "It hums.", segments = listOf("seg4", "seg9"))),
            MindSessionTopic(topic("b", "h", "Rent"), heatingTheme, link("b", "s1", now, "It goes up."))
        )
        val title = RecordingTitle.of(null, "Idea", RecordingMode.SIMPLE_LISTENING)

        val scene = MindMapRules.recordingScene("s1", title, now, topics, now)

        assertTrue(scene.twoLevel)
        assertEquals(MapTarget.RecordingPage("s1"), scene.centre.target)
        assertEquals(MapLabel.Recording(title), scene.centre.label)
        assertEquals(listOf(MapTarget.Node("a"), MapTarget.Node("b")), scene.children.map { it.target })
        assertEquals(listOf(2, 1), scene.children.map { it.children.size })
        assertEquals(MapTarget.RecordingPage("s1", "seg4"), scene.children[0].children[1].target)
        assertEquals(MapTarget.RecordingPage("s1", null), scene.children[1].children[0].target)
        assertEquals(MapLabel.Text("It hums."), scene.children[0].children[1].label)
    }

    @Test
    fun `the list or map choice holds until changed`() {
        val choice = MindMapViewChoice()

        assertNull(choice.list.value)
        choice.choose(true)
        assertEquals(true, choice.list.value)
        choice.choose(false)
        assertEquals(false, choice.list.value)
    }

    // ─── Whether there is a drawing ──────────────────────────────────────────

    private fun root(totalThemes: Int, shown: List<MindNodeItem> = emptyList(), query: String = "") =
        MapPage.Root(ThemeFilter.CORE, query, shown, totalThemes, elsewhere = 0, recordings = 3, unmapped = 0)

    @Test
    fun `the top level draws once the map has a theme, also while a search finds none`() {
        val themes = listOf(item(theme("h", "Heating")))

        assertTrue(MindMapRules.hasDrawing(root(1, themes), MindMapRules.rootScene(themes, 3, now)))
        assertTrue(MindMapRules.hasDrawing(root(1, query = "garden"), MindMapRules.rootScene(emptyList(), 3, now)))
        assertFalse(MindMapRules.hasDrawing(root(0), MindMapRules.rootScene(emptyList(), 3, now)))
    }

    @Test
    fun `a recording not mapped yet has no drawing, its page says how to map it`() {
        val title = RecordingTitle.of(null, null, RecordingMode.SIMPLE_LISTENING)
        val page = MapPage.Recording(
            SessionWithDetails(TranscriptionSession(id = "s1", name = null, createdAt = now, lastModifiedAt = now), emptyList(), emptyList()),
            topics = emptyList(),
            enoughText = true
        )
        val heatingTheme = theme("h", "Heating")
        val mapped = listOf(MindSessionTopic(topic("a", "h", "Boiler"), heatingTheme, link("a", "s1", now, "It is old.")))

        assertFalse(MindMapRules.hasDrawing(page, MindMapRules.recordingScene("s1", title, now, emptyList(), now)))
        assertTrue(MindMapRules.hasDrawing(page.copy(topics = mapped), MindMapRules.recordingScene("s1", title, now, mapped, now)))
    }

    @Test
    fun `nothing is drawn while loading or once the view is gone`() {
        assertFalse(MindMapRules.hasDrawing(MapPage.Loading, null))
        assertFalse(MindMapRules.hasDrawing(MapPage.Missing, null))
    }
}
