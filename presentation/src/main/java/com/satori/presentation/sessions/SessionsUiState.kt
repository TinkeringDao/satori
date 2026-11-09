package com.satori.presentation.sessions

import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.RecordTarget
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.Todo
import com.satori.domain.usecase.reminder.ReminderRules
import com.satori.presentation.firstrun.StripState

/**
 * UI state for the home screen.
 *
 * @property recordings The recordings to list, newest first, without the empty attempts
 * @property titles The title of every recording by id, the empty attempts included, for
 *   "From <recording>" next to a to-do
 * @property finishing The recordings being finished in the background by id, with how far each
 *   has come, for the line under their title
 * @property openTodos Open to-dos: those with a due time first (soonest first), then the rest
 * @property suggestions To-dos the AI found that wait for the user to keep or dismiss them
 * @property ideas The ideas found in recordings, newest first; they are no to-dos, so only a row
 *   that leads to them shows
 * @property newIdeas How many of [ideas] were found since the owner last saw the ideas row
 *   ([IdeasSighting])
 * @property categories Every category, in the order the Record as sheet lists them
 * @property target What the one-tap Record starts, and what the chip under it names
 * @property translateTarget The language the Translate row translates into
 * @property language The language the one-tap Record records in, which the chip names when it is
 *   Malayalam, and which "Record as" starts with; English or German while the Malayalam model
 *   isn't on the phone, whatever was chosen before
 * @property malayalamOnPhone Whether the Malayalam speech model is on the phone, so "Record as"
 *   offers Malayalam; without it the sheet links to its download
 * @property isLoading True until the first lists have been read, so the empty hint doesn't flash
 * @property error What went wrong, null if nothing did
 * @property strip The status strip at the top of the list: a download after the first run's setup,
 *   a Malayalam the setup dropped, the first notes' confirmation or a one-time offer, one at a time
 *   ([HomeStrip]); null when there is none
 * @property asksQuiet No offer and no ask shows now: a recorder is live, or a recording stopped less
 *   than a minute ago ([com.satori.domain.firstrun.NudgeRules.QUIET_AFTER_STOP_MS]); the to-do
 *   reminders' sheet waits for it too
 */
data class SessionsUiState(
    val recordings: List<SessionListItem> = emptyList(),
    val titles: Map<String, RecordingTitle> = emptyMap(),
    val finishing: Map<String, FinishingStatus> = emptyMap(),
    val openTodos: List<Todo> = emptyList(),
    val suggestions: List<Todo> = emptyList(),
    val ideas: List<Todo> = emptyList(),
    val newIdeas: Int = 0,
    val categories: List<RecordingCategory> = emptyList(),
    val target: RecordTarget = RecordTarget.VoiceNote,
    val translateTarget: String = "en",
    val language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
    val malayalamOnPhone: Boolean = false,
    val isLoading: Boolean = true,
    val error: SessionsError? = null,
    val strip: StripState? = null,
    val asksQuiet: Boolean = false
) {
    /**
     * The To do section shows when there is something to do or something to decide on, and when
     * there are ideas, whose row is part of it.
     */
    val hasTodoSection: Boolean get() = openTodos.isNotEmpty() || suggestions.isNotEmpty() || ideas.isNotEmpty()

    /** The row of the To do section that opens the ideas, or null without ideas. */
    val ideasRow: IdeasRow? get() = if (ideas.isEmpty()) null else IdeasRow(ideas.size, newIdeas.takeIf { it > 0 })

    /** An open to-do has a reminder that hasn't been shown yet, so it needs notifications. */
    val hasPendingReminders: Boolean
        get() {
            val now = System.currentTimeMillis()
            return openTodos.any { it.remindAt != null && !ReminderRules.isShown(it, now) }
        }

    /** Nothing to list: the home shows its one sentence of guidance. */
    val isEmpty: Boolean get() = recordings.isEmpty() && !hasTodoSection

    companion object {
        /**
         * How many of [ideas] were found after [seenAt] ([IdeasSighting.seenAt]). An idea moved
         * over from the to-dos, or brought back with Undo, keeps the time it was found, so it
         * doesn't count as new.
         */
        fun newIdeas(ideas: List<Todo>, seenAt: Long): Int = ideas.count { it.createdAt > seenAt }
    }
}

/**
 * The row under the suggestions that opens the ideas: "4 ideas", or "4 ideas, 2 new".
 *
 * @property count How many ideas there are
 * @property newCount How many of them were found since the owner last saw the row; null when none
 *   was, and the row says only how many there are
 */
data class IdeasRow(val count: Int, val newCount: Int?)

/**
 * How far the owner has seen home's ideas row, which says how many ideas are new: those found
 * after [seenAt].
 *
 * The row counts as seen once it was on screen for a moment with the phone unlocked ([shown]);
 * what it said then is no longer new once home is left. A row only composed ahead of a scroll,
 * one behind the lock screen and one on screen for a blink are not seen, so their new ideas stay
 * new. Tapping the row ([tapped]) opens the ideas: leaving home for them leaves none new. Home
 * that is back within [RETURN_WITHIN_MS] was not left: a fold moves it to the other screen, and
 * Android may stop it on the way.
 *
 * @property seenAt Ideas found after this (epoch ms) are new
 * @property shownSince The row was seen since home was last left, saying what it said at this
 *   time (epoch ms); null when it wasn't
 * @property rowTapped The row was tapped since home was last left
 * @property leftAt When home was left after the row was seen; null while home shows
 */
data class IdeasSighting(
    val seenAt: Long,
    val shownSince: Long? = null,
    val rowTapped: Boolean = false,
    val leftAt: Long? = null
) {
    /** The row was seen, saying what it said at [sinceMs]; a later sighting covers more. */
    fun shown(sinceMs: Long): IdeasSighting = copy(shownSince = maxOf(sinceMs, shownSince ?: sinceMs))

    /** The row was tapped, which opens the ideas. */
    fun tapped(): IdeasSighting = copy(rowTapped = true)

    /**
     * Home stopped showing at [nowMs]. Left for the ideas, none of them is new from then on; a
     * row seen counts once home stays away ([back]).
     */
    fun left(nowMs: Long): IdeasSighting = when {
        rowTapped -> IdeasSighting(seenAt = maxOf(seenAt, nowMs))
        shownSince != null && leftAt == null -> copy(leftAt = nowMs)
        else -> this
    }

    /**
     * Home shows again at [nowMs]. After a leave of [RETURN_WITHIN_MS] or more what the row said
     * when it was seen is no longer new; a shorter one changes nothing, as for a fold.
     */
    fun back(nowMs: Long): IdeasSighting {
        val left = leftAt ?: return this
        return if (nowMs - left in 0 until RETURN_WITHIN_MS) {
            copy(leftAt = null)
        } else {
            IdeasSighting(seenAt = maxOf(seenAt, shownSince ?: seenAt))
        }
    }

    companion object {
        /** A leave of home shorter than this (ms) is a fold or a glance away: the row stays as it was. */
        const val RETURN_WITHIN_MS = 2_000L
    }
}

/** What went wrong on the home screen. The screen words it, in the app's language. */
enum class SessionsError {
    /** The recordings couldn't be read. */
    Load,

    /** A new recording couldn't be created, so the recorder didn't open. */
    Create,

    /** A change to a to-do couldn't be stored. */
    Todo
}
