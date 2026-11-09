package com.satori.data.quickkey

import com.satori.domain.model.RecordingLanguage

/**
 * What a power-key double-press does in Satori. Motorola's My Key starts the quick key activity
 * on that press (docs/QUICK_KEY.md); the user picks the behaviour in Satori's Settings.
 *
 * Plain Kotlin on purpose, so it can move to :domain unchanged if the settings screen wants it there.
 *
 * @property categoryId Id of the [com.satori.domain.model.RecordingCategory] a quick
 *   recording is filed under. Never blank. The category may have been deleted since it was
 *   chosen; whoever starts the recording checks that it still exists.
 * @property stopOnSecondPress What a press does while Satori records or gets a recording ready:
 *   true stops a running recording and cancels one that is still getting ready; false only
 *   shows Satori
 * @property language The language a quick recording is made in ("Record as: Meeting, Malayalam").
 *   Malayalam records in English or German while its model isn't on the phone, and in Malayalam
 *   again once it is back ([RecordingLanguage.usableWith]); whoever starts the recording checks.
 * @property lastPressAtMs When the quick key activity last forwarded a press, in wall-clock ms, or
 *   null if it never did: a press that reached Satori says the key is set up
 *   ([com.satori.domain.settings.QuickKeyBinding]).
 * @property setUpByHand Whether the owner said the key is set up ("Already set up" in its setup),
 *   for a phone whose own settings can't be read and where no press has come yet.
 */
data class QuickKeySettings(
    val categoryId: String = DEFAULT_CATEGORY_ID,
    val stopOnSecondPress: Boolean = DEFAULT_STOP_ON_SECOND_PRESS,
    val language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
    val lastPressAtMs: Long? = null,
    val setUpByHand: Boolean = false,
) {
    init {
        require(categoryId.isNotBlank()) { "categoryId must not be blank" }
    }

    companion object {
        /** The voice-note starter category (see CategoryRepository.STARTER_IDS). */
        const val DEFAULT_CATEGORY_ID = "voice-note"

        const val DEFAULT_STOP_ON_SECOND_PRESS = true
    }
}
