package com.satori.data.repository

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A recording as [TranscriptionRepositoryImpl.createSession] stores it ([newRecording]): its
 * transcript is the live one of its language's live model from its first row, which the finisher
 * and the lines joined after Stop read as a live transcript (AI4Bharat's for Malayalam, Parakeet
 * v3's otherwise); its spoken language is not known yet.
 */
class NewRecordingTest {

    private fun recording(language: RecordingLanguage, topic: String? = null) = newRecording(
        id = "s1",
        now = 1_000L,
        name = null,
        mode = RecordingMode.SHORT_MEETING,
        outputLanguage = null,
        insightStrategy = InsightStrategy.REAL_TIME,
        topic = topic,
        zoneId = "Europe/Berlin",
        language = language
    )

    @Test
    fun aRecordingInMalayalamIsTranscribedByItsModelFromTheStart() {
        val malayalam = recording(RecordingLanguage.MALAYALAM)

        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, malayalam.transcriptModel)
        assertEquals(RecordingLanguage.MALAYALAM, malayalam.language)
    }

    @Test
    fun aRecordingInEnglishOrGermanIsTranscribedByParakeet() {
        val recording = recording(RecordingLanguage.ENGLISH_OR_GERMAN)

        assertEquals(SpeechModelId.PARAKEET_V3.id, recording.transcriptModel)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, recording.language)
    }

    @Test
    fun itsSpokenLanguageIsUnknownAndABlankTopicIsNone() {
        val recording = recording(RecordingLanguage.ENGLISH_OR_GERMAN, topic = "  ")

        assertEquals("", recording.inputLanguage)
        assertNull(recording.topic)
        assertEquals(1_000L, recording.createdAt)
        assertEquals(1_000L, recording.lastModifiedAt)
        assertEquals("Europe/Berlin", recording.zoneId)
        assertEquals("Weekly sync", recording(RecordingLanguage.ENGLISH_OR_GERMAN, topic = "Weekly sync").topic)
    }
}
