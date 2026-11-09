package com.satori.domain.usecase.mindmap

import java.text.Normalizer
import java.util.Locale

/**
 * The word rules of the mind map, for English and German alike: which words of a label say what
 * it is about, and in what form two of them count as the same word.
 *
 * Both checks the model can't be trusted with use them: whether a topic's label comes from the
 * text it read ([MindTopics]), and which theme a topic belongs to ([ThemeMatcher]). Both
 * languages' rules run on every text, because people mix them.
 *
 * A word is compared in its folded form (lower case, accents and umlauts dropped, "ß" as "ss"),
 * with its plural and case endings stripped: "Gärten", "Gartens" and "Garten" are one word, as
 * are "Häuser" and "Haus", "cities" and "city". The stems are for comparing, not for reading:
 * stripping is crude ("Garten" becomes "gart") but gives the forms of a word the same stem.
 */
internal object MindWords {

    /** The shortest stem an ending is stripped down to: "bus" keeps its "s", "Tage" becomes "tag". */
    private const val MIN_STEM = 3

    /** The shortest word that counts as a part of a compound: "arzt" of "arzttermin". */
    private const val MIN_PART = 4

    /** The shortest run two words must share to count as one: "meeting" of "teameeting" and "teemeeting". */
    private const val MIN_SHARED = 6

    /** Plural and case endings of both languages, longest first, stripped until none is left. */
    private val ENDINGS = listOf("ern", "ens", "en", "er", "es", "em", "e", "n", "s")

    private val MARKS = Regex("""\p{M}+""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /** Lower case, accents and umlauts dropped, "ß" as "ss": "Größe" is "grosse". */
    fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace("ß", "ss")

    /**
     * The folded words of [text] in order: runs of letters and digits. An apostrophe ends a word,
     * so the "s" of a possessive stands alone, and at one letter it is no content word.
     */
    fun words(text: String): List<String> = WORD.findAll(fold(text)).map { it.value }.toList()

    /**
     * The stems of the words of [text] that say what it is about ([isContentWord]), in order and
     * with repeats.
     */
    fun contentStems(text: String): List<String> = words(text).filter(::isContentWord).map(::stem)

    /**
     * Whether the label stem [stem] is a word of [vocabulary] (stems too), also when one of them is
     * a compound of the other: a model writes the compound of what was said, in German above all
     * ("Arzttermin" for "beim Arzt", "Heizungsreparatur" for "die Heizung wird repariert"), and
     * corrects a misheard part ("Teameeting" for "Teemeeting"). A part counts from [MIN_PART]
     * characters, a run both share from [MIN_SHARED].
     */
    fun inVocabulary(stem: String, vocabulary: Set<String>): Boolean =
        stem in vocabulary || vocabulary.any { word ->
            (word.length >= MIN_PART && stem.contains(word)) ||
                (stem.length >= MIN_PART && word.contains(stem)) ||
                longestSharedRun(stem, word) >= MIN_SHARED
        }

    /** The length of the longest run of characters [a] and [b] both hold. */
    private fun longestSharedRun(a: String, b: String): Int {
        if (a.length < MIN_SHARED || b.length < MIN_SHARED) return 0
        var best = 0
        val previous = IntArray(b.length + 1)
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0
                if (current[j] > best) best = current[j]
            }
            current.copyInto(previous)
        }
        return best
    }

    /** Whether the folded [word] says what a text is about: two or more characters, not a stop word. */
    fun isContentWord(word: String): Boolean = word.length >= 2 && word !in STOP_WORDS

    /**
     * The stem of the folded [word]: plural and case endings go one after the other while
     * [MIN_STEM] characters remain, then a final "y" is written "i", so that the English plurals
     * in "ies" meet their singulars whether these end in "y" or in "ie". "Terminen", "Termine" and
     * "Termin" all give "termi", "computers" and "computer" give "comput", "city" and "cities"
     * give "citi", "movie" and "movies" give "movi".
     */
    fun stem(word: String): String {
        var stem = word
        while (true) {
            val ending = ENDINGS.firstOrNull { stem.endsWith(it) && stem.length - it.length >= MIN_STEM } ?: break
            stem = stem.dropLast(ending.length)
        }
        return if (stem.length > MIN_STEM && stem.endsWith('y')) stem.dropLast(1) + "i" else stem
    }

    // Words that say nothing about a subject on their own: articles, pronouns, prepositions,
    // conjunctions, auxiliary and modal verbs and common adverbs, in English and German, folded
    // like the texts. A label is compared by its other words: "Trip to Berlin" by "trip" and
    // "berlin", "Termin beim Arzt" by "termin" and "arzt". Words that name a subject in the other
    // language stay out ("war", "man", "kind", "hat", "bald").
    private val STOP_WORDS: Set<String> = (
        "a an the and or but nor so yet if then than as at by for from in into of on onto off out over " +
            "to up with without about after before between during through under via per around against " +
            "among i me my mine myself we us our ours you your yours he him his she her hers it its they " +
            "them their theirs this that these those who whom whose which what where when why how am is " +
            "are was were be been being have has had having do does did doing done will would shall " +
            "should can could may might must need needs let lets not no yes all any some each every both " +
            "either neither more most much many few other another such own same very too also just only " +
            "still even again there here now ok okay etc um uh er ah oh don doesn didn isn aren wasn " +
            "weren won wouldn couldn shouldn ll re ve " +
            "der die das den dem des ein eine einen einem einer eines und oder aber sondern denn doch wenn " +
            "als wie dass ob weil da auch noch nur schon sehr mal ja nein nicht kein keine keinen keinem " +
            "keiner keines an am auf aus bei beim bis durch für gegen hinter im ins mit nach neben ohne " +
            "seit über um unter von vom vor während wegen zu zum zur zwischen ab ich mich mir mein meine " +
            "meinen meinem meiner meines du dich dir dein deine deinen deinem deiner deines ihn ihm sein " +
            "seine seinen seinem seiner seines sie ihr ihre ihren ihrem ihrer ihres es wir uns unser " +
            "unsere unseren unserem unserer euch euer eure sich dies diese diesen diesem dieser dieses " +
            "jene jener jenes bin bist ist sind seid waren warst gewesen habe hast haben habt hatte " +
            "hatten werden werde wird wirst wurde wurden worden kann können könnte muss müssen musste " +
            "soll sollen sollte will wollen wollte darf dürfen möchte möchten alle alles etwas nichts " +
            "hier dort jetzt dann also immer wieder ganz eigentlich genau halt eben ähm äh"
        ).split(' ').map(::fold).toSet()
}
