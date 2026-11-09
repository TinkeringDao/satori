package com.satori.domain.usecase.todo

/**
 * Keeps the raw answers the model gave for a recording, so the main session can read what the
 * model said beside what was stored: each window of to-do extraction ([ExtractTodosUseCase]), whose
 * answers hold the ideas too, and since 0.7.2 each call of the notes and the name ([answer]). The
 * debug build keeps them in the app's private storage; the release build keeps none ([NONE]).
 * Never a log: an answer holds what was said.
 */
fun interface AnswerLog {

    /**
     * @param sessionId The recording
     * @param window Which window of the recording the answer is for, from 0
     * @param answer The model's answer, as it gave it
     */
    fun todoAnswer(sessionId: String, window: Int, answer: String)

    /**
     * The model's answer to one call of the notes or the name, under a tag that says which
     * ([CALL]): "notes-final-0" for the last note after Stop and "notes-final-1" for its second
     * answer, "notes-part2-0" for part 2 of the whole pass, "notes-merge-0" for its merge, "name-0"
     * and "name-1" for the name's first and second answers, and "rerun-..." for a debug rerun.
     * Keeping none, there is nothing to do.
     *
     * @param sessionId The recording
     * @param call The tag, of the form [CALL]
     * @param answer The model's answer, as it gave it
     */
    fun answer(sessionId: String, call: String, answer: String) {}

    /**
     * Forgets the answers kept for [sessionId] under the tags that start with [calls] ("notes",
     * "notes-final", "name"), as a step that runs again writes its answers anew: a pass of the
     * notes in one part after one in three would leave the old parts' answers beside the new one.
     * Keeping none, there is nothing to do.
     */
    fun clear(sessionId: String, calls: String) {}

    /**
     * The recording is deleted: the answers kept for it go with it, as they hold what was said in
     * it, also items no to-do was made of. Keeping none, there is nothing to do.
     *
     * @param sessionId The recording
     */
    fun forget(sessionId: String) {}

    companion object {
        /** Keeps nothing. */
        val NONE = AnswerLog { _, _, _ -> }

        /** The form of a call's tag ([answer]): one of the steps, then words of small letters and digits, by hyphens. */
        val CALL = Regex("(?:notes|name|rerun)(?:-[a-z0-9]+)*")

        /** The tag of the [index]th answer (0 the first, 1 the second) of [call], one of the steps' tags ([CALL]). */
        fun tag(call: String, index: Int): String = "$call-$index"
    }
}
