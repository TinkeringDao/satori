package com.satori.domain.usecase.stt

import com.satori.domain.model.AudioSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLinesTest {

    // The old transcript: three lines.
    private val places = mapOf(
        "o1" to AudioSpan(1_000, 4_000),
        "o2" to AudioSpan(5_000, 9_000),
        "o3" to AudioSpan(10_000, 12_000)
    )

    // The new one cuts the same speech in other places.
    private val newLines = listOf(
        "n1" to AudioSpan(900, 2_500),
        "n2" to AudioSpan(2_600, 6_000),
        "n3" to AudioSpan(6_100, 9_100),
        "n4" to AudioSpan(10_050, 11_900)
    )

    @Test
    fun `the new lines said in the time of the old ones are found, by their middles`() {
        // o1 and o2 ran from 1 s to 9 s; n1, n2 and n3 have their middles in it, n4 doesn't.
        assertEquals(listOf("n1", "n2", "n3"), SourceLines.find(listOf("o1", "o2"), places, newLines))
        assertEquals(listOf("n4"), SourceLines.find(listOf("o3"), places, newLines))
    }

    @Test
    fun `an old line shorter than the new one holding it finds that one`() {
        val short = mapOf("o" to AudioSpan(3_000, 3_400))

        assertEquals(listOf("n2"), SourceLines.find(listOf("o"), short, newLines))
    }

    @Test
    fun `lines that can't be placed, or a time nothing new was said in, keep the old sources`() {
        assertNull(SourceLines.find(listOf("unknown"), places, newLines))
        assertNull(SourceLines.find(emptyList(), places, newLines))
        assertNull(SourceLines.find(listOf("late"), mapOf("late" to AudioSpan(20_000, 21_000)), newLines))
    }
}
