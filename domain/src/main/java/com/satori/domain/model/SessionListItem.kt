package com.satori.domain.model

/**
 * A recording as the home list needs it: the session, the name of its category, and whether
 * anything was kept from it.
 *
 * @property categoryName The name of its category, or null when it has none or that category was
 *   deleted since
 * @property hasTranscript Whether at least one transcript line was saved
 * @property hasNotes Whether the AI wrote at least one note
 */
data class SessionListItem(
    val session: TranscriptionSession,
    val categoryName: String? = null,
    val hasTranscript: Boolean,
    val hasNotes: Boolean
) {
    /** What to call the recording in a list. */
    val title: RecordingTitle get() = RecordingTitle.of(session.name, categoryName, session.mode)
}

/**
 * What a recording is called in a list, in order of preference: the name the AI or the user gave
 * it, the name of its category, the name of its mode. The screen words the last, as it is a
 * string resource; [name] and [categoryName] are never blank.
 */
data class RecordingTitle(
    val name: String?,
    val categoryName: String?,
    val mode: RecordingMode
) {
    companion object {
        /** The title of a recording; a blank [name] or [categoryName] counts as none. */
        fun of(name: String?, categoryName: String?, mode: RecordingMode) = RecordingTitle(
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            categoryName = categoryName?.trim()?.takeIf { it.isNotEmpty() },
            mode = mode
        )
    }
}
