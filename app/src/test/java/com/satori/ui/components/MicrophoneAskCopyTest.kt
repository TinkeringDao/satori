package com.satori.ui.components

import com.satori.presentation.firstrun.MicrophoneState
import com.satori.ui.R
import com.satori.ui.screens.CopyRes
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the microphone ask says in each of its states (design 3.5): why first, then one more try, then the settings page. */
class MicrophoneAskCopyTest {

    private fun ids(state: MicrophoneState) = microphoneAskCopy(state).caption.map { it.id }

    @Test
    fun `before Android's window it says why and which option to pick`() {
        assertEquals(listOf(R.string.g_fr_mic_why, R.string.g_fr_mic_how), ids(MicrophoneState.NOT_ALLOWED))
        assertEquals(CopyRes(R.string.g_fr_mic_action), microphoneAskCopy(MicrophoneState.NOT_ALLOWED).primary)
    }

    @Test
    fun `refused once it says recording needs it and asks again`() {
        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_how), ids(MicrophoneState.REFUSED_ONCE))
        assertEquals(CopyRes(R.string.g_fr_mic_action), microphoneAskCopy(MicrophoneState.REFUSED_ONCE).primary)
    }

    @Test
    fun `refused twice it leads to the app's settings page`() {
        assertEquals(listOf(R.string.g_fr_mic_refused, R.string.g_fr_mic_settings), ids(MicrophoneState.REFUSED_FOR_GOOD))
        assertEquals(CopyRes(R.string.g_rec_action_settings), microphoneAskCopy(MicrophoneState.REFUSED_FOR_GOOD).primary)
    }

    @Test
    fun `allowed, there is nothing new to say`() {
        assertEquals(microphoneAskCopy(MicrophoneState.NOT_ALLOWED), microphoneAskCopy(MicrophoneState.ALLOWED))
    }
}
