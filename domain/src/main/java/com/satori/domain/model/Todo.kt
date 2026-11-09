package com.satori.domain.model

/**
 * Something the user means to do, found in a recording or added by hand.
 *
 * The text is short and starts with a verb. A todo has a due time only when one was actually
 * said: the model never invents it, and dates are resolved in code against the recording's own
 * clock (see the time package).
 *
 * @property id Unique id (UUID)
 * @property sessionId The recording it came from; null if added by hand or once that recording
 *   is deleted
 * @property text What to do, at most about 15 words
 * @property kind A task, or an appointment at a fixed time
 * @property status Suggested (waiting for the user to keep it), open, done or dismissed; or an
 *   idea, which is no to-do
 * @property certainty Whether the due time was said firmly or only tentatively
 * @property capture How it came up: an explicit request to remember, a commitment, a request
 *   from someone else, or inferred by the model
 * @property dueAt When it is due (epoch ms), or null
 * @property dueHasTime False when only a date was said ("on Friday")
 * @property timePhrase The words the due time came from, as transcribed ("Freitag um 9")
 * @property remindAt When to remind (epoch ms), or null for no reminder
 * @property reminderShownAt When the reminder was shown (epoch ms), or null if it hasn't been.
 *   It counts for the current [remindAt] only when it isn't earlier than it, nor later than now:
 *   a reminder can't be shown before its time, so an earlier mark belongs to a time the reminder
 *   had before, and a later one to a clock that has since been set back. The reminder engine sets
 *   it (TodoRepository.markReminderShown); a new [remindAt] clears it.
 * @property sourceSegmentIds The transcript segments it was taken from
 * @property createdAt Creation time (epoch ms)
 * @property updatedAt Last change (epoch ms)
 * @property completedAt When it was marked done (epoch ms), or null
 */
data class Todo(
    val id: String,
    val sessionId: String?,
    val text: String,
    val kind: TodoKind = TodoKind.TASK,
    val status: TodoStatus = TodoStatus.OPEN,
    val certainty: TodoCertainty = TodoCertainty.CONFIRMED,
    val capture: TodoCapture = TodoCapture.EXPLICIT,
    val dueAt: Long? = null,
    val dueHasTime: Boolean = false,
    val timePhrase: String? = null,
    val remindAt: Long? = null,
    val reminderShownAt: Long? = null,
    val sourceSegmentIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null
)

enum class TodoKind { TASK, APPOINTMENT }

/**
 * SUGGESTED todos were inferred and wait for the user; only OPEN ones show and remind. An IDEA
 * shares the table but is no to-do (see [IDEA]).
 */
enum class TodoStatus {
    SUGGESTED,
    OPEN,
    DONE,
    DISMISSED,

    /**
     * An idea for how something could be, which nobody has said they will do ("there should be an
     * option to ..."). It never shows as a to-do and never reminds; the owner can make it one.
     */
    IDEA
}

enum class TodoCertainty { CONFIRMED, TENTATIVE }

/** EXPLICIT and COMMITMENT become open todos at once; the others start as suggestions. */
enum class TodoCapture { EXPLICIT, COMMITMENT, ASKED_BY_OTHER, INFERRED }