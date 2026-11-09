package com.satori.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the power key reaches Satori: the phone's own settings decide when they can be read; a
 * press that came, or the owner's word, when they can't; nothing at all is unknown.
 */
class QuickKeyBindingTest {

    private val app = "com.satori"
    private val ours = "intent:#Intent;action=com.satori.action.QUICK_KEY;launchFlags=0x10000000;" +
        "component=com.satori/com.satori.quickkey.QuickKeyActivity;end"

    @Test
    fun `the key names Satori and the double press runs it, so it is set up`() {
        assertEquals(QuickKeyBinding.SET_UP, QuickKeyBinding.of(KeyRead(ours, 2), null, false, app))
    }

    @Test
    fun `the key names another app, which wins over whatever came before`() {
        val camera = "intent:#Intent;component=com.motorola.camera/.Camera;end"
        assertEquals(QuickKeyBinding.OTHER_APP, QuickKeyBinding.of(KeyRead(camera, 2), lastPressAtMs = 1L, setUpByHand = true, applicationId = app))
    }

    @Test
    fun `nothing set, or the double press doing something else, is not set up`() {
        assertEquals(QuickKeyBinding.NOT_SET_UP, QuickKeyBinding.of(KeyRead("", 2), null, false, app))
        assertEquals(QuickKeyBinding.NOT_SET_UP, QuickKeyBinding.of(KeyRead(ours, 0), null, false, app))
    }

    @Test
    fun `with the key's app read and the gesture not, the app it names decides`() {
        assertEquals(QuickKeyBinding.SET_UP, QuickKeyBinding.of(KeyRead(ours, null), null, false, app))
    }

    @Test
    fun `settings that can't be read leave it to a press that came or the owner's word`() {
        assertEquals(QuickKeyBinding.SET_UP, QuickKeyBinding.of(KeyRead(null, null), lastPressAtMs = 1_760_000_000_000L, setUpByHand = false, applicationId = app))
        assertEquals(QuickKeyBinding.SET_UP, QuickKeyBinding.of(null, lastPressAtMs = null, setUpByHand = true, applicationId = app))
        assertEquals(QuickKeyBinding.UNKNOWN, QuickKeyBinding.of(null, null, false, app))
        assertEquals(QuickKeyBinding.UNKNOWN, QuickKeyBinding.of(KeyRead(null, 2), null, false, app))
    }

    @Test
    fun `after the rebrand the binding of the old app is another app's`() {
        // The same action and activity under another application id that also ends in satori, as
        // the install before the rebrand had them.
        val old = "intent:#Intent;action=com.example.satori.action.QUICK_KEY;launchFlags=0x10000000;" +
            "component=com.example.satori/com.example.satori.quickkey.QuickKeyActivity;end"
        assertEquals(QuickKeyBinding.OTHER_APP, QuickKeyBinding.of(KeyRead(old, 2), null, false, app))
    }

    @Test
    fun `an app whose id starts with Satori's is not Satori`() {
        val other = "intent:#Intent;component=com.satori.other/.Quick;end"
        assertEquals(QuickKeyBinding.OTHER_APP, QuickKeyBinding.of(KeyRead(other, 2), null, false, app))
    }
}
