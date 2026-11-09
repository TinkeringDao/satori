package com.satori.domain.usecase.category

import com.satori.domain.category.CategoryRules
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.first

/**
 * Makes sure the starter categories exist, with names in the app language. Run at every app
 * start: it only adds them while no category exists yet, so what the user reordered or deleted
 * stays as it is.
 *
 * A starter category still called by one of its own names in any of the app's languages
 * ([knownNames]) follows the app language: switched to German, "Voice note" becomes "Sprachnotiz",
 * and back. One the owner renamed keeps his name (the owner, 2026-10-15). A new name another
 * category has already is left alone, so no two share one ([CategoryRules.checkName]).
 *
 * Taking the four names by their meaning keeps them from being matched to the wrong starter id.
 */
class EnsureStarterCategoriesUseCase(
    private val categories: CategoryRepository
) {
    /**
     * @param knownNames Each starter id's names in all the app's languages, the current one among
     *   them; none renames nothing
     */
    suspend operator fun invoke(
        voiceNote: String,
        meeting: String,
        idea: String,
        todo: String,
        knownNames: Map<String, Set<String>> = emptyMap()
    ) {
        val names = mapOf(
            "voice-note" to voiceNote,
            "meeting" to meeting,
            "idea" to idea,
            "todo" to todo
        )
        categories.ensureStarterCategories(names)
        if (knownNames.isEmpty()) return
        val all = categories.observeAll().first()
        for ((id, name) in names) {
            val category = all.firstOrNull { it.id == id } ?: continue
            if (!followsLanguage(category.name, name, knownNames[id].orEmpty(), all.filter { it.id != id }.map { it.name })) continue
            categories.upsert(category.copy(name = name.trim()))
        }
    }

    companion object {
        /**
         * Whether a starter category called [current] takes [wanted], its name in the app language
         * now: it is called by one of its own names ([known]), not [wanted] already, and no other
         * category ([otherNames]) has [wanted].
         */
        fun followsLanguage(current: String, wanted: String, known: Set<String>, otherNames: Collection<String>): Boolean =
            current.trim() != wanted.trim() &&
                known.any { it.trim() == current.trim() } &&
                CategoryRules.checkName(wanted, otherNames).isValid
    }
}
