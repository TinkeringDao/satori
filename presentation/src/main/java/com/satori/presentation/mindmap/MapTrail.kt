package com.satori.presentation.mindmap

/**
 * One view of the mind map, by what is in its centre. Each view is a screen of its own
 * ("mindmap", "mindmap/{nodeId}", "mindmap/session/{sessionId}"), so Back and process death are
 * handled by the navigation like everywhere else in the app.
 */
sealed interface MapKey {
    /** The top level: "You" in the centre and the themes around. */
    data object Root : MapKey

    /** A theme or a topic in the centre. */
    data class Node(val id: String) : MapKey

    /** A recording's own map: the recording in the centre, its topics and their points around it. */
    data class Recording(val sessionId: String) : MapKey
}

/**
 * The views the user went through to reach a view of the map, oldest first, as its navigation
 * argument `trail`. The breadcrumb row is built from it, and a crumb goes back to its view by
 * popping exactly as many screens as stand between them, so Back and the crumbs never disagree.
 *
 * Written as letters and ids joined by [SEPARATOR]: "r" for the top level, "n" plus a node id,
 * "s" plus a recording id. Ids are UUIDs (letters, digits and hyphens), which a route carries as
 * they are.
 */
object MapTrail {

    /** Between the keys; a character no id contains and a route needn't escape. */
    private const val SEPARATOR = '~'

    private const val ROOT = 'r'
    private const val NODE = 'n'
    private const val RECORDING = 's'

    /** [keys] as the `trail` argument. */
    fun encode(keys: List<MapKey>): String = keys.joinToString(SEPARATOR.toString()) { key ->
        when (key) {
            MapKey.Root -> ROOT.toString()
            is MapKey.Node -> "$NODE${key.id}"
            is MapKey.Recording -> "$RECORDING${key.sessionId}"
        }
    }

    /**
     * The keys of a `trail` argument. Null or blank is no trail (a view opened from outside the
     * map); a part that doesn't read as a key is left out rather than failing the screen.
     */
    fun decode(text: String?): List<MapKey> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(SEPARATOR).mapNotNull { part ->
            val id = part.drop(1)
            when {
                part == ROOT.toString() -> MapKey.Root
                part.firstOrNull() == NODE && id.isNotEmpty() -> MapKey.Node(id)
                part.firstOrNull() == RECORDING && id.isNotEmpty() -> MapKey.Recording(id)
                else -> null
            }
        }
    }

    /**
     * The way back up from [current], reached through [trail]: one crumb per view behind it, with
     * the number of screens to go back, oldest first. A view opened from outside the map (a
     * recording's map from its page) has no top level behind it; it gets a crumb for the top level
     * that opens it afresh. The top level itself has no crumbs.
     */
    fun crumbs(trail: List<MapKey>, current: MapKey): List<Crumb> {
        if (current == MapKey.Root) return emptyList()
        val crumbs = ArrayList<Crumb>(trail.size + 1)
        if (trail.firstOrNull() != MapKey.Root) crumbs += Crumb(MapKey.Root, back = null)
        trail.forEachIndexed { index, key -> crumbs += Crumb(key, back = trail.size - index) }
        return crumbs
    }

    /** The trail a view opened from [current] gets: [trail] and then [current]. */
    fun childTrail(trail: List<MapKey>, current: MapKey): String = encode(trail + current)
}

/** How the map opens a recording's page. */
sealed interface RecordingOpening {
    /** The recording's page is right behind the map, which was opened from it: go back to it. */
    data object Back : RecordingOpening

    /** Open the page as a new screen, at the line [segmentId] when it is not null. */
    data class Open(val segmentId: String?) : RecordingOpening

    companion object {
        /**
         * How to open the page of [sessionId], at [segmentId] if not null, from a view of the map
         * whose screen behind is the page of [behind] (null when it is something else). Going back
         * keeps one page per recording on the stack; a line to show needs a page opened for it.
         */
        fun of(behind: String?, sessionId: String, segmentId: String?): RecordingOpening =
            if (segmentId == null && behind == sessionId) Back else Open(segmentId)
    }
}

/**
 * One crumb of the breadcrumb row.
 *
 * @property key The view it leads to
 * @property back How many screens back that view is, or null when it isn't behind this one and is
 *   opened as a new screen (the top level, for a recording's map opened from its page)
 */
data class Crumb(val key: MapKey, val back: Int?)
