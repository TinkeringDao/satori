package com.satori.ui.screens

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every text of the app's strings files, as aapt2 reads it, for the tests that check all of them
 * at once: each string and each plural form by "file/name" ("file/name:quantity" for a plural
 * form). Gradle runs the tests in the module's folder.
 */
internal object StringsFiles {

    /** The texts of every `strings*.xml` in res/[folder] ("values", "values-de"). */
    fun all(folder: String): Map<String, String> =
        File("src/main/res/$folder").listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file -> texts(file).map { (name, text) -> "${file.name}/$name" to text } }
            .toMap()

    private fun texts(file: File): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        val plurals = document.getElementsByTagName("plurals")
        return (0 until strings.length).map { strings.item(it) as Element }
            .map { it.getAttribute("name") to read(it.textContent) } +
            (0 until plurals.length).map { plurals.item(it) as Element }.flatMap { plural ->
                val items = plural.getElementsByTagName("item")
                (0 until items.length).map { items.item(it) as Element }
                    .map { "${plural.getAttribute("name")}:${it.getAttribute("quantity")}" to read(it.textContent) }
            }
    }

    // An escaped apostrophe and a no-break space read as aapt2 gives them.
    private fun read(raw: String): String = raw.replace("\\'", "'").replace("\\u00A0", " ")
}
