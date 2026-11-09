package com.satori.presentation.mindmap

import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindSessionTopic
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase

/**
 * What one view of the mind map shows: what is in its centre ([page]), the drawing ([scene]) and
 * the way back up ([crumbs]).
 *
 * @property key The view
 * @property page What the reading pane, or the page in list form, shows; [MapPage.Loading] until
 *   the first data is read
 * @property scene The map's drawing; null while loading and for a view whose centre is gone
 * @property crumbs The way back up, oldest first; empty at the top level
 * @property childTrail The `trail` argument of a view opened from this one
 * @property titles What each recording is called, by id
 * @property ai Whether the AI can run here now
 * @property job The AI work this view runs, if any
 * @property error What went wrong, if anything
 */
data class MindMapUiState(
    val key: MapKey = MapKey.Root,
    val page: MapPage = MapPage.Loading,
    val scene: MapScene? = null,
    val crumbs: List<CrumbView> = emptyList(),
    val childTrail: String = "",
    val titles: Map<String, RecordingTitle> = emptyMap(),
    val ai: AiState = AiState(),
    val job: MapJob? = null,
    val error: MindMapError? = null
)

/** A crumb with what it is called. */
data class CrumbView(val label: MapLabel, val back: Int?, val key: MapKey)

/** What a node or a crumb is called; the screen words [You] and recordings without a name. */
sealed interface MapLabel {
    /** The top level: the person whose thoughts these are. */
    data object You : MapLabel

    /** A theme, a topic or a point, as the AI wrote it. */
    data class Text(val text: String) : MapLabel

    /** A recording, called by its name, category or mode as everywhere else. */
    data class Recording(val title: RecordingTitle) : MapLabel
}

/** Which themes the top level shows; Core is where the map opens. */
enum class ThemeFilter {
    /** The pinned themes and the [MindMapRules.CORE_SIZE] with the most recordings. */
    CORE,

    /** The themes with a recording from the last [MindMapRules.RECENT_DAYS] days, newest first. */
    RECENT,

    /** Every theme. */
    ALL
}

/** What the pane under the map, or the page in list form, is about. */
sealed interface MapPage {
    /** Nothing read yet. */
    data object Loading : MapPage

    /** The node or recording in the centre no longer exists (deleted, or mapped anew). */
    data object Missing : MapPage

    /**
     * The top level.
     *
     * @property filter The chosen chip
     * @property query The search, as typed
     * @property themes The themes to list, after the chip and the search
     * @property totalThemes How many themes the map has in all, to tell an empty map from an
     *   empty filter
     * @property elsewhere How many themes outside the chip match the search, when none in it
     *   does: the page offers All
     * @property recordings How many recordings there are, for "You" in the centre
     * @property unmapped How many recordings wait to be mapped ("Map N older recordings")
     */
    data class Root(
        val filter: ThemeFilter,
        val query: String,
        val themes: List<MindNodeItem>,
        val totalThemes: Int,
        val elsewhere: Int,
        val recordings: Int,
        val unmapped: Int
    ) : MapPage

    /**
     * A theme's page: its summary, its topics and what its recordings say about it.
     *
     * @property topics The topics to list, without the one that only repeats the theme's name
     * @property byRecording The points, by recording, newest first
     */
    data class Theme(
        val theme: MindNode,
        val recordings: Int,
        val lastAt: Long?,
        val summary: SummaryState,
        val topics: List<MindNodeItem>,
        val byRecording: List<RecordingPoints>
    ) : MapPage

    /** A topic's page: what its recordings say about it, and the theme it is under. */
    data class Topic(
        val topic: MindNode,
        val theme: MindNode?,
        val recordings: Int,
        val lastAt: Long?,
        val byRecording: List<RecordingPoints>
    ) : MapPage

    /**
     * A recording's own map: its notes and its topics with their points.
     *
     * @property topics Its topics, in the order the AI named them; empty when it isn't mapped
     * @property enoughText Whether it has enough transcript to be mapped at all
     */
    data class Recording(
        val details: SessionWithDetails,
        val topics: List<MindSessionTopic>,
        val enoughText: Boolean
    ) : MapPage
}

/** How a theme's summary stands. */
enum class SummaryState {
    /** None written yet. */
    NONE,

    /** Written before the theme got or lost a recording. */
    STALE,

    /** Up to date with its recordings. */
    CURRENT
}

/**
 * What one recording says under a theme or topic.
 *
 * @property topics Its topics there, each with its points, in the order they were stored
 */
data class RecordingPoints(
    val sessionId: String,
    val recordedAt: Long,
    val topics: List<TopicPoints>
)

/**
 * The points of one topic in one recording.
 *
 * @property label The topic's name, or null where the page needn't say it (the topic of a
 *   topic's page, or the one that only repeats the theme's name)
 * @property segmentIds The lines of the transcript it came from, for opening the recording there
 */
data class TopicPoints(
    val topicId: String,
    val label: String?,
    val points: List<String>,
    val segmentIds: List<String>
)

/**
 * The drawing of one view: the centre and the children around it.
 *
 * @property leadCount How many of the first children sit together around 12 o'clock
 *   ([MindMapLayout.rings]); the rest follow round the ring
 * @property twoLevel Whether the children carry children of their own: a recording's topics and
 *   their points, drawn as a tree from the top down ([MindMapLayout.outline])
 * @property hidden How many more children there are than the drawing shows; the pane lists them
 */
data class MapScene(
    val centre: MapNode,
    val children: List<MapNode>,
    val leadCount: Int = 1,
    val twoLevel: Boolean = false,
    val hidden: Int = 0
)

/** What a node is, which decides how it is drawn and what TalkBack calls it. */
enum class MapNodeKind { YOU, THEME, TOPIC, RECORDING, POINT }

/**
 * One node of the drawing.
 *
 * @property key Unique within the drawing
 * @property recordings How many recordings it gathers, for its description
 * @property lastAt When its newest recording was made, for its description; null for "You"
 * @property weight Its size, 0 to 1, from its number of recordings against the largest drawn
 * @property recency Its brightness, 0 to 1: 1 when touched in the last week, fading over three
 *   months
 * @property target What a tap opens; null for the centre of a theme or topic, which is open
 * @property children Its points, in a recording's own map
 */
data class MapNode(
    val key: String,
    val kind: MapNodeKind,
    val label: MapLabel,
    val recordings: Int,
    val lastAt: Long?,
    val weight: Float,
    val recency: Float,
    val pinned: Boolean = false,
    val target: MapTarget? = null,
    val children: List<MapNode> = emptyList()
)

/** What tapping a node, a crumb or a row opens. */
sealed interface MapTarget {
    /** The view of a theme or a topic. */
    data class Node(val id: String) : MapTarget

    /** A recording's own map. */
    data class RecordingMap(val sessionId: String) : MapTarget

    /** A recording's page, at the line [segmentId] when there is one. */
    data class RecordingPage(val sessionId: String, val segmentId: String? = null) : MapTarget
}

/**
 * Whether the AI can run from this view now.
 *
 * @property available AI notes are on and a model is installed; null until checked
 * @property recorderBusy A recording is being made or finished; it needs the memory, and it is
 *   mapped when it finishes
 * @property otherJob Another view of the map has AI work running
 */
data class AiState(
    val available: Boolean? = null,
    val recorderBusy: Boolean = false,
    val otherJob: Boolean = false
) {
    /** Whether AI work may start now. */
    val canRun: Boolean get() = available == true && !recorderBusy && !otherJob
}

/** AI work a view of the map runs. Leaving the view stops it. */
sealed interface MapJob {
    /**
     * Mapping the older recordings, from the top level.
     *
     * @property progress Where it is; null until the first recording starts
     * @property stopping Cancel was pressed and the run is ending
     */
    data class Older(
        val progress: MapOlderRecordingsUseCase.Progress? = null,
        val stopping: Boolean = false
    ) : MapJob

    /** Rewriting a theme's summary (Update). */
    data object Summary : MapJob

    /** Mapping the recording of a recording's own map. */
    data object Recording : MapJob
}

/** What went wrong in a view of the map; the screen words it with what to do next. */
sealed interface MindMapError {
    /** The map couldn't be read. */
    data object Load : MindMapError

    /** A pin or unpin wasn't stored. */
    data object Pin : MindMapError

    /** The AI was turned off or its model removed meanwhile. */
    data object NoModel : MindMapError

    /** The model couldn't be loaded or run. */
    data object ModelFailed : MindMapError

    /** The model stopped working during a run, after [mapped] recordings had been mapped. */
    data class OlderStopped(val mapped: Int) : MindMapError

    /** The model's summary couldn't be read; the old one stays. */
    data object SummaryUnreadable : MindMapError

    /** The model's topics couldn't be read; the recording stays as it was. */
    data object MapUnreadable : MindMapError

    /** The recording has too little transcript to be mapped. */
    data object TooShort : MindMapError

    /** The map couldn't be read or written while a recording was mapped (a database error). */
    data object Save : MindMapError
}

/** A short confirmation the screen shows once, at the bottom. */
sealed interface MindMapNotice {
    /** A run of the older recordings ended: [mapped] got themes, [withoutTopics] had none. */
    data class OlderDone(val mapped: Int, val withoutTopics: Int) : MindMapNotice

    /** Another run was going on already. */
    data object AlreadyRunning : MindMapNotice

    /** The recording was mapped to [themes] themes. */
    data class RecordingMapped(val themes: Int) : MindMapNotice

    /** The AI read the recording and found no topic that held up. */
    data object NothingFound : MindMapNotice

    /**
     * A recording that started stopped "Map N older recordings" or "Map this recording" between
     * two recordings or summaries; what was mapped stayed.
     */
    data object MappingStopped : MindMapNotice

    /** A recording that started stopped Update before the summary was written. */
    data object UpdateStopped : MindMapNotice
}
