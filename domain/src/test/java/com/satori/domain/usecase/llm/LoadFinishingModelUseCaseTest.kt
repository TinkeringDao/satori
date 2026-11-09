package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.LARGE_MODEL
import com.satori.domain.fakes.LIVE_MODEL
import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.modelfile.ModelDamagedException
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.usecase.finish.RoomLlmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which model does the work after Stop, and what is left when Gemma 4 E2B can't be loaded. */
class LoadFinishingModelUseCaseTest {

    private fun useCase(llm: TwoModelLlmRepository, finishing: FakeFinishingModelRepository) =
        LoadFinishingModelUseCase(llm, finishing)

    @Test
    fun `E2B on the phone and switched on replaces the live model`() = runBlocking {
        val llm = TwoModelLlmRepository(answers = listOf("ok"))

        val model = useCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        assertEquals(FinishingModel.Switched, model)
        assertEquals(listOf("switch $LARGE_MODEL"), llm.log)
        assertEquals(LARGE_MODEL, llm.loaded)
        assertEquals(LARGE_MODEL, llm.registered)
    }

    @Test
    fun `a reserve is kept free on top of the margin, and one below 0 is none`() = runBlocking {
        val llm = RoomLlmRepository(TwoModelLlmRepository(answers = listOf("ok")))
        val load = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))

        assertEquals(FinishingModel.Switched, load(reserveBytes = 500L * 1_048_576L))
        load(reserveBytes = -1L)
        load()

        val margin = LoadFinishingModelUseCase.E2B_MARGIN_BYTES
        assertEquals(listOf(margin + 500L * 1_048_576L, margin, margin), llm.margins)
    }

    @Test
    fun `E2B switched off leaves the live model in memory`() = runBlocking {
        val llm = TwoModelLlmRepository(answers = listOf("ok"))

        val model = useCase(llm, FakeFinishingModelRepository(enabled = false, path = LARGE_MODEL))()

        assertEquals(FinishingModel.Live, model)
        assertTrue(llm.log.isEmpty())
        assertEquals(LIVE_MODEL, llm.loaded)
    }

    @Test
    fun `without the E2B file nothing changes`() = runBlocking {
        val llm = TwoModelLlmRepository(answers = listOf("ok"))

        val model = useCase(llm, FakeFinishingModelRepository(path = null))()

        assertEquals(FinishingModel.Live, model)
        assertTrue(llm.log.isEmpty())
    }

    @Test
    fun `E2B that does not fit in memory falls back, and the next step loads the live model again`() = runBlocking {
        val tooBig = IllegalStateException("Not enough free memory for $LARGE_MODEL")
        val llm = TwoModelLlmRepository(answers = listOf("ok"), switchFailure = tooBig)

        val model = useCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        assertEquals(FinishingModel.FellBack(tooBig), model)
        assertEquals(LIVE_MODEL, llm.registered)
        llm.reloadModel()
        assertEquals(listOf("switch $LARGE_MODEL", "load $LIVE_MODEL"), llm.log)
    }

    @Test
    fun `a damaged E2B file falls back with the live model still in memory`() = runBlocking {
        val damaged = ModelDamagedException("The AI model file is damaged or incomplete")
        val llm = TwoModelLlmRepository(answers = listOf("ok"), switchFailure = damaged, switchUnloads = false)

        val model = useCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        assertSame(damaged, (model as FinishingModel.FellBack).cause)
        assertEquals(LIVE_MODEL, llm.loaded)
        llm.reloadModel()
        assertEquals(listOf("switch $LARGE_MODEL"), llm.log)
    }

    @Test
    fun `a choice that cannot be read falls back without touching the models`() = runBlocking {
        val unreadable = IllegalStateException("settings unreadable")
        val llm = TwoModelLlmRepository(answers = listOf("ok"))

        val model = useCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL, readFailure = unreadable))()

        assertEquals(FinishingModel.FellBack(unreadable), model)
        assertTrue(llm.log.isEmpty())
        assertEquals(LIVE_MODEL, llm.loaded)
    }

    @Test
    fun `every load of E2B asks for its file and 600 MB to spare, and less falls back with the memory figures`() = runBlocking {
        val models = TwoModelLlmRepository(answers = listOf("ok"))
        val llm = RoomLlmRepository(models, roomBytes = 500L * MB)

        val model = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        val cause = (model as FinishingModel.FellBack).cause as NotEnoughMemoryException
        assertEquals(100L * MB, cause.neededBytes - cause.availableBytes)
        assertEquals(listOf(600L * MB), llm.margins)
        assertEquals(LIVE_MODEL, models.registered)
        assertNull(models.loaded)
    }

    @Test
    fun `room for the margin switches E2B in`() = runBlocking {
        val models = TwoModelLlmRepository(answers = listOf("ok"))
        val llm = RoomLlmRepository(models, roomBytes = 600L * MB)

        val model = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        assertEquals(FinishingModel.Switched, model)
        assertEquals(LARGE_MODEL, models.loaded)
        assertEquals(listOf(600L * MB), llm.margins)
    }

    @Test
    fun `E2B with only its file's worth free stays out, as the plain switch is never used`() = runBlocking {
        val models = TwoModelLlmRepository(answers = listOf("ok"))
        // Room for the file and nothing more, which the plain switch would let through: it is
        // measured as a margin of nothing here, as the app's repository measures it.
        val llm = RoomLlmRepository(models, roomBytes = 0, measuresPlainSwitch = true)

        val model = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))()

        assertTrue((model as FinishingModel.FellBack).cause is NotEnoughMemoryException)
        assertEquals(listOf(600L * MB), llm.margins)
        assertEquals(listOf("no room for $LARGE_MODEL", "unload"), models.log)
    }

    @Test
    fun `the margin is 600 MB`() {
        assertEquals(600L * MB, LoadFinishingModelUseCase.E2B_MARGIN_BYTES)
    }

    @Test
    fun `a cancelled switch is passed on, not reported as a fallback`() {
        val llm = TwoModelLlmRepository(answers = listOf("ok"), switchFailure = CancellationException("left"))

        assertThrows(CancellationException::class.java) {
            runBlocking { useCase(llm, FakeFinishingModelRepository(path = LARGE_MODEL))() }
        }
    }

    private companion object {
        const val MB = 1_048_576L
    }
}
