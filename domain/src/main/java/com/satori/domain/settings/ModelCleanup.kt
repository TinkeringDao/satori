package com.satori.domain.settings

import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.SpeechModelId

/** A model the app can have on the phone: a speech model or an AI model. */
sealed interface ModelRef {
    /** The speech model [id]. */
    data class Speech(val id: SpeechModelId) : ModelRef

    /** The AI model [variant]. */
    data class Ai(val variant: LlmModelVariant) : ModelRef
}

/**
 * A model with files on the phone.
 *
 * @property bytes What its files take, whole ones and what an unfinished download left
 * @property whole Whether all of it is there; false for a download that stopped half way
 */
data class ModelOnPhone(val ref: ModelRef, val bytes: Long, val whole: Boolean)

/** What in the current settings uses a model. */
enum class ModelUse {
    /** Parakeet v3: every recording in English or German needs it. */
    RECORDING,

    /** The Malayalam model: the power key or Record as is set to Malayalam. */
    MALAYALAM,

    /**
     * The Malayalam model, whenever it is on the phone: the recorder's language key switches a
     * recording to it, and a recording in Malayalam is transcribed again with it after Stop and
     * with Transcribe again. Without it the key goes from the recorder.
     */
    LANGUAGE_KEY,

    /** A better speech model: "Better transcript after Stop" names it. */
    BETTER_TRANSCRIPT,

    /** The AI model chosen in Advanced settings, while AI notes are on. */
    AI_NOTES,

    /** Gemma 4 E2B, while AI notes and "AI for finished recordings" are on. */
    FINISHING
}

/**
 * The settings that decide which models are used ([ModelCleanup.usesOf]).
 *
 * @property malayalamChosen Whether the power key or home's Record as records in Malayalam
 */
data class ModelSettingsFacts(
    val aiNotesOn: Boolean,
    val chosenAi: LlmModelVariant,
    val finishingModelOn: Boolean,
    val betterTranscript: SpeechModelId?,
    val malayalamChosen: Boolean
)

/**
 * What runs now that may hold a model: a recorder starting, recording or saving; recordings being
 * finished; a Transcribe again; other work of the AI (Rewrite notes, Find to-dos again, the mind
 * map); a download.
 */
data class ModelActivity(
    val recording: Boolean = false,
    val finishing: Boolean = false,
    val transcribingAgain: Boolean = false,
    val aiWork: Boolean = false,
    val downloading: Set<ModelRef> = emptySet()
)

/** Why a model can't be removed now. */
enum class RemoveRefusal {
    /** Parakeet v3, which every recording in English or German needs: never here. */
    NEEDED_TO_RECORD,

    /** A recording, a finishing, a Transcribe again, other AI work or its download may hold it now. */
    IN_USE_NOW
}

/**
 * The model cleanup of Settings' Models and storage (the owner's feedback of 2026-10-14: "a lot of
 * models are downloaded which are unnecessary"). Pure, so the rules are tested without a phone:
 * which setting uses each model ([usesOf]), which of them may go now ([refusal]), and what "Remove
 * what isn't used" removes ([unused]): every whole model no setting uses, Parakeet v3 never, the
 * Malayalam model never (the recorder's language key offers it as long as it is there), and none
 * that something running may hold. Nothing goes without the owner's tap; a half download is never
 * in the list, as its download may be resumed (the first run resumes its own), but it can go alone.
 */
object ModelCleanup {

    /** Unused models taking this much or more are worth home's one hint (Q4 draws it). */
    const val HINT_BYTES = 1_000_000_000L

    /** What in the settings [facts] uses [ref]; empty for a model nothing uses. */
    fun usesOf(ref: ModelRef, facts: ModelSettingsFacts): Set<ModelUse> = when (ref) {
        is ModelRef.Speech -> when (ref.id) {
            SpeechModelId.PARAKEET_V3 -> setOf(ModelUse.RECORDING)
            // Ordered: the setting that names it first, then what any recording can use it for.
            SpeechModelId.INDICCONFORMER_ML -> setOfNotNull(ModelUse.MALAYALAM.takeIf { facts.malayalamChosen }, ModelUse.LANGUAGE_KEY)
            SpeechModelId.PARAKEET_V2_EN, SpeechModelId.COHERE_TRANSCRIBE ->
                setOfNotNull(ModelUse.BETTER_TRANSCRIPT.takeIf { facts.betterTranscript == ref.id })
        }
        is ModelRef.Ai -> when {
            !facts.aiNotesOn -> emptySet()
            ref.variant.writesLiveNotes -> setOfNotNull(ModelUse.AI_NOTES.takeIf { ref.variant == facts.chosenAi })
            else -> setOfNotNull(ModelUse.FINISHING.takeIf { facts.finishingModelOn })
        }
    }

    /**
     * Why [ref] can't be removed now, or null when it can: Parakeet v3 never; another model not
     * while something running may hold it ([mayHold]).
     */
    fun refusal(ref: ModelRef, activity: ModelActivity, facts: ModelSettingsFacts): RemoveRefusal? = when {
        ref == ModelRef.Speech(SpeechModelId.PARAKEET_V3) -> RemoveRefusal.NEEDED_TO_RECORD
        mayHold(ref, activity, facts) -> RemoveRefusal.IN_USE_NOW
        else -> null
    }

    /**
     * Whether something running may hold [ref] now, so removing it could end that work: its own
     * download; for a speech model a recording (the Malayalam model), the finishing (a better
     * transcript after Stop, a Malayalam recording transcribed again) or a Transcribe again; for
     * the chosen AI model a recording's live notes, the finishing or other AI work; for Gemma 4
     * E2B the finishing or other AI work. Another AI model nothing chose is held by nothing. Wide
     * on purpose: which speech model a Transcribe again reads isn't known here. The Malayalam
     * model's entry holds the reader too (0.8.0), which the finishing and other AI work read with
     * (the readings a page's work makes first), and its download.
     */
    fun mayHold(ref: ModelRef, activity: ModelActivity, facts: ModelSettingsFacts): Boolean {
        if (ref in activity.downloading) return true
        return when (ref) {
            is ModelRef.Speech -> when (ref.id) {
                SpeechModelId.PARAKEET_V3 -> activity.recording || activity.finishing || activity.transcribingAgain
                SpeechModelId.INDICCONFORMER_ML ->
                    activity.recording || activity.finishing || activity.transcribingAgain || activity.aiWork
                SpeechModelId.PARAKEET_V2_EN, SpeechModelId.COHERE_TRANSCRIBE ->
                    activity.finishing || activity.transcribingAgain
            }
            is ModelRef.Ai -> when {
                ref.variant.writesLiveNotes && ref.variant == facts.chosenAi ->
                    activity.recording || activity.finishing || activity.aiWork
                !ref.variant.writesLiveNotes -> activity.finishing || activity.aiWork
                else -> false
            }
        }
    }

    /**
     * What "Remove what isn't used" removes from [models]: the whole ones no setting uses and
     * nothing running holds, in the order given, with what they free together. Those nothing uses
     * that something running holds are [waiting]: the screen says they can go once it ends.
     */
    fun unused(models: List<ModelOnPhone>, facts: ModelSettingsFacts, activity: ModelActivity): CleanupPlan {
        val candidates = models.filter { it.whole && usesOf(it.ref, facts).isEmpty() && it.ref != ModelRef.Speech(SpeechModelId.PARAKEET_V3) }
        val (waiting, removable) = candidates.partition { mayHold(it.ref, activity, facts) }
        return CleanupPlan(removable, waiting)
    }

    /** Whether home's one hint is due: unused models ([unused], waiting ones too) take [HINT_BYTES] or more. */
    fun hintDue(plan: CleanupPlan): Boolean = plan.unusedBytes >= HINT_BYTES
}

/**
 * What "Remove what isn't used" would remove ([models]) and what it frees ([bytes]), and the unused
 * models it leaves for now because something running may hold them ([waiting]).
 */
data class CleanupPlan(val models: List<ModelOnPhone>, val waiting: List<ModelOnPhone> = emptyList()) {
    val bytes: Long get() = models.sumOf { it.bytes }

    /** What every model nothing uses takes, the waiting ones too. */
    val unusedBytes: Long get() = bytes + waiting.sumOf { it.bytes }
}
