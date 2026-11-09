package com.satori.data.firstrun

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.model.DownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunStoreTest {

    private val prefs = MemoryPrefs()
    private val store = FirstRunStore(prefs)
    private val sizes: (FirstRunPart) -> Long = { part -> (part.ordinal + 1) * 1_000L }

    @Test
    fun `a fresh install is at get ready, with Malayalam wanted and nothing planned`() {
        assertEquals(StoredFirstRun(), store.read(sizes))
    }

    @Test
    fun `an install that finished the earlier onboarding is done`() {
        prefs.values[FirstRunPrefs.ONBOARDING_COMPLETE] = true

        assertEquals(FirstRunStage.DONE, store.read(sizes).stage)
    }

    @Test
    fun `everything written reads back, in one commit`() {
        val plan = FirstRunPlan.initial(1_000L, 2_000L, 3_000L)
            .done(FirstRunPart.SPEECH)
            .failed(FirstRunPart.MALAYALAM, DownloadFailure.SERVER)
            .withBiggerModel(4_000L)
        val written = StoredFirstRun(
            stage = FirstRunStage.DOWNLOADING,
            stageAtMs = 1_700_000_000_000L,
            lookedAround = true,
            malayalamWanted = false,
            consentAtMs = 1_700_000_000_001L,
            mobileOkForSetup = true,
            mobileOkParts = setOf(FirstRunPart.AI_MODEL),
            userPaused = true,
            firstRecordingSaved = true,
            aiDownloadStarted = true,
            plan = plan
        )

        store.write(written)

        assertEquals(1, prefs.commits)
        assertEquals(written, store.read(sizes))
    }

    @Test
    fun `the earlier onboarding's flag follows the stage, for what still reads it`() {
        store.write(StoredFirstRun(stage = FirstRunStage.DOWNLOADING))
        assertEquals(false, prefs.values[FirstRunPrefs.ONBOARDING_COMPLETE])

        store.write(StoredFirstRun(stage = FirstRunStage.READY))
        assertEquals(true, prefs.values[FirstRunPrefs.ONBOARDING_COMPLETE])
        assertEquals("READY", prefs.values[FirstRunPrefs.STAGE])
    }

    @Test
    fun `no plan leaves no key`() {
        store.write(StoredFirstRun(plan = FirstRunPlan.initial(1L, null, null)))
        assertTrue(prefs.contains(FirstRunPrefs.PLAN))

        store.write(StoredFirstRun(plan = null))
        assertFalse(prefs.contains(FirstRunPrefs.PLAN))
    }

    @Test
    fun `the plan is stored part by part, its sizes taken from the registry as it is read`() {
        val plan = FirstRunPlan.initial(670L, 138L, null).done(FirstRunPart.SPEECH).failed(FirstRunPart.MALAYALAM, DownloadFailure.OTHER)

        val text = FirstRunStore.textOf(plan)

        assertEquals("SPEECH:DONE:0:0,MALAYALAM:PENDING:1:0", text)
        assertEquals(
            listOf(FirstRunPart.SPEECH to 1_000L, FirstRunPart.MALAYALAM to 2_000L),
            FirstRunStore.planOf(text, sizes).items.map { it.part to it.bytes }
        )
    }

    @Test
    fun `a part or a status this version doesn't know is left out, and so is a broken entry`() {
        val plan = FirstRunStore.planOf("SPEECH:DONE:0:0,TELEPATHY:PENDING:0:0,MALAYALAM:LOST:0:0,AI_MODEL:PENDING,AI_MODEL:PENDING:x:-3", sizes)

        assertEquals(listOf(FirstRunPart.SPEECH, FirstRunPart.AI_MODEL), plan.items.map { it.part })
        assertEquals(0, plan.item(FirstRunPart.AI_MODEL)?.damaged)
        assertEquals(0, plan.item(FirstRunPart.AI_MODEL)?.serverErrors)
    }

    @Test
    fun `the microphone's refusal is kept until it is cleared`() {
        assertNull(store.microphone)

        store.microphone = "REFUSED_ONCE"
        assertEquals("REFUSED_ONCE", store.microphone)

        store.microphone = null
        assertNull(store.microphone)
    }
}
