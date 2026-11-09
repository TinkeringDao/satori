package com.satori.data.speech

import android.content.Context
import com.satori.domain.repository.SpeechVocabularyRepository
import com.satori.domain.usecase.stt.SpeechVocabulary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The words to recognize, kept as the very file sherpa-onnx reads them from (its `hotwordsFile`):
 * one word or phrase per line, in the app's internal storage, which no other app and no backup
 * reaches (data_extraction_rules.xml). The file holds only what [SpeechVocabulary.clean] lets
 * through, so the recognizer never meets a line it would misread or write to the log.
 *
 * @property file Where the words are; the tests pass a file of their own
 */
@Singleton
class SpeechVocabularyStore internal constructor(private val file: File) : SpeechVocabularyRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(File(context.filesDir, FOLDER), FILE_NAME))

    // Null until the file has been read once.
    private val words = MutableStateFlow<List<String>?>(null)

    // The file is read and written under this lock, on a background thread.
    private val lock = Any()

    override fun observe(): Flow<List<String>> = words
        .onStart {
            if (words.value == null) {
                val stored = withContext(Dispatchers.IO) { synchronized(lock) { read() } }
                words.compareAndSet(null, stored)
            }
        }
        .filterNotNull()

    override suspend fun set(words: List<String>): Result<Unit> {
        val cleaned = SpeechVocabulary.clean(SpeechVocabulary.asText(words))
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    write(cleaned)
                    this@SpeechVocabularyStore.words.value = cleaned
                    Result.success(Unit)
                } catch (e: IOException) {
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * The file for the recognizer's `hotwordsFile`, or null when there are no words. A file that
     * isn't as [set] writes it (changed by hand, say) is written again cleaned first, so what the
     * recognizer reads is always clean; one that can't be written then is not given to it, and the
     * recognizer listens for no words this time rather than failing to load. Reads the disk: call
     * it off the main thread.
     */
    fun hotwordsFile(): File? = synchronized(lock) {
        val stored = read()
        if (stored.isEmpty()) return null
        if (readText() != textOf(stored)) {
            try {
                write(stored)
            } catch (e: IOException) {
                return null
            }
        }
        file
    }

    private fun read(): List<String> = SpeechVocabulary.clean(readText().orEmpty())

    private fun readText(): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: IOException) {
        null
    }

    /**
     * Writes [cleaned] whole or not at all: a half-written file would lose words. No words leave
     * an empty file rather than none, as sherpa-onnx ends the whole app when a hotwords file it was
     * given can't be opened, and a recognizer may be loading from this one at that moment.
     *
     * @throws IOException when the phone is full, say; the file is as it was then
     */
    private fun write(cleaned: List<String>) {
        val folder = checkNotNull(file.parentFile) { "The words file has no folder" }
        folder.mkdirs()
        val temporary = File(folder, "${file.name}.new")
        try {
            temporary.writeText(textOf(cleaned))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }

    private fun textOf(words: List<String>): String = if (words.isEmpty()) "" else SpeechVocabulary.asText(words) + "\n"

    private companion object {
        const val FOLDER = "speech"
        const val FILE_NAME = "words.txt"
    }
}
