package com.satori.domain.usecase.llm

import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.LlmRepository
import kotlinx.coroutines.CancellationException

/** Which model does the work after Stop, as [LoadFinishingModelUseCase] left it. */
sealed interface FinishingModel {

    /** The model of the live notes: Gemma 4 E2B is switched off, not on the phone, or was not asked for. */
    data object Live : FinishingModel

    /** Gemma 4 E2B is in memory, in place of the model of the live notes. */
    data object Switched : FinishingModel

    /**
     * Gemma 4 E2B could not be loaded ([cause]: too little free memory, a damaged file, a native
     * error). The model of the live notes does the work, loaded again by the first step.
     */
    data class FellBack(val cause: Throwable) : FinishingModel
}

/**
 * Puts the AI model for the work after Stop in memory: Gemma 4 E2B, in place of the model of the
 * live notes, when the user has it switched on and its file is on the phone
 * ([FinishingModelRepository]); otherwise nothing changes.
 *
 * Call it once per recording, before the first step that needs the model and with the speech
 * model out of memory: E2B (about 3.4 GB) next to Parakeet (about 0.7 GB) is more than the phone
 * reliably has free. The model of the live notes stays registered until E2B is in memory
 * ([LlmRepository.switchModel]), so when E2B can't be loaded the steps load the live model again
 * and the recording gets the smaller model's notes, name and to-dos instead of none.
 *
 * Every load of E2B in the app comes through here: the finisher of recordings, Rewrite notes,
 * Find to-dos and ideas again, and the mind map's work. Each asks for the same room,
 * [E2B_MARGIN_BYTES] free beyond the file, so none of them can push the phone into memory
 * pressure; there is no way to ask for less. The finisher asks for more beside a capture whose
 * speech model can still grow ([invoke]'s reserve).
 *
 * Releasing E2B is the caller's: the same [LlmRepository.cleanup] that freed the live model after
 * finishing frees it.
 *
 * @property llmRepository Swaps the models
 * @property finishingModels Whether E2B is switched on, and where its file is
 */
class LoadFinishingModelUseCase(
    private val llmRepository: LlmRepository,
    private val finishingModels: FinishingModelRepository
) {
    /**
     * @param reserveBytes Memory to keep free on top of [E2B_MARGIN_BYTES]: what a live capture's
     *   speech model may still take beside E2B (a switch of a Malayalam recording back to English
     *   or German loads Parakeet v3 in place of the smaller model). None below 0.
     * @return Which model the work runs on. Less memory free than E2B's file, [E2B_MARGIN_BYTES]
     *   and [reserveBytes] is a [FinishingModel.FellBack] with a
     *   [com.satori.domain.repository.NotEnoughMemoryException], and no model is in memory
     *   then. A choice that can't be read counts as a failure to load E2B, so the work still runs
     *   on the live model; throws only [CancellationException].
     */
    suspend operator fun invoke(reserveBytes: Long = 0L): FinishingModel {
        val path = try {
            finishingModels.enabledModelPath() ?: return FinishingModel.Live
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FinishingModel.FellBack(e)
        }
        val switched = llmRepository.switchModel(path, E2B_MARGIN_BYTES + reserveBytes.coerceAtLeast(0L))
        val cause = switched.exceptionOrNull() ?: return FinishingModel.Switched
        if (cause is CancellationException) throw cause
        return FinishingModel.FellBack(cause)
    }

    /**
     * Puts the model of the live notes in memory, for work that runs on it ([invoke] gave
     * [FinishingModel.Live] or [FinishingModel.FellBack]): the load its first call to the model
     * would make ([LlmRepository.reloadModel]), made before that call, so the work learns at once
     * whether the model is there. Nothing changes when it is in memory already.
     *
     * @return A failure when it can't be loaded; a
     *   [com.satori.domain.repository.LoadStoppedForMemoryException] when the phone stayed
     *   short of memory as it loaded, which loading it again later may mend
     */
    suspend fun loadLive(): Result<Unit> = llmRepository.reloadModel()

    companion object {
        /**
         * The memory Gemma 4 E2B must leave free beyond its own file to load, measured once the
         * model in memory is out ([LlmRepository.switchModel]), for every load of it: Satori must
         * not push the phone into memory pressure (the owner, 2026-10-13). For the finisher of
         * recordings it is also the room for a capture's speech model and its audio beside it,
         * and less defers the run until the capture ends; with no capture live, less gives the
         * live model once the finisher's tries are over. For Rewrite notes, Find again and the
         * map, which run only with no recorder open, less gives the live model at once.
         *
         * On 2026-10-13 a load of E2B with about its file's worth free (some 3.2 GB; 0.5.0's bar
         * with no capture live, and Rewrite notes', Find again's and the map's until 0.5.2) put the
         * phone in direct reclaim, and the low-memory killer ended 45 cached apps in 2.6 s
         * (oom_score_adj 920-999); one beside a capture with 5.0 GB free ended none. At 12:12 a load
         * that passed this bar, about 5.0 GB free with no capture live, still ended 10 (900-999)
         * while llama.cpp read the whole file in: the bar is a floor, not yet a guarantee, and the
         * debug memtest (FIELD_LOG) measures what a load costs. The figure is checked on the
         * phone: the "model-switched" memory snapshot, and the system log's lowmemorykiller lines
         * around the load.
         */
        const val E2B_MARGIN_BYTES = 600L * 1_048_576L
    }
}
