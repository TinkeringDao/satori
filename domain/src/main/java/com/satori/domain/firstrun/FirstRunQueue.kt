package com.satori.domain.firstrun

import com.satori.domain.model.PauseReason

/**
 * What the first run's download queue does now, decided from the facts alone so that every
 * pairing is tested in a table: the part to work on ([next]) and whether it may download now
 * ([step]). The coordinator, the first run's one writer, asks after every event and carries the
 * answer out. The recorder's gate, which holds every model download while a recorder starts,
 * records or saves, is the download manager's: it holds what this lets run, so a recording never
 * needs a decision here.
 */
object FirstRunQueue {

    /**
     * The part to work on: the first of [plan] that isn't on the phone yet, in the plan's order
     * ([FirstRunPlan.next]), once Download was tapped or the setup was found on the phone; a part
     * after the first recording only once that is saved ([firstRecordingSaved]); and neither AI
     * model nor the reader while AI notes are off ([aiNotesOn], [FirstRunPart.forAiNotes]), as
     * nothing would use them. Null when there is nothing to do.
     */
    fun next(plan: FirstRunPlan, stage: FirstRunStage, firstRecordingSaved: Boolean, aiNotesOn: Boolean): FirstRunPlan.Item? {
        if (stage == FirstRunStage.GET_READY) return null
        val wanted = if (aiNotesOn) plan else FirstRunPlan(plan.items.filterNot { it.part.forAiNotes })
        return wanted.next(firstRecordingSaved)
    }

    /**
     * What happens to [item], the part [next] gave, given [facts]. Pause holds the setup and
     * nothing else; then what only he can change comes first (the space, a refused connection),
     * then the network, then a retry that is due later. A part that arrived damaged twice waits
     * for Try again ([FirstRunPlan.Item.waitsForTap]) and holds the queue, which runs in order.
     */
    fun step(item: FirstRunPlan.Item?, facts: QueueFacts): QueueStep {
        if (item == null) return QueueStep.Idle
        val part = item.part
        return when {
            facts.userPaused && part.beforeFirstRecording -> QueueStep.Hold(part, PauseReason.USER)
            item.waitsForTap -> QueueStep.WaitsForTap(part)
            facts.spaceShort -> QueueStep.Hold(part, PauseReason.NOT_ENOUGH_STORAGE)
            facts.blocked -> QueueStep.Hold(part, PauseReason.BLOCKED)
            else -> {
                val held = NetworkPolicy.verdict(facts.network, facts.mobileDataAllowed).pauseReason ?: facts.retryWait
                if (held != null) QueueStep.Hold(part, held) else QueueStep.Run(part)
            }
        }
    }
}

/**
 * Besides the plan, what decides whether the part to work on may download now
 * ([FirstRunQueue.step]).
 *
 * @property userPaused Pause was tapped on Getting ready, and not Continue
 * @property network The default network now
 * @property mobileDataAllowed Whether this part may use a metered network ([NetworkPolicy.mayUseMobileData])
 * @property spaceShort The free space is short of what this part still needs and the headroom
 *   ([FirstRunPlan.fits])
 * @property blocked Its last try was refused although the phone is online
 *   ([com.satori.domain.model.DownloadFailure.BLOCKED]), and nothing since, Try again or another
 *   network, gives a reason to try again
 * @property retryWait A retry that is due later: after a failed connect ([PauseReason.NO_NETWORK])
 *   or a server error ([PauseReason.SERVER]); null when none is
 */
data class QueueFacts(
    val userPaused: Boolean = false,
    val network: NetworkFacts = NetworkFacts.WIFI,
    val mobileDataAllowed: Boolean = false,
    val spaceShort: Boolean = false,
    val blocked: Boolean = false,
    val retryWait: PauseReason? = null
)

/** What the queue does with the part to work on ([FirstRunQueue.step]). */
sealed interface QueueStep {

    /** Nothing to download now. */
    data object Idle : QueueStep

    /** Download [part], or let it go on. */
    data class Run(val part: FirstRunPart) : QueueStep

    /** [part] is next but held for [reason]; it goes on by itself, or with the tap [reason] asks for. */
    data class Hold(val part: FirstRunPart, val reason: PauseReason) : QueueStep

    /** [part] arrived damaged twice in a row: it waits for Try again. */
    data class WaitsForTap(val part: FirstRunPart) : QueueStep
}
