package com.satori.presentation.mindmap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user switched the map to its list form or back, shared by all views of the map so
 * the choice holds while going in and out of themes, on the cover screen as on the inner one.
 * Null until they choose: the screen then shows the list with TalkBack on and the drawing
 * otherwise ([MapForm.of]). Kept for the life of the process, like the screens' other view
 * choices.
 */
@Singleton
class MindMapViewChoice @Inject constructor() {

    private val _list = MutableStateFlow<Boolean?>(null)

    /** True for the list, false for the map, null for the screen's own choice. */
    val list: StateFlow<Boolean?> = _list.asStateFlow()

    fun choose(list: Boolean) {
        _list.value = list
    }
}

/**
 * How a view of the map is laid out: the drawing, on the cover screen as on the inner one, or the
 * page alone as a list.
 */
enum class MapForm {
    /** The page alone, as a list that reads in order. */
    LIST,

    /** The drawing over a reading pane that holds the page: the inner screen. */
    DRAWING_AND_PANE,

    /**
     * The drawing alone under the top bar, on a compact height (the cover screen, about 480 by 386
     * dp), where a pane would leave the drawing no room. The page, with the top level's chips,
     * search and "Map N older recordings", is the list form's.
     */
    DRAWING;

    companion object {
        /**
         * The form a view shows: the user's choice ([chosen], null while they haven't made one);
         * without one the list while TalkBack or another service that explores by touch is on
         * ([touchExploration]), as a list reads in order, and the drawing otherwise, on the cover
         * screen ([compact]) as on the inner one. A view with nothing to draw ([canDraw] false,
         * [MindMapRules.hasDrawing]) is the list whatever the choice.
         */
        fun of(chosen: Boolean?, touchExploration: Boolean, compact: Boolean, canDraw: Boolean): MapForm = when {
            !canDraw || (chosen ?: touchExploration) -> LIST
            compact -> DRAWING
            else -> DRAWING_AND_PANE
        }
    }
}
