package com.satori.ui.screens

/**
 * The AI's Markdown with images and links turned into their text, so a note can't make the
 * renderer fetch an image from the internet or open a URI on a tap (one no app handles would
 * crash Satori). Satori's notes are written offline from what was said; they need neither.
 */
internal fun offlineMarkdown(content: String): String =
    content
        .replace(IMAGE, "$1")
        .replace(LINK, "$1")
        .replace(AUTOLINK, "$1")

// ![alt](target) -> alt; [text](target) -> text; <scheme:target> -> scheme:target
private val IMAGE = Regex("""!\[([^\]\n]*)]\([^)\n]*\)""")
private val LINK = Regex("""\[([^\]\n]*)]\([^)\n]*\)""")
private val AUTOLINK = Regex("""<([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\s<>]*)>""")
