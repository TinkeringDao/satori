package com.satori.domain.category

import com.satori.domain.category.CategoryRules.NameProblem
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRulesTest {

    private fun category(sortOrder: Int, name: String = "Category $sortOrder") = RecordingCategory(
        id = "id-$sortOrder",
        name = name,
        mode = RecordingMode.SIMPLE_LISTENING,
        sortOrder = sortOrder,
        createdAt = 0L
    )

    // Names

    @Test
    fun `a plain name is valid and kept as typed`() {
        val check = CategoryRules.checkName("Standup", otherNames = listOf("Voice note"))

        assertTrue(check.isValid)
        assertNull(check.problem)
        assertEquals("Standup", check.name)
    }

    @Test
    fun `the spaces around a name are dropped`() {
        val check = CategoryRules.checkName("   Standup \n", otherNames = emptyList())

        assertTrue(check.isValid)
        assertEquals("Standup", check.name)
    }

    @Test
    fun `an empty name is refused`() {
        assertEquals(NameProblem.EMPTY, CategoryRules.checkName("", emptyList()).problem)
    }

    @Test
    fun `a name of spaces only is refused as empty`() {
        val check = CategoryRules.checkName(" \t  ", emptyList())

        assertEquals(NameProblem.EMPTY, check.problem)
        assertEquals("", check.name)
    }

    @Test
    fun `24 characters are fine and 25 are too long`() {
        assertTrue(CategoryRules.checkName("a".repeat(24), emptyList()).isValid)
        assertEquals(NameProblem.TOO_LONG, CategoryRules.checkName("a".repeat(25), emptyList()).problem)
    }

    @Test
    fun `the length is counted after trimming`() {
        assertTrue(CategoryRules.checkName("  " + "a".repeat(24) + "  ", emptyList()).isValid)
    }

    @Test
    fun `an emoji counts as one character`() {
        // U+1F3A4 takes two UTF-16 units but is one character to the person typing it.
        val name = "🎤".repeat(24)

        assertTrue(CategoryRules.checkName(name, emptyList()).isValid)
        assertEquals(NameProblem.TOO_LONG, CategoryRules.checkName(name + "a", emptyList()).problem)
    }

    @Test
    fun `a name another category has is a duplicate in any letter case`() {
        val others = listOf("Meeting", "Idea")

        assertEquals(NameProblem.DUPLICATE, CategoryRules.checkName("Meeting", others).problem)
        assertEquals(NameProblem.DUPLICATE, CategoryRules.checkName("meeting", others).problem)
        assertEquals(NameProblem.DUPLICATE, CategoryRules.checkName("IDEA", others).problem)
    }

    @Test
    fun `a duplicate is found through the spaces around either name`() {
        assertEquals(
            NameProblem.DUPLICATE,
            CategoryRules.checkName("  idea ", listOf(" Idea  ")).problem
        )
    }

    @Test
    fun `umlauts match across letter case`() {
        assertEquals(NameProblem.DUPLICATE, CategoryRules.checkName("äpfel", listOf("Äpfel")).problem)
    }

    @Test
    fun `a similar name is not a duplicate`() {
        assertTrue(CategoryRules.checkName("Meeting 2", listOf("Meeting")).isValid)
    }

    @Test
    fun `keeping the name of the category being edited is fine`() {
        // The caller leaves the edited category out of otherNames.
        val others = listOf("Idea")

        assertTrue(CategoryRules.checkName("Meeting", others).isValid)
    }

    @Test
    fun `a problem is reported with the name that was checked`() {
        val check = CategoryRules.checkName(" Meeting ", listOf("meeting"))

        assertFalse(check.isValid)
        assertEquals("Meeting", check.name)
    }

    // The chosen category

    @Test
    fun `the chosen category is the one with that id`() {
        val categories = listOf(category(0), category(1), category(2))

        assertEquals(categories[1], CategoryRules.chosen(categories, "id-1"))
    }

    @Test
    fun `a category that was deleted falls back to the first one`() {
        val categories = listOf(category(0), category(1))

        assertEquals(categories[0], CategoryRules.chosen(categories, "gone"))
        assertEquals(categories[0], CategoryRules.chosen(categories, null))
    }

    @Test
    fun `no categories give no choice`() {
        assertNull(CategoryRules.chosen(emptyList(), "id-0"))
    }

    // New categories

    @Test
    fun `a new category goes after the last one`() {
        val existing = listOf(category(0), category(1), category(2))

        assertEquals(3, CategoryRules.nextSortOrder(existing))
    }

    @Test
    fun `the position follows the highest one even with gaps or out of order`() {
        val existing = listOf(category(5), category(1), category(3))

        assertEquals(6, CategoryRules.nextSortOrder(existing))
    }

    @Test
    fun `the first category starts at zero`() {
        assertEquals(0, CategoryRules.nextSortOrder(emptyList()))
    }

    @Test
    fun `create fills every field`() {
        val created = CategoryRules.create(
            name = "  Lecture  ",
            mode = RecordingMode.LONG_MEETING,
            existing = listOf(category(0), category(1)),
            id = "new-id",
            now = 1_700_000_000_000L
        )

        assertEquals(
            RecordingCategory(
                id = "new-id",
                name = "Lecture",
                mode = RecordingMode.LONG_MEETING,
                sortOrder = 2,
                createdAt = 1_700_000_000_000L
            ),
            created
        )
    }
}
