package com.satori.ui.debug

import android.content.Context
import android.content.ContextWrapper
import com.satori.data.config.ReaderModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.datasource.PinnedFileHash
import com.satori.data.monitor.Purge
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.model.ReaderModelId
import com.satori.domain.reading.PieceIds
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.feature.reader.LineReading
import com.satori.feature.reader.LoadedReader
import com.satori.feature.reader.LoadedTokenizer
import com.satori.feature.reader.PartReading
import com.satori.feature.reader.Prepacking
import com.satori.feature.reader.ReaderArm
import com.satori.feature.reader.ReaderLimits
import com.satori.feature.reader.ReaderYieldedException
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.reader.ReaderDims
import com.satori.reader.Reading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The reader's debug tools: their arguments, their files' formats, the tokenizer selftest's and
 * the reader selftest's rows, the memory figures V1 compares, the rules they run under (the
 * memtest's), and the download's refusals, stops and last check. The tokenizer, the reader and the
 * download are fakes; the text pipeline is the real one.
 */
class DebugReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val source = mapOf("<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3, "eng_Latn" to 4, "mal_Mlym" to 13, "▁a" to 20, "▁b" to 21)
    private val target = mapOf("<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3, "▁Hello" to 10, "▁world" to 11)
    private val ids = PieceIds(source, target)

    private val tokenizer = object : LoadedTokenizer {
        override val name = "test"
        override val pieceIds = ids
        override fun pieces(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" }
    }

    // --- Arguments ---

    @Test
    fun `the selftests' arguments have the design's defaults`() {
        val reader = DebugReaderArgs.reader(emptyMap()) as DebugReaderArgs.Reader
        assertEquals(ReaderModelId.INT8, reader.model)
        assertEquals("flores200_devtest.mal.txt", reader.input)
        assertEquals(ReaderArm.DEFAULT, reader.arm)
        assertEquals(4, reader.beam)
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4.tsv", reader.out)
        assertEquals("prepack=session,arena=on,threads=4, beam 4", reader.label)
        assertNull(reader.lines)

        val tokenizer = DebugReaderArgs.tokenizer(emptyMap()) as DebugReaderArgs.Tokenizer
        assertEquals("v0b_inputs.tsv", tokenizer.input)
        assertEquals("v0b-int8.tsv", tokenizer.out)
        assertEquals(DebugReaderArgs.Download(ReaderModelId.INT8, mobile = false), DebugReaderArgs.download(emptyMap()))
    }

    @Test
    fun `each option is taken, and a wrong one says why`() {
        val reader = DebugReaderArgs.reader(
            mapOf("variant" to "fp16", "arm" to "prepack=all,threads=2", "beam" to "1", "worstcase" to "true", "cold" to "true",
                "firststep" to "true", "lines" to "20", "input" to "smoke.mal.txt")
        ) as DebugReaderArgs.Reader
        assertEquals(ReaderModelId.FP16, reader.model)
        assertEquals(ReaderArm(Prepacking.ALL, threads = 2), reader.arm)
        assertEquals(1, reader.beam)
        assertTrue(reader.worstCase && reader.cold && reader.firstStep)
        assertEquals(20, reader.lines)
        assertEquals("smoke.mal.txt", reader.input)
        assertEquals("readings-fp16-prepack-all-arena-on-threads-2-beam1-worstcase-firststep-lines20-smoke-cold.tsv", reader.out)
        assertEquals("mine.tsv", (DebugReaderArgs.reader(mapOf("out" to "mine.tsv", "cold" to "true")) as DebugReaderArgs.Reader).out)
        assertEquals(DebugReaderArgs.Download(ReaderModelId.INT8, mobile = true), DebugReaderArgs.download(mapOf("mobile" to "true")))
        val toy = DebugReaderArgs.reader(mapOf("dir" to "toy", "input" to "smoke.mal.txt")) as DebugReaderArgs.Reader
        assertEquals("toy", toy.dir)
        assertEquals("readings-toy-prepack-session-arena-on-threads-4-beam4-smoke.tsv", toy.out)
        assertEquals(DebugReaderArgs.Tokenizer(ReaderModelId.INT8, "v0b_inputs.tsv", "v0b-toy.tsv", "toy"), DebugReaderArgs.tokenizer(mapOf("dir" to "toy")))

        assertEquals(DebugReaderArgs.Wrong("no variant int4, only int8 or fp16"), DebugReaderArgs.reader(mapOf("variant" to "int4")))
        assertEquals(DebugReaderArgs.Wrong("beam takes 1-8, not 9"), DebugReaderArgs.reader(mapOf("beam" to "9")))
        assertEquals(DebugReaderArgs.Wrong("lines takes a count, not 0"), DebugReaderArgs.reader(mapOf("lines" to "0")))
        assertEquals(DebugReaderArgs.Wrong("cold takes true or false"), DebugReaderArgs.reader(mapOf("cold" to "yes")))
        assertEquals(DebugReaderArgs.Wrong("arena takes on or off, not 1"), DebugReaderArgs.reader(mapOf("arm" to "arena=1")))
        assertEquals(DebugReaderArgs.Wrong("mobile takes true or false"), DebugReaderArgs.download(mapOf("mobile" to "1")))
    }

    @Test
    fun `a stop of the run's own is stopafter or stopload in ms, never both, and in its label and name`() {
        // As memtest.sh gives them: --ei, cold.
        val after = DebugReaderArgs.reader(mapOf("stopafter" to "2000", "cold" to "true")) as DebugReaderArgs.Reader
        val load = DebugReaderArgs.reader(mapOf("stopload" to "300")) as DebugReaderArgs.Reader
        val atOnce = DebugReaderArgs.reader(mapOf("stopafter" to "0")) as DebugReaderArgs.Reader

        assertEquals(ReaderStop(ReaderStop.Kind.AFTER, 2_000), after.stop)
        assertEquals("prepack=session,arena=on,threads=4, beam 4, cold, stopafter 2000", after.label)
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-stopafter2000-cold.tsv", after.out)
        assertEquals(ReaderStop(ReaderStop.Kind.LOAD, 300), load.stop)
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-stopload300.tsv", load.out)
        assertEquals(ReaderStop(ReaderStop.Kind.AFTER, 0), atOnce.stop)
        assertNull((DebugReaderArgs.reader(emptyMap()) as DebugReaderArgs.Reader).stop)

        assertEquals(DebugReaderArgs.Wrong("stopafter and stopload don't go together"), DebugReaderArgs.reader(mapOf("stopafter" to "1", "stopload" to "1")))
        assertEquals(DebugReaderArgs.Wrong("stopafter takes a number of ms, not -5"), DebugReaderArgs.reader(mapOf("stopafter" to "-5")))
        assertEquals(DebugReaderArgs.Wrong("stopload takes a number of ms, not soon"), DebugReaderArgs.reader(mapOf("stopload" to "soon")))
    }

    @Test
    fun `runs that differ in a setting never write to one output name`() {
        // memtest.sh's V1 and V2 runs, cold as it runs them, and the ones by hand beside them.
        val runs = listOf(
            emptyMap(),
            mapOf("firststep" to "true", "lines" to "200"),
            mapOf("firststep" to "true"),
            mapOf("lines" to "200"),
            mapOf("worstcase" to "true"),
            mapOf("input" to "smoke.mal.txt"),
            mapOf("input" to "smoke.noise.mal.txt"),
            mapOf("input" to "v2b.mal.txt"),
            mapOf("input" to "v2b.noise.mal.txt"),
            mapOf("arm" to "prepack=all"),
            mapOf("beam" to "1"),
            mapOf("stopafter" to "2000"),
            mapOf("stopload" to "2000")
        ).flatMap { run -> listOf(run + ("cold" to "true"), run) }
        val names = runs.map { (DebugReaderArgs.reader(it) as DebugReaderArgs.Reader).out }

        assertEquals(names.toString(), runs.size, names.toSet().size)
        // V2's file keeps its name: the one the phone steps pull and score.py reads.
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-cold.tsv", names[0])
    }

    @Test
    fun `an input's part of the name is its name without txt and mal, its other points dashes`() {
        fun out(input: String) = (DebugReaderArgs.reader(mapOf("input" to input)) as DebugReaderArgs.Reader).out

        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-smoke.tsv", out("smoke.mal.txt"))
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-v2b-noise.tsv", out("v2b.noise.mal.txt"))
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-export_fixtures.tsv", out("export_fixtures.mal.txt"))
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-lines.tsv", out("lines.txt"))
        assertEquals("readings-int8-prepack-session-arena-on-threads-4-beam4-lines-tsv.tsv", out("lines.tsv"))
    }

    // --- Files ---

    @Test
    fun `inputs and outputs are files of the selftests' folder, never a path`() {
        val dir = tmp.root
        assertEquals(File(dir, "v0b_inputs.tsv"), ReaderSelftestFiles.inFolder(dir, " v0b_inputs.tsv "))
        for (name in listOf(null, "", ".", "..", "../x.tsv", "a/b.tsv", "a\\b.tsv")) assertNull(name, ReaderSelftestFiles.inFolder(dir, name))
    }

    @Test
    fun `golden TSVs are read by their header, each field JSON`() {
        val text = "# Generated by golden.py\nname\tinput\ttext\n\"l 1\"\t\"a\\tb\"\t\"x\"\n\"l 2\"\t\"\\u0d05\"\t\"y\"\n"

        val (columns, rows) = ReaderSelftestFiles.rows(text)

        assertEquals(listOf("name", "input", "text"), columns)
        assertEquals(listOf("a\tb", "അ"), rows.map { it["input"] })
        assertEquals("l 2", rows[1]["name"])
        assertNull(rows[0]["pieces"])
        assertEquals("\"a\\tb\"", ReaderSelftestFiles.json("a\tb"))
        assertEquals("[\"▁a\",\"x\\\"y\"]", ReaderSelftestFiles.jsonStrings(listOf("▁a", "x\"y")))
        assertEquals(listOf("one", "", "three"), ReaderSelftestFiles.lines("one\n\nthree\n"))
        assertEquals(listOf("one"), ReaderSelftestFiles.lines("one"))
    }

    @Test
    fun `a TSV without a header, or a row that isn't one, is refused`() {
        for (text in listOf("", "# only comments\n", "name\ttext\n\"a\"\n", "name\ttext\n\"a\"\tnot json\n")) {
            val thrown = runCatching { ReaderSelftestFiles.rows(text) }.exceptionOrNull()
            assertTrue(text, thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `an output takes its name only once it is whole, and one that fails leaves the earlier file`() = runBlocking {
        val out = File(tmp.root, "readings.tsv")

        assertEquals(3, ReaderSelftestFiles.writing(out) { writer -> writer.append("first\n"); 3 })
        val thrown = runCatching { ReaderSelftestFiles.writing(out) { writer -> writer.append("half"); error("stopped") } }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalStateException)
        assertEquals("first\n", out.readText())
        assertFalse(File(tmp.root, "readings.tsv.partial").exists())
    }

    // --- The tokenizer selftest ---

    @Test
    fun `the tokenizer selftest preprocesses an input, holds it to the text given, and writes pieces and ids`() {
        val (columns, rows) = ReaderSelftestFiles.rows(
            "name\tinput\ttext\n\"same\"\t\"a b\"\t\"a b\"\n\"other\"\t\"a  b c\"\t\"a b\"\n\"given\"\t\"\"\t\"\"\n"
        )
        val out = StringBuilder()

        val summary = TokenizerSelftest { 0L }.run(columns, rows, tokenizer, out)

        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals("name\ttext\tmatches\tpieces\tids\tunknown\tparts", lines[0])
        assertEquals("\"same\"\t\"a b\"\t1\t[\"▁a\",\"▁b\"]\t[13,4,20,21,2]\t0\t1", lines[1])
        // The preprocessing gives "a b c" for "a  b c", not the text given; c is no piece of the vocabulary.
        assertEquals("\"other\"\t\"a b c\"\t0\t[\"▁a\",\"▁b\",\"▁c\"]\t[13,4,20,21,3,2]\t1\t1", lines[2])
        assertEquals("\"given\"\t\"\"\t1\t[]\t[13,4,2]\t0\t0", lines[3])
        assertEquals(TokenizerSelftest.Summary(3, 5, 1, 1, 0, 3, 2, 0), summary)
    }

    @Test
    fun `golden's v0b inputs, the text alone, go to SentencePiece as they are`() {
        val (columns, rows) = ReaderSelftestFiles.rows("name\ttext\n\"t\"\t\"a  b\"\n")
        val out = StringBuilder()

        val summary = TokenizerSelftest { 0L }.run(columns, rows, tokenizer, out)

        assertEquals("\"t\"\t\"a  b\"\t\t[\"▁a\",\"▁b\"]\t[13,4,20,21,2]\t0\t1", out.lines()[1])
        assertEquals(0, summary.compared)
    }

    @Test
    fun `a line over 253 pieces is framed whole, its ids not cut`() {
        val pieces = List(300) { if (it % 2 == 0) "▁a" else "▁b" }

        val whole = TokenizerSelftest.wholeSource(ids, pieces)

        assertEquals(303, whole.size)
        assertArrayEquals(intArrayOf(13, 4, 20, 21), whole.copyOfRange(0, 4))
        assertEquals(2, whole.last())
        assertTrue((2 until 302).all { whole[it] == if (it % 2 == 0) 20 else 21 })
    }

    @Test
    fun `an input without a name or a line column is refused`() {
        val (columns, rows) = ReaderSelftestFiles.rows("name\tpieces\n\"a\"\t\"b\"\n")

        val thrown = runCatching { TokenizerSelftest { 0L }.run(columns, rows, tokenizer, StringBuilder()) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }

    // --- The reader selftest ---

    /** A reader whose readings [readings] gives; [tick] hears of each call ("translate", "read") as it starts. */
    private inner class FakeReader(private val tick: (String) -> Unit = {}, private val readings: (String) -> LineReading) : LoadedReader {
        val sources = mutableListOf<Triple<IntArray, Int, Int>>()
        override val name = "test"
        override val pieceIds = ids
        override val limits = ReaderLimits(122_706, 256, 256, 2, 2, 1)
        override fun dims() = ReaderDims(18, 8, 64)
        override fun pieces(text: String): List<String> = tokenizer.pieces(text)
        override suspend fun translate(sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int): Reading {
            tick("translate")
            sources += Triple(sourceIds, maxNewTokens, minTokens)
            return Reading(IntArray(if (minTokens > 1) minTokens - 1 else 1) { 10 }, -0.25, capped = minTokens > 1, steps = maxOf(minTokens, 1))
        }

        override suspend fun read(line: String, beam: Int): LineReading {
            tick("read")
            return readings(line)
        }
    }

    @Test
    fun `the reader selftest writes each line's English with its time, tokens and score`() = runBlocking {
        val reader = FakeReader { line ->
            if (line.isEmpty()) LineReading.EMPTY else LineReading("Hello \"world\"", listOf(PartReading(5, 3, 4, -0.5, false, 40), PartReading(2, 1, 2, -1.0, true, 10)))
        }
        var now = 0L
        val out = StringBuilder()

        val summary = ReaderSelftest { now.also { now += 50 } }.lines(listOf("a b", ""), reader, beam = 4, firstStep = false, out)

        val lines = out.lines()
        assertEquals("line\tms\tfirst_ms\tpieces\tparts\ttokens\tsteps\tmean_log_prob\tcapped\tenglish", lines[0])
        assertEquals("1\t50\t\t7\t2\t4\t6\t-0.666667\t1\t\"Hello \\\"world\\\"\"", lines[1])
        assertEquals("2\t50\t\t0\t0\t0\t0\t\t0\t\"\"", lines[2])
        assertEquals(ReaderSelftest.Summary(lines = 2, inParts = 1, pieces = 7, tokens = 4, steps = 6, capped = 1, empty = 1, readMs = 100), summary)
        assertEquals(100.0 / 6, summary.msPerStep!!, 1e-9)
        assertNull(summary.firstSteps)
    }

    @Test
    fun `with first steps each line's first part is read once more, cut at one token`() = runBlocking {
        val reader = FakeReader { LineReading("Hello", listOf(PartReading(2, 1, 2, -0.5, false, 5))) }
        val out = StringBuilder()

        ReaderSelftest { 0L }.lines(listOf("a b"), reader, beam = 4, firstStep = true, out)

        assertEquals(1, reader.sources.size)
        assertArrayEquals(intArrayOf(13, 4, 20, 21, 2), reader.sources[0].first)
        assertEquals(1, reader.sources[0].second)
        assertEquals("0", out.lines()[1].split('\t')[2])
    }

    @Test
    fun `a line's ms is its reading alone, first_ms its first step alone, and the runs after the first are timed apart`() = runBlocking {
        // The first step takes 7 ms and a line's reading 40 ms; a line with no piece has no first step.
        var now = 0L
        val reader = FakeReader(tick = { call -> now += if (call == "translate") 7 else 40 }) { line ->
            if (line.isEmpty()) LineReading.EMPTY else LineReading("Hello", listOf(PartReading(2, 4, 5, -0.5, false, 40)))
        }
        val out = StringBuilder()

        val summary = ReaderSelftest { now }.lines(listOf("a b", "a", ""), reader, beam = 4, firstStep = true, out)

        val rows = out.lines().drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }
        assertEquals(listOf("40", "40", "40"), rows.map { it[1] })
        assertEquals(listOf("7", "7", ""), rows.map { it[2] })
        // Two lines with a first step: 14 ms of first steps, 80 ms of readings, 10 runs of which 8 after the first.
        assertEquals(ReaderSelftest.FirstSteps(lines = 2, firstMs = 14, readMs = 80, steps = 10), summary.firstSteps)
        assertEquals(7.0, summary.firstSteps!!.msPerLine, 1e-9)
        assertEquals(66.0 / 8, summary.firstSteps!!.msPerLaterStep!!, 1e-9)
        assertEquals(120.0 / 10, summary.msPerStep!!, 1e-9)
    }

    @Test
    fun `the worst case reads 253 pieces of the lines with the end held back to 255 steps`() = runBlocking {
        val reader = FakeReader { error("no line is read in the worst case") }
        val lines = List(100) { "a b c" }
        val out = StringBuilder()

        val worst = ReaderSelftest { 0L }.worstCase(lines, reader, beam = 4, out)

        val (source, maxNew, minTokens) = reader.sources.single()
        assertEquals(256, source.size)
        assertEquals(13, source[0])
        assertEquals(2, source.last())
        assertEquals(0, maxNew)
        assertEquals(255, minTokens)
        assertEquals(ReaderSelftest.WorstCase(pieces = 253, tokens = 254, steps = 255, capped = true, ms = 0), worst)
        assertTrue(out.lines()[1].startsWith("1\t0\t\t253\t1\t254\t255\t-0.250000\t1\t"))
    }

    @Test
    fun `a worst case over too few pieces writes nothing, and an earlier output keeps its name`() = runBlocking {
        val reader = FakeReader { LineReading.EMPTY }
        val out = File(tmp.root, "readings-int8-prepack-session-arena-on-threads-4-beam4-worstcase.tsv").apply { writeText("an earlier run\n") }

        val thrown = runCatching {
            ReaderSelftestFiles.writing(out) { writer -> ReaderSelftest { 0L }.worstCase(listOf("a b", "a"), reader, beam = 4, writer) }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is ReaderSelftest.TooFewPieces)
        assertEquals(3, (thrown as ReaderSelftest.TooFewPieces).pieces)
        assertEquals(253, thrown.want)
        assertTrue(reader.sources.isEmpty())
        assertEquals("an earlier run\n", out.readText())
        assertFalse(File(out.path + ".partial").exists())
    }

    @Test
    fun `a mean log-probability is written as Python reads it back`() {
        assertEquals("-inf", ReaderSelftest.number(Double.NEGATIVE_INFINITY))
        assertEquals("-0.123457", ReaderSelftest.number(-0.1234567))
    }

    // --- The memory figures ---

    @Test
    fun `RssAnon before the load and after the release are each read after a collection and a purge`() {
        val calls = mutableListOf<String>()
        val readings = ArrayDeque(listOf(455L, 412L))
        val memory = SelftestMemory(
            rssAnonMb = { readings.removeFirst().also { calls += "read $it" } },
            collect = { calls += "collect" },
            purge = { calls += "purge"; Purge.ALL }
        )

        val settled = memory.settle()

        assertEquals(listOf("read 455", "collect", "purge", "read 412"), calls)
        assertEquals(SelftestMemory.Settled(unsettledMb = 455, mb = 412, purged = Purge.ALL), settled)
        assertEquals(
            "RssAnon 455 -> 412 MB, the Java heap collected and the native heap purged (M_PURGE_ALL)",
            SelftestMemory.settledLine(settled)
        )
        val after = SelftestMemory.Settled(unsettledMb = 431, mb = 418, purged = Purge.ALL)
        assertEquals(
            "RssAnon 412 MB before the load, peak 655 MB, 418 MB after the release (431 MB before its collection and purge)",
            SelftestMemory.line(settled, 655, after)
        )
    }

    @Test
    fun `a purge that can't be made gives nothing back and stops nothing`() {
        val memory = SelftestMemory(rssAnonMb = { null }, collect = {}, purge = { throw UnsatisfiedLinkError("no libai-chat.so") })

        val settled = memory.settle()

        assertEquals(SelftestMemory.Settled(null, null, Purge.NONE), settled)
        assertEquals("RssAnon n/a -> n/a MB, the Java heap collected and the native heap not purged", SelftestMemory.settledLine(settled))
    }

    // --- The rules ---

    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val gate = AiWorkGate()
    private val finishingBusy = MutableStateFlow(false)
    private val service = Service()

    private fun exclusive() = DebugExclusive(recorder, gate, finishingBusy, service, "reader-selftest", serviceWaitMs = 1_000)

    @Test
    fun `a selftest runs under the AI's gate and service, and gives both back`() = runBlocking {
        val run = exclusive().hold {
            assertEquals("reader-selftest", gate.holder.value)
            assertEquals(setOf("reader-selftest"), service.holders)
            "read"
        }

        assertEquals(DebugExclusive.Run.Done("read", inForeground = true), run)
        assertNull(gate.holder.value)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `it refuses beside a busy recorder, the finisher or another holder of the AI`() = runBlocking {
        recorder.value = ActiveRecorder.Snapshot("s", ActiveRecorder.Activity.STARTING)
        assertEquals(DebugExclusive.Run.Refused("a recorder is busy"), exclusive().hold { error("ran") })
        recorder.value = ActiveRecorder.Snapshot("s", ActiveRecorder.Activity.IDLE)
        finishingBusy.value = true
        assertEquals(DebugExclusive.Run.Refused("the AI is busy"), exclusive().hold { error("ran") })
        finishingBusy.value = false
        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))
        assertEquals(DebugExclusive.Run.Refused("the AI is busy"), exclusive().hold { error("ran") })
        gate.exit(AiWorkGate.FINISHER)
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a recording that starts wins, the work cancelled and over before the gate goes`() = runBlocking {
        val working = CompletableDeferred<Unit>()
        var cancelled = false
        val run = async {
            exclusive().hold {
                working.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        // Bounded: a regression fails the test instead of holding the suite.
        withTimeout(5_000) { working.await() }

        recorder.value = ActiveRecorder.Snapshot("s", ActiveRecorder.Activity.RECORDING)

        assertEquals(DebugExclusive.Run.RecorderStarted, withTimeout(5_000) { run.await() })
        assertTrue(cancelled)
        assertNull(gate.holder.value)
    }

    @Test
    fun `a selftest says it has started once past the rules and holding the gate and service, a refused one never`() = runBlocking {
        val started = mutableListOf<String>()
        exclusive().hold({ started += "started holding ${service.holders} under ${gate.holder.value}" }) { "read" }
        finishingBusy.value = true
        exclusive().hold({ started += "refused one started" }) { error("ran") }

        assertEquals(listOf("started holding [reader-selftest] under reader-selftest"), started)
    }

    @Test
    fun `what the work throws becomes its line, a capture's stop among them`() = runBlocking {
        val yielded = exclusive().hold { throw ReaderYieldedException("test") }
        val failed = exclusive().hold { throw com.satori.reader.ReaderException.BadModel("decoder_model: no float logits (3-D)") }

        assertEquals(DebugExclusive.Run.Failed("stopped: a capture opened; the reader is let go"), yielded)
        assertEquals(DebugExclusive.Run.Failed("failed: BadModel: decoder_model: no float logits (3-D)"), failed)
        // A parser's message quotes the file it read; the line never does.
        val json = runCatching { com.satori.domain.reading.PieceIds.parse("{\"a\": secret}") }.exceptionOrNull() as Exception
        assertEquals("failed: a file that is no JSON (${json.javaClass.simpleName})", DebugExclusive.failure(json))
        yield()
        assertNull(gate.holder.value)
    }

    // --- The download ---

    private val network = MutableStateFlow(NetworkFacts.WIFI)
    private val downloadLines = mutableListOf<String>()

    private fun downloader(downloads: ReaderDownloads) =
        DebugReaderDownload(recorder, network, service, downloads, clock = { 0L }, warn = { downloadLines += "W $it" }) { downloadLines += it }

    @Test
    fun `the download refuses beside a recorder, without a network, and on mobile data unless asked`() = runBlocking {
        network.value = NetworkFacts.MOBILE
        val download = downloader(ReaderDownloads.of(ModelDownloadManager(ExternalFilesContext(tmp.root))))

        assertEquals(
            DebugReaderDownload.Outcome.Refused("Wi-Fi only, and the network is metered (--es mobile true allows mobile data)"),
            download.run(ReaderModelId.INT8, mobileAllowed = false)
        )
        network.value = NetworkFacts.NONE
        assertEquals(DebugReaderDownload.Outcome.Refused("no network"), download.run(ReaderModelId.INT8, mobileAllowed = true))
        network.value = NetworkFacts.WIFI
        recorder.value = ActiveRecorder.Snapshot("s", ActiveRecorder.Activity.RECORDING)
        assertEquals(DebugReaderDownload.Outcome.Refused("a recorder is busy"), download.run(ReaderModelId.INT8, mobileAllowed = false))
        assertEquals("W Reader download int8 refused: a recorder is busy", downloadLines.last())
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a recording that starts during the download stops it, and what came stays`() = runBlocking {
        val downloads = FakeDownloads(Fetch.Hangs)
        val run = async { downloader(downloads).run(ReaderModelId.INT8, mobileAllowed = false) }
        withTimeout(5_000) { downloads.fetching.await() }

        recorder.value = ActiveRecorder.Snapshot("s", ActiveRecorder.Activity.STARTING)

        assertEquals(DebugReaderDownload.Outcome.Stopped("a recording started"), withTimeout(5_000) { run.await() })
        assertTrue(downloads.cancelled)
        assertFalse(downloads.hashed)
        assertEquals("W Reader download int8 stopped: a recording started; what came stays for the next run", downloadLines.last())
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `the network turning metered, or going, stops the download`() = runBlocking {
        for ((facts, why) in listOf(NetworkFacts.MOBILE to "the network is metered now", NetworkFacts.NONE to "the network is gone")) {
            network.value = NetworkFacts.WIFI
            val downloads = FakeDownloads(Fetch.Hangs)
            val run = async { downloader(downloads).run(ReaderModelId.INT8, mobileAllowed = false) }
            withTimeout(5_000) { downloads.fetching.await() }

            network.value = facts

            assertEquals(DebugReaderDownload.Outcome.Stopped(why), withTimeout(5_000) { run.await() })
            assertTrue(downloads.cancelled)
            assertEquals("W Reader download int8 stopped: $why; what came stays for the next run", downloadLines.last())
        }
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `with mobile data allowed, a metered network doesn't stop it`() = runBlocking {
        network.value = NetworkFacts.MOBILE
        val downloads = FakeDownloads(Fetch.Ends)

        val outcome = downloader(downloads).run(ReaderModelId.INT8, mobileAllowed = true)

        assertEquals(DebugReaderDownload.Outcome.Done(11, ReaderModels.of(ReaderModelId.INT8).bytes, 0), outcome)
    }

    @Test
    fun `after the download every file is held to its pin, and one that isn't it is deleted`() = runBlocking {
        val pins = ReaderModels.of(ReaderModelId.INT8).files.map { PinnedFileHash(it.spec.name, it.sha256, it.sha256) }
        val wrong = pins.first { it.name == ReaderModels.CONFIG }.copy(sha256 = "0".repeat(64))
        val downloads = FakeDownloads(Fetch.Ends, hashes = pins.map { if (it.name == wrong.name) wrong else it })
        val config = File(downloads.dir, ReaderModels.CONFIG).apply { writeText("{\"edited\": 1}") }
        val kept = File(downloads.dir, ReaderModels.SOURCE_VOCABULARY).apply { writeText("{}") }

        val outcome = downloader(downloads).run(ReaderModelId.INT8, mobileAllowed = false)

        assertEquals(DebugReaderDownload.Outcome.Failed("1 of 11 files not the pinned ones"), outcome)
        assertFalse(config.exists())
        assertTrue(kept.exists())
        assertTrue(downloadLines.toString(), "W Reader download int8: config.json has SHA-256 ${"0".repeat(64)}, not the pinned ${wrong.pinned}; deleted" in downloadLines)
        assertEquals("W Reader download int8 failed after 0 ms: 1 of 11 files not the pinned ones", downloadLines.last())
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `one reader download at a time, as a run outlives its screen`() = runBlocking {
        val first = FakeDownloads(Fetch.Hangs)
        val running = async { downloader(first).run(ReaderModelId.INT8, mobileAllowed = false) }
        withTimeout(5_000) { first.fetching.await() }

        val second = downloader(FakeDownloads(Fetch.Ends)).run(ReaderModelId.INT8, mobileAllowed = false)

        assertEquals(DebugReaderDownload.Outcome.Refused("another reader download is under way"), second)
        assertEquals("W Reader download int8 refused: another reader download is under way", downloadLines.last())
        assertEquals(setOf(DebugReaderDownload.HOLDER), service.holders)
        running.cancel()
        withTimeout(5_000) { running.join() }
        // Once it has ended, the next one runs.
        assertEquals(DebugReaderDownload.Outcome.Done(11, ReaderModels.of(ReaderModelId.INT8).bytes, 0), downloader(FakeDownloads(Fetch.Ends)).run(ReaderModelId.INT8, mobileAllowed = false))
        assertTrue(service.holders.isEmpty())
    }

    @Test
    fun `a download says it has started once past its refusals and holding the service, a refused one never`() = runBlocking {
        val started = mutableListOf<String>()
        downloader(FakeDownloads(Fetch.Ends)).run(ReaderModelId.INT8, mobileAllowed = false) { started += "started holding ${service.holders}" }
        network.value = NetworkFacts.NONE
        downloader(FakeDownloads(Fetch.Ends)).run(ReaderModelId.INT8, mobileAllowed = false) { started += "refused one started" }

        assertEquals(listOf("started holding [reader-download]"), started)
    }

    @Test
    fun `a download whose every file is the pinned one is done, and says so`() = runBlocking {
        val downloads = FakeDownloads(Fetch.Ends)

        val outcome = downloader(downloads).run(ReaderModelId.INT8, mobileAllowed = false)

        val spec = ReaderModels.of(ReaderModelId.INT8)
        assertEquals(DebugReaderDownload.Outcome.Done(11, spec.bytes, 0), outcome)
        assertEquals("Reader download int8 done in 0 ms: 11 of 11 files, ${spec.bytes} bytes, each SHA-256 the pinned one, in ${downloads.dir.name}", downloadLines.last())
        assertTrue(downloadLines.toString(), downloadLines.any { it.startsWith("Reader download int8: ") && it.endsWith(" MB (100%)") })
    }

    /** How a fake download goes: it [Hangs] after its first progress until it is cancelled, or [Ends] at 100 %. */
    private enum class Fetch { Hangs, Ends }

    /** The reader download over [fetch], its files' hashes [hashes] (each the pin's by default), its folder in [tmp]. */
    private inner class FakeDownloads(
        private val fetch: Fetch,
        private val hashes: List<PinnedFileHash> =
            ReaderModels.of(ReaderModelId.INT8).files.map { PinnedFileHash(it.spec.name, it.sha256, it.sha256) }
    ) : ReaderDownloads {
        val dir: File = tmp.newFolder()
        val fetching = CompletableDeferred<Unit>()
        @Volatile var cancelled = false
        @Volatile var hashed = false

        override fun readerModelDir(model: ReaderModelId): File = dir
        override fun readerModelBytesOnPhone(model: ReaderModelId): Long = 0L

        override fun downloadReaderModel(model: ReaderModelId): Flow<ModelDownloadManager.DownloadProgress> {
            val total = ReaderModels.of(model).bytes
            return when (fetch) {
                Fetch.Ends -> flowOf(ModelDownloadManager.DownloadProgress(total, total, 100))
                Fetch.Hangs -> flow {
                    emit(ModelDownloadManager.DownloadProgress(1_000_000, total, 0))
                    fetching.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }

        override suspend fun hashReaderModel(model: ReaderModelId): List<PinnedFileHash> = hashes.also { hashed = true }
    }

    private class Service : LlmProcessingServiceController {
        val holders = mutableSetOf<String>()
        private val inForeground = MutableStateFlow(false)
        override val protected: StateFlow<Boolean> = inForeground

        override fun startProcessing(holder: String): Boolean {
            holders += holder
            inForeground.value = true
            return true
        }

        override fun stopProcessing(holder: String) {
            holders -= holder
            if (holders.isEmpty()) inForeground.value = false
        }
    }

    /** A context whose external files are in [dir]. */
    private class ExternalFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = dir
    }
}
