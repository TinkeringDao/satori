package com.satori.ui.screens

import com.satori.domain.usecase.todo.TodoSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the field log says a tick on home came from: the list as it first shows, or past it after Show all. */
class HomeTodoSourceTest {

    @Test
    fun `the first five to-dos are home's, the ones Show all adds the full list's`() {
        assertEquals(List(5) { TodoSource.HOME }, (0..4).map(::todoSourceAt))
        assertEquals(List(3) { TodoSource.LIST }, (5..7).map(::todoSourceAt))
    }
}
