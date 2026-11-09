package com.satori.feature.stt.datasource

import com.satori.feature.stt.datasource.SpeechSessionOptions.Companion.CONTENT
import com.satori.feature.stt.datasource.SpeechSessionOptions.Companion.PREPACK_PROPERTY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The speech models' ONNX Runtime options: prepacking off in every build, through a config file
 * that sherpa-onnx always finds, with values it parses, at a path that holds no "cuda" (each of
 * those would end the whole process); a debug build can load with ONNX Runtime's defaults again.
 */
class SpeechSessionOptionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = mutableListOf<String>()

    private fun options(dir: File, prepack: String?, debuggable: Boolean = true) =
        SpeechSessionOptions(dir, debuggable, read = { name -> if (name == PREPACK_PROPERTY) prepack else "" }, warn = { warnings += it })

    private fun release(dir: File) =
        SpeechSessionOptions(dir, debuggable = false, read = { throw AssertionError("read $it") }, warn = { warnings += it })

    @Test
    fun `a release build writes the file at start-up and loads every speech model with prepacking off, reading no property`() {
        val dir = File(tmp.root, "ort")
        val release = release(dir)

        release.install()

        val file = File(dir, "prepack-off.conf")
        assertEquals(CONTENT, file.readText())
        assertEquals("cpu:${file.absolutePath}", release.providerForLoad())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a debug build with the switch empty or off loads with prepacking off, the file's options word for word`() {
        val dir = File(tmp.root, "ort")
        val options = options(dir, "")

        options.install()

        val file = File(dir, "prepack-off.conf")
        assertEquals(
            "# Satori: ONNX Runtime's options for the speech models, prepacking off\n" +
                "SessionConfig.session.disable_prepacking=1\n" +
                "EnableMemPattern=0\n" +
                "DEBUG=1\n",
            file.readText()
        )
        assertEquals(file, options.file)
        for (value in listOf("", "off", " OFF ", "0", "false")) {
            assertEquals(value, "cpu:${file.absolutePath}", options(dir, value).providerForLoad())
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a debug build's switch on loads with ONNX Runtime's defaults`() {
        val dir = File(tmp.root, "ort")

        for (value in listOf("on", " ON ", "1", "true")) {
            assertEquals(value, "cpu", options(dir, value).providerForLoad())
        }

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a file gone or changed since start-up is written again before the load, never handed on missing`() {
        val dir = File(tmp.root, "ort")
        for (options in listOf(options(dir, ""), release(dir))) {
            options.install()

            options.file.delete()
            assertEquals("cpu:${options.file.absolutePath}", options.providerForLoad())
            assertEquals(CONTENT, options.file.readText())

            // A value ONNX Runtime couldn't parse, or an earlier build's text.
            options.file.writeText("SessionConfig.session.disable_prepacking=maybe\n")
            assertEquals("cpu:${options.file.absolutePath}", options.providerForLoad())
            assertEquals(CONTENT, options.file.readText())
        }
        assertTrue(warnings.isEmpty())
        // Nothing but the file is left in its directory.
        assertEquals(listOf("prepack-off.conf"), dir.list()?.toList())
    }

    @Test
    fun `a file that can't be written leaves the load at ONNX Runtime's defaults, with a warning`() {
        // A file where the directory should be: nothing can be written in it.
        val dir = File(tmp.root, "ort").apply { writeText("not a directory") }
        val options = release(dir)

        options.install()
        val provider = options.providerForLoad()

        assertEquals("cpu", provider)
        assertEquals(
            listOf(
                "ORT options not written to ${options.file.path}; the speech models load with prepacking on",
                "Not turning ORT prepacking off: ${options.file.path} can't be written"
            ),
            warnings
        )
    }

    @Test
    fun `a file that can't be written refuses a large model's load, which prepacking on would make 2 GB of copies`() {
        val dir = File(tmp.root, "ort").apply { writeText("not a directory") }
        val options = release(dir)

        val refused = runCatching { options.providerForLoad(large = true) }.exceptionOrNull()

        assertTrue(refused.toString(), refused is PrepackingStaysOnException)
        assertEquals(listOf("Not loading a large speech model with ORT prepacking on: ${options.file.path} can't be written"), warnings)
        // The live models still load, at ONNX Runtime's defaults.
        assertEquals("cpu", options.providerForLoad(large = false))
        // And a written file serves both.
        val fine = release(File(tmp.root, "ort2"))
        assertEquals("cpu:${fine.file.absolutePath}", fine.providerForLoad(large = true))
    }

    @Test
    fun `a path that holds cuda is never handed on, as Cohere would bind its outputs to a GPU`() {
        val dir = File(tmp.newFolder("Cudacache"), "ort")
        val options = release(dir)

        options.install()

        assertEquals("cpu", options.providerForLoad())
        assertFalse(options.file.exists())
        assertEquals(
            listOf("Not turning ORT prepacking off: the path of its config file holds \"cuda\" (${options.file.path})"),
            warnings
        )
    }

    @Test
    fun `a value it doesn't know, or a property it can't read, keeps prepacking off with a warning`() {
        val dir = File(tmp.root, "ort")
        val file = File(dir, "prepack-off.conf")

        assertEquals("cpu:${file.absolutePath}", options(dir, "maybe").providerForLoad())
        assertEquals("cpu:${file.absolutePath}", options(dir, null).providerForLoad())

        assertEquals(
            listOf(
                "Ignoring debug.satori.ort.prepack=maybe: not on or off",
                "Ignoring debug.satori.ort.prepack: the property can't be read"
            ),
            warnings
        )
    }

    @Test
    fun `every option line is key=value with a value ONNX Runtime and sherpa-onnx parse`() {
        val lines = CONTENT.lines().filter { it.isNotBlank() && !it.startsWith("#") }

        assertEquals(listOf("SessionConfig.session.disable_prepacking", "EnableMemPattern", "DEBUG"), lines.map { it.substringBefore('=') })
        assertTrue(lines.all { it.substringAfter('=') in setOf("0", "1") })
        // sherpa-onnx skips a line that starts with # (csrc/session.cc ParseConfigFile, v1.13.8).
        assertTrue(CONTENT.lines().first().startsWith("#"))
    }
}
