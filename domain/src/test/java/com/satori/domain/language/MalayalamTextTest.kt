package com.satori.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The clean-up of a final line's Malayalam (Unicode 17.0, section 12.9): correct text stays as it
 * is, each broken spelling the speech model can write is put right, and a second pass changes
 * nothing. Every string here is put together from code points, syllables that follow or break the
 * rules, never a line anyone said.
 */
class MalayalamTextTest {

    // Letters
    private val ka = "\u0D15"
    private val tta = "\u0D20"
    private val nna = "\u0D23"
    private val ta = "\u0D24"
    private val na = "\u0D28"
    private val pa = "\u0D2A"
    private val ma = "\u0D2E"
    private val ra = "\u0D30"
    private val rra = "\u0D31"
    private val la = "\u0D32"
    private val lla = "\u0D33"
    private val va = "\u0D35"

    // Vowel letters
    private val a = "\u0D05"
    private val i = "\u0D07"
    private val ii = "\u0D08"
    private val u = "\u0D09"
    private val uu = "\u0D0A"
    private val e = "\u0D0E"
    private val ai = "\u0D10"
    private val o = "\u0D12"
    private val oo = "\u0D13"
    private val au = "\u0D14"

    // Vowel signs and the other signs
    private val aaSign = "\u0D3E"
    private val iSign = "\u0D3F"
    private val uSign = "\u0D41"
    private val eSign = "\u0D46"
    private val eeSign = "\u0D47"
    private val oSign = "\u0D4A"
    private val ooSign = "\u0D4B"
    private val auSign = "\u0D4C"
    private val auLength = "\u0D57"
    private val virama = "\u0D4D"
    private val anusvara = "\u0D02"
    private val visarga = "\u0D03"

    // Chillus
    private val chilluNn = "\u0D7A"
    private val chilluN = "\u0D7B"
    private val chilluRr = "\u0D7C"
    private val chilluL = "\u0D7D"
    private val chilluLl = "\u0D7E"

    private val zwj = "\u200D"
    private val zwnj = "\u200C"
    private val nbsp = "\u00A0"
    private val dottedCircle = "\u25CC"

    private fun clean(text: String) = MalayalamText.clean(text)

    @Test
    fun `a text without Malayalam comes back as it is`() {
        for (text in listOf("", "We still have to book the room.", "Die Regale kommen am Dienstag.", "3 x 4 = 12", "a\u200Db", " ")) {
            assertSame(text, clean(text))
        }
    }

    @Test
    fun `correct Malayalam comes back as it is`() {
        val correct = listOf(
            // A word, a conjunct with a vowel sign, the half-u written with u and the candrakkala.
            ka + aaSign + la + " " + pa + virama + ra + aaSign + " " + ka + uSign + virama,
            // A word ending in a chillu, and nta as recommended.
            a + va + chilluRr + " " + e + chilluN + virama + rra + eSign,
            // The anusvara repeated, a vowel sign and an anusvara after digits.
            ii + anusvara + anusvara + anusvara + anusvara + " 355" + aaSign + anusvara,
            // The joiners asking for a conjunct's form, and the candrakkala on a vowel letter.
            ka + virama + zwnj + ra + " " + ka + zwj + virama + ra + " " + ka + zwnj + virama + ra + " " + e + virama + na + aaSign,
            // A conjunct of a chillu, a vowel sign on the chillu, and the side-by-side n and rra.
            chilluL + virama + pa + " " + a + chilluN + aaSign + " " + a + chilluN + rra + aaSign,
            // Signs shown on their own, on a no-break space and on the dotted circle.
            nbsp + aaSign + " " + dottedCircle + iSign,
            // The precomposed two-part vowels, the modern au, and the anusvara after the candrakkala.
            ka + oSign + " " + ka + ooSign + " " + ka + auSign + " " + ka + auLength + " " + la + virama + anusvara,
            // English words in Latin letters among Malayalam ones.
            "meeting " + ka + aaSign + la + " Friday " + ma + iSign
        )
        for (text in correct) assertEquals(text, clean(text))
    }

    @Test
    fun `a two-part vowel sign in two parts is the one sign`() {
        assertEquals(ka + oSign, clean(ka + eSign + aaSign))
        assertEquals(ka + ooSign, clean(ka + eeSign + aaSign))
        assertEquals(ka + auSign, clean(ka + eSign + auLength))
        assertEquals(ma + oSign + la + iSign, clean(ma + eSign + aaSign + la + iSign))
    }

    @Test
    fun `a vowel letter written as a letter and a sign is the one letter`() {
        assertEquals(ii, clean(i + auLength))
        assertEquals(uu, clean(u + auLength))
        assertEquals(ai, clean(e + eSign))
        assertEquals(oo, clean(o + aaSign))
        assertEquals(au, clean(o + auLength))
        assertEquals(oo + ma, clean(o + aaSign + ma))
    }

    @Test
    fun `a chillu written with a virama and a zero width joiner is the atomic chillu`() {
        assertEquals(a + va + chilluNn, clean(a + va + nna + virama + zwj))
        assertEquals(a + va + chilluN, clean(a + va + na + virama + zwj))
        assertEquals(a + va + chilluRr, clean(a + va + ra + virama + zwj))
        assertEquals(a + va + chilluL, clean(a + va + la + virama + zwj))
        assertEquals(a + va + chilluLl, clean(a + va + lla + virama + zwj))
        // Only those five had a chillu written so; chillu k is atomic only.
        assertEquals(ka + virama + zwj, clean(ka + virama + zwj))
    }

    @Test
    fun `nta is written one way, as the standard recommends`() {
        assertEquals(e + chilluN + virama + rra + eSign, clean(e + na + virama + rra + eSign))
        // At the start of a word no nta stands: left as it is.
        assertEquals(na + virama + rra + aaSign, clean(na + virama + rra + aaSign))
        assertEquals(ka + " " + na + virama + rra, clean(ka + " " + na + virama + rra))
        // With a zero width joiner it is the side-by-side form, as long as nothing says otherwise.
        assertEquals(e + chilluN + rra + eSign, clean(e + na + virama + zwj + rra + eSign))
    }

    @Test
    fun `a vowel sign or a virama written twice is written once, the anusvara as often as it is`() {
        assertEquals(ka + aaSign, clean(ka + aaSign + aaSign))
        assertEquals(ka + iSign + la, clean(ka + iSign + iSign + iSign + la))
        assertEquals(ka + virama + ka, clean(ka + virama + virama + ka))
        assertEquals(ka + aaSign + anusvara + anusvara, clean(ka + aaSign + anusvara + anusvara))
    }

    @Test
    fun `a virama before a vowel sign goes, and the vowel sign stays`() {
        assertEquals(ka + aaSign, clean(ka + virama + aaSign))
        assertEquals(pa + virama + ra + aaSign, clean(pa + virama + ra + virama + aaSign))
        assertEquals(ka + oSign, clean(ka + virama + eSign + aaSign))
        // The half-u is the u sign and then the candrakkala: a vowel sign after it has no place.
        assertEquals(ka + uSign + virama, clean(ka + uSign + virama + aaSign))
    }

    @Test
    fun `a virama after the anusvara or the visarga goes`() {
        assertEquals(ka + anusvara, clean(ka + anusvara + virama))
        assertEquals(ka + visarga + ta, clean(ka + visarga + virama + ta))
    }

    @Test
    fun `a syllable has one vowel sign, and none after its anusvara or visarga`() {
        assertEquals(ka + aaSign, clean(ka + aaSign + iSign))
        assertEquals(ka + oSign, clean(ka + oSign + aaSign))
        assertEquals(ka + anusvara, clean(ka + anusvara + aaSign))
        assertEquals(ka + visarga, clean(ka + visarga + iSign))
    }

    @Test
    fun `a sign at the start of a word goes to the Malayalam word before it, where it has a place`() {
        assertEquals(ka + va + aaSign, clean(ka + va + " " + aaSign))
        assertEquals(a + va + na + virama + " " + ka, clean(a + va + na + " " + virama + " " + ka))
        assertEquals(pa + aaSign + tta + anusvara, clean(pa + aaSign + tta + " " + anusvara))
        assertEquals(ka + oSign, clean(ka + " " + eSign + aaSign))
        assertEquals(ka + aaSign, clean(ka + virama + "  " + aaSign))
    }

    @Test
    fun `a sign with nothing to carry it is left out`() {
        // At the start of the line, after a word in Latin letters, after punctuation.
        assertEquals(ka + la, clean(aaSign + ka + la))
        assertEquals("OK " + ma, clean("OK " + iSign + ma))
        assertEquals("$ka,", clean("$ka," + aaSign))
        // Where it has no place at the end of the word before, the space stays.
        assertEquals(ka + aaSign + " " + ma, clean(ka + aaSign + " " + aaSign + ma))
        assertEquals(ka + anusvara + " " + ma, clean(ka + anusvara + " " + iSign + ma))
    }

    @Test
    fun `a chillu never starts a word, so one after a Malayalam word ends it`() {
        assertEquals(a + va + chilluRr, clean(a + va + " " + chilluRr))
        assertEquals(ka + aaSign + chilluL + " " + ma, clean(ka + aaSign + " " + chilluL + " " + ma))
        assertEquals(ii + chilluN, clean(ii + " " + chilluN))
        // Written the old way as well.
        assertEquals(a + va + chilluRr, clean(a + va + " " + ra + virama + zwj))
    }

    @Test
    fun `a chillu with no Malayalam word before it stays, as a letter that can be read`() {
        assertEquals(chilluRr + ka, clean(chilluRr + ka))
        assertEquals("OK " + chilluN, clean("OK " + chilluN))
        // After a chillu, or the anusvara, it is no word's end.
        assertEquals(a + va + chilluN + " " + chilluL, clean(a + va + chilluN + " " + chilluL))
        assertEquals(ka + anusvara + " " + chilluL, clean(ka + anusvara + " " + chilluL))
    }

    @Test
    fun `everything put right in one line, English words in it left alone`() {
        val broken = "today " + ka + virama + aaSign + la + " " + chilluN + " " + e + na + virama + rra + eSign + eSign + " " +
            ma + eSign + aaSign + " ok"
        val clean = "today " + ka + aaSign + la + chilluN + " " + e + chilluN + virama + rra + eSign + " " + ma + oSign + " ok"

        assertEquals(clean, clean(broken))
    }

    @Test
    fun `cleaning twice changes nothing more, and no rule's sequence is left in a cleaned text`() {
        // Every pair and triple of the characters the rules look at, and random lines of them.
        val alphabet = listOf(
            ka, na, ra, rra, la, va, nna, lla, i, u, e, o, aaSign, iSign, uSign, eSign, eeSign, auLength, virama,
            anusvara, visarga, chilluN, chilluRr, chilluL, zwj, zwnj, nbsp, " ", "5", "x", ",", dottedCircle
        )
        val texts = alphabet.flatMap { first -> alphabet.map { first + it } } +
            alphabet.flatMap { first -> alphabet.flatMap { second -> listOf(first + second + ka, first + " " + second) } } +
            List(4_000) { line ->
                val random = Random(line)
                (1..random.nextInt(1, 14)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            }
        for (text in texts) {
            val once = clean(text)
            assertEquals(text, once, clean(once))
            assertTrue(text, once.length <= text.length)
            assertFalse(text, broken(once))
        }
    }

    /** Whether [text] still holds one of the sequences the rules put right. */
    private fun broken(text: String): Boolean {
        val signs = setOf('\u0D3E', '\u0D3F', '\u0D41', '\u0D46', '\u0D47', '\u0D4A', '\u0D4B', '\u0D4C', '\u0D57')
        val marks = signs + setOf('\u0D4D', '\u0D02', '\u0D03')
        val chillus = setOf('\u0D54', '\u0D55', '\u0D56', '\u0D7A', '\u0D7B', '\u0D7C', '\u0D7D', '\u0D7E', '\u0D7F')
        val malayalamLetters = ('\u0D05'..'\u0D3A').toSet() + chillus
        fun canCarry(c: Char) = c in malayalamLetters || c in marks || c.isDigit() || c == '\u00A0' || c == '\u25CC'
        for (index in text.indices) {
            val c = text[index]
            val before = text.getOrNull(index - 1)
            // The base of a sign, past a joiner.
            var baseAt = index - 1
            while (baseAt >= 0 && text[baseAt] in setOf('\u200D', '\u200C')) baseAt--
            val base = text.getOrNull(baseAt)
            when {
                c in marks && (base == null || !canCarry(base)) -> return true
                c in signs && (before == c || before == '\u0D4D' || before == '\u0D02' || before == '\u0D03') -> return true
                c in signs && before in signs && "$before$c" !in setOf("\u0D46\u0D3E", "\u0D47\u0D3E", "\u0D46\u0D57") -> return true
                "$before$c" in setOf("\u0D46\u0D3E", "\u0D47\u0D3E", "\u0D46\u0D57") -> return true
                c == '\u0D4D' && (before == '\u0D4D' || before == '\u0D02' || before == '\u0D03') -> return true
                c == '\u200D' && before == '\u0D4D' && text.getOrNull(index - 2) in setOf('\u0D23', '\u0D28', '\u0D30', '\u0D32', '\u0D33') -> return true
                c == '\u0D31' && before == '\u0D4D' && text.getOrNull(index - 2) == '\u0D28' &&
                    text.getOrNull(index - 3)?.let { it in malayalamLetters || it in marks } == true -> return true
                c in chillus && before == ' ' &&
                    text.substring(0, index).trimEnd(' ').lastOrNull()?.let { it in ('\u0D05'..'\u0D3A') || it in signs } == true -> return true
            }
        }
        return false
    }
}
