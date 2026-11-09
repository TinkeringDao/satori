package com.satori.presentation.settings

import com.satori.domain.model.AppSettings
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.settings.ModelActivity
import com.satori.domain.settings.ModelOnPhone
import com.satori.domain.settings.ModelRef
import com.satori.domain.settings.ModelUse
import com.satori.domain.settings.RemoveRefusal
import com.satori.presentation.main.ActiveRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What Models and storage reads of the app: the settings that use each model, what runs that may
 * hold one (a recorder that isn't idle, the finishing, a Transcribe again, other AI work, a
 * download), and each model with its uses and whether it can go now.
 */
class ModelStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `Malayalam is chosen when home or the power key records in it`() {
        val settings = AppSettings(llmEnabled = true, llmModelVariant = LlmModelVariant.IQ4_NL, transcribeAfterStop = SpeechModelId.COHERE_TRANSCRIBE)
        val facts = ModelStorageReader.factsOf(settings, RecordingLanguage.ENGLISH_OR_GERMAN, finishingModelOn = false)

        assertFalse(facts.malayalamChosen)
        assertEquals(LlmModelVariant.IQ4_NL, facts.chosenAi)
        assertEquals(SpeechModelId.COHERE_TRANSCRIBE, facts.betterTranscript)
        assertTrue(ModelStorageReader.factsOf(settings, RecordingLanguage.MALAYALAM, finishingModelOn = false).malayalamChosen)
        assertTrue(ModelStorageReader.factsOf(settings.copy(homeLanguage = RecordingLanguage.MALAYALAM), RecordingLanguage.ENGLISH_OR_GERMAN, false).malayalamChosen)
    }

    @Test
    fun `what runs comes from the recorder, the finishing queue and the holders of the AI work`() {
        assertEquals(ModelActivity(), ModelStorageReader.activityOf(null, finishing = false, holders = emptySet()))
        assertEquals(ModelActivity(), ModelStorageReader.activityOf(ActiveRecorder.Activity.IDLE, finishing = false, holders = emptySet()))
        for (recording in listOf(ActiveRecorder.Activity.STARTING, ActiveRecorder.Activity.RECORDING, ActiveRecorder.Activity.SAVING)) {
            assertTrue(ModelStorageReader.activityOf(recording, finishing = false, holders = emptySet()).recording)
        }
        assertTrue(ModelStorageReader.activityOf(null, finishing = true, holders = emptySet()).finishing)
        assertTrue(ModelStorageReader.activityOf(null, finishing = false, holders = setOf("finish:s1")).finishing)
        assertTrue(ModelStorageReader.activityOf(null, finishing = false, holders = setOf("transcribe:s1")).transcribingAgain)
        for (holder in listOf("rewrite:s1", "todos:s1", "map", "map-older")) {
            val activity = ModelStorageReader.activityOf(null, finishing = false, holders = setOf(holder))
            assertTrue(holder, activity.aiWork)
            assertFalse(holder, activity.transcribingAgain)
        }
    }

    @Test
    fun `a download that runs or checks its files holds its model`() {
        val running = DownloadState.Downloading(DownloadProgress(1L, 10L, 10))
        val checking = DownloadState.Verifying(DownloadProgress(10L, 10L, 100))

        assertEquals(
            setOf(ModelRef.Speech(SpeechModelId.COHERE_TRANSCRIBE), ModelRef.Ai(LlmModelVariant.Q8_0)),
            ModelStorageReader.downloadingOf(running, SpeechModelId.COHERE_TRANSCRIBE, checking, LlmModelVariant.Q8_0)
        )
        assertEquals(
            emptySet<ModelRef>(),
            ModelStorageReader.downloadingOf(DownloadState.Completed, SpeechModelId.COHERE_TRANSCRIBE, DownloadState.Idle, LlmModelVariant.Q8_0)
        )
    }

    @Test
    fun `each model says what uses it and whether it can go now`() {
        val facts = ModelStorageReader.factsOf(AppSettings(llmEnabled = true, llmModelVariant = LlmModelVariant.Q8_0), RecordingLanguage.ENGLISH_OR_GERMAN, true)
        val models = listOf(
            ModelOnPhone(ModelRef.Speech(SpeechModelId.PARAKEET_V3), 670_000_000L, whole = true),
            ModelOnPhone(ModelRef.Ai(LlmModelVariant.Q8_0), 1_069_000_000L, whole = true),
            ModelOnPhone(ModelRef.Ai(LlmModelVariant.IQ4_NL), 650_000_000L, whole = true)
        )

        val stored = ModelStorageViewModel.storedModels(models, facts, ModelActivity(recording = true))

        assertEquals(setOf(ModelUse.RECORDING), stored[0].uses)
        assertEquals(RemoveRefusal.NEEDED_TO_RECORD, stored[0].refusal)
        assertEquals(setOf(ModelUse.AI_NOTES), stored[1].uses)
        assertEquals(RemoveRefusal.IN_USE_NOW, stored[1].refusal)
        assertTrue(stored[2].uses.isEmpty())
        assertNull(stored[2].refusal)
    }

    @Test
    fun `the free space is that of the nearest folder there is`() {
        val models = File(tmp.root, "models").apply { mkdirs() }

        assertEquals(models, ModelStorageViewModel.nearestFolder(File(models, "stt/parakeet")))
        assertEquals(models, ModelStorageViewModel.nearestFolder(models))
    }
}
