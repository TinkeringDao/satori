package com.satori.domain.usecase.transcription

import com.satori.domain.model.AudioSpan
import com.satori.domain.model.VadParameters
import com.satori.domain.usecase.transcription.TranscriptJoiner.Line
import com.satori.domain.usecase.transcription.TranscriptJoiner.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJoinerTest {

    /** The default of Advanced settings, at which no line runs to the maximum within 20 s. */
    private val maxSpeech = 20_000L

    private fun line(id: String, text: String, from: Long, to: Long, citedBy: Set<String> = emptySet(), capture: Int? = null) =
        Line(id, text, AudioSpan(from, to), stamp = to + 500, citedBy = citedBy, capture = capture)

    private fun join(vararg lines: Line, maxSpeechMs: Long = maxSpeech, language: String? = null) =
        TranscriptJoiner.join(lines.toList(), maxSpeechMs, language)

    private fun texts(vararg lines: Line, maxSpeechMs: Long = maxSpeech, language: String? = null) =
        join(*lines, maxSpeechMs = maxSpeechMs, language = language).groups.map { it.text }

    private fun reasonOf(vararg lines: Line, maxSpeechMs: Long = maxSpeech, language: String? = null): Reason? =
        join(*lines, maxSpeechMs = maxSpeechMs, language = language).reasons.entries.singleOrNull { it.value > 0 }?.key

    // ========== The signals ==========

    @Test
    fun `a line the recognizer left open goes on in the next, whose small word loses its capital`() {
        val a = line("a", "We could take the train", 0, 2_000)
        val b = line("b", "On Sunday morning.", 2_400, 3_600)

        assertEquals(listOf("We could take the train on Sunday morning."), texts(a, b))
        assertEquals(Reason.OPEN, reasonOf(a, b))
    }

    @Test
    fun `a line that starts small goes on from the one before, which loses its full stop`() {
        val a = line("a", "The garden needs water.", 0, 1_800)
        val b = line("b", "because it has not rained for weeks.", 2_300, 4_400)

        assertEquals(listOf("The garden needs water because it has not rained for weeks."), texts(a, b))
        assertEquals(Reason.LOWERCASE, reasonOf(a, b))
    }

    @Test
    fun `a line that ends on a word no sentence ends on goes on`() {
        val a = line("a", "The bikes are in the shed and.", 0, 2_000)
        val b = line("b", "The car is outside.", 2_500, 3_700)

        assertEquals(listOf("The bikes are in the shed and the car is outside."), texts(a, b))
        assertEquals(Reason.DANGLING, reasonOf(a, b))
        // German: "des" is only ever an article
        assertEquals(
            listOf("Das ist der Plan des Vereins für den Sommer."),
            texts(line("c", "Das ist der Plan des.", 0, 1_500), line("d", "Vereins für den Sommer.", 1_900, 3_200))
        )
    }

    @Test
    fun `a sentence that ends on a number doesn't end on a word that dangles`() {
        assertEquals(2, join(line("a", "I have the dentist at 3.", 0, 1_500), line("b", "Then I pick up the kids.", 1_900, 3_300)).groups.size)
        assertEquals(2, join(line("a", "We want it done by 2027.", 0, 1_500), line("b", "That is the plan.", 1_900, 3_300)).groups.size)
        assertEquals(2, join(line("a", "We read chapter 2 of 5.", 0, 1_500), line("b", "The rest is next week.", 1_900, 3_300)).groups.size)
        assertEquals(
            2,
            join(line("a", "Der Termin ist am 5.", 0, 1_500), line("b", "Dann fahren wir los.", 1_900, 3_300), language = "de").groups.size
        )
    }

    @Test
    fun `the article an dangles only in English and am only in German, as each ends sentences in the other`() {
        val particle = line("a", "Ich rufe dich morgen an.", 0, 1_500)
        val afterParticle = line("b", "Dann gehe ich einkaufen.", 1_900, 3_300)
        val verb = line("c", "Yes, I am.", 0, 900)
        val afterVerb = line("d", "The plan is ready.", 1_300, 2_500)
        val article = line("e", "We still have to buy an.", 0, 1_500)
        val noun = line("f", "Umbrella for the trip.", 1_900, 3_300)
        val contraction = line("g", "Wir treffen uns am.", 0, 1_500)
        val day = line("h", "Montag um neun.", 1_900, 3_000)

        assertEquals(2, join(particle, afterParticle, language = "de").groups.size)
        assertEquals(2, join(verb, afterVerb, language = "en").groups.size)
        assertEquals(Reason.DANGLING, reasonOf(article, noun, language = "en"))
        assertEquals(listOf("Wir treffen uns am Montag um neun."), texts(contraction, day, language = "de"))
        // A transcript in both languages, or too short to tell, counts neither.
        assertEquals(2, join(article, noun).groups.size)
        assertEquals(2, join(contraction, day).groups.size)
    }

    @Test
    fun `a line cut at the longest stretch of speech goes on, a shorter one doesn't`() {
        val cut = line("a", "We talked about the plans for the new kitchen and the cupboards.", 0, 9_800)
        val next = line("b", "They come next week.", 10_100, 11_600)

        assertEquals(
            listOf("We talked about the plans for the new kitchen and the cupboards they come next week."),
            texts(cut, next, maxSpeechMs = 10_000)
        )
        assertEquals(Reason.FORCED, reasonOf(cut, next, maxSpeechMs = 10_000))

        val paused = line("a", "We talked about the plans for the new kitchen and the cupboards.", 0, 9_600)
        assertEquals(2, join(paused, next, maxSpeechMs = 10_000).groups.size)
    }

    @Test
    fun `the reading of a cut needs the line's place in the audio`() {
        val unplaced = Line("a", "We talked about the plans for the new kitchen.", span = null, stamp = 20_000)
        val next = Line("b", "They come next week.", span = null, stamp = 21_500)

        assertEquals(2, join(unplaced, next, maxSpeechMs = 3_000).groups.size)
    }

    @Test
    fun `a line that starts with a word carrying the sentence on goes on`() {
        val a = line("a", "We bought the blue one.", 0, 1_500)
        val b = line("b", "Which was cheaper than the red one.", 1_900, 3_400)

        assertEquals(listOf("We bought the blue one which was cheaper than the red one."), texts(a, b))
        assertEquals(Reason.CONTINUER, reasonOf(a, b))
        val home = line("c", "Ich bleibe heute zu Hause.", 0, 1_400)
        val rain = line("d", "Weil es draußen regnet.", 1_800, 3_300)
        assertEquals(listOf("Ich bleibe heute zu Hause weil es draußen regnet."), texts(home, rain, language = "de"))
        assertEquals(Reason.CONTINUER, reasonOf(home, rain, language = "de"))
    }

    @Test
    fun `a question of its own stays apart though it starts with such a word`() {
        val a = line("a", "We have two bikes.", 0, 1_400)
        val b = line("b", "Which one do you want?", 1_800, 3_000)

        assertEquals(2, join(a, b).groups.size)
    }

    @Test
    fun `a line cut at the longest stretch is read by its own length, not by the lines joined before it`() {
        val start = line("a", "We talked about the plans for the new kitchen", 0, 5_000)
        val end = line("b", "and the cupboards.", 5_300, 10_000)
        val next = line("c", "They come next week.", 10_300, 11_800)

        // The joined line runs to 10 s, but the detector cut neither of its lines there.
        assertEquals(2, join(start, end, next, maxSpeechMs = 10_000).groups.size)
    }

    @Test
    fun `a line joined before is read by its last piece`() {
        val joinedBefore = Line(
            "a", "We talked about the plans for the new kitchen and the cupboards they come next week.",
            span = AudioSpan(0, 11_600), stamp = 12_100, lastPiece = AudioSpan(10_100, 11_600)
        )
        val next = line("c", "The shop is open on Monday.", 11_900, 13_000)
        val cutAtTheEnd = joinedBefore.copy(span = AudioSpan(0, 13_900), lastPiece = AudioSpan(4_100, 13_900))
        val afterTheCut = line("c", "They come next week.", 14_200, 15_500)

        assertEquals(2, join(joinedBefore, next, maxSpeechMs = 10_000).groups.size)
        assertEquals(Reason.FORCED, reasonOf(cutAtTheEnd, afterTheCut, maxSpeechMs = 10_000))
    }

    @Test
    fun `the first signal that holds is the one counted`() {
        val a = line("a", "We could take the train", 0, 2_000)
        val b = line("b", "on Sunday morning.", 2_400, 3_600)

        assertEquals(Reason.OPEN, reasonOf(a, b))
    }

    // ========== What keeps lines apart ==========

    @Test
    fun `a pause over one and a half seconds ends the sentence`() {
        val a = line("a", "We could take the train", 0, 2_000)

        assertEquals(1, join(a, line("b", "on Sunday morning.", 3_500, 4_700)).groups.size)
        assertEquals(2, join(a, line("b", "on Sunday morning.", 3_501, 4_700)).groups.size)
    }

    @Test
    fun `the pause is the one after the last line joined, not after the first`() {
        val a = line("a", "We could take the train", 0, 2_000)
        val b = line("b", "on Sunday", 2_300, 3_300)
        // 1.6 s after the first line ended, 0.3 s after the second
        val c = line("c", "or on Monday.", 3_600, 4_400)

        assertEquals(listOf("We could take the train on Sunday or on Monday."), texts(a, b, c))
    }

    @Test
    fun `lines of two captures stay apart, however close their places`() {
        // The recording stopped on an error after the first line and was started again.
        val before = line("a", "We need to buy", 8_000, 9_900, capture = 0)
        val after = line("b", "okay where was I.", 10_700, 12_000, capture = 1)

        assertEquals(2, join(before, after).groups.size)
        assertEquals(1, join(before, after.copy(capture = 0)).groups.size)
    }

    @Test
    fun `a line said before the one stamped ahead of it stays apart`() {
        // The clock was set back: the later stamp went to the line said first.
        val a = Line("a", "We could take the train", AudioSpan(5_000, 7_000), stamp = 7_500)
        val b = Line("b", "on Sunday morning.", AudioSpan(1_000, 2_500), stamp = 8_000)
        // A line within the place of the one before is one of its pieces, as a death between the
        // writes of a join leaves them.
        val joinedPlace = Line("c", "We could take the train", AudioSpan(0, 3_600), stamp = 2_500)
        val piece = Line("d", "On Sunday morning.", AudioSpan(2_400, 3_600), stamp = 4_100)

        assertEquals(2, join(a, b).groups.size)
        assertEquals(listOf(AudioSpan(0, 3_600)), join(joinedPlace, piece).groups.map { it.span })
    }

    @Test
    fun `a joined line stays within twenty seconds and four hundred characters`() {
        val a = line("a", "We could take the train", 0, 12_000)

        assertEquals(1, join(a, line("b", "on Sunday morning.", 12_500, 20_000)).groups.size)
        assertEquals(2, join(a, line("b", "on Sunday morning.", 12_500, 20_001)).groups.size)

        val long = line("c", "and" + " word".repeat(76), 0, 9_000)
        val tail = line("d", "and it ends here", 9_400, 11_000)
        assertEquals(400, "${long.text} ${tail.text}".length)
        assertEquals(1, join(long, tail).groups.size)
        assertEquals(2, join(long, tail.copy(text = "and it ends here.")).groups.size)
    }

    @Test
    fun `without places the length is the time between the stamps and the time it took to say the first line`() {
        // 309 characters, about 15.5 s to say, then 79 more given out 4.5 s or 5 s later
        val first = Line("a", "word ".repeat(62).trim(), span = null, stamp = 10_000)
        val second = Line("b", "and" + " more".repeat(15) + ".", span = null, stamp = 14_500)

        assertEquals(1, TranscriptJoiner.join(listOf(first, second), maxSpeech).groups.size)
        assertEquals(2, TranscriptJoiner.join(listOf(first, second.copy(stamp = 15_000)), maxSpeech).groups.size)
    }

    @Test
    fun `a question or an exclamation ends the sentence for sure`() {
        assertEquals(
            2,
            join(line("a", "Do we need milk?", 0, 1_200), line("b", "and eggs as well.", 1_500, 2_700)).groups.size
        )
        assertEquals(
            2,
            join(line("a", "That was great!", 0, 1_200), line("b", "and then we went home.", 1_500, 2_900)).groups.size
        )
    }

    @Test
    fun `a sentence ends at its mark inside closing quotes and brackets`() {
        assertEquals(
            2,
            join(line("a", "Then she asked \"which one?\"", 0, 1_500), line("b", "and nobody answered.", 1_900, 3_000)).groups.size
        )
        assertEquals(
            2,
            join(line("a", "He said (we are done.)", 0, 1_500), line("b", "The meeting ended there.", 1_900, 3_000)).groups.size
        )
    }

    @Test
    fun `lines join only when the same notes cite them`() {
        val a = line("a", "We could take the train", 0, 2_000, citedBy = setOf("note1"))

        assertEquals(2, join(a, line("b", "on Sunday morning.", 2_400, 3_600)).groups.size)
        assertEquals(2, join(a, line("b", "on Sunday morning.", 2_400, 3_600, citedBy = setOf("note2"))).groups.size)
        assertEquals(1, join(a, line("b", "on Sunday morning.", 2_400, 3_600, citedBy = setOf("note1"))).groups.size)
    }

    @Test
    fun `a sentence that starts after a full stop stays apart`() {
        val a = line("a", "The screen was very bright.", 0, 1_600)
        val b = line("b", "That should not happen at night.", 2_000, 3_600)

        assertEquals(2, join(a, b).groups.size)
    }

    @Test
    fun `a filler with a capital starts a sentence, a small one goes on`() {
        val a = line("a", "We went to the market.", 0, 1_600)

        assertEquals(2, join(a, line("b", "Um and then we went home.", 2_000, 3_600)).groups.size)
        assertEquals(
            listOf("We went to the market um and then we went home."),
            texts(a, line("b", "um and then we went home.", 2_000, 3_600))
        )
    }

    @Test
    fun `a name keeps its capital`() {
        val a = line("a", "Tomorrow I will meet", 0, 1_500)

        assertEquals(listOf("Tomorrow I will meet Clara at the station."), texts(a, line("b", "Clara at the station.", 1_900, 3_300)))
        assertEquals(listOf("Tomorrow I will meet I think at noon."), texts(a, line("b", "I think at noon.", 1_900, 3_300)))
        // A word written in capitals is a short form, though written small it would be a common one.
        assertEquals(
            listOf("We asked the IT team for help."),
            texts(line("c", "We asked the", 0, 1_000), line("d", "IT team for help.", 1_300, 2_500))
        )
    }

    @Test
    fun `a capital A at the end is a letter or a grade, not an article`() {
        val a = line("a", "We got an A.", 0, 1_000)
        val b = line("b", "The teacher was happy.", 1_400, 2_600)

        assertEquals(2, join(a, b).groups.size)
    }

    @Test
    fun `a German sentence may end on a pronoun that is also an article`() {
        val a = line("a", "Ich weiß das.", 0, 1_000)
        val b = line("b", "Morgen kommt sie.", 1_400, 2_400)

        assertEquals(2, join(a, b).groups.size)
    }

    @Test
    fun `a line without a word joins nothing`() {
        val a = line("a", "We need", 0, 1_000)

        assertEquals(2, join(a, line("b", ".", 1_300, 1_600)).groups.size)
        assertEquals(2, join(line("b", ".", 0, 300), line("c", "and some bread.", 700, 1_800)).groups.size)
    }

    @Test
    fun `an ellipsis stays where the speaker trailed off`() {
        val a = line("a", "I was thinking...", 0, 1_200)
        val b = line("b", "and then it rained.", 1_600, 2_900)

        assertEquals(listOf("I was thinking... and then it rained."), texts(a, b))
    }

    @Test
    fun `without places the pause is the time between the stamps less the time it took to say the line`() {
        val a = Line("a", "We could take the train", span = null, stamp = 10_000)
        // 18 characters, about 0.9 s to say: a pause of 1.1 s, then 1.6 s
        val soon = Line("b", "on Sunday morning.", span = null, stamp = 12_000)
        val late = Line("b", "on Sunday morning.", span = null, stamp = 12_500)

        assertEquals(1, TranscriptJoiner.join(listOf(a, soon), maxSpeech).groups.size)
        assertEquals(2, TranscriptJoiner.join(listOf(a, late), maxSpeech).groups.size)
    }

    // ========== Groups ==========

    @Test
    fun `a joined line has the first line's id, the last one's stamp and the place of both`() {
        val a = line("a", "We could take the train", 1_000, 3_000)
        val b = line("b", "on Sunday", 3_300, 4_100)
        val c = line("c", "or on Monday.", 4_400, 5_200)
        val d = line("d", "The tickets are cheap.", 5_600, 7_000)

        val joined = join(a, b, c, d)

        assertEquals(listOf("a", "d"), joined.groups.map { it.id })
        assertEquals(listOf("We could take the train on Sunday or on Monday.", "The tickets are cheap."), joined.groups.map { it.text })
        assertEquals(listOf(c.stamp, d.stamp), joined.groups.map { it.stamp })
        assertEquals(listOf(AudioSpan(1_000, 5_200), AudioSpan(5_600, 7_000)), joined.groups.map { it.span })
        assertEquals(mapOf("a" to "a", "b" to "a", "c" to "a", "d" to "d"), joined.groupIds())
        assertEquals(2, joined.joinedAway)
    }

    @Test
    fun `a line that stays alone keeps its text as it was`() {
        val alone = line("a", "  The tickets are cheap.  ", 0, 1_500)

        assertEquals("  The tickets are cheap.  ", join(alone).groups.single().text)
    }

    @Test
    fun `the counts read as the log line`() {
        val joined = join(
            line("a", "We could take the train", 0, 2_000),
            line("b", "on Sunday morning.", 2_400, 3_600),
            line("c", "The tickets are cheap.", 4_000, 5_400),
            line("d", "because it is not the season.", 5_800, 7_500),
            line("e", "We will see.", 9_500, 10_400)
        )

        assertEquals("Lines 5->3 (open 1, lowercase 1, dangling 0, forced 0, continuer 0)", joined.summary())
    }

    @Test
    fun `the longest stretch of speech is read in milliseconds`() {
        assertEquals(20_000L, TranscriptJoiner.maxSpeechMsOf(VadParameters.FILE))
    }

    // ========== Malayalam ==========

    @Test
    fun `a Malayalam line has no marks, which say nothing, so a pause keeps it apart from the next`() {
        // Made up: "the room is booked for Friday" / "the agenda goes to the team today".
        val a = line("a", "വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്തു", 0, 2_000)
        val b = line("b", "അജണ്ട ഇന്ന് ടീമിന് അയക്കും", 2_400, 4_000)

        assertEquals(2, join(a, b).groups.size)
        assertEquals(2, join(a, b, language = "ml").groups.size)
    }

    @Test
    fun `in a recording switched between the languages, its English lines are joined as before`() {
        val malayalam = line("a", "വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്തു", 0, 2_000)
        val open = line("b", "We could take the train", 2_400, 4_400)
        val rest = line("c", "On Sunday morning.", 4_800, 6_000)

        assertEquals(
            listOf("വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്തു", "We could take the train on Sunday morning."),
            texts(malayalam, open, rest)
        )
    }

    @Test
    fun `a Malayalam line the voice detector cut at its longest stretch goes on in the next`() {
        val cut = line("a", "വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്തു എന്നിട്ട്", 0, 9_900)
        val next = line("b", "അജണ്ട ഇന്ന് ടീമിന് അയക്കും", 10_200, 11_800)

        assertEquals(
            listOf("വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്തു എന്നിട്ട് അജണ്ട ഇന്ന് ടീമിന് അയക്കും"),
            texts(cut, next, maxSpeechMs = 10_000, language = "ml")
        )
        assertEquals(Reason.FORCED, reasonOf(cut, next, maxSpeechMs = 10_000, language = "ml"))
    }
}
