package com.satori.domain.model

/**
 * Identifies which LLM GGUF quantization variant to download and use.
 *
 * The variant is persisted in [AppSettings] so the user can switch at any time;
 * re-downloading the model is required after switching.
 *
 * @property writesLiveNotes Whether the model can be the one in [AppSettings.llmModelVariant],
 *   which writes the notes while recording, next to the speech model. False for a model too big
 *   for that: it only does the work after Stop, once the speech model is out of memory (see
 *   [com.satori.domain.repository.FinishingModelRepository]).
 */
enum class LlmModelVariant(val writesLiveNotes: Boolean = true) {
    /**
     * 8-bit quantization — higher accuracy, ~1 GB on disk.
     * Suited to flagship devices with ≥ 10 GB RAM and a modern CPU (Cortex-A78 / Oryon or newer).
     */
    Q8_0,

    /**
     * 4-bit non-linear quantization — ~650 MB on disk, lower CPU load and battery use.
     * Recommended for mid-range and older devices (e.g. Snapdragon 865 / Cortex-A77 era).
     */
    IQ4_NL,

    /**
     * Qwen 3.5 0.8B Q8_0 — alternative model architecture, ~870 MB on disk.
     * Beta: experimental, available only via manual download in Settings.
     * Not recommended automatically by [com.satori.data.device.DeviceTierDetector].
     */
    QWEN3_5_Q8_0,

    /**
     * Gemma 4 E2B, quantization-aware trained to Q4_0: about 3.3 GB on disk, a better writer than
     * Gemma 3 1B and slower. Next to the speech model (about 0.7 GB) it would need about 4.1 GB,
     * which the razr does not reliably have free (2.9 to 4.4 GB), so it never writes the live
     * notes: it writes the last notes, the name and the to-dos after Stop, and rewrites notes, in
     * place of the live model. Not recommended automatically, and never selected as the live model.
     */
    GEMMA4_E2B_Q4_0(writesLiveNotes = false);

    companion object {
        /** The models that can write the live notes, in the enum's order. */
        val forLiveNotes: List<LlmModelVariant> = entries.filter { it.writesLiveNotes }
    }
}
