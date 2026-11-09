package com.satori.ui

import android.content.res.AssetManager
import android.util.Log
import com.satori.domain.model.VadParameters
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig

/**
 * Builds the Silero VAD. Every recording gets a new one from AppModule's VadFactory, with the
 * voice detection settings as they are then (VadProfile.LIVE), and every Transcribe again one
 * with the fixed values for files (VadProfile.FILE); SherpaOnnxDataSource owns it from there. It
 * releases a recording's VAD when the next recording replaces it, not at Stop, as the processing
 * of the last words may still read it then; Transcribe again's when it ends.
 */
object SimulateStreamingAsr {
    private const val TAG = "Satori"

    fun createVad(assetManager: AssetManager?, parameters: VadParameters): Vad {
        val type = 0
        Log.i(TAG, "Select VAD model type $type")
        val config = getVadModelConfig(type)!!

        // Apply the profile's values
        config.sileroVadModelConfig.minSilenceDuration = parameters.minSilenceDuration
        config.sileroVadModelConfig.maxSpeechDuration = parameters.maxSpeechDuration
        config.sileroVadModelConfig.threshold = parameters.threshold

        val vad = Vad(
            assetManager = assetManager,
            config = config,
        )
        Log.i(TAG, "sherpa-onnx vad initialized for ${parameters.profile}: " +
                "minSilence=${parameters.minSilenceDuration}s, maxSpeech=${parameters.maxSpeechDuration}s, " +
                "threshold=${parameters.threshold}")
        return vad
    }
}
