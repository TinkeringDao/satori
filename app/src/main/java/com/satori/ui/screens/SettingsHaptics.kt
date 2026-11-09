package com.satori.ui.screens

import android.content.Context
import com.satori.service.RecordingCues
import com.satori.service.RecordingHaptics
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Reaches the app's [RecordingHaptics] from a composable, which Hilt doesn't inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsHapticsEntryPoint {
    fun recordingHaptics(): RecordingHaptics
}

/**
 * "Try it" next to the vibrate setting: plays the buzz a recording gives as its microphone goes
 * on, through the same [RecordingHaptics], so what is felt here is what a recording gives. The
 * screen is in front then, so Android lets it play.
 */
internal object SettingsHaptics {

    fun playStart(context: Context) {
        EntryPointAccessors.fromApplication(context.applicationContext, SettingsHapticsEntryPoint::class.java)
            .recordingHaptics()
            .play(RecordingCues.Cue.START)
    }
}
