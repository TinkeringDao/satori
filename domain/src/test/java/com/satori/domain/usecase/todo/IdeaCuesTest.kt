package com.satori.domain.usecase.todo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeaCuesTest {

    @Test
    fun `english sentences that put an idea forward`() {
        listOf(
            "There should be a way to sort the recordings by place.",
            "There needs to be a bigger stop button.",
            "There could be a widget for the cover screen.",
            "It this should be offered in the settings.",
            "So it's should be much louder.",
            "The vibration, which should be stronger.",
            "It would also be good to show the battery use.",
            "Would be nice to have a dark mode.",
            "You should be able to pin a recording.",
            "The list should have an option to group by day.",
            "We have an option to keep the audio forever.",
            "We could show the map on the cover.",
            "Maybe we could add the weather.",
            "What if the notes came as a list?",
            "How about a shortcut on the lock screen.",
            "My idea is a weekly summary.",
            "The idea is to tag people.",
            "One suggestion: a search over all notes.",
            "This is a feature request for exports.",
            "The app should start faster.",
            "The screen should stay on while recording.",
            "Satori should know my calendar."
        ).forEach { assertTrue(it, IdeaCues.has(it)) }
    }

    @Test
    fun `german sentences that put an idea forward`() {
        listOf(
            "Es sollte eine Liste der Orte geben.",
            "Es müsste schneller starten.",
            "Es wäre doch schön, wenn die Uhr größer wäre.",
            "Es wäre praktisch, die Notizen zu teilen.",
            "Wäre schön, wenn es einen Widget gäbe.",
            "Man könnte die Aufnahmen nach Tagen ordnen.",
            "Man sollte die Lautstärke einstellen können.",
            "Wir könnten eine Karte zeigen.",
            "Vielleicht könnte man die Fotos anhängen.",
            "Was wäre, wenn die Notizen kürzer wären?",
            "Eine Idee: ein Wochenrückblick.",
            "Mein Vorschlag ist ein dunkles Design.",
            "Einen eigenen Knopf dafür, das sollte es geben."
        ).forEach { assertTrue(it, IdeaCues.has(it)) }
    }

    @Test
    fun `the words before a cue make it part of an idea, unless they speak to someone`() {
        fun wishes(before: String, request: Boolean = true) = IdeaCues.wishes(before.split(' '), request)
        assertTrue(wishes("it would be nice if the app could"))
        assertTrue(wishes("there should be an option to"))
        assertTrue(wishes("the phone could"))
        assertTrue(wishes("it should"))
        assertTrue(wishes("it would be better if i", request = false))
        // Asked of someone, it is a request.
        assertFalse(wishes("it would be great if you could"))
        assertFalse(wishes("could you please"))
        assertFalse(wishes("you could"))
        // Without an idea phrase, a modal makes a wish only of a request to be reminded.
        assertFalse(wishes("i think i", request = false))
        assertFalse(wishes("it could", request = false))
    }

    @Test
    fun `plans, facts and an idea said in passing are none`() {
        listOf(
            "I'll call the bank tomorrow.",
            "I have no idea where the keys are.",
            "The meeting should start at nine.",
            "Ich muss morgen die Steuer machen.",
            "Wir waren gestern im Kino.",
            "Remind me to buy milk.",
            "The ideas were all good."
        ).forEach { assertFalse(it, IdeaCues.has(it)) }
    }
}
