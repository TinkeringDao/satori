package com.satori.data.firstrun

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunStage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run as it is stored ([FirstRunPrefs]), in the SharedPreferences file in which the
 * earlier onboarding kept `onboarding_complete`. Read synchronously, as the app starts and the
 * quick key asks at once whether the app is set up; written with commit, before the screen that
 * follows the change shows, so a process that ends right after keeps it.
 */
@Singleton
class FirstRunStore(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FirstRunPrefs.FILE, Context.MODE_PRIVATE))

    /** The stored first run; the plan's parts get their sizes from [bytes]. */
    fun read(bytes: (FirstRunPart) -> Long): StoredFirstRun = StoredFirstRun(
        stage = FirstRunStage.stored(
            prefs.getString(FirstRunPrefs.STAGE, null),
            prefs.getBoolean(FirstRunPrefs.ONBOARDING_COMPLETE, false)
        ),
        stageAtMs = prefs.getLong(FirstRunPrefs.STAGE_AT, 0L),
        lookedAround = prefs.getBoolean(FirstRunPrefs.LOOKED_AROUND, false),
        malayalamWanted = prefs.getBoolean(FirstRunPrefs.MALAYALAM_WANTED, true),
        consentAtMs = prefs.getLong(FirstRunPrefs.CONSENT_AT, 0L),
        mobileOkForSetup = prefs.getBoolean(FirstRunPrefs.MOBILE_OK_FOR_SPEECH, false),
        mobileOkParts = prefs.getStringSet(FirstRunPrefs.MOBILE_OK_PARTS, null).orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> FirstRunPart.entries.firstOrNull { it.name == name } },
        userPaused = prefs.getBoolean(FirstRunPrefs.USER_PAUSED, false),
        firstRecordingSaved = prefs.getBoolean(FirstRunPrefs.FIRST_RECORDING_SAVED, false),
        aiDownloadStarted = prefs.getBoolean(FirstRunPrefs.AI_DOWNLOAD_STARTED, false),
        plan = prefs.getString(FirstRunPrefs.PLAN, null)?.let { planOf(it, bytes) }
    )

    /**
     * Stores [firstRun] whole, before the screen that follows it shows: commit, not apply, as the
     * process may end right after.
     */
    @SuppressLint("ApplySharedPref")
    fun write(firstRun: StoredFirstRun) {
        val edit = prefs.edit()
            .putString(FirstRunPrefs.STAGE, firstRun.stage.name)
            .putBoolean(FirstRunPrefs.ONBOARDING_COMPLETE, FirstRunFlow.onboardingComplete(firstRun.stage))
            .putLong(FirstRunPrefs.STAGE_AT, firstRun.stageAtMs)
            .putBoolean(FirstRunPrefs.LOOKED_AROUND, firstRun.lookedAround)
            .putBoolean(FirstRunPrefs.MALAYALAM_WANTED, firstRun.malayalamWanted)
            .putLong(FirstRunPrefs.CONSENT_AT, firstRun.consentAtMs)
            .putBoolean(FirstRunPrefs.MOBILE_OK_FOR_SPEECH, firstRun.mobileOkForSetup)
            .putStringSet(FirstRunPrefs.MOBILE_OK_PARTS, firstRun.mobileOkParts.mapTo(mutableSetOf()) { it.name })
            .putBoolean(FirstRunPrefs.USER_PAUSED, firstRun.userPaused)
            .putBoolean(FirstRunPrefs.FIRST_RECORDING_SAVED, firstRun.firstRecordingSaved)
            .putBoolean(FirstRunPrefs.AI_DOWNLOAD_STARTED, firstRun.aiDownloadStarted)
        val plan = firstRun.plan
        if (plan == null) edit.remove(FirstRunPrefs.PLAN) else edit.putString(FirstRunPrefs.PLAN, textOf(plan))
        edit.commit()
    }

    /** The microphone's last refusal as the first run knew it, by name; null when it was never refused. */
    var microphone: String?
        get() = prefs.getString(FirstRunPrefs.MICROPHONE, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(FirstRunPrefs.MICROPHONE) else putString(FirstRunPrefs.MICROPHONE, value) }.apply()
        }

    companion object {

        /** [plan] as it is stored: `PART:STATUS:damaged:serverErrors`, comma-separated; the sizes come from the registry. */
        internal fun textOf(plan: FirstRunPlan): String =
            plan.items.joinToString(",") { "${it.part.name}:${it.status.name}:${it.damaged}:${it.serverErrors}" }

        /** The plan stored as [text], its parts sized by [bytes]; a part this version doesn't know is left out. */
        internal fun planOf(text: String, bytes: (FirstRunPart) -> Long): FirstRunPlan = FirstRunPlan(
            text.split(',').mapNotNull { entry ->
                val fields = entry.trim().split(':')
                if (fields.size != 4) return@mapNotNull null
                val part = FirstRunPart.entries.firstOrNull { it.name == fields[0] } ?: return@mapNotNull null
                val status = FirstRunPlan.Status.entries.firstOrNull { it.name == fields[1] } ?: return@mapNotNull null
                FirstRunPlan.Item(
                    part = part,
                    bytes = bytes(part),
                    status = status,
                    damaged = fields[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    serverErrors = fields[3].toIntOrNull()?.coerceAtLeast(0) ?: 0
                )
            }.distinctBy { it.part }
        )
    }
}

/**
 * The first run as it is stored ([FirstRunStore]).
 *
 * @property stageAtMs When [stage] was written (epoch ms)
 * @property consentAtMs When Download was tapped (epoch ms); 0 before
 * @property mobileOkForSetup Download was tapped on mobile data, or Use mobile data during the
 *   setup: its parts may use mobile data
 * @property mobileOkParts The parts after the setup for which Use mobile data was tapped
 * @property userPaused Pause was tapped, and not Continue
 * @property aiDownloadStarted The AI model's download was started after the first recording
 * @property plan What downloads, and how far each part has come; null before there is one
 */
data class StoredFirstRun(
    val stage: FirstRunStage = FirstRunStage.GET_READY,
    val stageAtMs: Long = 0L,
    val lookedAround: Boolean = false,
    val malayalamWanted: Boolean = true,
    val consentAtMs: Long = 0L,
    val mobileOkForSetup: Boolean = false,
    val mobileOkParts: Set<FirstRunPart> = emptySet(),
    val userPaused: Boolean = false,
    val firstRecordingSaved: Boolean = false,
    val aiDownloadStarted: Boolean = false,
    val plan: FirstRunPlan? = null
)
