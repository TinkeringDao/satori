package com.satori.ui.components

import com.satori.domain.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoSheetRulesTest {

    @Test
    fun `move to ideas is offered for a to-do still to do or to decide on`() {
        val offered = TodoStatus.entries.filter(::movesToIdeas)

        assertEquals(listOf(TodoStatus.SUGGESTED, TodoStatus.OPEN), offered)
    }
}
