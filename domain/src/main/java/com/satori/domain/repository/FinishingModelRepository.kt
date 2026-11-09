package com.satori.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Which AI model does the work after Stop: the last notes, the name and the to-dos of a recording,
 * and a rewrite of its notes.
 *
 * The live notes are written by the model picked in the AI model list, next to the speech model.
 * After Stop the speech model is out of memory, which leaves room for a bigger model that could
 * not run next to it: Gemma 4 E2B ([com.satori.domain.model.LlmModelVariant.GEMMA4_E2B_Q4_0]).
 * While its file is on the phone it does that work unless the user has switched it off; without
 * the file the live model does everything, as before.
 *
 * Kept apart from [SettingsRepository] on purpose: it is read once per recording, at Stop, and
 * needs the file on the phone as much as the user's choice.
 */
interface FinishingModelRepository {

    /** Whether Gemma 4 E2B does the work after Stop while its file is on the phone; true until switched off. */
    fun observeEnabled(): Flow<Boolean>

    /** Stores whether Gemma 4 E2B does the work after Stop. */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * The file of Gemma 4 E2B when it is switched on and on the phone, else null, and the live
     * model does the work after Stop as well. Looks at the disk, not at the file's contents:
     * [LlmRepository.switchModel] checks those before it loads anything.
     */
    suspend fun enabledModelPath(): String?
}
