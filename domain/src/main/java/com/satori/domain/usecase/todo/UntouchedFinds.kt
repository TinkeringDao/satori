package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.repository.TodoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A to-do changed within this long after it was found counts as untouched by the owner. */
internal const val UNTOUCHED_MS = 5_000L

/**
 * Whether [this] to-do or idea is as the app found it: nobody has changed it since, or only within
 * [UNTOUCHED_MS] of finding it. Each change the owner makes stamps its last change
 * ([Todo.updatedAt]): an edit, a tick and its Undo, keeping or dismissing a suggestion, moving it to
 * the ideas or making an idea a to-do, deleting an idea, a snooze of its reminder. Showing a
 * reminder doesn't. The app's own one-time changes stamp it as well (the recheck, and the move to
 * the ideas, of to-dos an older version found), so a to-do they changed counts as touched and is
 * left alone, as one the owner changed would be.
 */
internal fun Todo.isUntouched(): Boolean = updatedAt <= createdAt + UNTOUCHED_MS

/**
 * A recording's to-dos and ideas as the app found them, against those the owner changed
 * ([isUntouched]): what the AI steps made again over a Malayalam recording's readings give way to
 * (0.8.0, design R9), as Transcribe again's text made again does. The owner's stay as he left them.
 *
 * @property todos Where the to-dos are read
 * @property actions Takes one away, to the field log and with its reminder
 */
class UntouchedFinds(private val todos: TodoRepository, private val actions: TodoActions) {

    /**
     * A recording's finds: the ids of those nobody has touched since the app found them
     * ([untouched]), and how many the owner changed ([touched]).
     */
    data class Finds(val untouched: Set<String>, val touched: Int)

    /** [sessionId]'s finds now. */
    suspend fun of(sessionId: String): Finds {
        val all = todos.observeForSession(sessionId).first()
        val untouched = all.filter { it.isUntouched() }.mapTo(linkedSetOf()) { it.id }
        return Finds(untouched, all.size - untouched.size)
    }

    /**
     * Takes away those of [ids] that are still as the app found them, once what replaces them is
     * stored (ExtractSessionTodosUseCase's takeAway), each to the field log as from [source], with
     * its reminder ([TodoActions.delete]); one the owner changed meanwhile stays. Best effort: one
     * that can't be read or removed stays; only a cancel is thrown.
     *
     * @return How many went
     */
    suspend fun takeAway(sessionId: String, ids: Set<String>, source: TodoSource): Int {
        var removed = 0
        try {
            todos.observeForSession(sessionId).first()
                .filter { it.id in ids && it.isUntouched() }
                .forEach { old ->
                    actions.delete(old.id, source)
                    removed++
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What is removed stays removed; the rest stays.
        }
        return removed
    }
}
