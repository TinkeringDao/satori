package com.satori.presentation.main

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveModelTest {

    private val gate = AiWorkGate()
    private var freed = 0

    // Freeing the model waits for this, so a test can look at the gate meanwhile.
    private var unload = CompletableDeferred(Unit)

    private fun TestScope.liveModel() = LiveModel(
        gate,
        freeModel = {
            freed++
            unload.await()
        },
        closeScope = CoroutineScope(StandardTestDispatcher(testScheduler))
    )

    @Test
    fun `a turn is taken only while the engine is free, and given back once`() = runTest {
        val live = liveModel()

        val turn = live.tryTake()
        assertNotNull(turn)
        assertEquals(AiWorkGate.LIVE_NOTE, gate.holder.value)
        assertNull(live.tryTake())

        turn!!.giveBack()
        assertNull(gate.holder.value)
        assertNotNull(live.tryTake())
        assertEquals(0, freed)
    }

    @Test
    fun `a turn given back twice can't free the engine under whoever took it since`() = runTest {
        val live = liveModel()
        val first = live.tryTake()!!
        first.giveBack()

        // The next live note, then the finisher.
        val next = live.tryTake()!!
        first.giveBack()
        assertEquals(AiWorkGate.LIVE_NOTE, gate.holder.value)
        next.giveBack()
        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))
        next.giveBack()

        assertEquals(AiWorkGate.FINISHER, gate.holder.value)
    }

    @Test
    fun `a recorder that closes while the engine is free frees the model holding it, without waiting`() = runTest {
        val live = liveModel()
        unload = CompletableDeferred()

        assertEquals(LiveModel.Closing.Now, live.close())

        // Under way, holding the engine, and close() has returned.
        assertEquals(1, freed)
        assertEquals(AiWorkGate.RECORDER_RELEASE, gate.holder.value)
        unload.complete(Unit)
        runCurrent()
        assertNull(gate.holder.value)
    }

    @Test
    fun `a recorder that closes while its own work holds the turn has that work free the model`() = runTest {
        val live = liveModel()
        val load = live.tryTake()!!

        assertEquals(LiveModel.Closing.ByOwnWork, live.close())
        assertEquals(0, freed)
        assertEquals(AiWorkGate.LIVE_NOTE, gate.holder.value)

        load.giveBack()

        assertEquals(1, freed)
        assertNull(gate.holder.value)
        // Nothing of the recorder takes the engine any more.
        assertNull(live.tryTake())
    }

    @Test
    fun `a recorder that closes while someone else holds the engine leaves the model to them`() = runTest {
        val live = liveModel()
        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))

        assertEquals(LiveModel.Closing.LeftTo(AiWorkGate.FINISHER), live.close())

        runCurrent()
        assertEquals(0, freed)
        assertEquals(AiWorkGate.FINISHER, gate.holder.value)
    }

    @Test
    fun `a turn given back before the recorder closes keeps the model`() = runTest {
        val live = liveModel()
        live.tryTake()!!.giveBack()

        assertEquals(0, freed)
    }

    @Test
    fun `only a meeting or a translation with live notes loads the model as the recorder gets ready`() {
        fun loads(
            mode: RecordingMode = RecordingMode.SHORT_MEETING,
            strategy: InsightStrategy = InsightStrategy.REAL_TIME,
            mayLoadNow: Boolean = true,
            finishing: Boolean = false,
            language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN
        ) = LiveModel.loadsInBackground(mayLoadNow, strategy, mode, finishing, language.writesLiveNotes)

        assertTrue(loads())
        assertTrue(loads(mode = RecordingMode.REAL_TIME_TRANSLATION))
        // A voice note and a long meeting load it at the first note.
        assertFalse(loads(mode = RecordingMode.SIMPLE_LISTENING))
        assertFalse(loads(mode = RecordingMode.LONG_MEETING))
        // Notes after Stop only; a model that arrives while recording.
        assertFalse(loads(strategy = InsightStrategy.END_OF_SESSION))
        assertFalse(loads(mayLoadNow = false))
        // The finisher is about to take the engine for an older recording.
        assertFalse(loads(finishing = true))
        // A Malayalam meeting writes no live notes: its memory stays free for the speech model and the finishing.
        assertFalse(loads(language = RecordingLanguage.MALAYALAM))
    }

    @Test
    fun `the live model is registered again once the engine is free after anyone but a live note`() {
        assertTrue(LiveModel.registersAgain(AiWorkGate.FINISHER, null))
        assertTrue(LiveModel.registersAgain(AiWorkGate.MAP, null))
        assertTrue(LiveModel.registersAgain(AiWorkGate.RECORDER_RELEASE, null))
        assertFalse(LiveModel.registersAgain(AiWorkGate.LIVE_NOTE, null))
        assertFalse(LiveModel.registersAgain(null, null))
        assertFalse(LiveModel.registersAgain(null, AiWorkGate.FINISHER))
        assertFalse(LiveModel.registersAgain(AiWorkGate.FINISHER, AiWorkGate.MAP))
    }
}
