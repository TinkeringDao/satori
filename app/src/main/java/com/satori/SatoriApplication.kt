package com.satori

import android.app.Application
import com.satori.diagnostics.FieldLog
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.feature.stt.datasource.SpeechSessionOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Satori's application class: sets up Hilt and starts the on-phone field log ([FieldLog]) first
 * thing, so a crash during start-up is recorded too. It then writes the speech models' ONNX Runtime
 * options file ([SpeechSessionOptions.install]), which sherpa-onnx must find at every speech model
 * load: since 0.7.1 every build loads them with prepacking off.
 *
 * The first run's coordinator is made here, so the stored stage is read as the process starts
 * (the quick key and the finishing ask for it at once), but its downloads start only with the app
 * itself, at MainActivity's start ([FirstRunCoordinator.start]): a process Android starts for a
 * boot receiver, a reminder or the finishing's wake job downloads nothing, as no data sync
 * service may start from there (design 4.4: a reboot is picked up at the next open).
 */
@HiltAndroidApp
class SatoriApplication : Application() {

    @Inject lateinit var speechSessionOptions: SpeechSessionOptions

    // Made with the process: its first state holds the stored stage and plan.
    @Inject lateinit var firstRunCoordinator: FirstRunCoordinator

    override fun onCreate() {
        FieldLog.install(this)
        super.onCreate()
        speechSessionOptions.install()
    }
}
