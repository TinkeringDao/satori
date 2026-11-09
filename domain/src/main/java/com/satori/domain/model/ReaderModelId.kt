package com.satori.domain.model

/**
 * The builds of the reader, the model that reads a Malayalam line into English (0.8.0): AI4Bharat
 * IndicTrans2 distilled to 200M parameters, Indic to English, as one uploader's ONNX export, each
 * build pinned to a commit with every file's size and SHA-256 (the registry `ReaderModels` in the
 * data module). A kind of model of its own beside the speech models ([SpeechModelId], whose
 * entries are all speech recognizers) and the AI models, in a folder of its own.
 *
 * @property id Stable id: the build's folder under the models' directory (`models/reader/<id>`)
 *   and its name in the field log; never changed once shipped
 * @property variant The short name the debug tools take (`--es variant int8|fp16`)
 */
enum class ReaderModelId(val id: String, val variant: String) {
    /** The int8 build, weights quantized per channel, 242 MB: the one Satori reads with. */
    INT8("indictrans2-indic-en-dist-200m-int8", "int8"),

    /**
     * The fp16 build, 466 MB, the pinned fallback should int8 read too poorly (V2 between 58 and
     * 60.5 chrF++). Its memory on ONNX Runtime's CPU path is not known yet (a V1 arm).
     */
    FP16("indictrans2-indic-en-dist-200m-fp16", "fp16");

    companion object {
        /** The build a debug tool names, [INT8] for none; null for a name that is no build's. */
        fun ofVariant(name: String?): ReaderModelId? {
            val given = name?.trim().orEmpty()
            if (given.isEmpty()) return INT8
            return entries.firstOrNull { it.variant == given || it.id == given }
        }
    }
}
