package com.satori.domain.repository

/**
 * A model was not loaded because too little memory is free for it: [availableBytes] were free,
 * once the model in memory was out, and [neededBytes] were asked for, its file and the margin the
 * caller wants kept free beside it ([LlmRepository.switchModel]). Nothing is in memory afterwards,
 * as for any failed switch.
 *
 * The finisher of recordings tells this apart from a load that failed: while a capture runs, too
 * little room means waiting until it ends, not finishing on the smaller model, and with none,
 * trying again for a while before the smaller model does the work (FinishRecordingUseCase's model
 * step).
 */
class NotEnoughMemoryException(
    val availableBytes: Long,
    val neededBytes: Long
) : IllegalStateException(
    "Not enough free memory: ${availableBytes / BYTES_PER_MB} MB available, ${neededBytes / BYTES_PER_MB} MB needed"
) {
    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}
