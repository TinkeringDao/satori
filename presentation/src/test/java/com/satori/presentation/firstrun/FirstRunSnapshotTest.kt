package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSnapshotTest {

    @Test
    fun `whatever reads the coordinator sees the snapshot it holds and asks through it`() {
        val coordinator = FakeFirstRunCoordinator()
        val plan = FirstRunPlan.initial(670_478_772L, 137_566_803L, 1_069_306_368L)
        coordinator.snapshot.value = FirstRunSnapshot(stage = FirstRunStage.READY, plan = plan)

        assertEquals(FirstRunScreen.GETTING_READY, coordinator.state.value.screen)
        assertTrue(coordinator.state.value.aiModelPending)
        assertTrue(coordinator.state.value.firstRecordingAhead)

        coordinator.lookAround()
        coordinator.recordingSaved()
        assertEquals(listOf("lookAround", "recordingSaved"), coordinator.calls)

        coordinator.snapshot.value = coordinator.state.value.copy(stage = FirstRunStage.DONE, plan = plan.done(FirstRunPart.AI_MODEL))
        assertFalse(coordinator.state.value.aiModelPending)
        assertFalse(coordinator.state.value.firstRecordingAhead)
        assertEquals(FirstRunScreen.HOME, coordinator.state.value.screen)
    }
}
