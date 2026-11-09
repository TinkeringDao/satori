package com.satori.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The owner's words to recognize (Advanced settings), which Parakeet v3 listens for
 * ([com.satori.domain.usecase.stt.SpeechVocabulary]). They are personal: they stay in the
 * app's own storage and never go into a log.
 */
interface SpeechVocabularyRepository {

    /** The words, as [set] cleaned them; empty when there are none. */
    fun observe(): Flow<List<String>>

    /**
     * Replaces the words with [words], cleaned first
     * ([com.satori.domain.usecase.stt.SpeechVocabulary.clean]). They apply from the next
     * time a speech model is loaded: the next recording, or the next Transcribe again.
     *
     * @return A failure when they couldn't be stored (the phone is full, say); the words are as
     *   they were then
     */
    suspend fun set(words: List<String>): Result<Unit>
}
