package com.satori.domain.usecase.todo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a found item's own words say (brief 0.7.1, part A.2): whether it describes the text instead
 * of naming a thing to do, whether a to-do written in English for a Malayalam line names an action
 * or a commitment, and whether it holds a key of the answer. The items are made up, of the shapes
 * the 1B wrote.
 */
class FoundItemWordsTest {

    @Test
    fun `an item that says what the text or its speaker is about describes the text`() {
        val describing = listOf(
            "The text describes a sad story about a farmer's",
            "The speaker talks about the old village",
            "Speaker discusses the harvest",
            "The story is about a family and its land",
            "The passage mainly focuses on the river",
            "This is about the harvest festival",
            "Der Text handelt von einem Bauern",
            "Die Aufnahme beschreibt das Dorf",
            "Es geht um die Ernte"
        )
        for (item in describing) assertTrue(item, TodoGate.describesText(item))
    }

    @Test
    fun `an item that leaves the subject out, starts with About, or names a story about something describes the text`() {
        val describing = listOf(
            "Describes a sad story about a farmer",
            "Tells the story of a farmer and his son",
            "Narrates the grief of a mother",
            "Recounts the harvest in the village",
            "Beschreibt das Dorf am Fluss",
            "About a mother waiting for her son",
            "A poignant story about a mother's love",
            "Story of parents whose children went away",
            "Eine traurige Geschichte über einen Bauern",
            // A subject that is also a to-do's noun, with what it reports after its verb.
            "The recording describes the village",
            "The book talks about Kerala"
        )
        for (item in describing) assertTrue(item, TodoGate.describesText(item))
    }

    @Test
    fun `a subject that is also a to-do's noun, followed by no report, describes nothing`() {
        val kept = listOf(
            "Recording states shown on the cover screen",
            "Book talks with the publisher",
            "Write a story about the trip",
            "Tell the story to the children",
            "History of the town for the school project"
        )
        for (item in kept) assertFalse(item, TodoGate.describesText(item))
    }

    @Test
    fun `a third-person verb with what it is done to names no action`() {
        for (item in listOf("Describes a poignant story about a mother", "Tells the story of a mother", "Narrates the grief of a mother")) {
            assertFalse(item, TodoGate.namesAction(item))
        }
        // Verbs of an order that end on -s stay orders.
        assertTrue(TodoGate.namesAction("Discuss the budget with Tom"))
        assertTrue(TodoGate.namesAction("Focus the camera on the stage"))
    }

    @Test
    fun `a to-do with such a word, or one that names its action with who does it, describes nothing`() {
        val todos = listOf(
            "Read the text about the harvest",
            "Text Anna about the harvest",
            "Book the room for the reading",
            "The speaker needs to call the bank",
            "Speaker notes for the talk",
            "Recording highlights for Anna",
            "Den Text für Anna drucken"
        )
        for (item in todos) assertFalse(item, TodoGate.describesText(item))
    }

    @Test
    fun `a to-do names an action as an order, a modal verb phrase, a request to be reminded, or a time with a verb`() {
        val actions = listOf(
            "Call the bank",
            "Hand over the loan papers at the bank",
            "Withdraw money for the rent",
            "Line up the chairs for the meeting",
            "Then pay the electricity bill",
            "Mother needs to see a doctor",
            "Should pay the rent",
            "Remind me to water the plants",
            "Don't forget the keys",
            "Anna arrives tomorrow at 10",
            "Die Bank anrufen",
            "Mutter muss zum Arzt gehen"
        )
        for (item in actions) assertTrue(item, TodoGate.namesAction(item))
    }

    @Test
    fun `a story, a feeling or a thing alone names no action`() {
        val none = listOf(
            "A sad story about a farmer",
            "Quarrel over the old house",
            "Family conflict",
            "Mother's struggle for the land",
            "Children of the village",
            "Party on Friday",
            "The harvest festival",
            "Mother will be fine",
            "Ein trauriger Tag"
        )
        for (item in none) assertFalse(item, TodoGate.namesAction(item))
    }

    @Test
    fun `an item that holds a key of the answer at its start or in quotes names a field`() {
        assertTrue(TodoGate.namesField("said: ബാങ്കിൽ പോകണം"))
        assertTrue(TodoGate.namesField("Capture: explicit"))
        assertTrue(TodoGate.namesField("\"segment\": \"S2\", \"type\": \"task\""))
        assertFalse(TodoGate.namesField("Check what she said: the date of the party"))
        assertFalse(TodoGate.namesField("Type the letter to the landlord"))
        assertFalse(TodoGate.namesField("Text Anna when the train leaves"))
    }

    /**
     * 0.7.4: what comes before the key is passed over only when it is no word, also on the JVM.
     * Before, the JVM read the pattern's \W as ASCII only and passed over a Malayalam word before
     * the key, where the phone doesn't.
     */
    @Test
    fun `a word before the key is no field's start, in any script, as on the phone`() {
        assertFalse(TodoGate.namesField("അമ്മ said: ബാങ്കിൽ പോകണം"))
        assertFalse(TodoGate.namesField("Über text: die Rechnung zahlen"))
        assertTrue(TodoGate.namesField("- said: ബാങ്കിൽ പോകണം"))
        assertTrue(TodoGate.namesField("* text: die Rechnung zahlen"))
    }
}
