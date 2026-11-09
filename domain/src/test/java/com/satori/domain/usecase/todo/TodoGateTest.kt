package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoKind
import com.satori.domain.usecase.todo.TodoGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cases shaped like the first real voice notes on the phone (2026-10-11), in other words, with
 * their expected fate, and one per rule of the gate, in their order, in sentences of the shapes the
 * owner says them in.
 */
class TodoGateTest {

    private fun todo(text: String, kind: TodoKind, timed: Boolean) =
        Todo(id = text, sessionId = "s", text = text, kind = kind, dueHasTime = timed, createdAt = 0, updatedAt = 0)

    private fun verdict(text: String, source: String, kind: TodoKind = TodoKind.TASK, timed: Boolean = false) =
        TodoGate.verdict(todo(text, kind, timed), source)

    @Test
    fun `a diary entry copied sentence by sentence gives no to-dos`() {
        val lines = listOf(
            "What did I feel? I felt a deep calm and gladness by watching how" to "What did I feel today? I felt a deep calm and gladness by watching how glad my friend was.",
            "Um after walking for miles and miles" to "Um after walking for miles and miles.",
            "Uh without stopping once" to "And uh without stopping once.",
            "It had been" to "It had been",
            "A wonderful trip" to "A wonderful trip.",
            "That I was on" to "That I was on.",
            "Yeah" to "Yeah."
        )
        for ((text, source) in lines) assertEquals(text, Verdict.DROP, verdict(text, source))
    }

    @Test
    fun `feedback about the app read back as a sentence is no to-do, and in the model's words an idea`() {
        assertEquals(
            Verdict.DROP,
            verdict(
                "For the AI to write its notes. Uh we should add a buzz when",
                "For the AI to write its notes. Uh we should add a buzz when"
            )
        )
        // Read back word for word, idea phrase and all, it is the sentence; named as the change, an idea.
        val screen = "or switch it back. It this should be offered in the settings for people to change."
        assertEquals(Verdict.DROP, verdict("Or switch it back. It this should be offered in the settings for people to change", screen))
        assertEquals(Verdict.IDEA, verdict("Offer the screen setting in the settings", screen))
    }

    // --- The rules in their order ------------------------------------------------------------

    @Test
    fun `rule 1 - a question, filler or fragment goes, also in german and also as an idea`() {
        assertEquals(Verdict.DROP, verdict("Soll ich die Bank anrufen?", "Soll ich die Bank anrufen?"))
        assertEquals(Verdict.DROP, verdict("Ähm also", "Ähm also, das war es."))
        assertEquals(Verdict.DROP, verdict("Den Bericht für die", "Ich muss den Bericht für die Chefin schicken."))
        assertEquals(
            Verdict.DROP,
            TodoGate.verdict("An option to", appointment = false, timed = false, source = "There should be an option to.", idea = true)
        )
    }

    @Test
    fun `rule 2 - a strong cue keeps a to-do open and says its capture, before an idea cue and the model's idea`() {
        val remind = "Remind me to add the export to the notes page."
        assertEquals(Verdict.OPEN, verdict("Add the export to the notes page", remind))
        assertEquals(TodoCapture.EXPLICIT, TodoGate.cueCapture("Add the export to the notes page", remind))

        val need = "I need to add an option to export the notes."
        assertEquals(Verdict.OPEN, TodoGate.verdict("Add an option to export the notes", false, false, need, idea = true))
        assertEquals(TodoCapture.COMMITMENT, TodoGate.cueCapture("Add an option to export the notes", need))

        val nicht = "Nicht vergessen, morgen die Steuerunterlagen abzuholen."
        assertEquals(Verdict.OPEN, verdict("Die Steuerunterlagen abholen", nicht))
        assertEquals(TodoCapture.EXPLICIT, TodoGate.cueCapture("Die Steuerunterlagen abholen", nicht))

        val muss = "Es wäre gut, aber ich muss erst die Rechnung prüfen."
        assertEquals(Verdict.OPEN, verdict("Die Rechnung prüfen", muss))
        assertEquals(TodoCapture.COMMITMENT, TodoGate.cueCapture("Die Rechnung prüfen", muss))

        // A cue of another sentence of the segment is not this one's.
        assertEquals(null, TodoGate.cueCapture("Heizung reparieren lassen", "Die Heizung wird repariert. Ich muss zum Arzt."))

        // Asked for in a question, or politely, it still asks for a reminder.
        assertEquals(TodoCapture.EXPLICIT, TodoGate.cueCapture("Call the bank", "Can you remind me to call the bank tomorrow?"))
        assertEquals(TodoCapture.EXPLICIT, TodoGate.cueCapture("Call the bank", "It would be great if you could remind me to call the bank."))
    }

    @Test
    fun `rule 2 - only the speaker's cue counts, not the model's words, and a condition keeps the model's capture`() {
        // The model wrote the cue; the speaker said none.
        val keys = "The keys are on the hall table."
        assertEquals(null, TodoGate.cueCapture("Don't forget the keys", keys))
        assertEquals(Verdict.OPEN, verdict("Don't forget the keys", keys))

        val rain = "If it rains, I need to close the windows."
        assertEquals(null, TodoGate.cueCapture("Close the windows", rain))
        assertEquals(Verdict.OPEN, verdict("Close the windows", rain))
        assertEquals(null, TodoGate.cueCapture("Kündigen", "Wenn ich im Lotto gewinne, muss ich kündigen."))
    }

    @Test
    fun `rule 2 - a strong cue asked, denied, doubted, done, said as talk or inside an idea is no cue at all`() {
        val noCue = listOf(
            "Die Bank anrufen" to "Ich muss morgen nicht die Bank anrufen.",
            "Keine Rechnung zahlen" to "Ich muss diesen Monat keine Rechnung zahlen.",
            "Call the bank about the loan" to "Do I need to call the bank about the loan?",
            "Hotel am See wieder buchen" to "Ich muss sagen, das Hotel am See war wunderbar.",
            "Book the hotel by the lake again" to "I have to say the hotel by the lake was lovely.",
            "Call the plumber" to "I don't think I need to call the plumber.",
            "Pay the invoice" to "I must not pay the invoice before it is checked.",
            "Rechnung erledigen" to "Die Rechnung muss ich bereits erledigt haben.",
            "Drink water every hour" to "It would be nice if the app could remind me to drink water every hour.",
            "Remind me before meetings" to "The phone could remind me before meetings.",
            "Call the landlord" to "Please don't remind me to call the landlord."
        )
        for ((text, source) in noCue) {
            assertEquals(text, null, TodoGate.cueCapture(text, source))
            assertEquals(text, false, TodoGate.hasFirstPersonCue(source))
        }
        // The later rules decide: not a copy, a suggestion; a copy, gone; inside an idea, an idea.
        assertEquals(Verdict.SUGGEST, verdict("Die Bank anrufen", "Ich muss morgen nicht die Bank anrufen."))
        assertEquals(Verdict.SUGGEST, verdict("Book the hotel by the lake again", "I have to say the hotel by the lake was lovely."))
        assertEquals(Verdict.SUGGEST, verdict("Hotel am See wieder buchen", "Ich muss sagen, das Hotel am See war wunderbar."))
        assertEquals(Verdict.DROP, verdict("Call the bank about the loan", "Do I need to call the bank about the loan?"))
        assertEquals(
            Verdict.DROP,
            verdict("I have to say the walk along the river was lovely", "I have to say the walk along the river was lovely.")
        )
        assertEquals(
            Verdict.IDEA,
            verdict("Drink water every hour", "It would be nice if the app could remind me to drink water every hour.")
        )
    }

    @Test
    fun `rule 3 - an idea cue without a first-person cue makes an idea`() {
        assertEquals(
            Verdict.IDEA,
            verdict("Option to remove older audio files", "There should be an option to remove older audio files, not the notes.")
        )
        // The cue is in its own sentence of the segment: "it's should be" as the recognizer wrote it.
        assertEquals(
            Verdict.IDEA,
            verdict("Make the start buzz stronger", "The buzz at the start is too weak. So it's should be much stronger.")
        )
        assertEquals(
            Verdict.IDEA,
            verdict("Die Uhr größer machen", "Es wäre schön, wenn die Uhr auf dem Außendisplay größer wäre.")
        )
        assertEquals(Verdict.IDEA, verdict("Ordner für Aufnahmen anlegen", "Man könnte Ordner für die Aufnahmen anlegen."))
        // With the speaker saying they will do it, it stays a to-do.
        assertEquals(Verdict.OPEN, verdict("Add an option to export the notes", "I'll add an option to export the notes."))
        assertEquals(
            Verdict.OPEN,
            verdict("Eine Exportfunktion einbauen", "Ich werde eine Exportfunktion einbauen, es wäre praktisch.")
        )
    }

    @Test
    fun `rule 3 - a to-do with a clock time or an appointment keeps its time, whatever its sentence adds`() {
        // An idea has no time: as one these would lose their time and their reminder.
        assertEquals(
            Verdict.OPEN,
            TodoGate.verdict("Plumber visit", appointment = true, timed = true, source = "The plumber comes tomorrow at 8, it should be fixed then.")
        )
        assertEquals(
            Verdict.OPEN,
            TodoGate.verdict("Handwerker", appointment = true, timed = true, source = "Der Handwerker kommt morgen um 8, es sollte dann repariert sein.")
        )
        assertEquals(
            Verdict.SUGGEST,
            TodoGate.verdict("Pick up the parcel", appointment = false, timed = true, source = "I'm picking up the parcel at 5 today, that should be enough time.")
        )
        assertEquals(
            Verdict.SUGGEST,
            TodoGate.verdict("Handwerker", appointment = true, timed = false, source = "Der Handwerker kommt morgen, es sollte dann repariert sein.")
        )
        // Without a time it is an idea.
        assertEquals(
            Verdict.IDEA,
            TodoGate.verdict("Backup every Friday", appointment = false, timed = false, source = "There should be a backup every Friday.")
        )
    }

    @Test
    fun `rule 3 - a sentence given back as a task, idea phrase and all, is no idea`() {
        // Diary lines with a wish in them: the model copied them as tasks.
        val lake = "Yesterday we walked by the lake and it would be nice to go hiking again."
        assertEquals(Verdict.DROP, verdict("Yesterday we walked by the lake and it would be nice to go hiking again", lake))
        assertEquals(Verdict.DROP, verdict("We could visit the old town again some time", "We could visit the old town again some time."))
        assertEquals(Verdict.DROP, verdict("Wir könnten im Sommer wieder an den See fahren", "Wir könnten im Sommer wieder an den See fahren."))
        // Said as the change it names, or called an idea by the model, it is one.
        assertEquals(Verdict.IDEA, verdict("Go hiking again", lake))
        assertEquals(
            Verdict.IDEA,
            TodoGate.verdict("Yesterday we walked by the lake and it would be nice to go hiking again", false, false, lake, idea = true)
        )
        assertEquals(
            Verdict.IDEA,
            verdict("Option to delete old recordings", "There should be an option to delete old recordings.")
        )
    }

    @Test
    fun `rule 4 - the model calling it an idea makes an idea, also of a copy of its sentence`() {
        val clock = "Maybe make the clock on the cover bigger."
        assertEquals(Verdict.IDEA, TodoGate.verdict("Bigger clock on the cover", false, false, clock, idea = true))
        // Without the model's word it is an uncued copy.
        assertEquals(Verdict.DROP, TodoGate.verdict("Bigger clock on the cover", false, false, clock))
        assertEquals(
            Verdict.IDEA,
            TodoGate.verdict(
                "Größere Uhr auf dem Außendisplay", false, false, "Vielleicht die Uhr auf dem Außendisplay größer machen.", idea = true
            )
        )
        // A weak cue is no first-person cue strong enough to make it a to-do: the model's idea stays.
        assertEquals(
            Verdict.IDEA,
            TodoGate.verdict("Add an option to export the notes", false, false, "I'll add an option to export the notes.", idea = true)
        )
    }

    @Test
    fun `rule 5 - a weak cue keeps the to-do as the model found it`() {
        val train = "I'll book the train tickets on Monday."
        assertEquals(Verdict.OPEN, verdict("Book the train tickets", train))
        assertEquals(null, TodoGate.cueCapture("Book the train tickets", train))

        val keller = "Ich werde morgen den Keller aufräumen."
        assertEquals(Verdict.OPEN, verdict("Den Keller aufräumen", keller))
        assertEquals(null, TodoGate.cueCapture("Den Keller aufräumen", keller))

        // A weak cue said as talk, or denied, is none.
        assertEquals(Verdict.SUGGEST, verdict("Thank everyone for coming", "I want to thank you all for coming."))
        assertEquals(Verdict.SUGGEST, verdict("Call the bank", "I will not call the bank this week."))
    }

    @Test
    fun `rule 6 and 7 - an uncued copy goes and the rest is suggested, in german too`() {
        assertEquals(Verdict.DROP, verdict("Wir sind am Abend am See gewesen", "Wir sind am Abend am See gewesen."))
        assertEquals(Verdict.SUGGEST, verdict("Neue Reifen kaufen", "Die Reifen sind schon ziemlich abgefahren, sagt die Werkstatt."))
    }

    @Test
    fun `rule 7 - a bare noun never opens a to-do, whatever the model's capture, but keeps a copy from going`() {
        // It speaks of a to-do and is none.
        val meta = "I noticed one of the to do to pack the bags was not picked up."
        assertEquals(Verdict.SUGGEST, verdict("Pack the bags", meta))
        assertEquals(null, TodoGate.cueCapture("Pack the bags", meta))
        assertEquals(Verdict.SUGGEST, verdict("One of the to do to pack the bags was not picked up", meta))
        assertEquals(Verdict.SUGGEST, verdict("Formular ausfüllen", "Eine Aufgabe wäre das Formular für die Versicherung."))
        assertEquals(Verdict.SUGGEST, verdict("Steuererklärung machen", "Die nächste Aufgabe ist die Steuererklärung."))
    }

    @Test
    fun `a first-person cue is a strong or a weak one, not a bare noun nor a plan of others`() {
        assertEquals(true, TodoGate.hasFirstPersonCue("Then I'll call the bank."))
        assertEquals(true, TodoGate.hasFirstPersonCue("Don't forget the keys."))
        assertEquals(true, TodoGate.hasFirstPersonCue("Ich möchte noch die Garage streichen."))
        assertEquals(true, TodoGate.hasFirstPersonCue("Morgen muss ich zum Amt."))
        assertEquals(false, TodoGate.hasFirstPersonCue("A lot of to dos were missed."))
        assertEquals(false, TodoGate.hasFirstPersonCue("Die nächste Aufgabe wartet."))
        assertEquals(false, TodoGate.hasFirstPersonCue("We need to make sure the recording is clean."))
        assertEquals(false, TodoGate.hasFirstPersonCue("He'll call the bank."))
        // Only one said as a plan or a request, a condition or not.
        assertEquals(true, TodoGate.hasFirstPersonCue("If it rains, I need to close the windows."))
        assertEquals(false, TodoGate.hasFirstPersonCue("Do I need to bring anything?"))
        assertEquals(false, TodoGate.hasFirstPersonCue("I have to say, the buzz is too weak."))
        assertEquals(false, TodoGate.hasFirstPersonCue("I'll be honest, the buzz is too weak."))
        assertEquals(false, TodoGate.hasFirstPersonCue("Ich muss sagen, der Ton ist zu leise."))
        assertEquals(false, TodoGate.hasFirstPersonCue("Ich muss heute nicht ins Büro."))
        assertEquals(false, TodoGate.hasFirstPersonCue("I don't forget my keys."))
    }

    @Test
    fun `explicit and first-person to-dos stay open`() {
        assertEquals(Verdict.OPEN, verdict("Call the bank about the loan", "Remind me to call the bank tomorrow at ten about the loan."))
        assertEquals(Verdict.OPEN, verdict("Buy a birthday present for Anna", "I also need to buy a birthday present for Anna this weekend."))
        assertEquals(Verdict.OPEN, verdict("Beim Arzt anrufen", "Ich muss morgen um neun beim Arzt anrufen."))
        assertEquals(Verdict.OPEN, verdict("Anna die Fotos schicken", "Außerdem soll ich Anna bis Sonntag die Fotos vom Urlaub schicken."))
    }

    @Test
    fun `an appointment with a clock time stays open without a cue`() {
        assertEquals(Verdict.OPEN, verdict("Dentist appointment", "The dentist appointment is on Friday at 3 pm.", TodoKind.APPOINTMENT, timed = true))
    }

    @Test
    fun `a plausible to-do without a cue is only suggested`() {
        assertEquals(Verdict.SUGGEST, verdict("Book the follow-up review", "We should book the follow-up review with the whole team soon."))
    }

    @Test
    fun `fragments, fillers and questions go`() {
        assertEquals(Verdict.DROP, verdict("Buy a present for Anna this", "I need to buy a present for Anna this weekend."))
        assertEquals(Verdict.DROP, verdict("Call", "Remind me to call."))
        assertEquals(Verdict.DROP, verdict("Should I call the bank?", "Should I call the bank?"))
        assertEquals(Verdict.DROP, verdict("Okay so", "Okay so, that was it."))
    }

    @Test
    fun `an appointment may be one word, and with a clock time it needs no cue`() {
        assertEquals(Verdict.OPEN, TodoGate.verdict("Teammeeting", appointment = true, timed = true, source = "Am Freitag ist das Teammeeting um 10."))
        assertEquals(Verdict.SUGGEST, TodoGate.verdict("Dentist", appointment = true, timed = false, source = "Tomorrow I have the dentist."))
        assertEquals(Verdict.DROP, TodoGate.verdict("Teammeeting", appointment = false, timed = false, source = "Am Freitag ist das Teammeeting."))
    }

    @Test
    fun `a to-do dictated as a note is said with its cue, even word for word`() {
        assertEquals(Verdict.OPEN, verdict("Pay the rent", "Pay the rent."))
        assertEquals(Verdict.OPEN, verdict("Call the plumber", "Call the plumber on Friday."))
        assertEquals(Verdict.OPEN, verdict("Call the bank about the loan", "Right, call the bank about the loan tomorrow."))
        assertEquals(Verdict.OPEN, verdict("Water the plants", "And water the plants."))
        assertEquals(Verdict.OPEN, verdict("Anna noch mal anrufen", "Am Freitag Anna noch mal anrufen."))
        assertEquals(Verdict.OPEN, verdict("Die Miete überweisen", "Bitte die Miete überweisen."))
    }

    @Test
    fun `a cue in another sentence of the segment doesn't make a to-do said`() {
        // A German note on the phone: the landlord's repair became an open to-do through "Ich muss".
        val segment = "Die Heizung wird bis Ende des Monats repariert, und die Miete bleibt bis März gleich. " +
            "Ich muss morgen um 9 beim Arzt anrufen. Außerdem soll ich Anna bis Sonntag die Fotos schicken."
        assertEquals(Verdict.SUGGEST, verdict("Heizung reparieren lassen", segment))
        assertEquals(Verdict.SUGGEST, verdict("Miete bis März gleich bleiben", segment))
        assertEquals(Verdict.OPEN, verdict("Beim Arzt anrufen", segment))
        assertEquals(Verdict.OPEN, verdict("Fotos an Anna schicken", segment))
    }

    @Test
    fun `a sentence that only starts or ends like a note is none`() {
        assertEquals(false, TodoGate.saysToDo("Look, it had been an amazing journey."))
        assertEquals(false, TodoGate.saysToDo("Call?"))
        assertEquals(false, TodoGate.saysToDo("Should we call the bank?"))
        assertEquals(false, TodoGate.saysToDo("Wir sind den ganzen Tag gewandert und haben gegessen."))
        assertEquals(false, TodoGate.saysToDo("Bis morgen."))
        assertEquals(false, TodoGate.saysToDo("Im Garten."))
        assertEquals(true, TodoGate.saysToDo("It was a long day. Then book the hotel for Friday."))
    }

    @Test
    fun `a heading of a list said aloud is no to-do, a to-do after an ordinal is`() {
        assertEquals(true, TodoGate.isHeadingOrGreeting("Second, the budget"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Finally, the customer feedback"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Zweitens das Budget"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("First, call the bank"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Zweitens den Bericht schicken"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Review the second draft"))
        // Set off, or before an article or one word alone.
        assertEquals(true, TodoGate.isHeadingOrGreeting("Second the budget"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Third, hiring"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Third hiring"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Erstens: der Umzug ins neue Büro"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Finally - the customer feedback"))
    }

    @Test
    fun `an item whose first word is an ordinal of its own is no heading`() {
        assertEquals(false, TodoGate.isHeadingOrGreeting("Second meeting with Anna"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Second dentist appointment"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("First aid kit for the trip"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Third quarter report for Tom"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Second hand bike for Anna"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("First-hand report of the move"))
        assertEquals(false, TodoGate.isHeading("See you"))
        assertEquals(true, TodoGate.isGreeting("See you"))
    }

    @Test
    fun `a greeting is no to-do, with words of when or whom after it, but a to-do that starts like one is`() {
        assertEquals(true, TodoGate.isHeadingOrGreeting("See you"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("See you next week"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Thanks everyone"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Danke euch"))
        assertEquals(true, TodoGate.isHeadingOrGreeting("Bis dann"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("See you at the dentist"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Thank Anna for the flowers"))
        assertEquals(false, TodoGate.isHeadingOrGreeting("Say thanks to Tom"))
    }

    @Test
    fun `no cue can be read in a Malayalam source, so the model's capture decides, past a fragment or an idea`() {
        // Tomorrow morning call the doctor.
        val malayalam = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം"

        assertEquals(Verdict.OPEN, TodoGate.verdict("Call the doctor", appointment = false, timed = false, source = malayalam))
        assertEquals(Verdict.IDEA, TodoGate.verdict("Bigger clock", appointment = false, timed = false, source = malayalam, idea = true))
        assertEquals(Verdict.DROP, TodoGate.verdict("Call the", appointment = false, timed = false, source = malayalam))
        assertEquals(null, TodoGate.cueCapture("ഡോക്ടറെ വിളിക്കണം", malayalam))
    }

    @Test
    fun `a to-do written in English from German speech finds its sentence by the words copied from it`() {
        val german = "Das Wetter war schön. Ich muss morgen die Bank anrufen."

        // The English text shares no word with the German sentence; the copied words find it.
        assertEquals(Verdict.OPEN, TodoGate.verdict("Call the bank", appointment = false, timed = false, source = german, about = "die Bank anrufen"))
        assertEquals(TodoCapture.COMMITMENT, TodoGate.cueCapture("die Bank anrufen", german))
    }

    @Test
    fun `a drop says which rule it comes from, and a to-do kept has no reason`() {
        fun reason(text: String, source: String) = TodoGate.dropReason(text, appointment = false, timed = false, source = source)

        assertEquals(TodoGate.DropReason.QUESTION, reason("What did I feel?", "What did I feel today?"))
        assertEquals(TodoGate.DropReason.TOO_SHORT, reason("Yeah", "Yeah."))
        assertEquals(TodoGate.DropReason.TOO_SHORT, reason("Um okay", "Um okay."))
        assertEquals(TodoGate.DropReason.CUT_OFF, reason("It had been", "It had been"))
        assertEquals(TodoGate.DropReason.CUT_OFF, reason("Den Brief an die", "Den Brief an die Bank."))
        assertEquals(TodoGate.DropReason.UNCUED_COPY, reason("The weather was lovely at the lake", "The weather was lovely at the lake."))
        assertEquals(null, reason("Call the bank", "I have to call the bank tomorrow."))
        assertEquals(null, reason("Repaint the fence", "The fence looks old."))
        // The verdict is the same as before.
        assertEquals(Verdict.DROP, TodoGate.verdict("The weather was lovely at the lake", appointment = false, timed = false, source = "The weather was lovely at the lake."))
    }
}
