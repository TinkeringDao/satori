package com.satori.domain.model

import com.satori.domain.model.LlmModelVariant.IQ4_NL
import com.satori.domain.model.LlmModelVariant.Q8_0
import com.satori.domain.model.LlmModelVariant.QWEN3_5_Q8_0
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStatusRulesTest {

    private fun downloading(percent: Int) = DownloadState.Downloading(DownloadProgress(percent * 10L, 1_000L, percent))

    private fun ai(
        variant: LlmModelVariant = Q8_0,
        selected: LlmModelVariant = Q8_0,
        installed: Boolean = false,
        download: DownloadState = DownloadState.Idle,
        downloadVariant: LlmModelVariant? = null,
        partialBytes: Long = 0L
    ) = ModelStatusRules.ai(variant, selected, installed, download, downloadVariant, partialBytes)

    // AI models

    @Test
    fun `a model on the phone is in use when it is the selected one`() {
        assertEquals(AiModelStatus.Installed(inUse = true), ai(installed = true, selected = Q8_0))
    }

    @Test
    fun `a model on the phone that is not selected is installed but not in use`() {
        assertEquals(AiModelStatus.Installed(inUse = false), ai(variant = IQ4_NL, selected = Q8_0, installed = true))
    }

    @Test
    fun `the file settles it, whatever an older download said`() {
        val status = ai(
            installed = true,
            download = DownloadState.Error("stopped", DownloadFailure.NO_CONNECTION),
            downloadVariant = Q8_0,
            partialBytes = 500L
        )

        assertEquals(AiModelStatus.Installed(inUse = true), status)
    }

    @Test
    fun `a running download shows on the row of its own model`() {
        assertEquals(
            AiModelStatus.Downloading(40),
            ai(variant = IQ4_NL, download = downloading(40), downloadVariant = IQ4_NL)
        )
    }

    @Test
    fun `a running download of another model is not shown here`() {
        assertEquals(
            AiModelStatus.NotInstalled,
            ai(variant = Q8_0, download = downloading(40), downloadVariant = IQ4_NL)
        )
    }

    @Test
    fun `a download that is starting shows zero percent`() {
        assertEquals(
            AiModelStatus.Downloading(0),
            ai(download = downloading(0), downloadVariant = Q8_0)
        )
    }

    @Test
    fun `a failed download shows its reason on the row of its own model`() {
        val failed = DownloadState.Error("no route", DownloadFailure.NO_CONNECTION)

        assertEquals(AiModelStatus.Failed(DownloadFailure.NO_CONNECTION), ai(download = failed, downloadVariant = Q8_0))
        assertEquals(AiModelStatus.NotInstalled, ai(variant = QWEN3_5_Q8_0, download = failed, downloadVariant = Q8_0))
    }

    @Test
    fun `what is left of an unfinished download is a paused one`() {
        assertEquals(AiModelStatus.Paused, ai(partialBytes = 400_000_000L))
    }

    @Test
    fun `a failure that names no model shows as paused, not as a failure`() {
        // It is not any row's failure; the partial file is the fact.
        val unnamed = DownloadState.Error("Download interrupted at 400MB.")

        assertEquals(AiModelStatus.Paused, ai(download = unnamed, downloadVariant = null, partialBytes = 400_000_000L))
    }

    @Test
    fun `a failed download of this model wins over its partial file`() {
        val failed = DownloadState.Error("HTTP 503", DownloadFailure.SERVER)

        assertEquals(
            AiModelStatus.Failed(DownloadFailure.SERVER),
            ai(download = failed, downloadVariant = Q8_0, partialBytes = 100L)
        )
    }

    @Test
    fun `nothing on the phone and nothing running is not installed`() {
        assertEquals(AiModelStatus.NotInstalled, ai())
    }

    // Speech models

    private fun speech(
        model: SpeechModelId = SpeechModelId.PARAKEET_V3,
        installed: Boolean = false,
        download: DownloadState = DownloadState.Idle,
        downloadModel: SpeechModelId? = null,
        downloadedBytes: Long = 0L
    ) = ModelStatusRules.speechModel(model, installed, download, downloadModel, downloadedBytes)

    @Test
    fun `a speech model is installed when its files are there`() {
        assertEquals(SpeechModelStatus.Installed, speech(installed = true))
        assertEquals(
            SpeechModelStatus.Installed,
            speech(installed = true, download = DownloadState.Completed, downloadModel = SpeechModelId.PARAKEET_V3)
        )
    }

    @Test
    fun `a speech model with nothing on the phone is not installed`() {
        assertEquals(SpeechModelStatus.NotInstalled, speech())
    }

    @Test
    fun `a download whose files are being checked still reads as downloading, not paused`() {
        val checking = DownloadState.Verifying(DownloadProgress(1_000L, 1_000L, 100))
        // Its parts are on the phone: without the check it would read as paused for those seconds.
        assertEquals(
            SpeechModelStatus.Downloading(100),
            speech(model = SpeechModelId.INDICCONFORMER_ML, download = checking, downloadModel = SpeechModelId.INDICCONFORMER_ML, downloadedBytes = 1_000L)
        )
        assertEquals(AiModelStatus.Downloading(100), ai(variant = IQ4_NL, download = checking, downloadVariant = IQ4_NL, partialBytes = 1_000L))
    }

    @Test
    fun `a running speech download shows its percentage on the row of its own model`() {
        assertEquals(
            SpeechModelStatus.Downloading(25),
            speech(model = SpeechModelId.COHERE_TRANSCRIBE, download = downloading(25), downloadModel = SpeechModelId.COHERE_TRANSCRIBE)
        )
        assertEquals(
            SpeechModelStatus.NotInstalled,
            speech(model = SpeechModelId.PARAKEET_V2_EN, download = downloading(25), downloadModel = SpeechModelId.COHERE_TRANSCRIBE)
        )
    }

    @Test
    fun `downloading a model again shows the download, not the files it replaces`() {
        assertEquals(
            SpeechModelStatus.Downloading(0),
            speech(installed = true, download = downloading(0), downloadModel = SpeechModelId.PARAKEET_V3)
        )
    }

    @Test
    fun `a failed speech download shows its reason on its own row only`() {
        val failed = DownloadState.Error("full", DownloadFailure.NOT_ENOUGH_STORAGE)

        assertEquals(
            SpeechModelStatus.Failed(DownloadFailure.NOT_ENOUGH_STORAGE),
            speech(download = failed, downloadModel = SpeechModelId.PARAKEET_V3, downloadedBytes = 100L)
        )
        assertEquals(
            SpeechModelStatus.Installed,
            speech(model = SpeechModelId.PARAKEET_V2_EN, installed = true, download = failed, downloadModel = SpeechModelId.PARAKEET_V3)
        )
    }

    @Test
    fun `what is left of an unfinished speech download is a paused one`() {
        assertEquals(SpeechModelStatus.Paused, speech(model = SpeechModelId.COHERE_TRANSCRIBE, downloadedBytes = 3_090_822L))
    }
}
