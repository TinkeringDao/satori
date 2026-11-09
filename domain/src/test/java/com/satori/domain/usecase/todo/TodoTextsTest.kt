package com.satori.domain.usecase.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoTextsTest {

    @Test
    fun `the spoken lead-in and the due time leave an english todo`() {
        assertEquals("Call the bank", TodoTexts.clean("I need to call the bank tomorrow.", "tomorrow"))
        assertEquals("Call the bank", TodoTexts.clean("Tomorrow I need to call the bank", "Tomorrow"))
        assertEquals("Water the plants", TodoTexts.clean("Remind me to water the plants", null))
        assertEquals("Send the slides", TodoTexts.clean("I'll send the slides", null))
        assertEquals("Book the room", TodoTexts.clean("Please book the room!", null))
    }

    @Test
    fun `the spoken lead-in and the due time leave a german todo`() {
        assertEquals("Die Bank anrufen", TodoTexts.clean("Ich muss morgen die Bank anrufen", "morgen"))
        assertEquals("Blumen gießen", TodoTexts.clean("Nicht vergessen: Blumen gießen", null))
        assertEquals("Zahnarzt", TodoTexts.clean("Zahnarzt am Freitag um 9", "Freitag um 9"))
    }

    @Test
    fun `a german todo that starts with its time loses the verb and subject that follow it`() {
        assertEquals("Die Bank anrufen", TodoTexts.clean("Morgen muss ich die Bank anrufen", "Morgen"))
        assertEquals("Die Folien fertig machen", TodoTexts.clean("Am Freitag muss ich die Folien fertig machen", "Freitag"))
        assertEquals("Das Angebot rausschicken", TodoTexts.clean("Am Freitag müssen wir das Angebot rausschicken", "Am Freitag"))
        assertEquals("Das Angebot rausschicken", TodoTexts.clean("Nächste Woche müssen wir das Angebot rausschicken", "Nächste Woche"))
        assertEquals("Beim Arzt sein", TodoTexts.clean("Um 9 muss ich beim Arzt sein", "Um 9"))
        // Without an infinitive the words stay, in statement order rather than as a question.
        assertEquals("Ich muss zum Arzt", TodoTexts.clean("Um 9 muss ich zum Arzt", "Um 9"))
    }

    @Test
    fun `a lead-in stays when what follows it is no infinitive`() {
        assertEquals("Ich will ein Eis", TodoTexts.clean("Ich will ein Eis", null))
        assertEquals("Ich muss zum Arzt", TodoTexts.clean("Ich muss zum Arzt", null))
    }

    @Test
    fun `only the preposition of the time goes, not a word that belongs to the verb`() {
        assertEquals("Log in to the portal", TodoTexts.clean("Log in to the portal on Friday", "Friday"))
    }

    @Test
    fun `a time word in a possessive is part of the todo and stays`() {
        assertEquals("Prepare for Friday's meeting", TodoTexts.clean("Prepare for Friday's meeting", "Friday"))
        assertEquals("Prepare for Friday’s meeting", TodoTexts.clean("Prepare for Friday’s meeting", "Friday"))
        // The time parser reads the possessive itself as the time phrase.
        assertEquals("Prepare for Friday's meeting", TodoTexts.clean("Prepare for Friday's meeting", "Friday's"))
    }

    @Test
    fun `quotes, list marks and end punctuation are removed and the first letter is raised`() {
        assertEquals("Buy milk", TodoTexts.clean(" \"- buy milk.\" ", null))
    }

    @Test
    fun `a text with nothing left to do is rejected`() {
        assertNull(TodoTexts.clean("tomorrow", "tomorrow"))
        assertNull(TodoTexts.clean("  ...  ", null))
    }

    @Test
    fun `a long text is cut to fifteen words without a dangling joining word`() {
        val long = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen and sixteen seventeen"
        assertEquals(
            "One two three four five six seven eight nine ten eleven twelve thirteen fourteen",
            TodoTexts.clean(long, null)
        )
    }

    @Test
    fun `the same todo in other words is a duplicate`() {
        assertTrue(TodoTexts.isDuplicate("Call the bank", "call the Bank!"))
        assertTrue(TodoTexts.isDuplicate("Send Anna the slides", "Send the slides to Anna"))
        assertTrue(TodoTexts.isDuplicate("Bank anrufen", "Die Bank anrufen"))
        assertTrue(TodoTexts.isDuplicate("Ich muss noch die Bank anrufen", "Bank anrufen"))
        assertTrue(TodoTexts.isDuplicate("Pay the gas bill", "Pay the Gas Bill."))
        assertTrue("only filler words, but the same", TodoTexts.isDuplicate("Do it", "do it."))
    }

    @Test
    fun `a todo that adds something is not a duplicate`() {
        assertFalse(TodoTexts.isDuplicate("Buy milk", "Buy milk and eggs"))
        assertFalse(TodoTexts.isDuplicate("Pay the rent", "Pay the electricity bill and the rent"))
        assertFalse(TodoTexts.isDuplicate("Update the website", "Update the website and send the newsletter to all customers"))
        assertFalse(TodoTexts.isDuplicate("Call Anna", "Call Anna again"))
        assertFalse(TodoTexts.isDuplicate("Anna anrufen", "Anna noch mal anrufen"))
        assertFalse(TodoTexts.isDuplicate("Call Anna", "Don't call Anna"))
        // A time word left in a text was not taken as its due time, so it counts as a word.
        assertFalse(TodoTexts.isDuplicate("Call the bank tomorrow", "Call the bank"))
    }

    @Test
    fun `different todos are not duplicates`() {
        assertFalse(TodoTexts.isDuplicate("Call mom", "Call dad"))
        assertFalse(TodoTexts.isDuplicate("Book a flight to Berlin", "Book a hotel in Berlin"))
        assertFalse(TodoTexts.isDuplicate("Do it", "Do that"))
        assertFalse(TodoTexts.isDuplicate("Prepare slides for the Monday meeting", "Prepare slides for the Friday meeting"))
        assertFalse(TodoTexts.isDuplicate("Take the morning pills", "Take the evening pills"))
    }

    @Test
    fun `wegen ties a reason to a todo like about does`() {
        assertTrue(TodoTexts.isRepeat("Die Bank anrufen", "Bank wegen des Kredits anrufen"))
        // The reason itself still tells two todos apart.
        assertFalse(TodoTexts.isDuplicate("Bank wegen des Kredits anrufen", "Bank wegen der Karte anrufen"))
    }

    @Test
    fun `a todo said again in more words from one recording is a repeat`() {
        assertTrue(TodoTexts.isRepeat("Call the bank", "Call the bank about the loan"))
        assertTrue(TodoTexts.isRepeat("Call the bank about the loan", "Call the bank"))
        assertTrue(TodoTexts.isRepeat("Book the venue", "Book the venue for the party"))
        assertTrue(TodoTexts.isRepeat("Send Anna the slides", "Send the slides to Anna"))
        assertTrue(TodoTexts.isRepeat("Do it", "do it."))
    }

    @Test
    fun `a second thing to do, another day, again or not make another todo in one recording too`() {
        assertFalse(TodoTexts.isRepeat("Pay the rent", "Pay the electricity bill and the rent"))
        assertFalse(TodoTexts.isRepeat("Update the website", "Update the website and send the newsletter to all customers"))
        assertFalse(TodoTexts.isRepeat("Prepare slides for the Monday meeting", "Prepare slides for the Friday meeting"))
        assertFalse(TodoTexts.isRepeat("Call Anna", "Call Anna again"))
        assertFalse(TodoTexts.isRepeat("Anna anrufen", "Anna noch mal anrufen"))
        assertFalse(TodoTexts.isRepeat("Call Anna", "Do not call Anna"))
        assertFalse(TodoTexts.isRepeat("Bank anrufen", "Bank nicht anrufen"))
        assertFalse(TodoTexts.isRepeat("Call", "Call Anna"))
        assertFalse(TodoTexts.isRepeat("Call mom", "Call dad"))
    }

    @Test
    fun `another owner, or cancelling or moving it, makes another todo`() {
        assertFalse(TodoTexts.isRepeat("Call Anna", "Call Anna's mother"))
        assertFalse(TodoTexts.isRepeat("Call Anna", "Call Anna’s mother"))
        assertFalse(TodoTexts.isRepeat("Feed the cat", "Feed the neighbour's cat"))
        assertFalse(TodoTexts.isRepeat("Termin beim Zahnarzt", "Termin beim Zahnarzt absagen"))
        assertFalse(TodoTexts.isRepeat("Termin beim Zahnarzt", "Termin beim Zahnarzt verschieben"))
        assertFalse(TodoTexts.isRepeat("Dentist appointment", "Cancel the dentist appointment"))
        assertFalse(TodoTexts.isRepeat("Dentist appointment", "Move the dentist appointment"))
        assertFalse(TodoTexts.isRepeat("Team meeting on the budget", "Reschedule the team meeting on the budget"))
        assertFalse(TodoTexts.isRepeat("Team meeting on the budget", "Postpone the team meeting on the budget"))
        assertFalse(TodoTexts.isRepeat("Dentist appointment", "Dentist appointment cancelled"))
        assertFalse(TodoTexts.isRepeat("Termin beim Zahnarzt", "Termin beim Zahnarzt verlegen"))
        assertFalse(TodoTexts.isRepeat("Flug nach Berlin", "Flug nach Berlin stornieren"))
        // Said in both, the owner and the verb are the same todo.
        assertTrue(TodoTexts.isDuplicate("Call Anna's mother", "call Anna’s Mother"))
        assertTrue(TodoTexts.isRepeat("Call Anna's mother", "Call Anna's mother about the party"))
        assertTrue(TodoTexts.isRepeat("Cancel the dentist appointment", "Cancel the dentist appointment on Friday"))
        // The "'s" of "it's" or "let's" names no owner.
        assertTrue(TodoTexts.isRepeat("Charge the phone", "Charge the phone, it's empty"))
        assertTrue(TodoTexts.isRepeat("Book the flight", "Let's book the flight"))
    }

    @Test
    fun `a model's text with two sentences keeps its first, without the copula the cut left and its article`() {
        // The phone, 10 October 2026: a copy of two sentences, cut short.
        val mangled = TodoTexts.cleanAnswer("The dentist appointment is. I also need to buy a birthday present for Anna this", "on Friday at 3 pm")!!
        assertEquals("Dentist appointment", mangled.text)
        assertTrue(mangled.heldSentenceBreak)

        val two = TodoTexts.cleanAnswer("Call the bank about the loan! Then email Anna", null)!!
        assertEquals("Call the bank about the loan", two.text)
        assertTrue(two.heldSentenceBreak)
    }

    @Test
    fun `a copula the removal of the time words left at an end of a model's text goes with the article`() {
        val copied = TodoTexts.cleanAnswer("The dentist appointment is on Friday at 3 pm", "on Friday at 3 pm")!!
        assertEquals("Dentist appointment", copied.text)
        assertFalse(copied.heldSentenceBreak)
        assertEquals("Teammeeting", TodoTexts.cleanAnswer("Nächsten Freitag ist das Teammeeting um halb drei", "Nächsten Freitag", "um halb drei")!!.text)
        assertEquals("Zahnarzttermin", TodoTexts.cleanAnswer("Der Zahnarzttermin ist am Freitag", "Freitag")!!.text)
        assertEquals("Meetings with the client", TodoTexts.cleanAnswer("The meetings with the client are on Monday", "Monday")!!.text)
    }

    @Test
    fun `a copula the model said itself or that ends a question stays, and a text without one keeps its article`() {
        assertEquals("Check when the meeting is", TodoTexts.cleanAnswer("Check when the meeting is on Friday", "on Friday")!!.text)
        assertEquals("Herausfinden, wo der Schlüssel ist", TodoTexts.cleanAnswer("Herausfinden, wo der Schlüssel ist", null)!!.text)
        assertEquals("The plan is", TodoTexts.cleanAnswer("The plan is", null)!!.text)
        assertEquals("Ist das Meeting", TodoTexts.cleanAnswer("Ist das Meeting", null)!!.text)
        assertEquals("Die Steuererklärung abschicken", TodoTexts.cleanAnswer("Ich muss morgen die Steuererklärung abschicken", "morgen")!!.text)
    }

    @Test
    fun `a dot in an abbreviation, a date or a number breaks no sentence of a model's text`() {
        for (text in listOf("Call Dr. Smith about the results", "Termin am 16. Oktober absagen", "Bring z. B. Milch mit", "Pay 9.50 for the parking")) {
            val answer = TodoTexts.cleanAnswer(text, null)!!
            assertEquals(text, answer.text)
            assertFalse(text, answer.heldSentenceBreak)
        }
    }

    @Test
    fun `the words of one text all said in the other are within it, however few they are`() {
        assertTrue(TodoTexts.isWithin(TodoTexts.key("Dentist"), TodoTexts.key("Dentist appointment")))
        assertFalse("a repeat needs a share of the words", TodoTexts.isRepeat("Dentist", "Dentist appointment"))
        assertTrue(TodoTexts.isWithin(TodoTexts.key("Call the bank"), TodoTexts.key("Call the bank about the loan and the card")))
        assertFalse(TodoTexts.isWithin(TodoTexts.key("Dentist appointment"), TodoTexts.key("Cancel the dentist appointment")))
        assertFalse(TodoTexts.isWithin(TodoTexts.key("Call Anna"), TodoTexts.key("Call Anna's mother")))
        assertFalse(TodoTexts.isWithin(TodoTexts.key("Call Anna"), TodoTexts.key("Email Anna")))
    }

    @Test
    fun `a text names a time phrase only as it was said`() {
        assertTrue(TodoTexts.names("Dentist at 3", "at 3"))
        assertTrue(TodoTexts.names("Zahnarzt um 15 Uhr", "um 15 Uhr"))
        assertTrue(TodoTexts.names("Arzt um 9 Uhr", "um 9"))
        assertTrue(TodoTexts.names("dentist AT 3 pm", "at 3"))
        assertTrue(TodoTexts.names("Dentist at 3pm", "at 3"))
        assertFalse(TodoTexts.names("Dentist", "at 3"))
        assertFalse(TodoTexts.names("Dentist at 3:30", "at 3"))
        assertFalse(TodoTexts.names("Zimmer 9 putzen", "um 9"))
        assertFalse(TodoTexts.names("Call at 30 past", "at 3"))
        assertFalse(TodoTexts.names("Anything", " "))
    }

    @Test
    fun `a todo speaks of the sentences that share a content word of four letters with it`() {
        val segment = "Die Heizung wird bis Ende des Monats repariert. Ich muss morgen beim Arzt anrufen. Bis dann."
        assertEquals(listOf("Die Heizung wird bis Ende des Monats repariert."), TodoTexts.sentencesAbout("Heizung reparieren lassen", segment))
        // "beim" is a function word and ties nothing; "Arzt" does.
        assertEquals(listOf("Ich muss morgen beim Arzt anrufen."), TodoTexts.sentencesAbout("Beim Arzt anrufen", segment))
        // The first five letters are compared: "options" meets "option", "deleting" meets "delete".
        assertEquals(
            listOf("There should be an option to delete the audio."),
            TodoTexts.sentencesAbout("Offer options for deleting", "Nice weather. There should be an option to delete the audio.")
        )
        assertEquals(emptyList<String>(), TodoTexts.sentencesAbout("Water the plants", segment))
        // Words of three letters or fewer tie nothing.
        assertEquals(emptyList<String>(), TodoTexts.sentencesAbout("Pay tax", "I pay the tax."))
        // Nor do function words of five: "about" is in both, and only "bank" counts.
        assertEquals(
            listOf("The bank is closed."),
            TodoTexts.sentencesAbout("Call the bank about the loan", "I talked about the weather. The bank is closed.")
        )
    }

    @Test
    fun `a hedge is a word of its own, in either language`() {
        assertTrue(TodoTexts.isHedged("I'll send it, maybe on Friday."))
        assertTrue(TodoTexts.isHedged("Probably Monday."))
        assertTrue(TodoTexts.isHedged("Vielleicht am Montag."))
        assertTrue(TodoTexts.isHedged("Das ist möglicherweise zu spät."))
        assertFalse(TodoTexts.isHedged("I'll send it on Friday."))
        assertFalse(TodoTexts.isHedged("The maybelline order."))
    }

    @Test
    fun `hesitations go wherever they are and however often, with the commas around them`() {
        // Of the shape of found texts of 2026-10-14, paraphrased.
        assertEquals(
            "Weigh pros and cons of the short plan and note them under each option",
            TodoTexts.clean("Weigh pros and cons of uh uh the short plan and note them under each option")
        )
        assertEquals("Listen to the tape yourself and see what you can fix", TodoTexts.clean("Listen to the tape yourself and see um what you can fix"))
        assertEquals("Call the bank", TodoTexts.clean("I need to, uh, call the bank"))
        assertEquals("Call the bank", TodoTexts.clean("Uh I need to call the bank"))
        assertEquals("Option to delete old recordings", TodoTexts.clean("Option to, umm, delete old recordings"))
        assertEquals("Email the landlord", TodoTexts.clean("Email er the landlord"))
        assertEquals("Die Bank anrufen", TodoTexts.clean("Ähm, die Bank, äh, anrufen"))
        assertEquals("Slides for the review", TodoTexts.clean("Hmm hmm slides for the review"))
    }

    @Test
    fun `German um and er are words, and stay in a German text`() {
        assertEquals("Früh aufstehen, um den Zug zu kriegen", TodoTexts.clean("Früh aufstehen, um den Zug zu kriegen"))
        assertEquals("Er soll Peter anrufen", TodoTexts.clean("Er soll Peter anrufen"))
        // A clock time's "um" is the time's, which goes as the time does.
        assertEquals("Peter anrufen", TodoTexts.clean("Peter um 9 anrufen", "um 9"))
        // Also with no word that only German has: only an English text loses them.
        assertEquals("Er kommt Montag vorbei", TodoTexts.clean("Er kommt Montag vorbei"))
        assertEquals("Anruf um Mitternacht", TodoTexts.clean("Anruf um Mitternacht"))
    }

    @Test
    fun `a word in capitals is no hesitation`() {
        assertEquals("Take mom to the ER", TodoTexts.clean("Take mom to the ER"))
        assertEquals("Call the ER about the test results", TodoTexts.clean("Call the ER about the test results"))
        assertEquals("Return the jacket to HM", TodoTexts.clean("Return the jacket to HM"))
        assertEquals("Ask UM about the grant", TodoTexts.clean("Ask UM about the grant"))
    }

    @Test
    fun `the full stop after a hesitation stays where it ends a sentence`() {
        assertEquals("Buy milk. Then call Anna", TodoTexts.clean("Buy milk, hmm. Then call Anna"))
        assertEquals("Buy milk. Then call Anna", TodoTexts.clean("Buy milk. Hmm. Then call Anna"))
        assertEquals("Call the bank", TodoTexts.clean("Hmm. Call the bank"))
        assertEquals("Call the bank", TodoTexts.clean("Call the bank, uh."))
        assertEquals("Call the plumber", TodoTexts.clean("Call the, uh... plumber"))
    }

    @Test
    fun `a small word a stammer says twice is said once, a word said twice on purpose stays`() {
        assertEquals("Book the hotel for the trip", TodoTexts.clean("Book the the hotel for the trip"))
        assertEquals("Call the bank", TodoTexts.clean("I I need to call the bank"))
        assertEquals("Die Wäsche aufhängen", TodoTexts.clean("Die die Wäsche aufhängen"))
        assertEquals("The plumber about the leak", TodoTexts.clean("The the plumber about the leak"))
        assertEquals("Very very user friendly settings", TodoTexts.clean("Very very user friendly settings"))
        // Neither a word inside another nor the end of a time.
        assertEquals("Thank the theater", TodoTexts.clean("Thank the theater"))
    }

    @Test
    fun `a word grammar says twice in a row stays`() {
        assertEquals("Die Kollegen anrufen, die die Pakete bringen", TodoTexts.clean("Die Kollegen anrufen, die die Pakete bringen"))
        assertEquals("Fragen, ob Sie sie abholen können", TodoTexts.clean("Fragen, ob Sie sie abholen können"))
        assertEquals("Fragen, ob sie sie abholen", TodoTexts.clean("Fragen, ob sie sie abholen"))
        assertEquals("Log in in the morning", TodoTexts.clean("Log in in the morning"))
        assertEquals("Turn the heating on on Monday", TodoTexts.clean("Turn the heating on on Monday"))
        // Said three times it is a stammer again.
        assertEquals("Es reparieren", TodoTexts.clean("Ich muss es es es reparieren"))
    }

    @Test
    fun `nothing that looks like a hesitation inside a word goes`() {
        assertEquals("Wind the Uhr up", TodoTexts.clean("Wind the Uhr up"))
        assertEquals("Buy an umbrella and an ermine coat", TodoTexts.clean("Buy an umbrella and an ermine coat"))
        assertEquals("Call 'em back", TodoTexts.clean("Call 'em back"))
    }
}
