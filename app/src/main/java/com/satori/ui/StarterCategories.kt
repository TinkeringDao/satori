package com.satori.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.satori.domain.usecase.category.EnsureStarterCategoriesUseCase
import com.satori.ui.design.AppLanguageCodes
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Makes sure the starter categories exist, named in the app language of [context], and has those
 * still called by one of their own names in the app's languages follow it
 * ([EnsureStarterCategoriesUseCase]). Nothing depends on this finishing, so a failure is logged and
 * the app goes on.
 */
suspend fun EnsureStarterCategoriesUseCase.ensureIn(context: Context) {
    try {
        val inEach = AppLanguageCodes.map { code -> context.inLanguage(code) }
        invoke(
            voiceNote = context.getString(R.string.g_category_voice_note),
            meeting = context.getString(R.string.g_category_meeting),
            idea = context.getString(R.string.g_category_idea),
            todo = context.getString(R.string.g_category_todo),
            knownNames = mapOf(
                "voice-note" to inEach.mapTo(mutableSetOf()) { it.getString(R.string.g_category_voice_note) },
                "meeting" to inEach.mapTo(mutableSetOf()) { it.getString(R.string.g_category_meeting) },
                "idea" to inEach.mapTo(mutableSetOf()) { it.getString(R.string.g_category_idea) },
                "todo" to inEach.mapTo(mutableSetOf()) { it.getString(R.string.g_category_todo) }
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("StarterCategories", "Failed to add the starter categories", e)
    }
}

/**
 * [this] with its resources in the language [code], for the starter categories' names in it. It
 * only reads them: the app's language stays as it is, and the APK carries both languages
 * (resourceConfigurations), so there is no language split to download.
 */
@SuppressLint("AppBundleLocaleChanges")
private fun Context.inLanguage(code: String): Context =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(code)) })
