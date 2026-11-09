package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A first-run coordinator for the tests of whatever reads one: its state is set by the test
 * ([snapshot]), and every request is recorded in [calls], in order, as "name" or "name arguments".
 */
class FakeFirstRunCoordinator(initial: FirstRunSnapshot = FirstRunSnapshot()) : FirstRunCoordinator {

    val snapshot = MutableStateFlow(initial)
    override val state: StateFlow<FirstRunSnapshot> = snapshot

    val calls = mutableListOf<String>()

    override fun start() { calls += "start" }
    override fun download(malayalam: Boolean, useMobileData: Boolean) { calls += "download malayalam=$malayalam mobile=$useMobileData" }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun retry(part: FirstRunPart) { calls += "retry $part" }
    override fun useMobileData(part: FirstRunPart) { calls += "useMobileData $part" }
    override fun lookAround() { calls += "lookAround" }
    override fun recordingSaved() { calls += "recordingSaved" }
    override fun getBiggerModel() { calls += "getBiggerModel" }
}
