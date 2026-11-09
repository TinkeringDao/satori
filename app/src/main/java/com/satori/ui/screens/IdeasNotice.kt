package com.satori.ui.screens

import android.content.Context
import android.content.SharedPreferences
import com.satori.ui.STARTUP_PREFS

/**
 * Home's notice that the one-time move put to-dos found earlier among the ideas
 * (MoveFoundIdeasUseCase), because it changes to-dos the owner may have kept. MainActivity leaves
 * how many in the startup SharedPreferences ([postMoved]); home takes it as it first shows
 * ([takeMoved]), which clears it, so the notice comes once, also when the move ran while home
 * was not on show.
 */
internal object IdeasNotice {

    private const val KEY_MOVED = "ideas_moved"

    /** Leaves the notice that [count] to-dos moved to the ideas. */
    fun postMoved(context: Context, count: Int) = postMoved(prefs(context), count)

    /** How many to-dos moved, once: null when there is no notice, or it was taken already. */
    fun takeMoved(context: Context): Int? = takeMoved(prefs(context))

    fun postMoved(prefs: SharedPreferences, count: Int) {
        prefs.edit().putInt(KEY_MOVED, count).apply()
    }

    fun takeMoved(prefs: SharedPreferences): Int? {
        if (!prefs.contains(KEY_MOVED)) return null
        val count = prefs.getInt(KEY_MOVED, 0)
        prefs.edit().remove(KEY_MOVED).apply()
        return count
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(STARTUP_PREFS, Context.MODE_PRIVATE)
}
