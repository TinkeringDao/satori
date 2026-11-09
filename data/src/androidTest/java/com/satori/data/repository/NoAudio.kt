package com.satori.data.repository

import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.repository.RecordingAudioRepository

/** The audio of recordings that kept none, for the repository tests that don't look at audio. */
internal object NoAudio : RecordingAudioRepository {
    override suspend fun find(sessionId: String): RecordingAudio? = null
    override suspend fun hasAudio(sessionId: String): Boolean = false
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>) = Unit
    override suspend fun setKept(sessionId: String, kept: Boolean) = Unit
    override suspend fun delete(sessionId: String) = Unit
    override suspend fun deleteAll() = Unit
}
