package com.satori.domain.reading

/**
 * What IndicProcessor put in place of a line's e-mail addresses, web addresses, figures and tags,
 * so the model copies `<ID1>` instead of translating them, and what [restore] puts back.
 *
 * The model may write a placeholder back in another form (`[ID1]`, `< ID1 >`, or "ID" in another
 * script), so each one has every key IndicProcessor's map has for it, in the map's order. The map
 * is a Python dict: a key written twice keeps its first place, which decides the order of
 * [restore]'s replacements.
 *
 * @property matches The text of `<ID1>`, `<ID2>`, ..., in that order
 */
class Placeholders internal constructor(val matches: List<String>) {

    /** Every key with the text it stands for, in the order IndicProcessor's map has them. */
    internal val entries: List<Pair<String, String>> by lazy {
        val map = LinkedHashMap<String, String>()
        matches.forEachIndexed { index, match ->
            for (key in keys(index + 1)) map[key] = match
        }
        map.entries.map { it.key to it.value }
    }

    /** IndicProcessor's postprocessing of a translation: each key, in order, becomes its text. */
    fun restore(text: String): String {
        var out = text
        for ((key, match) in entries) out = out.replace(key, match)
        return out
    }

    override fun equals(other: Any?): Boolean = other is Placeholders && other.matches == matches

    override fun hashCode(): Int = matches.hashCode()

    override fun toString(): String = "Placeholders(${matches.size})"

    companion object {

        /** A line with nothing put in place. */
        val NONE = Placeholders(emptyList())

        /**
         * The keys of placeholder [serial], as `_wrap_with_placeholders` writes them: `<ID1>` and
         * fifteen other spellings, then eleven forms of each way "ID" came out in another script.
         */
        internal fun keys(serial: Int): List<String> {
            val n = serial.toString()
            val keys = mutableListOf(
                "<ID$n>", "< ID$n >", "[ID$n]", "[ ID$n ]", "[ID $n]", "<ID$n]", "< ID$n]", "<ID$n ]",
                "<id$n>", "< id$n >", "[id$n]", "[ id$n ]", "[id $n]", "<id$n]", "< id$n]", "<id$n ]"
            )
            for (case in FAILURE_CASES) {
                keys += listOf(
                    "<$case$n>", "< $case$n >", "< $case $n >", "<$case $n]", "< $case $n ]", "[$case$n]",
                    "[$case $n]", "[ $case$n ]", "[ $case $n ]", "$case $n", "$case$n"
                )
            }
            return keys
        }

        /**
         * IndicProcessor's `_INDIC_FAILURE_CASES`: "ID" as the model has written it in Urdu,
         * Meitei, Devanagari and Ol Chiki letters. As in the reference, the eleventh is two of its
         * strings run together, a comma being missing between them in its source.
         */
        private val FAILURE_CASES = listOf(
            "آی ڈی ",
            "ꯑꯥꯏꯗꯤ",
            "आईडी",
            "आई . डी . ",
            "आई . डी .",
            "आई. डी. ",
            "आई. डी.",
            "आय. डी. ",
            "आय. डी.",
            "आय . डी . ",
            "आय . डी .आइ . डी . ",
            "आइ . डी .",
            "आइ. डी. ",
            "आइ. डी.",
            "ऐटि",
            "آئی ڈی ",
            "ᱟᱭᱰᱤ ᱾",
            "आयडी",
            "ऐडि",
            "आइडि",
            "ᱟᱭᱰᱤ"
        )
    }
}
