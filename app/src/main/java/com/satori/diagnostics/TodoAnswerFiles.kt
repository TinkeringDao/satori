package com.satori.diagnostics

import android.util.Log
import com.satori.domain.usecase.todo.AnswerLog
import java.io.File
import java.io.IOException

/**
 * [AnswerLog] of the debug build: each raw answer of the to-do model goes to
 * `<dir>/<sessionId>-<window>.json`, and since 0.7.2 each raw answer of the notes and the name to
 * `<dir>/<sessionId>-<call>.txt` beside them ([AnswerLog.answer]'s tags), in the app's private
 * storage, where `run-as` reads them. The answers hold what was said, so they never go to the field
 * log, and they go when the recording is deleted ([forget]). Finding a recording's to-dos again
 * writes its answers anew, and so does a step of the notes or the name, which first clears its own
 * ([clear]). An id that is not a file name, or a tag that isn't one of the steps', is skipped, and
 * a write that fails costs only that answer, never the notes, the name or the to-dos.
 *
 * @param warn Where problems are logged; never with the answer
 */
internal class TodoAnswerFiles(
    private val dir: File,
    private val warn: (String, Throwable?) -> Unit = { message, e -> Log.w(TAG, message, e) }
) : AnswerLog {

    override fun todoAnswer(sessionId: String, window: Int, answer: String) {
        if (!SAFE_ID.matches(sessionId) || window < 0) {
            warn("No raw to-do answer kept for an id that is not a file name", null)
            return
        }
        write("$sessionId-$window.json", answer, "A raw to-do answer was not kept")
    }

    override fun answer(sessionId: String, call: String, answer: String) {
        if (!SAFE_ID.matches(sessionId) || !AnswerLog.CALL.matches(call)) {
            warn("No raw answer kept for an id that is not a file name or a tag of no step", null)
            return
        }
        write("$sessionId-$call.txt", answer, "A raw answer was not kept")
    }

    /** Deletes the answers of [sessionId] whose tag is [calls] or starts with it and a hyphen: `<sessionId>-<calls>[-...].txt`. */
    override fun clear(sessionId: String, calls: String) {
        if (!SAFE_ID.matches(sessionId) || !AnswerLog.CALL.matches(calls)) return
        delete(Regex(Regex.escape("$sessionId-$calls") + "(?:-[a-z0-9]+)*\\.txt"), "raw answers of a step run again")
    }

    /**
     * Deletes every answer kept for [sessionId], and only those: `<sessionId>-<window>.json` and
     * `<sessionId>-<call>.txt`. A tag starts with a step's word, which no recording id (hex digits
     * and hyphens) holds, so the answers of an id that starts with this one stay.
     */
    override fun forget(sessionId: String) {
        if (!SAFE_ID.matches(sessionId)) return
        delete(Regex(Regex.escape(sessionId) + "-(?:\\d+\\.json|" + AnswerLog.CALL.pattern + "\\.txt)"), "raw answers of a deleted recording")
    }

    private fun write(name: String, answer: String, failure: String) {
        try {
            dir.mkdirs()
            File(dir, name).writeText(answer)
        } catch (e: IOException) {
            warn(failure, e)
        }
    }

    private fun delete(names: Regex, what: String) {
        val kept = dir.listFiles { file -> names.matches(file.name) } ?: return
        val left = kept.count { !it.delete() }
        if (left > 0) warn("$left $what were not deleted", null)
    }

    companion object {
        /** Where the answers go, under the app's files directory. */
        const val DIR = "debug/todo-answers"

        private const val TAG = "SatoriDebug"

        // Recording ids are UUIDs; anything else could name a file outside the folder.
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")
    }
}
