package com.satori.domain.settings

/**
 * Whether the power key's double press reaches Satori, as far as the app can tell, for the line
 * under the Power key row of Settings. Satori can't bind Motorola's My Key itself (docs/QUICK_KEY.md),
 * and nothing else in the app knew whether it was bound, so the row promised a setup for ever, also
 * on a phone where the key worked. Three signals tell, the first decisive both ways when it can be
 * had:
 * 1. the phone's own settings ([KeyRead]): `my_key_tap_app` names Satori's quick key activity and
 *    `double_tap_power_button_gesture` is 2, the value at which My Key runs it;
 * 2. a press that reached Satori: the quick key activity stores when it last forwarded one;
 * 3. the owner's word: the setup's "Already set up".
 *
 * Per install: after the rebrand to com.satori the old binding names the old app, so the new one
 * starts as [OTHER_APP] or [UNKNOWN], and the owner binds the new id, the one time the setup is
 * wanted again.
 */
enum class QuickKeyBinding {
    /** The key starts a recording in Satori. */
    SET_UP,

    /** The phone's settings say the key starts no app's quick key, or not on a double press. */
    NOT_SET_UP,

    /** The phone's settings say the key starts another app, such as Satori under its old name. */
    OTHER_APP,

    /** Nothing tells: the phone's settings can't be read, no press has come, and nobody said. */
    UNKNOWN;

    companion object {
        /** The value of `double_tap_power_button_gesture` at which a double press runs My Key's app. */
        const val MY_KEY_GESTURE = 2

        /**
         * @param read What the phone's settings say, null when neither could be read
         * @param lastPressAtMs When the quick key activity last forwarded a press, if ever
         * @param setUpByHand Whether the owner said the key is set up
         * @param applicationId This app's id, which the key's intent names as its component's package
         */
        fun of(read: KeyRead?, lastPressAtMs: Long?, setUpByHand: Boolean, applicationId: String): QuickKeyBinding {
            val tapApp = read?.tapApp ?: return if (lastPressAtMs != null || setUpByHand) SET_UP else UNKNOWN
            val gesture = read?.gesture
            return when {
                tapApp.isBlank() -> NOT_SET_UP
                "component=$applicationId/" !in tapApp -> OTHER_APP
                // Unreadable, the gesture says nothing against the app the key names.
                gesture != null && gesture != MY_KEY_GESTURE -> NOT_SET_UP
                else -> SET_UP
            }
        }
    }
}

/**
 * What the phone's settings say of the power key, each value null where it couldn't be read:
 * Android 12 and later refuse an app the hidden keys of their own, and whether Motorola's are
 * among them is not known (UNVERIFIED on the phone).
 *
 * @property tapApp `Settings.System` `my_key_tap_app`, the intent My Key starts; empty when unset
 * @property gesture `Settings.Secure` `double_tap_power_button_gesture`
 */
data class KeyRead(val tapApp: String?, val gesture: Int?)
