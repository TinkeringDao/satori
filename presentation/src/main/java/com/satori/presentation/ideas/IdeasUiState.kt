package com.satori.presentation.ideas

import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.Todo

/**
 * UI state for the Ideas page: the ideas found in recordings, grouped by recording.
 *
 * @property groups Newest first: the group whose newest idea is the newest comes first, so ideas
 *   found again in an older recording show at the top too
 * @property isLoading True until the ideas have been read, so the empty hint doesn't flash
 * @property error What went wrong, null if nothing did
 */
data class IdeasUiState(
    val groups: List<IdeaGroup> = emptyList(),
    val isLoading: Boolean = true,
    val error: IdeasError? = null
) {
    /** No idea to list: the page says where ideas come from. */
    val isEmpty: Boolean get() = groups.isEmpty()

    /** The idea [id] as listed now, or null when it is no longer an idea (or [id] is null). */
    fun idea(id: String?): ListedIdea? {
        if (id == null) return null
        for (group in groups) {
            val idea = group.ideas.firstOrNull { it.id == id } ?: continue
            return ListedIdea(idea, group.recording)
        }
        return null
    }

    companion object {
        /**
         * Groups [ideas] by the recording they were said in, newest first.
         *
         * Within a group the newest idea comes first; the groups follow their newest idea, then the
         * recording, newest first. Ideas whose recording isn't among [recordings] (it was deleted,
         * which keeps its ideas) make one group of their own, placed by its newest idea too, and
         * after the recordings' groups whose newest idea is as new. The order depends only on what
         * is stored, not on the order of [ideas], so an idea that Undo brings back returns to
         * where it was.
         *
         * @param recordings Every recording, as the recordings list reads them
         */
        fun group(ideas: List<Todo>, recordings: List<SessionListItem>): List<IdeaGroup> {
            val known = recordings.associate { item ->
                item.session.id to IdeaRecording(item.session.id, item.title, item.session.createdAt)
            }
            val newestFirst = compareByDescending<Todo> { it.createdAt }.thenBy { it.id }
            return ideas
                .groupBy { idea -> idea.sessionId?.let(known::get) }
                .map { (recording, inGroup) -> IdeaGroup(recording, inGroup.sortedWith(newestFirst)) }
                .sortedWith(
                    compareByDescending<IdeaGroup> { it.ideas.first().createdAt }
                        .thenByDescending { it.recording?.createdAt ?: Long.MIN_VALUE }
                        .thenBy { it.recording?.sessionId.orEmpty() }
                )
        }
    }
}

/**
 * The ideas of one recording, or of recordings that are gone.
 *
 * @property recording The recording they were said in, or null when it was deleted
 * @property ideas Never empty; newest first
 */
data class IdeaGroup(val recording: IdeaRecording?, val ideas: List<Todo>)

/**
 * A recording as the Ideas page names it, above its ideas.
 *
 * @property createdAt When it was made (epoch ms)
 */
data class IdeaRecording(val sessionId: String, val title: RecordingTitle, val createdAt: Long)

/**
 * An idea as the page lists it, for its sheet.
 *
 * @property recording The recording it was said in, which its sheet can open; null when that was
 *   deleted
 */
data class ListedIdea(val idea: Todo, val recording: IdeaRecording?) {
    /** The line it was said at, where its recording opens; null for the recording's top. */
    val firstSourceSegment: String? get() = idea.sourceSegmentIds.firstOrNull()
}

/** What went wrong on the Ideas page. The screen words it, in the app's language. */
enum class IdeasError {
    /** The ideas or the recordings couldn't be read. */
    Load,

    /** Making an idea a to-do, deleting it or taking that back couldn't be stored. */
    Change
}
