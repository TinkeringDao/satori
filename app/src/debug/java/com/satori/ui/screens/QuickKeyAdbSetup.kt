package com.satori.ui.screens

/**
 * The adb commands that point the Motorola My Key power double-press at Satori. Satori cannot
 * write that Android setting itself (docs/QUICK_KEY.md, "Can Satori set the key itself?"),
 * so a debug build's Settings hands these over to run once from a computer. Debug source set
 * only: a release build has neither the commands nor this code (the owner's decision of
 * 2026-10-15), not even in its dex.
 */
object QuickKeyAdbSetup {

    /** FLAG_ACTIVITY_NEW_TASK, which the doc sets on the intent My Key starts. */
    private const val NEW_TASK_FLAG = "0x10000000"

    /**
     * The commands as plain ASCII, one per line, for a terminal: turn on the double-press for
     * My Key, bind it to the quick key activity, then print the binding to check it.
     *
     * @param applicationId The installed app's id.
     * @param activityClass The quick key activity's class name.
     * @param action The action its intent filter accepts. The URI names it because a bare
     *   `#Intent;` URI gets the action VIEW, which that filter does not match.
     */
    fun commands(applicationId: String, activityClass: String, action: String): String {
        val intent = "intent:#Intent;action=$action;launchFlags=$NEW_TASK_FLAG;" +
            "component=$applicationId/$activityClass;end"
        return listOf(
            "adb shell settings put secure double_tap_power_button_gesture 2",
            // The inner single quotes keep the phone's shell from splitting the URI at each ';'.
            "adb shell \"settings put system my_key_tap_app '$intent'\"",
            "adb shell settings get system my_key_tap_app"
        ).joinToString(separator = "\n", postfix = "\n")
    }
}
