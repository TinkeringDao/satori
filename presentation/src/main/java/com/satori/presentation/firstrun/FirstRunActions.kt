package com.satori.presentation.firstrun

/**
 * What the first-run screens ask of the first run's view model, one function per control of
 * [FirstRunUiState]. What only a screen can do stays with the screen: Android's microphone window,
 * the app's settings page, the storage settings, and Record, which opens the recorder as home's
 * grille does. The screen reports what came back from them here.
 */
interface FirstRunActions {

    /** Get ready's Malayalam switch. */
    fun setMalayalam(wanted: Boolean)

    /** Get ready's primary, "Download 808 MB": the consent, on mobile data too, as it shows the size. */
    fun download()

    /** Get ready's Wait for Wi-Fi, on mobile data: the consent, for Wi-Fi only. */
    fun waitForWifi()

    /** Getting ready's Pause. */
    fun pause()

    /** Getting ready's Continue. */
    fun resume()

    /** Try now without a connection, and the primary Try again. */
    fun retry()

    /** Use mobile data, while the setup waits for Wi-Fi. */
    fun useMobileData()

    /**
     * Android's microphone window answered: [granted], and whether Android would ask again
     * ([canAskAgain], its shouldShowRequestPermissionRationale); see [MicrophoneState.answered].
     */
    fun microphoneAnswered(granted: Boolean, canAskAgain: Boolean)

    /**
     * The permission as read again on every return to the screen, from the app's settings page,
     * say, so the pill turns into Record with no tap ([MicrophoneState.reread]).
     */
    fun microphoneRechecked(granted: Boolean)

    /** Ready's Look around first. */
    fun lookAround()
}
