package com.satori.data.speech

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The words to recognize are the file sherpa-onnx reads: cleaned, one per line, written whole,
 * and gone with the last word. A write that fails leaves the words as they were.
 */
class SpeechVocabularyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "speech/words.txt")

    @Test
    fun `no words at first, and no file for the recognizer`() = runBlocking {
        val store = SpeechVocabularyStore(file)

        assertEquals(emptyList<String>(), store.observe().first())
        assertNull(store.hotwordsFile())
    }

    @Test
    fun `the words are cleaned, kept one per line, and read back by a new store`() = runBlocking {
        SpeechVocabularyStore(file).set(listOf(" Ravi ", "Gemma  4", ":2.0 Lena", "Ravi"))

        assertEquals("Ravi\nGemma 4\n2.0 Lena\n", file.readText())
        val reopened = SpeechVocabularyStore(file)
        assertEquals(listOf("Ravi", "Gemma 4", "2.0 Lena"), reopened.observe().first())
        assertEquals(file, reopened.hotwordsFile())
    }

    @Test
    fun `a change is seen by whoever watches`() = runBlocking {
        val store = SpeechVocabularyStore(file)
        store.observe().first()

        store.set(listOf("Satori"))

        assertEquals(listOf("Satori"), store.observe().first())
    }

    @Test
    fun `no words empty the file, which the recognizer is then not given`() = runBlocking {
        val store = SpeechVocabularyStore(file)
        store.set(listOf("Tasker"))

        store.set(listOf("  ", "-"))

        // Emptied, not deleted: a recognizer loading from it at that moment still opens it.
        assertEquals("", file.readText())
        assertNull(store.hotwordsFile())
        assertEquals(emptyList<String>(), store.observe().first())
    }

    @Test
    fun `words that can't be written leave the ones there were, and say so`() = runBlocking {
        val store = SpeechVocabularyStore(file)
        assertTrue(store.set(listOf("Tasker")).isSuccess)
        val folder = checkNotNull(file.parentFile)
        // As a full phone does: nothing new can be written in the folder.
        folder.setWritable(false)
        try {
            val result = store.set(listOf("Satori"))

            assertTrue(result.isFailure)
            assertEquals(listOf("Tasker"), store.observe().first())
            assertEquals("Tasker\n", file.readText())
            assertFalse(File(folder, "words.txt.new").exists())
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `a file changed by hand that can't be cleaned is not given to the recognizer`() {
        val folder = checkNotNull(file.parentFile).apply { mkdirs() }
        file.writeText("Anna\n:boost\n")
        folder.setWritable(false)
        try {
            assertNull(SpeechVocabularyStore(file).hotwordsFile())
        } finally {
            folder.setWritable(true)
        }
        assertEquals("Anna\n:boost\n", file.readText())
    }

    @Test
    fun `a file that was changed by hand is read as cleaned, and cleaned before the recognizer reads it`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("Anna\n:boost\n\n#tag\n")
        val store = SpeechVocabularyStore(file)

        assertEquals(listOf("Anna", "boost", "tag"), store.observe().first())
        assertEquals(file, store.hotwordsFile())
        assertEquals("Anna\nboost\ntag\n", file.readText())
    }
}
