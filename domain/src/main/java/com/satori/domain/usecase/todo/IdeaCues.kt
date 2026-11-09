package com.satori.domain.usecase.todo

/**
 * The words with which a speaker puts forward an idea for how something could be, which nobody
 * has said they will do: "there should be an option to delete old recordings", "it would be good
 * if the screen stayed on", "es wäre schön, wenn ...", "man könnte ...".
 *
 * [TodoGate] makes a to-do the model found in such a sentence an idea, unless a first-person cue
 * says the speaker will do it ("I'll add an option to ..."). [MoveFoundIdeasUseCase] moves the
 * to-dos an earlier build found in such sentences to the ideas.
 *
 * The phrases are matched as whole words, whatever the case, and with the small words that may
 * stand inside one left out ("it would also be good", "es wäre doch schön"), as [TodoGate]
 * matches its cues. English and German are both looked for in every sentence, because people mix
 * the languages. "idea" alone is no cue ("I have no idea"), but "an idea", "my idea" and "the idea
 * is" are.
 */
internal object IdeaCues {

    /** Whether [text] puts forward an idea: it says one of the cue phrases. */
    fun has(text: String): Boolean = TodoGate.says(text, PHRASES)

    /**
     * Whether [before], the lower-case words said before a cue in its clause, make the cue part of
     * an idea, so that it asks for nothing and plans nothing: an idea phrase ("it would be nice if
     * the app could remind me to ...", "it would be better if I need to tap only once"), and for a
     * request to be reminded ([request]) also a modal said of something else ("the phone could
     * remind me", "it should remind me"). Not when the words speak to someone ("it would be great
     * if you could remind me"): that asks for the reminder.
     */
    fun wishes(before: List<String>, request: Boolean): Boolean {
        if (before.any { it in LISTENERS }) return false
        return has(before.joinToString(" ")) || (request && before.lastOrNull() in WISH_MODALS)
    }

    // Lower-case words, as TodoGate.says compares them. "would be nice" covers "it would be nice".
    private val PHRASES = listOf(
        "there should be", "there needs to be", "there could be",
        "it should be", "this should be", "that should be", "it's should be", "its should be", "which should be",
        "it would be good", "it would be great", "it would be better", "it would be helpful", "would be nice",
        "should be able to", "should have an option", "an option to",
        "we could", "maybe we can", "maybe we could", "what if", "how about",
        "an idea", "my idea", "the idea is", "suggestion", "suggestions", "feature request",
        "the app should", "the screen should", "the system should", "the phone should", "the setting should",
        "satori should",
        "es sollte", "es müsste",
        "es wäre gut", "es wäre toll", "es wäre besser", "es wäre praktisch", "wäre schön",
        "man könnte", "man sollte", "wir könnten", "vielleicht könnte man", "was wäre wenn",
        "idee", "ideen", "vorschlag", "vorschläge", "sollte es geben"
    )

    // Who a request may be said to: with them, "could" asks.
    private val LISTENERS = setOf("you", "du")

    // A modal right before "remind me" that says what something else could do, not what is asked for.
    private val WISH_MODALS = setOf("could", "should", "would", "might", "can", "may")
}
