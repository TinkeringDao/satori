package com.satori.domain.usecase.todo

import com.satori.domain.language.MalayalamText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * The Malayalam words that ask for a thing to be done, promise it, ask for a reminder or say when:
 * the cue a to-do of the small model needs in what it quotes, and what puts an idea forward. Every
 * sentence is made up, and glossed in English above it.
 */
class MalayalamTodoCuesTest {

    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

    @Test
    fun `a request, a commitment, a reminder and a time are each a cue`() {
        val cues = mapOf(
            // I need a new umbrella.
            "എനിക്ക് പുതിയ കുട വേണം" to "വേണം",
            // The housework must be done.
            "വീട്ടിലെ ജോലി ചെയ്യണം" to "ചെയ്യണം",
            // Remind me to call mother.
            "അമ്മയെ വിളിക്കാൻ എന്നെ ഓർമ്മിപ്പിക്കുക" to "ഓർമ്മിപ്പിക്കുക",
            // Tomorrow I'll see the doctor.
            "നാളെ ഡോക്ടറെ കാണാം" to "നാളെ",
            // There is a meeting at nine.
            "ഒമ്പത് മണിക്ക് യോഗം ഉണ്ട്" to "മണിക്ക്",
            // The report must be finished before Friday: the day with its ending, and the verb.
            "വെള്ളിയാഴ്ചയ്ക്ക് മുമ്പ് റിപ്പോർട്ട് തീർക്കണം" to "വെള്ളിയാഴ്ച",
            // Don't forget to buy medicine.
            "മരുന്ന് വാങ്ങാൻ മറക്കരുത്" to "മറക്കരുത്",
            // Must go to the bank in the morning.
            "രാവിലെ ബാങ്കിൽ പോകണം" to "പോകണം",
            // Milk and bread must be bought.
            "പാലും റൊട്ടിയും വാങ്ങണം" to "വാങ്ങണം",
            // The letter must be written.
            "കത്ത് എഴുതണം" to "എഴുതണം",
            // Remind me, said in English.
            "റിമൈൻഡ് ചെയ്യൂ" to "റിമൈൻഡ്"
        )
        for ((sentence, cue) in cues) assertTrue("$cue in $sentence", MalayalamTodoCues.holds(sentence))
    }

    @Test
    fun `promises, requests for the speaker and the other obligatives of the review are cues`() {
        val cues = mapOf(
            // I'll give him the book: the promissive -aam.
            "ഞാൻ അവന് പുസ്തകം കൊടുക്കാം" to "കൊടുക്കാം",
            // I'll book the hotel, with no one named.
            "ഹോട്ടൽ ബുക്ക് ചെയ്യാം" to "ചെയ്യാം",
            // Could you read that letter: the promissive asked.
            "ആ കത്ത് ഒന്ന് വായിക്കാമോ" to "വായിക്കാമോ",
            // Please send me that photo: -u tharanam.
            "ആ ഫോട്ടോ ഒന്ന് അയച്ചുതരണം" to "അയച്ചുതരണം",
            // Buy me a pen: -i tharanam.
            "എനിക്ക് ഒരു പേന വാങ്ങിത്തരണം" to "വാങ്ങിത്തരണം",
            // Tell me the way, with tharanam a word of its own.
            "വഴി പറഞ്ഞു തരണം" to "തരണം",
            // The door must be locked, the shirt put on: -ttanam, -tanam.
            "വാതിൽ പൂട്ടണം" to "പൂട്ടണം",
            "കുപ്പായം ഇടണം" to "ഇടണം",
            // The tyre must be changed: -ttanam with the rra.
            "ടയർ മാറ്റണം" to "മാറ്റണം",
            // Must go to the hospital, as it is said.
            "ആശുപത്രിയിൽ പോണം" to "പോണം",
            // Don't go and forget to take the passport.
            "പാസ്പോർട്ട് എടുക്കാൻ മറന്നുപോകരുത്" to "മറന്നുപോകരുത്"
        )
        for ((sentence, cue) in cues) assertTrue("$cue in $sentence", MalayalamTodoCues.holds(sentence))
    }

    @Test
    fun `today, this evening, tomorrow's and a clock time inside a word are times`() {
        // Today evening there's a meeting.
        assertTrue(MalayalamTodoCues.holds("ഇന്ന് വൈകീട്ട് ഒരു മീറ്റിംഗ് ഉണ്ട്"))
        // There's a bus at ten, "ten" and "o'clock" written as one word.
        assertTrue(MalayalamTodoCues.holds("പത്തുമണിക്ക് ബസ് ഉണ്ട്"))
        // Tomorrow's meeting.
        assertTrue(MalayalamTodoCues.holds("നാളത്തെ യോഗം"))
        // Tomorrow I'm going to Bangalore: a plan said in the present.
        assertTrue(MalayalamTodoCues.holds("നാളെ ഞാൻ ബാംഗ്ലൂരിലേക്ക് പോകുന്നു"))
        // Yesterday starts like today, and is no time ahead.
        assertFalse(MalayalamTodoCues.holds("ഇന്നലെ അവൻ വീട്ടിൽ ഇരുന്നു"))
    }

    @Test
    fun `a time in a clause told in the past is no cue`() {
        // One morning he went to the market.
        assertFalse(MalayalamTodoCues.holds("ഒരു ദിവസം രാവിലെ അവൻ ചന്തയിൽ പോയി"))
        // It rained last night.
        assertFalse(MalayalamTodoCues.holds("ഇന്നലെ രാത്രി മഴ പെയ്തു"))
        // On Friday they had stayed there.
        assertFalse(MalayalamTodoCues.holds("വെള്ളിയാഴ്ച അവർ അവിടെ താമസിച്ചിരുന്നു"))
        // But a time in the clause before, not told in the past, still is: tomorrow morning there's a meeting, then they sang.
        assertTrue(MalayalamTodoCues.holds("നാളെ രാവിലെ യോഗം ഉണ്ട്, പിന്നെ അവർ പാട്ട് പാടി"))
    }

    @Test
    fun `a line says when with a day, a part of it, an hour or a time in words in a row, told in the past or not (D9)`() {
        val times = listOf(
            // Tomorrow I'll see the doctor.
            "നാളെ ഡോക്ടറെ കാണാം",
            // Today evening there's a meeting: today as a word of its own.
            "ഇന്ന് വൈകീട്ട് ഒരു മീറ്റിംഗ് ഉണ്ട്",
            // There's a bus at ten, "ten" and "o'clock" written as one word.
            "പത്തുമണിക്ക് ബസ് ഉണ്ട്",
            // Tomorrow's meeting, the day with its ending.
            "നാളത്തെ യോഗം",
            // On Friday the papers come.
            "വെള്ളിയാഴ്ച പേപ്പറുകൾ വരും",
            // Next week the painter comes, the time in two words.
            "അടുത്ത ആഴ്ച പെയിന്റർ വരും",
            // Tomorrow, said in English in Malayalam letters, at the end of a clause.
            "ബാങ്കിൽ പോകണം ടുമാറോ.",
            // One morning he went to the market: a time all the same, told in the past.
            "ഒരു ദിവസം രാവിലെ അവൻ ചന്തയിൽ പോയി"
        )
        for (line in times) assertTrue(line, MalayalamTodoCues.holdsTime(line))

        val none = listOf(
            // The roof of the shed leaks again.
            "ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു",
            // A new shelf has to be bought: a request, but no time.
            "ഒരു പുതിയ ഷെൽഫ് വാങ്ങണം",
            // Yesterday starts like today, and is no "today".
            "ഇന്നലെ അവൻ വീട്ടിൽ ഇരുന്നു",
            ""
        )
        for (line in none) assertFalse(line, MalayalamTodoCues.holdsTime(line))
    }

    @Test
    fun `a time is also said by the hour's other spelling, a date, the afternoon, this or next week or month, and a clock time in figures`() {
        val times = listOf(
            // Call him at five, the -ക്കു spelling.
            "അഞ്ചു മണിക്കു അവനെ വിളിക്കണം",
            // Be at the office by five.
            "അഞ്ചു മണിയോടെ ഓഫീസിൽ എത്തണം",
            // On the 15th the rent goes out.
            "പതിനഞ്ചാം തീയതി വാടക കൊടുക്കണം",
            // In the afternoon the plumber comes.
            "ഉച്ചകഴിഞ്ഞ് പ്ലംബർ വരും",
            // Next month the car goes to the garage.
            "അടുത്ത മാസം കാർ ഗാരേജിൽ കൊടുക്കണം",
            // This week the forms have to be filled in.
            "ഈ ആഴ്ച ഫോമുകൾ പൂരിപ്പിക്കണം",
            // This month the bill is paid.
            "ഈ മാസം ബിൽ അടയ്ക്കണം",
            // On Sunday, said short.
            "ഞായർ അവർ വരും",
            // The meeting is at 10:30.
            "മീറ്റിംഗ് 10:30 ന് ആണ്"
        )
        for (line in times) assertTrue(line, MalayalamTodoCues.holdsTime(line))

        val none = listOf(
            // Five people came: a number, no hour.
            "അഞ്ചു പേർ വന്നു",
            // A ratio of 3:1 is no clock time, nor a lone colon.
            "അനുപാതം 3:1 ആണ് :",
            // The month was good: a month, but not this one or the next.
            "ആ മാസം നല്ലതായിരുന്നു"
        )
        for (line in none) assertFalse(line, MalayalamTodoCues.holdsTime(line))

        // The to-do cues stay as they were: the hour's other spelling alone asks for nothing.
        assertFalse(MalayalamTodoCues.holds("അഞ്ചു മണിക്കു"))
        assertFalse(MalayalamTodoCues.holds("ഉച്ചകഴിഞ്ഞ് പ്ലംബർ വരും"))
    }

    @Test
    fun `the obligative is found before a further ending, and a phrase across words`() {
        // He said that I must see the doctor.
        assertTrue(MalayalamTodoCues.holds("ഡോക്ടറെ കാണണമെന്ന് അവൻ പറഞ്ഞു"))
        // The house has to be painted next week.
        assertTrue(MalayalamTodoCues.holds("അടുത്ത ആഴ്ച വീട് പെയിന്റ് ചെയ്യേണ്ടതുണ്ട്"))
    }

    @Test
    fun `nouns in -anam and -aam, a may-be and a narrative are no cue`() {
        val none = listOf(
            // They sold the house because money was short: money, because.
            "പണം കുറവായതു കാരണം അവർ വീട് വിറ്റു",
            // In the old town a farmer lived with his son: the town.
            "പഴയ പട്ടണത്തിൽ ഒരു കർഷകൻ തന്റെ മകനോടൊപ്പം ജീവിച്ചിരുന്നു",
            // That is a town.
            "അത് ഒരു പട്ടണമാണ്",
            // Her death saddened everyone.
            "അവളുടെ മരണം എല്ലാവരെയും ദുഃഖിപ്പിച്ചു",
            // In the rainy season the river overflowed, and the children played on its bank.
            "മഴക്കാലത്ത് പുഴ നിറഞ്ഞൊഴുകി, കുട്ടികൾ കരയിൽ കളിച്ചു",
            // All went well: "all" ends in -aam.
            "എല്ലാം ശരിയായി",
            // The children of the second class.
            "രണ്ടാം ക്ലാസിലെ കുട്ടികൾ",
            // Half a kilogram of rice; the program on the television.
            "അര കിലോഗ്രാം അരി",
            "ടിവിയിലെ പ്രോഗ്രാം",
            // That may be right; he might come.
            "അത് ശരിയാകാം",
            "അവൻ വന്നേക്കാം",
            // They overcame the crisis: tharanam before "do".
            "അവർ പ്രതിസന്ധി തരണം ചെയ്തു",
            // The distribution of food.
            "ഭക്ഷണ വിതരണം നടന്നു",
            ""
        )
        for (sentence in none) assertFalse(sentence, MalayalamTodoCues.holds(sentence))
    }

    @Test
    fun `the quoted clause runs from the break before the quote to the one after it, or over all of an unbroken segment`() {
        // In the evening, don't forget to buy medicine for mother.
        val segment = "വൈകുന്നേരം അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്."

        // The quote holds no cue; the verb after it does.
        assertFalse(MalayalamTodoCues.holds("അമ്മയ്ക്ക് മരുന്ന്"))
        assertEquals(
            "വൈകുന്നേരം അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്",
            MalayalamTodoCues.quotedClause("അമ്മയ്ക്ക് മരുന്ന്", segment)
        )
        // A good medicine for mother must be bought at the shop: the verb five words after the quote.
        val shop = "അമ്മയ്ക്ക് വേണ്ടി നല്ല ഒരു മരുന്ന് കടയിൽ നിന്ന് വാങ്ങണം"
        assertTrue(MalayalamTodoCues.holds(MalayalamTodoCues.quotedClause("അമ്മയ്ക്ക് വേണ്ടി നല്ല", shop)))
        // Tomorrow morning I must go to the bank, then they sang a song: the quote's clause is the second.
        val two = "നാളെ രാവിലെ ബാങ്കിൽ പോകണം, പിന്നെ അവർ പാട്ട് പാടി."
        assertEquals("പിന്നെ അവർ പാട്ട് പാടി", MalayalamTodoCues.quotedClause("അവർ പാട്ട്", two))
        assertFalse(MalayalamTodoCues.holds(MalayalamTodoCues.quotedClause("അവർ പാട്ട്", two)))
        // A quote over a break keeps both clauses: medicine must be bought, the doctor must be seen.
        assertEquals("മരുന്ന് വാങ്ങണം, ഡോക്ടറെ കാണണം", MalayalamTodoCues.quotedClause("വാങ്ങണം ഡോക്ടറെ", "മരുന്ന് വാങ്ങണം, ഡോക്ടറെ കാണണം."))
        // A quote the model changed counts alone, and no quote is nothing.
        assertEquals("മരുന്ന് വേഗം", MalayalamTodoCues.quotedClause("മരുന്ന് വേഗം", segment))
        assertEquals("", MalayalamTodoCues.quotedClause(null, segment))
    }

    @Test
    fun `an idea is put forward with would-be-good, if-only or a request, never with a time alone`() {
        // It would be good if there were a dark colour at night.
        assertTrue(MalayalamTodoCues.holdsIdea("രാത്രിയിൽ ഇരുണ്ട നിറം ഉണ്ടായിരുന്നെങ്കിൽ നന്നായിരിക്കും"))
        // A delete button is needed.
        assertTrue(MalayalamTodoCues.holdsIdea("ഒരു ഡിലീറ്റ് ബട്ടൺ വേണം"))
        // One could have added a search.
        assertTrue(MalayalamTodoCues.holdsIdea("ഒരു തിരച്ചിൽ ചേർക്കാമായിരുന്നു"))
        // Tomorrow morning: a time, no idea.
        assertFalse(MalayalamTodoCues.holdsIdea("നാളെ രാവിലെ"))
        // A mother waits for her son: a story's line, neither an idea nor a to-do.
        assertFalse(MalayalamTodoCues.holdsIdea("ഒരു അമ്മ തന്റെ മകനെ കാത്തിരിക്കുന്നു"))
        assertFalse(MalayalamTodoCues.holds("ഒരു അമ്മ തന്റെ മകനെ കാത്തിരിക്കുന്നു"))
    }

    @Test
    fun `the spellings of an old keyboard and its joiners read as a stored line`() {
        // Tomorrow I'll see the doctor, with a zero width joiner inside "tomorrow".
        assertTrue(MalayalamTodoCues.holds("നാ\u200Dളെ ഡോക്ടറെ കാണാം"))
        // The day after tomorrow I'll go, its last letter written the way of Unicode 5.0 (ള, virama, joiner).
        assertTrue(MalayalamTodoCues.holds("മറ്റന്നാള്\u200D പോകാം"))
        // I saw the doctor: told in the past, with no cue.
        assertFalse(MalayalamTodoCues.holds("ഡോക്ടറെ കണ്ടു"))
    }

    @Test
    fun `each cue is written as a stored line spells it, and each obligative ending ends on its anusvara`() {
        assertTrue(MalayalamTodoCues.ENDINGS.all { it.endsWith("ം") })
        val all = MalayalamTodoCues.WORDS + MalayalamTodoCues.ENDINGS + MalayalamTodoCues.TIMES + MalayalamTodoCues.TODAY +
            MalayalamTodoCues.PHRASES + MalayalamTodoCues.TIME_PHRASES + MalayalamTodoCues.IDEAS + MalayalamTodoCues.NOT_PROMISES +
            MalayalamTodoCues.NOT_PROMISE_ENDINGS + MalayalamTodoCues.NOT_OBLIGATIVE + listOf(MalayalamTodoCues.O_CLOCK, MalayalamTodoCues.PROMISE)
        for (cue in all) {
            assertFalse(cue.isBlank())
            assertEquals(cue, Normalizer.normalize(cue, Normalizer.Form.NFC))
            // An ending that starts with a vowel sign has no letter for it to sit on until it ends a word.
            val word = if (Character.getType(cue.first()).toByte() in MARKS) "ക$cue" else cue
            assertEquals(cue, MalayalamText.clean(word).removePrefix(word.removeSuffix(cue)))
        }
    }
}
