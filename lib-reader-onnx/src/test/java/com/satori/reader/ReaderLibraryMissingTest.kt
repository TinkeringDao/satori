package com.satori.reader

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Without libreader-onnx on java.library.path, as in an ordinary Gradle run, the library's failure
 * to load is [ReaderException.LoadFailed] at every construction (ReaderNative), never
 * UnsatisfiedLinkError the first time and NoClassDefFoundError after it. Skipped when
 * run_host_tests.sh puts the macOS build of the library there.
 */
class ReaderLibraryMissingTest {

    @Test
    fun aLibraryThatDidNotLoadIsLoadFailedEveryTime() {
        assumeTrue("the host library is on java.library.path", System.getenv("SATORI_READER_HOST_LIB_DIR") == null)
        val constructors = listOf<Pair<String, () -> AutoCloseable>>("engine" to ::ReaderEngine, "tokenizer" to ::ReaderTokenizer)
        repeat(2) { round ->
            for ((name, construct) in constructors) {
                val thrown = runCatching { construct().close() }.exceptionOrNull()
                assertTrue("$name, round $round: $thrown", thrown is ReaderException.LoadFailed)
                assertTrue("$name, round $round: ${thrown?.message}", thrown!!.message!!.startsWith("libreader-onnx did not load"))
            }
        }
    }
}
