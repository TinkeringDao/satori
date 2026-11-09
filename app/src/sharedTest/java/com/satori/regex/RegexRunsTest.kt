package com.satori.regex

import com.satori.data.audio.AudioIndex
import com.satori.diagnostics.TodoAnswerFiles
import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.SpokenLanguage
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.InsightFormat
import com.satori.domain.usecase.llm.InsightOutputParser
import com.satori.domain.usecase.llm.PromptEcho
import com.satori.domain.usecase.llm.PromptRules
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.SessionTitles
import com.satori.domain.usecase.llm.WrongScript
import com.satori.domain.usecase.stt.SpeechVocabulary
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.TodoGate
import com.satori.domain.usecase.transcription.SearchSnippet
import com.satori.domain.usecase.transcription.TranscriptJoiner
import com.satori.presentation.mindmap.MindMapRules
import com.satori.ui.components.heardLanguage
import com.satori.ui.screens.LicenceParagraphs
import com.satori.ui.screens.offlineMarkdown
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Each class of the app's code that holds a regular expression, run once on a made-up input with
 * the answer its own unit tests give. The same class runs in the app's unit tests, on the JVM's
 * java.util.regex, and in its instrumented tests, on Android's regex engine, ICU (the release check,
 * docs/DEV_SETUP.md): an answer the two read differently fails one of them, and a pattern ICU
 * rejects fails its class's start on the emulator, as 0.7.3's PromptRules did on the phone
 * (2026-10-19). [IcuRegexTest] compiles every pattern of the app on ICU besides. Made-up text only.
 */
class RegexRunsTest {

    private fun segment(text: String, at: Long) = TranscriptionSegment("seg$at", "s1", text, at, isComplete = true)

    // A system prompt of the app's kind, made up: rules, a JSON line and a placeholder, the
    // pattern of the placeholder being the one 0.7.3 left a "}" bare in.
    private val prompt = "You write the notes of a voice note.\n" +
        "Plain statements, one idea per point. No filler words.\n" +
        "Keep names, numbers, dates and places.\n" +
        "Answer as JSON: {\"title\": \"...\", \"points\": [\"...\"]}\n" +
        "The notes so far: {noted}.\n"

    private val english = "We agreed to move the launch to March, and Anna will update the plan before Friday " +
        "so that the whole team can review it on Monday morning."
    private val german = "Also, der Umzug nach Bremen ist jetzt für den fünfzehnten November geplant. " +
        "Den Transporter muss ich noch diese Woche reservieren."

    // We go to the market this evening and buy vegetables, then to the bank (Malayalam, made up).
    private val malayalam = "ഇന്ന് വൈകുന്നേരം ചന്തയിൽ പോയി പച്ചക്കറി വാങ്ങണം, പിന്നെ ബാങ്കിൽ പോകണം, അതിനു ശേഷം വീട്ടിൽ വരണം."

    @Test
    fun `PromptRules and AnswerChecks hold a piece to the rules of its prompt`() {
        val rules = PromptRules.of(prompt)
        assertTrue(rules.echoes("Plain statements, one idea per point."))
        assertFalse(rules.echoes("Anna books the train to Bremen for Friday."))
        // A label's word read whole, umlaut and all (0.7.4's audit).
        assertTrue(PromptRules.PHRASES.echoes("Übersicht: Sprachnotizen."))

        val checks = AnswerChecks(rules = rules)
        assertEquals(AnswerDrop.RULES, checks.dropOf("No filler words."))
        assertNull(checks.dropOf("Anna books the train to Bremen for Friday."))
        assertEquals(AnswerDrop.OTHER_SCRIPT, checks.dropOf("ఇది తెలుగులో ఒక గమనిక"))
    }

    @Test
    fun `PromptEcho finds a point that repeats the instructions of its prompt`() {
        val lines = listOf("Read the Malayalam below and write in English.")
        assertTrue(PromptEcho.isEcho("Malayalam text read, written in English.", lines))
        assertFalse(PromptEcho.isEcho("Anna books the train to Bremen.", lines))
        // A label before a colon, read as ICU reads it: none after a joiner, a word's sign there (0.7.4).
        assertTrue(PromptEcho.isEcho("Note: Audio transcribed.", lines))
        assertFalse(PromptEcho.isEcho("\u200CNote: Audio transcribed.", lines))
    }

    @Test
    fun `WrongScript tells a piece in another script from a Latin one with a word of it`() {
        assertTrue(WrongScript.isWrong("ఇది తెలుగులో ఒక గమనిక"))
        assertFalse(WrongScript.isWrong("Call Anna about the rent"))
        assertFalse(WrongScript.isWrong("Ask അമ്മ about the rent on Friday"))
        assertEquals(setOf(Character.UnicodeScript.MALAYALAM), WrongScript.scriptsOf("ഇന്ന് okay"))
    }

    @Test
    fun `NoteRepeats leaves out a point that says an earlier one again`() {
        val (kept, dropped) = Internals.call(
            "com.satori.domain.usecase.llm.NoteRepeats",
            "withoutRepeats",
            listOf("Printer firmware is needed", "Printer firmware is essential", "The garden fence needs paint")
        ) as Pair<*, *>
        assertEquals(listOf("Printer firmware is needed", "The garden fence needs paint"), kept)
        assertEquals(1, dropped)
    }

    @Test
    fun `SessionTitles takes the label off a name`() {
        assertEquals("Bakery opening hours", SessionTitles.clean("Title: Bakery opening hours"))
        assertEquals("Umzug nach Bremen", SessionTitles.clean("Thema: Umzug nach Bremen"))
    }

    @Test
    fun `InsightOutputParser reads a note out of code fences`() {
        val answer = "```json\n{\"title\": \"Launch in March\", \"points\": [\"The launch moves to March.\"]}\n```"
        val parsed = InsightOutputParser.parse(answer, RecordingMode.SIMPLE_LISTENING, InsightFormat.Keys.ENGLISH)
        assertEquals("Launch in March", parsed.title)
        assertTrue(parsed.content, "The launch moves to March." in parsed.content)
        assertEquals("{\"a\": 1}", InsightOutputParser.stripCodeFences("```json\n{\"a\": 1}\n```"))
        assertEquals("Plan", InsightOutputParser.extractJsonField("{\"title\": \"Plan\"}", "title"))
    }

    @Test
    fun `TodoGate reads a key of the answer and a description of the text`() {
        assertTrue(TodoGate.namesField("- said: ബാങ്കിൽ പോകണം"))
        assertFalse(TodoGate.namesField("അമ്മ said: ബാങ്കിൽ പോകണം"))
        assertTrue(TodoGate.describesText("The speaker describes a trip to the coast"))
        assertFalse(TodoGate.describesText("Book the trip to the coast"))
    }

    @Test
    fun `ExtractTodosUseCase leaves the German infinitive out of the things of a to-do`() {
        val key = Internals.call("com.satori.domain.usecase.todo.TodoTexts", "key", "Beim Zahnarzt anrufen")!!
        val things = (Internals.callStatic("com.satori.domain.usecase.todo.ExtractTodosUseCaseKt", "thingsOf", key) as Set<*>).map { it.toString() }
        assertTrue(things.toString(), things.none { it.startsWith("anruf") })
        assertTrue(things.toString(), things.any { it.startsWith("zahnarzt") })
    }

    @Test
    fun `MalayalamTodoCues finds a request and a time and none in a story`() {
        val cues = "com.satori.domain.usecase.todo.MalayalamTodoCues"
        assertEquals(true, Internals.call(cues, "holds", "നാളെ ബാങ്കിൽ പോകണം"))
        assertEquals(false, Internals.call(cues, "holds", "ഇന്നലെ മഴ പെയ്തു"))
        assertEquals(true, Internals.call(cues, "holdsIdea", "ഒരു ഓപ്ഷൻ വേണം"))
        assertEquals("പിന്നെ ബാങ്കിൽ പോകണം", Internals.call(cues, "quotedClause", "ബാങ്കിൽ", malayalam))
    }

    @Test
    fun `TodoTexts cleans a to-do of its lead-in and hesitations and stutters and finds its time`() {
        val texts = "com.satori.domain.usecase.todo.TodoTexts"
        assertEquals("Call the bank", Internals.call(texts, "clean", "Okay, so I need to, uh, call the the bank on Friday", arrayOf<String?>("on Friday")))
        assertEquals(true, Internals.call(texts, "names", "Dentist at 3", "at 3"))
        assertEquals(false, Internals.call(texts, "names", "Dentist at 3:30", "at 3"))
        assertEquals(true, Internals.call(texts, "isDuplicate", "Ich muss noch die Bank anrufen", "Bank anrufen"))
    }

    @Test
    fun `ExplicitTodoSpotter spots a plan said outright`() {
        val spotter = "com.satori.domain.usecase.todo.ExplicitTodoSpotter"
        assertEquals(true, Internals.call(spotter, "isInfinitiveWord", "anrufen"))
        assertEquals(true, Internals.call(spotter, "isTalk", "say the hotel was lovely", false))
        val line = Internals.create(
            "com.satori.domain.usecase.todo.TodoPrompt\$Line",
            1, segment("I need to call the bank tomorrow.", 1_000L), "I need to call the bank tomorrow.",
            ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ZoneOffset.UTC), emptyList<Any>(), false
        )
        val window = Internals.create("com.satori.domain.usecase.todo.TodoPrompt\$Window", listOf(line))
        val spotted = Internals.call(spotter, "spot", window) as List<*>
        assertEquals(1, spotted.size)
        assertTrue(Internals.callOn(spotted.single()!!, "getText").toString().contains("bank"))
    }

    @Test
    fun `TodoGrounding finds a German to-do in the zu infinitive it was made from`() {
        val grounding = "com.satori.domain.usecase.todo.TodoGrounding"
        assertEquals(true, Internals.call(grounding, "isGrounded", "Einkaufen gehen", "Ich habe vergessen einzukaufen, ich muss noch gehen."))
        assertEquals(false, Internals.call(grounding, "isGrounded", "Den Hund füttern", "Ich habe vergessen einzukaufen."))
    }

    @Test
    fun `TodoPrompt and TodoAnswer and AnswerLog read a text and the ids of an answer and the tag of a call`() {
        assertEquals("Call the bank", Internals.call("com.satori.domain.usecase.todo.TodoPrompt", "text", "  Call   the\nbank  "))
        val items = Internals.call(
            "com.satori.domain.usecase.todo.TodoAnswer", "parse",
            "{\"todos\": [{\"segment\": \"S1, S2\", \"text\": \"Call the bank\", \"when\": \"none\"}]}"
        ) as List<*>
        assertEquals(listOf("S1", "S2"), Internals.callOn(items.single()!!, "getSegments"))
        assertTrue(AnswerLog.CALL.matches("notes-2"))
        assertFalse(AnswerLog.CALL.matches("notes 2"))
    }

    @Test
    fun `TranscriptJoiner joins a line cut mid-sentence`() {
        val joined = TranscriptJoiner.join(
            listOf(
                TranscriptJoiner.Line("a", "We agreed to move the launch to", AudioSpan(0, 2_000), stamp = 1),
                TranscriptJoiner.Line("b", "March and the plan goes out on Friday.", AudioSpan(2_200, 5_000), stamp = 2)
            ),
            maxSpeechMs = 30_000,
            language = "en"
        )
        assertEquals(1, joined.groups.size)
        assertEquals("We agreed to move the launch to March and the plan goes out on Friday.", joined.groups.single().text)
    }

    @Test
    fun `MindMapRules finds a theme whatever its accents and umlauts`() {
        val theme = MindNodeItem(MindNode("t1", MindNodeKind.THEME, "Müller und Söhne", "muller", createdAt = 0, updatedAt = 0), 1, 0)
        assertEquals(listOf(theme), MindMapRules.searchThemes(listOf(theme), emptyMap(), "mueller"))
        assertEquals(listOf(theme), MindMapRules.searchThemes(listOf(theme), emptyMap(), "SÖHNE"))
    }

    @Test
    fun `SearchSnippet shows what was found without its Markdown`() {
        assertEquals("Launch moves to March", SearchSnippet.plain("- **Launch** moves to `March`"))
        assertTrue(SearchSnippet.of("We moved the launch to March after the review.", "launch").contains("launch"))
    }

    @Test
    fun `LanguageDetector and SpokenLanguage tell English and German and Malayalam apart`() {
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect(english))
        assertEquals(LanguageDetector.GERMAN, LanguageDetector.detect(german))
        assertEquals("ml", LanguageDetector.detect(malayalam))
        assertEquals(LanguageDetector.ENGLISH, SpokenLanguage.of(listOf(segment(english, 1_000L), segment(english, 2_000L))))
        assertEquals(LanguageDetector.GERMAN, heardLanguage(listOf(segment(german, 1_000L))))
    }

    @Test
    fun `the helpers of the mind map read topics and words and lines and a summary`() {
        val mind = "com.satori.domain.usecase.mindmap"
        assertEquals("Launch in March", Internals.call("$mind.MindTopics", "label", "- Launch in March"))
        assertEquals(listOf("uberblick", "uber", "plane"), Internals.call("$mind.MindWords", "words", "Überblick über Pläne"))
        assertEquals("one line", Internals.call("$mind.MindText", "oneLine", "one\n   line "))
        assertEquals(
            "The launch moves to March.",
            Internals.call("$mind.ThemeSummaryPrompt", "parse", "{\"summary\": \"**The launch** moves to March.\"}")
        )
    }

    @Test
    fun `the use cases count the words of what was said`() {
        assertEquals(listOf("Anna", "Bremen", "Köln"), SpeechVocabulary.clean("Anna, Bremen;\nKöln"))
        val notes = listOf(LlmInsight("n1", "s1", "Launch", "The launch moves to March.", timestamp = 1, sourceSegmentIds = emptyList()))
        assertEquals("- Launch: The launch moves to March.", GenerateSessionTitleUseCase.titleInput(listOf(segment(english, 1_000L)), notes))
        assertFalse(RewriteNotesUseCase.hasEnoughText(listOf(segment("Too short.", 1_000L))))
        assertEquals(4, Internals.callStatic("com.satori.domain.usecase.sync.SyncSttLlmUseCaseKt", "wordsIn", " four words  said here "))
        assertEquals(
            false,
            Internals.callStatic("com.satori.presentation.sessiondetails.SessionDetailsUiStateKt", "holdsWordsForNotes", listOf(segment("Two words", 1_000L)))
        )
        // Too few words for notes: the use cases answer before they ask the model anything.
        val final = GenerateFinalInsightUseCase(unused<LlmRepository>(), unused<SettingsRepository>(), unused<ResourceProvider>())
        assertNull(runBlocking { final("Two words", "s1", RecordingMode.SIMPLE_LISTENING, "en") }.getOrThrow())
        val batch = GenerateBatchInsightUseCase(unused<LlmRepository>(), unused<SettingsRepository>(), unused<ResourceProvider>())
        assertNull(runBlocking { batch(listOf(segment("Two words", 1_000L)), "s1", RecordingMode.LONG_MEETING, "en") }.getOrThrow())
    }

    @Test
    fun `the audio index and the memory floor and the tensors of the native library read their figures`() {
        val entries = AudioIndex.parse("run 1000  0\nline seg1 0 2000\nkeep 1\n")
        assertEquals(1, entries.runs.size)
        assertEquals(AudioSpan(0, 2_000), entries.lines["seg1"])
        assertTrue(entries.kept)

        val zoneinfo = "Node 0, zone   Normal\n  pages free     1000\n        boost    0\n        min      256\n" +
            "        low      512\n        high     768\n        managed  262144\n"
        val watermarks = Internals.callOn(Internals.field("com.satori.data.monitor.KernelWatermarks", "Companion")!!, "parse", zoneinfo, 4_096L)!!
        assertEquals(2L, Internals.callOn(watermarks, "getLowMb"))
        assertEquals(3L, Internals.callOn(watermarks, "getHighMb"))
        assertTrue((Internals.field("com.satori.data.audio.RecordingAudioStore", "SAFE_ID") as Regex).matches("4f2a-91c0"))
        assertEquals(
            "ENOENT",
            (Internals.field("com.satori.data.monitor.MemoryFloor", "ERRNO") as Regex).find("open failed: ENOENT (No such file)")?.groupValues?.get(1)
        )

        val tensor = { name: String, offset: Long -> Internals.create("com.arm.aichat.internal.GgufTensors\$Tensor", name, offset, 100L) }
        val ranges = Internals.call(
            "com.arm.aichat.internal.InPlaceTensors", "ranges",
            listOf(tensor("blk.0.attn_q.weight", 0L), tensor("output_norm.weight", 100L)), true
        )
        assertEquals(listOf(100L until 200L), ranges)
    }

    @Test
    fun `the text helpers of the app turn links into text and join lines and find the answers of a call`() {
        assertEquals("logo, see the docs", offlineMarkdown("![logo](https://example.org/a.png), see [the docs](https://example.org)"))
        assertEquals("One line that goes on\n- an item", LicenceParagraphs.of("One line\nthat goes on\n- an item"))

        val dir = Files.createTempDirectory("answers").toFile()
        try {
            val files = TodoAnswerFiles(dir) { _, _ -> }
            files.answer("4f2a-91c0", "notes-1", "an answer")
            files.answer("4f2a-91c0", "name", "a name")
            files.todoAnswer("4f2a-91c0", 2, "{}")
            files.clear("4f2a-91c0", "notes")
            assertEquals(setOf("4f2a-91c0-name.txt", "4f2a-91c0-2.json"), dir.list()!!.toSet())
            files.forget("4f2a-91c0")
            assertTrue(dir.list()!!.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the debug tools read the id of a rerun and the manifest of a test server`() {
        // Debug code, which the phone's build has: not in a release build's unit tests.
        val args = try {
            Class.forName("com.satori.ui.debug.DebugRerun\$Args")
        } catch (e: ClassNotFoundException) {
            null
        }
        assumeTrue("a debug build's own", args != null)
        val of = { extras: Map<String, String?> -> Internals.callOn(Internals.field(args!!.name, "Companion")!!, "of", extras)!! }
        assertEquals("Rerun", of(mapOf("session" to "4f2a-91c0", "step" to "notes")).javaClass.simpleName)
        assertEquals("Wrong", of(mapOf("session" to "../4f2a", "step" to "notes")).javaClass.simpleName)
        val server = Internals.callOn(
            Internals.field("com.satori.data.config.ModelSource\$TestServer", "Companion")!!, "parse",
            "https://example.org/models", "${"ab".repeat(32)} 1200  stt/encoder.onnx\n# a comment\n"
        )!!
        assertEquals(setOf("stt/encoder.onnx"), (Internals.callOn(server, "getFiles") as Map<*, *>).keys)
    }

    /** An interface no call of the run reaches. */
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("not used by this run: ${method.name}")
        } as T

}
