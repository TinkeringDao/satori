package com.satori.domain.usecase.mindmap

import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind

/**
 * Decides where a topic of a recording goes in the mind map: onto a topic that is already there,
 * under an existing theme, or into a new theme of its own. Pure Kotlin and deterministic, so the
 * same labels always build the same map, and the model has no say in it.
 *
 * Topics and themes are compared by their match keys ([keyOf]): the stems of the label's content
 * words ([MindWords]), so case, umlauts, word order, stop words and plural or case endings don't
 * count. A topic goes, in this order:
 * 1. onto the topic with the same key, under that topic's theme (the same topic said again);
 * 2. under the theme with the same key (its first topic is gone, the theme is still there);
 * 3. under the theme whose key overlaps it best, if either the content words overlap by a Jaccard
 *    index of at least [MIN_JACCARD], or the two share a content word that is rare across the
 *    themes: one no other theme's key has, and not a generic word that names an occasion, a time
 *    or a kind of note rather than a subject ("meeting", "plan", "Termin"). Best is the higher
 *    Jaccard index, then more shared rare words, then more shared words; a tie goes to the older
 *    theme;
 * 4. otherwise into a new theme.
 *
 * "Heating costs" so joins "Heating repair" by "heating", and "Meeting with the bank" doesn't
 * join "Team meeting", whose only shared word is generic. Nodes made while a recording is mapped
 * are [add]ed, so its later topics see them.
 *
 * @param nodes The map's nodes, themes and topics, oldest first (as MindMapRepository.allNodes
 *   gives them); ties go to the one listed first.
 */
class ThemeMatcher(nodes: List<MindNode>) {

    /** Where a topic goes. */
    sealed interface Place {
        /** Onto the existing [topic], which is under [theme]. */
        data class OnTopic(val topic: MindNode, val theme: MindNode) : Place

        /** Under the existing [theme], as a new topic. */
        data class UnderTheme(val theme: MindNode) : Place

        /** Into a new theme, with the topic as its first topic. */
        data object NewTheme : Place
    }

    private class Keyed(val node: MindNode, val words: Set<String>)

    private val themes = mutableListOf<Keyed>()
    private val topics = mutableListOf<Keyed>()

    init {
        nodes.forEach(::add)
    }

    /** Adds [node], made since the matcher was built, so the topics placed after it can find it. */
    fun add(node: MindNode) {
        val keyed = Keyed(node, wordsOf(node.matchKey))
        when (node.kind) {
            MindNodeKind.THEME -> themes += keyed
            MindNodeKind.TOPIC -> topics += keyed
        }
    }

    /**
     * Where the topic with the match key [key] goes, by the rules of the class description. A key
     * without words matches nothing and goes into a new theme; the mapping step never gives one.
     */
    fun placeOf(key: String): Place {
        val words = wordsOf(key)
        if (words.isEmpty()) return Place.NewTheme
        for (topic in topics) {
            if (topic.words != words) continue
            val theme = themes.firstOrNull { it.node.id == topic.node.parentId } ?: continue
            return Place.OnTopic(topic.node, theme.node)
        }
        themes.firstOrNull { it.words == words }?.let { return Place.UnderTheme(it.node) }
        return bestTheme(words)?.let { Place.UnderTheme(it) } ?: Place.NewTheme
    }

    private class Candidate(val theme: MindNode, val jaccard: Double, val rare: Int, val shared: Int)

    private fun bestTheme(words: Set<String>): MindNode? {
        var best: Candidate? = null
        for (theme in themes) {
            val shared = words.intersect(theme.words)
            if (shared.isEmpty()) continue
            val jaccard = shared.size.toDouble() / words.union(theme.words).size
            val rare = shared.count { word -> word !in GENERIC_STEMS && themes.count { word in it.words } == 1 }
            if (jaccard < MIN_JACCARD && rare == 0) continue
            val candidate = Candidate(theme.node, jaccard, rare, shared.size)
            if (best == null || isBetter(candidate, best)) best = candidate
        }
        return best?.theme
    }

    /** Strictly better only, so that a tie keeps the theme listed first. */
    private fun isBetter(a: Candidate, b: Candidate): Boolean = when {
        a.jaccard != b.jaccard -> a.jaccard > b.jaccard
        a.rare != b.rare -> a.rare > b.rare
        else -> a.shared > b.shared
    }

    companion object {
        /** Two keys with at least this Jaccard index of their content words are one theme. */
        const val MIN_JACCARD = 0.5

        /** The separator of the words in a match key. */
        private const val KEY_SEPARATOR = " "

        /**
         * The match key of [label]: the stems of its content words ([MindWords]), each once, in
         * alphabetical order and separated by spaces. "The garden shed", "Gartenhaus" and
         * "Sheds in the garden" give "gard shed", "gartenhau" and "gard shed". Empty for a label
         * without content words ("Of the").
         */
        fun keyOf(label: String): String =
            MindWords.contentStems(label).toSortedSet().joinToString(KEY_SEPARATOR)

        /**
         * The words of a stored [matchKey]. They are folded and stemmed again, which leaves a key
         * written by [keyOf] as it is and lets a key written another way (unstemmed words, say)
         * still match.
         */
        private fun wordsOf(matchKey: String): Set<String> =
            matchKey.split(KEY_SEPARATOR).filter { it.isNotBlank() }
                .flatMap { MindWords.words(it) }
                .map(MindWords::stem)
                .toSet()

        /**
         * Words that say what kind of thing a topic is rather than what it is about, in English and
         * German, as stems: an occasion, a time, a kind of note, or something done, paid or held
         * for a subject ("repair", "costs", "party", "Rechnung"). Shared alone they say nothing
         * about two topics having one subject ("Team meeting" and "Meeting with the bank", "Heating
         * repair" and "Car repair"), so they never count as rare; they still count in the Jaccard
         * index ("Meeting" goes with "Team meeting").
         */
        private val GENERIC_STEMS: Set<String> = (
            "meeting call appointment plan planning idea note thought update question issue problem " +
                "topic thing stuff list task todo item point discussion talk chat conversation review " +
                "overview summary recap reminder today tomorrow yesterday week weekend month year day " +
                "morning afternoon evening night time next new old general various misc personal private " +
                "work home project status info information detail recording voice memo test " +
                "repair fix cost price payment bill invoice fee budget contract order purchase delivery " +
                "booking reservation party trip visit renovation cleaning maintenance installation " +
                "replacement change decision request application offer quote deadline date schedule " +
                "result report check inspection service support help search " +
                "termin treffen besprechung sitzung anruf telefonat planung idee notiz gedanke frage " +
                "thema sache ding liste aufgabe punkt gespräch diskussion überblick zusammenfassung " +
                "erinnerung heute morgen gestern woche wochenende monat jahr tag vormittag nachmittag " +
                "abend nacht zeit nächste neu alt allgemein sonstiges verschiedenes persönlich privat " +
                "arbeit zuhause projekt stand aufnahme sprachnotiz " +
                "reparatur kosten preis zahlung rechnung gebühr vertrag bestellung kauf lieferung buchung " +
                "reservierung feier fest reise ausflug besuch renovierung reinigung wartung austausch " +
                "änderung entscheidung anfrage antrag angebot frist datum ergebnis bericht prüfung " +
                "kontrolle hilfe suche"
            ).split(' ').map { MindWords.stem(MindWords.fold(it)) }.toSet()
    }
}
