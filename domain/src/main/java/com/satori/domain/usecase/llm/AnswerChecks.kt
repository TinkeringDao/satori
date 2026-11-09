package com.satori.domain.usecase.llm

/** Why a piece of an AI answer was left out, with the words the field log says it in. */
enum class AnswerDrop(val label: String) {
    /** It is mostly in another script than the Latin one ([WrongScript]), since 0.7.3. */
    OTHER_SCRIPT("in another script"),

    /** It repeats the instruction lines of its call's user message ([PromptEcho]). */
    PROMPT("that repeat their prompt"),

    /** It gives back the rules of its call's system prompt ([PromptRules]), since 0.7.3. */
    RULES("that repeat the rules of their prompt")
}

/**
 * What each piece of one AI call's answer (a note's title, point, summary or action item, a
 * recording's name, a to-do or an idea, a map topic's label or point, a sentence of a theme's
 * summary) is held to before it is kept, in this order: its script, which must be the Latin one
 * ([WrongScript]: the app asks for English or German), the instruction lines of the call's user
 * message ([PromptEcho]), then the rules of its system prompt ([PromptRules]).
 *
 * @property lines The instruction lines of the call's user message ([NotesTopic.Prompt.instructions],
 *   [TextLanguageNote]); none for a call whose user message is what was said alone
 * @param rules The rules of the call's system prompt ([PromptRules.of])
 * @param spoken What the call read of what was said: the text a note or a name is written from, a
 *   part of a to-do search. Its words, said together, are the speaker's own ([PromptRules.echoes]),
 *   and its scripts are the transcript's ([WrongScript.scriptsOf]). Null when it read no speech (a
 *   theme's points): no piece is the speaker's, and the script of what was read is not known.
 */
class AnswerChecks(
    val lines: List<String> = emptyList(),
    private val rules: PromptRules = PromptRules.NONE,
    spoken: String? = null
) {
    private val spokenWords: PromptRules.Spoken? = spoken?.let(PromptRules::Spoken)

    // The transcript's scripts besides the Latin one: a piece in a third goes whatever its share.
    private val scripts = spoken?.let(WrongScript::scriptsOf)

    /** Why [piece] is left out, or null when it is kept. */
    fun dropOf(piece: String): AnswerDrop? = when {
        WrongScript.isWrong(piece, scripts) -> AnswerDrop.OTHER_SCRIPT
        PromptEcho.isEcho(piece, lines) -> AnswerDrop.PROMPT
        rules.echoes(piece, lines, spokenWords) -> AnswerDrop.RULES
        else -> null
    }

    /**
     * Why the first of [forms] of one piece that is left out is (a title as cleaned, then as the
     * model wrote it: "Goal: Provide English notes" before its kind word went), or null when every
     * form is kept. Since the review of 0.7.3.
     */
    fun dropOfAny(vararg forms: String?): AnswerDrop? = forms.asSequence().filterNotNull().firstNotNullOfOrNull(::dropOf)

    /** [pieces] without those [dropOf] leaves out, and how many it left out for each reason. */
    fun <T> kept(pieces: List<T>, textOf: (T) -> String): Pair<List<T>, Map<AnswerDrop, Int>> {
        val drops = mutableMapOf<AnswerDrop, Int>()
        val kept = pieces.filter { piece ->
            val drop = dropOf(textOf(piece)) ?: return@filter true
            drops[drop] = (drops[drop] ?: 0) + 1
            false
        }
        return kept to drops
    }
}
