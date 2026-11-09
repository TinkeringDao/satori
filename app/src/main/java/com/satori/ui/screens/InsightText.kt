package com.satori.ui.screens

import androidx.annotation.StringRes
import com.satori.domain.model.RecordingMode
import com.satori.ui.R
import org.json.JSONObject

// How notes are read for display and for export, so both show the same thing.

/**
 * The name of the second tab, and of the notes' heading in the export: "Notes", or for a
 * Translate recording what its notes are, the translation.
 */
@StringRes
internal fun notesTabRes(mode: RecordingMode): Int =
    if (mode == RecordingMode.REAL_TIME_TRANSLATION) R.string.g_details_tab_translation else R.string.g_tab_insights

/** Older insights store their content as JSON with a "summary" field, newer ones as plain text. */
internal fun summaryText(content: String): String {
    if (!content.trimStart().startsWith("{")) return content
    return try {
        val json = JSONObject(content)
        if (json.has("summary")) json.getString("summary") else content
    } catch (e: Exception) {
        content
    }
}
