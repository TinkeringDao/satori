package com.satori.domain.usecase.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text in the wrong script goes (0.7.3): the notes, titles, names, to-dos and ideas are asked for
 * in English or German, so a piece most of whose words are in another script than the Latin one is
 * none of them. The share is chosen with these: a Malayalam name kept in a mostly English point
 * stays however long it is in letters, half the words stays, more than half goes. Every sample is
 * made up for this test.
 */
class WrongScriptTest {

    @Test
    fun `a piece in any script but the Latin goes`() {
        for (piece in listOf(
            "తోటలో పూలు నాటాలి",              // Telugu: plant flowers in the garden
            "আগামীকাল বাজারে যেতে হবে",        // Bengali: tomorrow I must go to the market
            "לקנות חלב ולחם",                 // Hebrew: buy milk and bread
            "कल सुबह डॉक्टर के पास जाना है",   // Devanagari (Hindi): go to the doctor tomorrow morning
            "நாளை காலை கூட்டம் உள்ளது",       // Tamil: a meeting tomorrow morning
            "ನಾಳೆ ಬೆಳಿಗ್ಗೆ ಸಭೆ ಇದೆ",            // Kannada: a meeting tomorrow morning
            "اجتماع غدا في الصباح",           // Arabic: a meeting tomorrow morning
            "明天早上开会",                    // Chinese: a meeting tomorrow morning
            "明日の朝に会議があります",          // Japanese: there is a meeting tomorrow morning
            "Завтра утром встреча с врачом",  // Cyrillic (Russian): tomorrow morning, the doctor
            "Αύριο το πρωί συνάντηση",        // Greek: a meeting tomorrow morning
            "നാളെ രാവിലെ യോഗമുണ്ട്",           // Malayalam: there is a meeting tomorrow morning
            "తోట বাগান גן"                    // garden, in Telugu, Bengali and Hebrew letters in one piece
        )) {
            assertTrue(piece, WrongScript.isWrong(piece))
        }
    }

    @Test
    fun `Latin letters with accents and the German umlauts are Latin`() {
        for (piece in listOf(
            "Café mit Jürgen am Freitag", "Größe der Tür messen", "Señora García calls back", "Smørrebrød for lunch",
            "Trip to Łódź in May", "Ça va, Zoë?", "Straße sperren"
        )) {
            assertFalse(piece, WrongScript.isWrong(piece))
        }
    }

    @Test
    fun `a Malayalam word kept in a mostly English point leaves it English, however many letters it has`() {
        for (piece in listOf(
            "Call അമ്മ about the rent",
            "Meet രാധാകൃഷ്ണൻ at the station",
            "Go to the വിദ്യാഭ്യാസമന്ത്രാലയം on Friday",
            "Go to വിദ്യാഭ്യാസമന്ത്രാലയം",
            "Mit അമ്മ zum Arzt gehen",
            "Ask ഉണ്ണികൃഷ്ണൻ about the ഓഫ്‌ലൈൻ app"
        )) {
            assertFalse(piece, WrongScript.isWrong(piece))
        }
    }

    @Test
    fun `half the words stay, more than half go`() {
        assertFalse(WrongScript.isWrong("Meet അമ്മ"))
        assertFalse(WrongScript.isWrong("Visit ആശുപത്രി"))
        assertTrue(WrongScript.isWrong("ആശുപത്രി"))
        assertTrue(WrongScript.isWrong("അമ്മ അച്ഛൻ Meet"))
        assertTrue(WrongScript.isWrong("AI తోట పని"))
        // A joiner inside an Indic word keeps it one word: two words of three are Telugu here.
        assertTrue(WrongScript.isWrong("Driver సాఫ్ట్‌వేర్ కావాలి"))
    }

    @Test
    fun `digits and punctuation are no script`() {
        assertFalse(WrongScript.isWrong("10:30"))
        assertFalse(WrongScript.isWrong("2026 – 2027"))
        assertFalse(WrongScript.isWrong(""))
        assertTrue(WrongScript.isWrong("5 ലക്ഷം"))
    }

    // The review of 0.7.3: what the transcript is written in tells a script it can't be the source of.
    private val malayalamTranscript = setOf(Character.UnicodeScript.MALAYALAM)
    private val latinTranscript = emptySet<Character.UnicodeScript>()

    @Test
    fun `a word in a script the transcript isn't in drops a piece, half Latin as it is`() {
        for (piece in listOf(
            "Bluetooth firmware కోసం update",     // Telugu: for
            "Order the parts আজ",                // Bengali: today
            "Call שרה about the shop",           // Hebrew: a name
            "Нужно Android SDK",                 // Cyrillic: needed
            "OK अभी"                             // Devanagari: now
        )) {
            assertTrue(piece, WrongScript.isWrong(piece, malayalamTranscript))
            assertTrue(piece, WrongScript.isWrong(piece, latinTranscript))
        }
        // A Malayalam word kept in an English point is the transcript's own, for a Malayalam recording only.
        assertFalse(WrongScript.isWrong("Call അമ്മ about the rent", malayalamTranscript))
        assertTrue(WrongScript.isWrong("Call അമ്മ about the rent", latinTranscript))
        // One letter of another script is a sign, not a word.
        assertFalse(WrongScript.isWrong("The area is π times r squared", latinTranscript))
    }

    @Test
    fun `scripts written without spaces count each letter as a word`() {
        for (piece in listOf("Android 部品を注文する", "Firmware 更新很重要", "PC ซ่อมแซมวันนี้")) {
            assertTrue(piece, WrongScript.isWrong(piece))
            assertTrue(piece, WrongScript.isWrong(piece, malayalamTranscript))
        }
    }

    @Test
    fun `a name of up to three words in the transcript's script counts as one word`() {
        assertFalse(WrongScript.isWrong("Call രാധ കൃഷ്ണൻ", malayalamTranscript))
        assertFalse(WrongScript.isWrong("Visit രാധ കൃഷ്ണൻ നായർ", malayalamTranscript))
        // Four in a row are no name: each counts.
        assertTrue(WrongScript.isWrong("Go to ഇന്ന് നാളെ മറ്റന്നാൾ എപ്പോഴും", malayalamTranscript))
        assertTrue(WrongScript.isWrong("രാധ കൃഷ്ണൻ", malayalamTranscript))
    }

    @Test
    fun `the scripts of a transcript are those it has a letter of`() {
        assertEquals(setOf(Character.UnicodeScript.MALAYALAM), WrongScript.scriptsOf("Remind me to call ഗീത about the rent."))
        assertEquals(emptySet<Character.UnicodeScript>(), WrongScript.scriptsOf("Größe der Tür messen, 10:30."))
    }

    @Test
    fun `the checks of every call put the script first`() {
        val checks = AnswerChecks(rules = PromptRules.of(ShippedPrompts().getSessionTitlePrompt("en")))

        assertTrue(checks.dropOf("తోటలో పూలు నాటాలి") == AnswerDrop.OTHER_SCRIPT)
        assertTrue(checks.dropOf("Call അമ്മ about the rent") == null)
    }
}
