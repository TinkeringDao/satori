package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import java.util.concurrent.ConcurrentHashMap

/**
 * The to-dos the app took away in this process as it found a recording's anew over its readings
 * ([TodoSource.READ_AGAIN]: a finishing made again, design R9), each as it was when it went. The
 * owner may have had one open in the edit sheet as it went: what he saves then brings it back as his
 * ([TodoActions.edit]) instead of going nowhere. One for the process, shared by every [TodoActions];
 * a to-do goes from it as it comes back.
 */
class TakenAwayTodos {
    private val taken = ConcurrentHashMap<String, Todo>()

    /** [todo] was taken away, as it was then. */
    fun record(todo: Todo) {
        taken[todo.id] = todo
    }

    /** The to-do [id] as it was when the app took it away, once; null when it took none such. */
    fun takeBack(id: String): Todo? = taken.remove(id)
}
