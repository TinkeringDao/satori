package com.satori.domain.reading

/**
 * The English of what the model wrote: its pieces joined as `tokenization_indictrans.py` joins
 * them, the line's placeholders put back, and the rules of Moses' English detokenizer that
 * IndicProcessor's postprocessing runs (sacremoses 0.2.0, `MosesDetokenizer(lang="en")`), so
 * "It ' s here ." reads "It's here.".
 *
 * Only English's rules are here; the branches sacremoses keeps for French, Italian, Czech and
 * Finnish never run for English.
 */
object EnglishDetokenizer {

    /** The text of a translation's [pieces], with [placeholders] (the source line's) restored. */
    fun detokenize(pieces: List<String>, placeholders: Placeholders = Placeholders.NONE): String {
        val joined = PythonText.strip(pieces.joinToString("").replace(PieceIds.WORD_START, ' '))
        return moses(placeholders.restore(joined))
    }

    /**
     * `MosesDetokenizer(lang="en").detokenize(text.split(" "))`: the XML escapes undone, then each
     * token joined to its neighbour or not, by what it is, left to right.
     */
    internal fun moses(text: String): String {
        var padded = " $text ".replace(" @-@ ", "-")
        for ((escaped, plain) in UNESCAPES) padded = padded.replace(escaped, plain)
        val tokens = PythonText.split(padded)
        val quotes = HashMap<String, Int>()
        val out = StringBuilder(padded.length)
        var space = " "
        for ((i, token) in tokens.withIndex()) {
            when {
                MosesTables.contains(MosesTables.CJK, token.codePointAt(0)) -> {
                    val afterCjk = i > 0 && tokens[i - 1].let { MosesTables.contains(MosesTables.CJK, it.codePointBefore(it.length)) }
                    if (afterCjk) out.append(token) else out.append(space).append(token)
                    space = " "
                }
                isCurrency(token) -> {
                    out.append(space).append(token)
                    space = ""
                }
                token.all { it in CLOSING } -> {
                    out.append(token)
                    space = " "
                }
                i > 0 && isContraction(token) -> {
                    out.append(token)
                    space = " "
                }
                token.all { it in QUOTES } -> {
                    val key = if (token.all { it in DOUBLE_QUOTES }) "\"" else token
                    val seen = quotes[key] ?: 0
                    if (seen % 2 == 0) {
                        if (token == "'" && i > 0 && tokens[i - 1].endsWith("s")) {
                            // A possessive: "the players' scores".
                            out.append(token)
                            space = " "
                        } else {
                            out.append(space).append(token)
                            space = ""
                            quotes[key] = seen + 1
                        }
                    } else {
                        out.append(token)
                        space = " "
                        quotes[key] = seen + 1
                    }
                }
                else -> {
                    out.append(space).append(token)
                    space = " "
                }
            }
        }
        return PythonText.strip(oneSpace(out))
    }

    /** `^[{IsSc}\(\[\{\¿\¡]+$`: currency signs and opening brackets alone. */
    private fun isCurrency(token: String): Boolean {
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            val opening = cp < 0x100 && OPENING.indexOf(cp.toChar()) >= 0
            if (!opening && !MosesTables.contains(MosesTables.CURRENCY, cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /** `^['][{IsAlpha}]`: an apostrophe and a letter, as in "'s" and "'re". */
    private fun isContraction(token: String): Boolean =
        token.length > 1 && token[0] == '\'' && MosesTables.contains(MosesTables.ALPHA, token.codePointAt(1))

    /** `" {2,}"` to one space. */
    private fun oneSpace(text: CharSequence): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            if (text[i] != ' ' || i == 0 || text[i - 1] != ' ') out.append(text[i])
        }
        return out.toString()
    }

    /** The XML escapes Moses undoes, in sacremoses' order (`&amp;` last). */
    private val UNESCAPES = listOf(
        "&bar;" to "|", "&#124;" to "|", "&lt;" to "<", "&gt;" to ">", "&bra;" to "[", "&ket;" to "]",
        "&quot;" to "\"", "&apos;" to "'", "&#91;" to "[", "&#93;" to "]", "&amp;" to "&"
    )

    /** `IS_PUNCT`: what joins the word before it. */
    private const val CLOSING = ",.?!:;\\%}])"

    /** The opening brackets and Spanish marks `IS_CURRENCY_SYMBOL` takes with the currency signs. */
    private const val OPENING = "([{¿¡"

    /** `IS_OPEN_QUOTE`. */
    private const val QUOTES = "'\"„“`"

    /** Quotes counted as one kind, `"`. */
    private const val DOUBLE_QUOTES = "„“”"
}
