package com.satori.domain.usecase.todo

/** What the To do section says about notifications. */
enum class ReminderPrompt {
    /** Nothing: no reminder is waiting, or notifications are on. */
    None,

    /** Explain, once, why reminders need notifications, then ask Android for them. */
    Ask,

    /** Reminders can't show; one quiet line offers the app's notification settings. */
    TurnedOff
}

/** When the home screen asks for notifications and when it only says they are off. */
object ReminderPrompts {

    /**
     * @param hasReminders Some open to-do has a reminder time
     * @param notificationsOn The app may post notifications now
     * @param canAsk Android can still show its permission dialog (Android 13 or later, and the
     *   permission isn't granted); on Android 12 notifications can only be turned on in settings
     * @param alreadyAsked The explanation was shown before, so it is never shown again
     */
    fun decide(
        hasReminders: Boolean,
        notificationsOn: Boolean,
        canAsk: Boolean,
        alreadyAsked: Boolean
    ): ReminderPrompt = when {
        !hasReminders || notificationsOn -> ReminderPrompt.None
        canAsk && !alreadyAsked -> ReminderPrompt.Ask
        else -> ReminderPrompt.TurnedOff
    }
}
