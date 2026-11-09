package com.satori.domain.model

/**
 * What a tap on Record starts: a category, live translation, or (only until the starter
 * categories exist) a plain voice note.
 */
sealed interface RecordTarget {

    /** Records as [category]: its mode, and the recording is filed under it. */
    data class Category(val category: RecordingCategory) : RecordTarget

    /** Live translation into the target language of the settings. It has no category. */
    data object Translate : RecordTarget

    /** A voice note without a category, for the moment before the starter categories are seeded. */
    data object VoiceNote : RecordTarget
}

/** Which target the one-tap grille starts, and how a target is remembered. */
object RecordTargets {

    /** Stored in [AppSettings.homeCategoryId] when Translate was the last thing recorded as. */
    const val TRANSLATE_ID = "translate"

    /** The starter category a fresh install records as; the first of CategoryRepository.STARTER_IDS. */
    const val DEFAULT_ID = "voice-note"

    /**
     * The target stored under [homeCategoryId]: Translate for [TRANSLATE_ID], else the category
     * with that id, else the first category (it was deleted, or is not stored yet). Without any
     * category it is a plain voice note.
     */
    fun resolve(homeCategoryId: String, categories: List<RecordingCategory>): RecordTarget {
        if (homeCategoryId == TRANSLATE_ID) return RecordTarget.Translate
        val category = categories.firstOrNull { it.id == homeCategoryId } ?: categories.firstOrNull()
        return if (category != null) RecordTarget.Category(category) else RecordTarget.VoiceNote
    }

    /** What to store as the home category after recording as [target]; null stores nothing. */
    fun idOf(target: RecordTarget): String? = when (target) {
        is RecordTarget.Category -> target.category.id
        RecordTarget.Translate -> TRANSLATE_ID
        RecordTarget.VoiceNote -> null
    }
}

/**
 * How to create the session for a [RecordTarget]: the arguments of CreateSessionUseCase that
 * the target decides.
 *
 * @property categoryId The category to file the recording under, or null
 * @property outputLanguage The language to translate into, only for a translating recording
 * @property insightStrategy When the notes are written, from the settings for the mode
 */
data class RecordRequest(
    val mode: RecordingMode,
    val categoryId: String?,
    val outputLanguage: String?,
    val insightStrategy: InsightStrategy
) {
    companion object {
        /** The languages a recording can be translated into; the settings offer these two. */
        private val TRANSLATION_TARGETS = setOf("en", "de")

        /**
         * The request for [target] under [settings]. A category in the translation mode
         * translates too, so it gets the target language as well.
         */
        fun of(target: RecordTarget, settings: AppSettings): RecordRequest {
            val mode = when (target) {
                is RecordTarget.Category -> target.category.mode
                RecordTarget.Translate -> RecordingMode.REAL_TIME_TRANSLATION
                RecordTarget.VoiceNote -> RecordingMode.SIMPLE_LISTENING
            }
            return RecordRequest(
                mode = mode,
                categoryId = (target as? RecordTarget.Category)?.category?.id,
                outputLanguage = if (mode == RecordingMode.REAL_TIME_TRANSLATION) {
                    translationTarget(settings.translationTargetLanguage)
                } else {
                    null
                },
                insightStrategy = settings.insightStrategyFor(mode)
            )
        }

        /** [language] if recordings can be translated into it, else English. */
        fun translationTarget(language: String): String = language.takeIf { it in TRANSLATION_TARGETS } ?: "en"
    }
}

/** When the notes of a [mode] recording are written, as chosen in the settings. */
fun AppSettings.insightStrategyFor(mode: RecordingMode): InsightStrategy = when (mode) {
    RecordingMode.SIMPLE_LISTENING -> simpleListeningDefaultStrategy
    RecordingMode.SHORT_MEETING -> shortMeetingDefaultStrategy
    RecordingMode.LONG_MEETING -> longMeetingDefaultStrategy
    RecordingMode.REAL_TIME_TRANSLATION -> translationDefaultStrategy
}
