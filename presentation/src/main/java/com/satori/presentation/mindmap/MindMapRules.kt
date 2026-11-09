package com.satori.presentation.mindmap

import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.MindMapRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * The rules of the mind map's screens, kept out of the ViewModel so each can be unit-tested: which
 * themes each chip shows, what the search finds, how big and how bright a node is drawn, and how
 * the pages and drawings are put together from what the repository reads.
 */
object MindMapRules {

    /** Core holds the pinned themes and this many with the most recordings. */
    const val CORE_SIZE = 7

    /** A theme with a recording from this many days back is Recent. */
    const val RECENT_DAYS = 7

    /** A node fades from full brightness after [RECENT_DAYS] to its dimmest after this many days. */
    const val FADE_DAYS = 90

    /** Labels on the drawing are cut to this many characters; the pane shows them whole. */
    const val LABEL_CHARS = 19

    /**
     * The most children a drawing shows, the research's node budget for the inner screen (30 to
     * 50): past it [MindMapLayout.MAX_RINGS] rings crowd so that most labels hide each other at
     * any zoom, so the rest are listed in the pane only.
     */
    const val CANVAS_NODES = 40

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private const val ELLIPSIS = "…"

    // ─── The top level ───────────────────────────────────────────────────────

    /**
     * The themes [filter] shows, from [themes] in the repository's order (most recordings first):
     * - Core: the first [CORE_SIZE] and the pinned themes, in the repository's order;
     * - Recent: those with a recording from the last [RECENT_DAYS] days, the newest first;
     * - All: every theme.
     */
    fun selectThemes(themes: List<MindNodeItem>, filter: ThemeFilter, now: Long): List<MindNodeItem> = when (filter) {
        ThemeFilter.CORE -> themes.filterIndexed { index, item -> index < CORE_SIZE || item.node.pinned }
        ThemeFilter.RECENT -> themes
            .filter { now - it.lastRecordedAt <= RECENT_DAYS * DAY_MS }
            // Stable: themes of the same moment keep the repository's order.
            .sortedByDescending { it.lastRecordedAt }
        ThemeFilter.ALL -> themes
    }

    /**
     * The themes of [themes] whose label, or the label of one of their topics ([topicLabels] by
     * theme id), contains [query]. Case, accents and the two ways of writing German umlauts don't
     * matter: "muller", "mueller" and "Müller" find each other. A blank query keeps them all.
     */
    fun searchThemes(themes: List<MindNodeItem>, topicLabels: Map<String, List<String>>, query: String): List<MindNodeItem> {
        val wanted = query.trim()
        if (wanted.isEmpty()) return themes
        val stripped = strip(wanted)
        val spelled = spellOut(wanted)
        return themes.filter { item ->
            (sequenceOf(item.node.label) + topicLabels[item.node.id].orEmpty().asSequence()).any { label ->
                strip(label).contains(stripped) || spellOut(label).contains(spelled)
            }
        }
    }

    /** The labels of the topics under each theme, by theme id, for [searchThemes]. */
    fun topicLabelsByTheme(nodes: List<MindNode>): Map<String, List<String>> {
        val labels = LinkedHashMap<String, MutableList<String>>()
        for (node in nodes) {
            val parent = node.parentId ?: continue
            labels.getOrPut(parent) { ArrayList() } += node.label
        }
        return labels
    }

    /** Lower case without accents: "Müller" is "muller". */
    private fun strip(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    /** Lower case with German umlauts and ß spelled out: "Müller" is "mueller", "Straße" "strasse". */
    private fun spellOut(text: String): String = strip(
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    )

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    // ─── Size and brightness ─────────────────────────────────────────────────

    /**
     * How bright a node is, 0 to 1: 1 for one touched in the last [RECENT_DAYS] days, then less
     * each day until [FADE_DAYS]. Brightness, not colour, says what is recent: red only ever means
     * the microphone is live.
     */
    fun recency(lastAt: Long, now: Long): Float {
        val days = (now - lastAt).toDouble() / DAY_MS
        if (days <= RECENT_DAYS) return 1f
        val faded = 1.0 - (days - RECENT_DAYS) / (FADE_DAYS - RECENT_DAYS)
        return faded.toFloat().coerceIn(0f, 1f)
    }

    /**
     * How big a node is, 0 to 1: its number of recordings against the largest drawn beside it,
     * on a square root, so one theme with many recordings doesn't shrink all the others to dots.
     */
    fun weight(count: Int, maxCount: Int): Float {
        if (maxCount <= 0 || count <= 0) return 0f
        return sqrt(count.coerceAtMost(maxCount).toFloat() / maxCount)
    }

    /**
     * [label] cut to [max] characters for the drawing, ending in an ellipsis when it was longer.
     * The full label is in the pane.
     */
    fun cutLabel(label: String, max: Int = LABEL_CHARS): String {
        val text = label.trim()
        if (text.length <= max) return text
        var end = max - 1
        // Don't split a character that takes two chars (an emoji, say).
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + ELLIPSIS
    }

    // ─── Pages ───────────────────────────────────────────────────────────────

    /** How the summary of [theme] stands. */
    fun summaryState(theme: MindNode): SummaryState = when {
        theme.summary.isNullOrBlank() -> SummaryState.NONE
        theme.summaryStale -> SummaryState.STALE
        else -> SummaryState.CURRENT
    }

    /**
     * The topics of [theme] worth showing on its page and drawing. The topic a theme was founded
     * with has the theme's own name (the AI's first topic becomes both), so it only says the theme
     * again; its recordings are on the page either way.
     */
    fun visibleTopics(theme: MindNode, topics: List<MindNodeItem>): List<MindNodeItem> =
        topics.filterNot { repeatsTheme(theme, it.node) }

    /** Whether [topic] has the name of [theme]. */
    fun repeatsTheme(theme: MindNode, topic: MindNode): Boolean =
        (theme.matchKey.isNotBlank() && topic.matchKey == theme.matchKey) ||
            topic.label.trim().equals(theme.label.trim(), ignoreCase = true)

    /**
     * [links] by recording, in their order (newest recording first), each recording's topics in
     * the order their links were stored.
     *
     * @param labelOf The label to show for a topic, or null to show none
     */
    fun byRecording(links: List<MindLink>, labelOf: (topicId: String) -> String?): List<RecordingPoints> {
        val groups = LinkedHashMap<String, MutableList<MindLink>>()
        for (link in links) groups.getOrPut(link.sessionId) { ArrayList() } += link
        return groups.map { (sessionId, ofRecording) ->
            RecordingPoints(
                sessionId = sessionId,
                recordedAt = ofRecording.maxOf { it.recordedAt },
                topics = ofRecording.map { link ->
                    TopicPoints(link.topicId, labelOf(link.topicId), link.points, link.segmentIds)
                }
            )
        }
    }

    /** The recordings of [links], each once, newest first, with when each was made. */
    fun recordingsOf(links: List<MindLink>): List<Pair<String, Long>> {
        val dates = LinkedHashMap<String, Long>()
        for (link in links) {
            val known = dates[link.sessionId]
            if (known == null || link.recordedAt > known) dates[link.sessionId] = link.recordedAt
        }
        return dates.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /**
     * Whether a recording has enough complete transcript to be mapped, counted as
     * [MindMapRepository.unmappedSessionIds] counts it: the lines with the spaces at their ends
     * removed, at least [MindMapRepository.MIN_TRANSCRIPT_CHARS] characters in all.
     */
    fun hasEnoughTranscript(segments: List<TranscriptionSegment>): Boolean {
        var chars = 0
        for (segment in segments) {
            if (!segment.isComplete) continue
            chars += segment.text.trim(' ').length
            if (chars >= MindMapRepository.MIN_TRANSCRIPT_CHARS) return true
        }
        return false
    }

    // ─── Drawings ────────────────────────────────────────────────────────────

    /**
     * Whether a view has a drawing to show ([MapForm.of]): the top level once the map has a theme,
     * also while a chip or a search finds none (the drawing is then "You" alone, so the layout
     * stays put while a search is typed above it), and any other view once something sits around
     * its centre. A recording not mapped yet would be its own pill alone; its page says how to map
     * it.
     */
    fun hasDrawing(page: MapPage, scene: MapScene?): Boolean = when {
        scene == null -> false
        page is MapPage.Root -> page.totalThemes > 0
        else -> scene.children.isNotEmpty()
    }

    /**
     * The top level: "You" in the centre with the number of recordings, the themes around it, the
     * first [CANVAS_NODES] of [themes] drawn.
     */
    fun rootScene(themes: List<MindNodeItem>, recordings: Int, now: Long): MapScene {
        val drawn = themes.take(CANVAS_NODES)
        val most = drawn.maxOfOrNull { it.recordingCount } ?: 0
        val centre = MapNode(
            key = ROOT_KEY,
            kind = MapNodeKind.YOU,
            label = MapLabel.You,
            recordings = recordings,
            lastAt = null,
            weight = 1f,
            recency = 1f
        )
        val children = drawn.map { item ->
            MapNode(
                key = item.node.id,
                kind = MapNodeKind.THEME,
                label = MapLabel.Text(item.node.label),
                recordings = item.recordingCount,
                lastAt = item.lastRecordedAt,
                weight = weight(item.recordingCount, most),
                recency = recency(item.lastRecordedAt, now),
                pinned = item.node.pinned,
                target = MapTarget.Node(item.node.id)
            )
        }
        return MapScene(centre, children, hidden = themes.size - drawn.size)
    }

    /**
     * A theme in the centre: its topics around the top of the ring, then its recordings, newest
     * first, round to the bottom; [CANVAS_NODES] in all.
     *
     * @param topics Its topics to draw ([visibleTopics]), most recordings first
     * @param recordings Its recordings with their dates, newest first ([recordingsOf])
     */
    fun themeScene(
        theme: MindNode,
        topics: List<MindNodeItem>,
        recordings: List<Pair<String, Long>>,
        titles: Map<String, RecordingTitle>,
        now: Long
    ): MapScene {
        val drawnTopics = topics.take(CANVAS_NODES)
        val drawnRecordings = recordings.take(CANVAS_NODES - drawnTopics.size)
        val most = drawnTopics.maxOfOrNull { it.recordingCount } ?: 1
        val newest = recordings.maxOfOrNull { it.second }
        val centre = MapNode(
            key = theme.id,
            kind = MapNodeKind.THEME,
            label = MapLabel.Text(theme.label),
            recordings = recordings.size,
            lastAt = newest,
            weight = 1f,
            recency = newest?.let { recency(it, now) } ?: 1f,
            pinned = theme.pinned
        )
        val children = drawnTopics.map { item ->
            MapNode(
                key = item.node.id,
                kind = MapNodeKind.TOPIC,
                label = MapLabel.Text(item.node.label),
                recordings = item.recordingCount,
                lastAt = item.lastRecordedAt,
                weight = weight(item.recordingCount, most),
                recency = recency(item.lastRecordedAt, now),
                target = MapTarget.Node(item.node.id)
            )
        } + drawnRecordings.map { (sessionId, at) -> recordingNode(sessionId, at, titles, now) }
        return MapScene(
            centre = centre,
            children = children,
            leadCount = drawnTopics.size.coerceAtLeast(1),
            hidden = topics.size + recordings.size - children.size
        )
    }

    /** A topic in the centre and its recordings around it, newest first. */
    fun topicScene(
        topic: MindNode,
        recordings: List<Pair<String, Long>>,
        titles: Map<String, RecordingTitle>,
        now: Long
    ): MapScene {
        val drawn = recordings.take(CANVAS_NODES)
        val newest = recordings.maxOfOrNull { it.second }
        val centre = MapNode(
            key = topic.id,
            kind = MapNodeKind.TOPIC,
            label = MapLabel.Text(topic.label),
            recordings = recordings.size,
            lastAt = newest,
            weight = 1f,
            recency = newest?.let { recency(it, now) } ?: 1f
        )
        return MapScene(
            centre = centre,
            children = drawn.map { (sessionId, at) -> recordingNode(sessionId, at, titles, now) },
            hidden = recordings.size - drawn.size
        )
    }

    /**
     * A recording's own map: the recording, its topics, and each topic's points under it, as the
     * mapping step stored them. A tap on the recording opens its page; on a topic, the topic's
     * view; on a point, the recording's page at the lines the topic came from.
     */
    fun recordingScene(
        sessionId: String,
        title: RecordingTitle,
        recordedAt: Long,
        topics: List<MindSessionTopic>,
        now: Long
    ): MapScene {
        val bright = recency(recordedAt, now)
        val centre = MapNode(
            key = sessionId,
            kind = MapNodeKind.RECORDING,
            label = MapLabel.Recording(title),
            recordings = 1,
            lastAt = recordedAt,
            weight = 1f,
            recency = bright,
            target = MapTarget.RecordingPage(sessionId)
        )
        val children = topics.map { topic ->
            val segment = topic.link.segmentIds.firstOrNull()
            MapNode(
                key = topic.topic.id,
                kind = MapNodeKind.TOPIC,
                label = MapLabel.Text(topic.topic.label),
                recordings = 1,
                lastAt = recordedAt,
                weight = 1f,
                recency = bright,
                target = MapTarget.Node(topic.topic.id),
                children = topic.link.points.mapIndexed { index, point ->
                    MapNode(
                        key = "${topic.topic.id}#$index",
                        kind = MapNodeKind.POINT,
                        label = MapLabel.Text(point),
                        recordings = 1,
                        lastAt = recordedAt,
                        weight = 0f,
                        recency = bright,
                        target = MapTarget.RecordingPage(sessionId, segment)
                    )
                }
            )
        }
        return MapScene(centre, children, twoLevel = true)
    }

    /** A recording on the ring of a theme or topic; a tap opens its own map. */
    private fun recordingNode(sessionId: String, at: Long, titles: Map<String, RecordingTitle>, now: Long) = MapNode(
        key = RECORDING_KEY_PREFIX + sessionId,
        kind = MapNodeKind.RECORDING,
        label = titles[sessionId]?.let { MapLabel.Recording(it) } ?: MapLabel.Text(""),
        recordings = 1,
        lastAt = at,
        weight = 0f,
        recency = recency(at, now),
        target = MapTarget.RecordingMap(sessionId)
    )

    /** The key of "You" in a drawing. */
    const val ROOT_KEY = "you"

    /** Recordings and topics share a ring; their keys must not clash. */
    private const val RECORDING_KEY_PREFIX = "rec:"
}
