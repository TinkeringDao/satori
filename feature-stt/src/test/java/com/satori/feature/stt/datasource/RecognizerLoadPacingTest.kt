package com.satori.feature.stt.datasource

import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.SpeechModelLoadPacing
import com.satori.domain.modelfile.ModelFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which load a recognizer takes: the recorder's own for a recording, which never waits for another
 * and stops before its model loads once the recording wants another one, and for a file
 * (Transcribe again, the better transcript after Stop, a Malayalam recording transcribed again) one
 * that waits its turn behind every other model load and stops while it waits once its caller has
 * gone.
 */
class RecognizerLoadPacingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Recorded : SpeechModelLoadPacing {
        val calls = mutableListOf<String>()
        var cancelled: (() -> Boolean)? = null
        var stop: (() -> LoadStop?)? = null

        override fun <T> recorderLoad(name: String, files: List<File>, parseBytes: Long, stop: () -> LoadStop?, load: () -> T): T {
            calls += "recorder $name, parse $parseBytes"
            this.stop = stop
            return load()
        }

        override fun <T> otherLoad(name: String, files: List<File>, parseBytes: Long, cancelled: () -> Boolean, load: () -> T): T {
            calls += "other $name, parse $parseBytes"
            this.cancelled = cancelled
            return load()
        }
    }

    @Test
    fun `a recording's model is the recorder's own load, which a switch stops, a file's waits its turn and can be stopped`() {
        val pacing = Recorded()
        val gone = { true }
        val switched = { LoadStop.SWITCHED }

        assertEquals("live", pacing.loadFor(RecognizerUse.LIVE, "parakeet-tdt-0.6b-v3-int8", emptyList(), 670L, { false }, switched) { "live" })
        assertEquals("file", pacing.loadFor(RecognizerUse.FILE, "indicconformer-ml-int8", emptyList(), 138L, gone, { null }) { "file" })

        assertEquals(listOf("recorder parakeet-tdt-0.6b-v3-int8, parse 670", "other indicconformer-ml-int8, parse 138"), pacing.calls)
        assertSame(switched, pacing.stop)
        assertSame(gone, pacing.cancelled)
    }

    @Test
    fun `the room is for the ONNX files that hold their weights, not the external data ONNX Runtime maps nor the tokens`() {
        val dir = tmp.newFolder("cohere")
        fun file(name: String, bytes: Int) = File(dir, name).apply { writeBytes(ByteArray(bytes)) }
        val files = listOf(
            file("encoder.int8.onnx", 3) to ModelFileFormat.Onnx,
            file("encoder.int8.onnx.data", 2_000) to ModelFileFormat.Data,
            file("decoder.int8.onnx", 150) to ModelFileFormat.Onnx,
            file("tokens.txt", 40) to ModelFileFormat.Text
        )

        assertEquals(153L, SpeechRecognizerFactory.parseBytesOf(files))
    }
}
