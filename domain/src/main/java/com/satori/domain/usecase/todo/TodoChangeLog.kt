package com.satori.domain.usecase.todo

import com.satori.domain.model.TodoStatus

/**
 * Where a change of a to-do's status came from, as the field log names it. On 2026-10-13 no to-do
 * on the owner's phone had ever been ticked off (none DONE, and every open one unchanged since it
 * was found) while ticking worked on the emulator: these lines say which taps reach the store.
 */
enum class TodoSource(val label: String) {
    /** Home's To do list as it first shows, up to five to-dos: a circle, a to-do's sheet, the suggestions. */
    HOME("home"),

    /** Home's list after Show all: a circle past the first five. */
    LIST("list"),

    /** A recording's page: a circle, a to-do's sheet, its suggestions. */
    PAGE("page"),

    /** Done on a to-do's reminder. */
    NOTIFICATION("notification"),

    /** Undo after a to-do was ticked off, or after an idea was deleted. */
    UNDO("undo"),

    /** The ideas page. */
    IDEAS("ideas"),

    /** The app itself: the one-time recheck and move of to-dos an older version found. */
    APP("app"),

    /**
     * Transcribe again in another language: the to-dos and ideas the old transcript gave, which
     * nobody had touched, go with it, and are found again in the new one.
     */
    TRANSCRIBED_AGAIN("transcribed again"),

    /**
     * The AI steps made again over a Malayalam recording's readings (0.8.0, design R9): the to-dos
     * and ideas the AI found in its lines as they were said, which nobody had touched, go, and are
     * found again in the readings.
     */
    READ_AGAIN("read again")
}

/**
 * Where the field log hears of each change of a to-do's status: its id, the status before and
 * after, and where the change came from ([TodoSource]). Never the to-do's text. A change that left
 * the status as it was is told too ("DONE -> DONE"): a second tap, or Done on a reminder whose
 * to-do is done already, is what the field log is to show.
 */
fun interface TodoChangeLog {

    /**
     * [id] went from [from] to [to]: null [from] for a to-do that wasn't there (deleted
     * meanwhile), null [to] for one that is deleted.
     */
    fun changed(id: String, from: TodoStatus?, to: TodoStatus?, source: TodoSource)

    companion object {
        /** Logs nothing. */
        val NONE = TodoChangeLog { _, _, _, _ -> }

        /** The field log's line: "To-do <id>: OPEN -> DONE (home)", with "gone" for a status that isn't there. */
        fun line(id: String, from: TodoStatus?, to: TodoStatus?, source: TodoSource): String =
            "To-do $id: ${from?.name ?: GONE} -> ${to?.name ?: GONE} (${source.label})"

        private const val GONE = "gone"
    }
}
