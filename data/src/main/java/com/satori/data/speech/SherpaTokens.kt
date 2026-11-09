package com.satori.data.speech

import java.io.File
import java.io.IOException

/**
 * A tokens.txt as sherpa-onnx reads it (symbol-table.cc, v1.13.8): each line a token and its id,
 * or an id alone, whose token is then a space. sherpa-onnx ends the whole process on a line with
 * anything after the id, on an id that isn't a number, and, decoding with greedy CTC search, on a
 * list without a blank (`<blk>`, `<eps>` or `<blank>`); an id the model writes that the list lacks
 * is an uncaught C++ exception, which ends it too. So the list is checked before every load of a
 * model whose registry entry names its size ([com.satori.data.config.SpeechModelFile.tokens]).
 */
object SherpaTokens {

    /** The tokens greedy CTC search takes for "nothing said", one of which the list must have. */
    private val BLANKS = setOf("<blk>", "<eps>", "<blank>")

    /** What sherpa-onnx trims off each line, and splits it at: C's white space. */
    private const val SPACE = " \t\n\r\u000B\u000C"

    /**
     * Why [file] isn't a list of [count] tokens sherpa-onnx can decode with, or null when it is:
     * every line parses, the ids are 0 to [count] - 1, each once, and one token is a blank.
     *
     * @throws IOException when the file can't be read
     */
    fun problem(file: File, count: Int): String? {
        val ids = HashSet<Int>()
        var blank = false
        var lineNumber = 0
        var problem: String? = null
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                lineNumber++
                val fields = line.trim { it in SPACE }.split(*SPACE.toCharArray()).filter { it.isNotEmpty() }
                val (token, idText) = when (fields.size) {
                    1 -> " " to fields[0]
                    2 -> fields[0] to fields[1]
                    else -> {
                        problem = "line $lineNumber has ${fields.size} fields, not a token and its id"
                        break
                    }
                }
                val id = idText.toIntOrNull()
                if (id == null || id < 0 || id >= count) {
                    problem = "line $lineNumber has the id $idText, not one of 0 to ${count - 1}"
                    break
                }
                if (!ids.add(id)) {
                    problem = "line $lineNumber has the id $id a second time"
                    break
                }
                if (token in BLANKS) blank = true
            }
        }
        return problem ?: when {
            ids.size != count -> "${ids.size} tokens, not $count"
            !blank -> "no blank among the tokens (${BLANKS.joinToString()})"
            else -> null
        }
    }
}
