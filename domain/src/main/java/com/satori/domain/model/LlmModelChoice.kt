package com.satori.domain.model

/** Which AI model to use when some are on the phone. */
object LlmModelChoice {

    /**
     * The model that writes the live notes: the [selected] one if its file is on the phone,
     * otherwise the one that suits the phone ([recommended]) if that is there, otherwise the first
     * of [installed] in the enum's order. Null when no such model is on the phone.
     *
     * Only models that can write live notes ([LlmModelVariant.writesLiveNotes]) count: Gemma 4 E2B
     * on its own is no model for a recording, it only takes over after Stop. A model copied over by
     * hand, or the one a person deleted, can leave the selection on a file that is not there while
     * another one is; this picks the one that works.
     *
     * @param recommended null where the phone's tier is not known (the recorder, the details page):
     *   then the first model on the phone stands in for the selected one.
     */
    fun pick(
        selected: LlmModelVariant,
        recommended: LlmModelVariant?,
        installed: Set<LlmModelVariant>
    ): LlmModelVariant? {
        val usable = installed.filterTo(mutableSetOf()) { it.writesLiveNotes }
        return when {
            selected in usable -> selected
            recommended != null && recommended in usable -> recommended
            else -> LlmModelVariant.forLiveNotes.firstOrNull { it in usable }
        }
    }
}
