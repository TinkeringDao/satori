package com.satori.domain.category

import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode

/** What makes a recording category's name and position acceptable, and how a new one is made. */
object CategoryRules {

    /** Longest name, in characters. */
    const val MAX_NAME_LENGTH = 24

    /** Why a name can't be used. */
    enum class NameProblem {
        /** Nothing but spaces. */
        EMPTY,

        /** More than [MAX_NAME_LENGTH] characters. */
        TOO_LONG,

        /** Another category already has this name, in any letter case. */
        DUPLICATE
    }

    /**
     * The outcome of [checkName].
     *
     * @property name The name to store: the input without the spaces around it.
     * @property problem Why [name] can't be used, or null when it can.
     */
    class NameCheck(val name: String, val problem: NameProblem?) {
        val isValid: Boolean get() = problem == null
    }

    /**
     * Checks a name typed for a category. Names are trimmed, 1 to [MAX_NAME_LENGTH] characters
     * and unique among [otherNames] ignoring case, so "idea" clashes with "Idea".
     *
     * @param otherNames The names of the other categories; leave out the one being renamed, so
     *   keeping its name is fine.
     */
    fun checkName(input: String, otherNames: Collection<String>): NameCheck {
        val name = input.trim()
        val problem = when {
            name.isEmpty() -> NameProblem.EMPTY
            name.codePointCount(0, name.length) > MAX_NAME_LENGTH -> NameProblem.TOO_LONG
            otherNames.any { it.trim().equals(name, ignoreCase = true) } -> NameProblem.DUPLICATE
            else -> null
        }
        return NameCheck(name, problem)
    }

    /**
     * The category with [id], or the first one when it was deleted since it was chosen (or [id]
     * is null); null only when there are no categories. What the quick key shows and records as.
     */
    fun chosen(categories: List<RecordingCategory>, id: String?): RecordingCategory? =
        categories.firstOrNull { it.id == id } ?: categories.firstOrNull()

    /** The position after the last of [existing], so a new category goes to the end of the list. */
    fun nextSortOrder(existing: List<RecordingCategory>): Int =
        (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /**
     * A new category at the end of [existing]. The caller checked [name] with [checkName]; it is
     * trimmed again here.
     *
     * @param id A fresh unique id.
     * @param now Creation time in epoch milliseconds.
     */
    fun create(
        name: String,
        mode: RecordingMode,
        existing: List<RecordingCategory>,
        id: String,
        now: Long
    ): RecordingCategory = RecordingCategory(
        id = id,
        name = name.trim(),
        mode = mode,
        sortOrder = nextSortOrder(existing),
        createdAt = now
    )
}
