package com.satori.presentation.sessions

import android.content.Context
import android.content.SharedPreferences

/**
 * The one flag that says Android's notification window was asked for, shared by the two that ask
 * (design 4.1): the to-do reminders' sheet and the ping offer on home's strip. Whichever asks
 * first, the other doesn't. It is kept where the reminders' sheet always kept it, so an install
 * that answered that sheet before isn't asked again.
 *
 * Beside it, how many times a recording's notes were ready while Satori was out of sight and "Your
 * notes are ready" could not be posted, as notifications aren't allowed ([markNotesMissed]): the
 * moment the ping is needed, which makes home's strip offer it the next time it is seen.
 */
object NotificationAsk {

    /** The SharedPreferences file. */
    const val PREFERENCES = "reminder_prompt"

    /** True once Android's window was asked for, or the reminders' sheet was answered. */
    const val KEY_ASKED = "asked"

    /** How many "notes are ready" notifications couldn't be posted, as notifications aren't allowed. */
    const val KEY_NOTES_MISSED = "notes_missed"

    /** Whether the window was asked for. */
    fun asked(context: Context): Boolean = preferences(context).getBoolean(KEY_ASKED, false)

    /** The window is asked for now, or the reminders' sheet was answered. */
    fun markAsked(context: Context) {
        preferences(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** How many notes-ready notifications couldn't be posted so far ([markNotesMissed]). */
    fun notesMissed(context: Context): Int = preferences(context).getInt(KEY_NOTES_MISSED, 0)

    /**
     * A recording's notes were ready and their notification couldn't be posted, as notifications
     * aren't allowed: counted. Returns the count with this one, for the field log.
     */
    fun markNotesMissed(context: Context): Int {
        val preferences = preferences(context)
        // Two recordings' cues can count at once, on the cues' threads; neither count may be lost.
        synchronized(this) {
            val missed = preferences.getInt(KEY_NOTES_MISSED, 0) + 1
            preferences.edit().putInt(KEY_NOTES_MISSED, missed).apply()
            return missed
        }
    }

    /** The file the flag is in, for a screen that follows the flag as it changes. */
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
