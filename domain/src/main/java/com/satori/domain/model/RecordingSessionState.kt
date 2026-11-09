package com.satori.domain.model

/**
 * Represents the state of a recording session.
 * Platform-agnostic model for recording lifecycle management.
 */
sealed class RecordingSessionState {
    /**
     * No recording session is active.
     */
    data object Idle : RecordingSessionState()

    /**
     * Recording session is active.
     *
     * @param segmentCount Number of transcription segments captured
     * @param durationMs Recording duration in milliseconds
     */
    data class Recording(
        val segmentCount: Int,
        val durationMs: Long
    ) : RecordingSessionState()

    /**
     * Recording session is in the process of stopping.
     */
    data object Stopping : RecordingSessionState()
}
