package com.satori.presentation.sessiondetails

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.satori.domain.model.RecordingAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the kept audio of one recording at a time ([RecordingAudio]) for its details page: play,
 * pause, and play from a place, such as a tapped transcript line. Another recording that starts
 * playing stops the one before it, and a page that closes stops its own ([stop]).
 *
 * It holds transient audio focus while it plays, so music pauses meanwhile, and pauses when
 * another app takes the focus, as a call does. Everything runs on the main thread, where the page
 * calls it and MediaPlayer reports.
 */
@Singleton
class RecordingPlayer @Inject constructor(@ApplicationContext context: Context) {

    /**
     * What plays.
     *
     * @property sessionId The recording whose audio is loaded, or null
     * @property playing Whether it plays, or will as soon as it is ready
     * @property positionMs Where it is
     * @property durationMs How long it is
     * @property failed The file couldn't be played; cleared by the next play
     */
    data class State(
        val sessionId: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val failed: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = MainScope()

    // Made as the first audio plays, which is all they are for: the page's view models are built
    // in JVM tests as well, where Android's audio classes are stubs.
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
            .build()
    }

    private var player: MediaPlayer? = null
    private var loadedPath: String? = null
    private var prepared = false
    // Asked for before the file was ready: where to start, and whether to play then.
    private var pendingPosition: Long? = null
    private var playWhenReady = false
    private var ticker: Job? = null

    /**
     * Plays [audio] from [fromMs]; with null, on from where it was paused, or from the start once
     * it has played to its end ([startPosition]).
     */
    fun play(audio: RecordingAudio, fromMs: Long? = null) {
        val mp = playerFor(audio) ?: return
        if (!prepared) {
            if (fromMs != null) pendingPosition = fromMs
            playWhenReady = true
            _state.update { it.copy(playing = true, positionMs = fromMs ?: it.positionMs, failed = false) }
            return
        }
        start(mp, fromMs)
    }

    /** Moves to [positionMs] of [audio], playing on if it plays. */
    fun seekTo(audio: RecordingAudio, positionMs: Long) {
        val mp = playerFor(audio) ?: return
        if (prepared) mp.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST) else pendingPosition = positionMs
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun pause() {
        val mp = player ?: return
        if (prepared && mp.isPlaying) mp.pause()
        playWhenReady = false
        stopTicking()
        audioManager.abandonAudioFocusRequest(focus)
        _state.update { it.copy(playing = false, positionMs = if (prepared) mp.currentPosition.toLong() else it.positionMs) }
    }

    /** Stops the audio of [sessionId] and frees the player, if that is what is loaded. */
    fun stop(sessionId: String) {
        if (_state.value.sessionId != sessionId) return
        release()
        _state.value = State()
    }

    /** The player for [audio]'s file, loading it in place of another one; null if it can't be. */
    private fun playerFor(audio: RecordingAudio): MediaPlayer? {
        player?.takeIf { loadedPath == audio.path }?.let { return it }
        release()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(audio.path)
        } catch (e: Exception) {
            Log.w(TAG, "The audio of a recording can't be opened", e)
            mp.release()
            _state.value = State(sessionId = audio.sessionId, durationMs = audio.durationMs, failed = true)
            return null
        }
        mp.setOnPreparedListener { onPrepared(it) }
        mp.setOnCompletionListener { onCompleted() }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "The audio of a recording stopped playing: what=$what extra=$extra")
            release()
            _state.update { it.copy(playing = false, failed = true) }
            true
        }
        player = mp
        loadedPath = audio.path
        _state.value = State(sessionId = audio.sessionId, durationMs = audio.durationMs)
        mp.prepareAsync()
        return mp
    }

    private fun onPrepared(mp: MediaPlayer) {
        prepared = true
        val duration = mp.duration.toLong()
        if (duration > 0) _state.update { it.copy(durationMs = duration) }
        val position = pendingPosition
        pendingPosition = null
        if (playWhenReady) {
            playWhenReady = false
            start(mp, position)
        } else if (position != null) {
            mp.seekTo(position, MediaPlayer.SEEK_CLOSEST)
        }
    }

    private fun start(mp: MediaPlayer, fromMs: Long?) {
        val current = _state.value
        startPosition(fromMs, current.positionMs, current.durationMs)?.let { mp.seekTo(it, MediaPlayer.SEEK_CLOSEST) }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A call holds the focus: playing over it would be wrong, and nothing else would stop it.
            _state.update { it.copy(playing = false) }
            return
        }
        mp.start()
        _state.update { it.copy(playing = true, positionMs = mp.currentPosition.toLong(), failed = false) }
        stopTicking()
        ticker = scope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = mp.currentPosition.toLong()) }
                delay(TICK_MS)
            }
        }
    }

    private fun onCompleted() {
        stopTicking()
        audioManager.abandonAudioFocusRequest(focus)
        _state.update { it.copy(playing = false, positionMs = it.durationMs) }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun release() {
        stopTicking()
        player?.let {
            audioManager.abandonAudioFocusRequest(focus)
            it.release()
        }
        player = null
        loadedPath = null
        prepared = false
        pendingPosition = null
        playWhenReady = false
    }

    companion object {
        private const val TAG = "SatoriPlayer"

        /** How often the position is read while playing: often enough for the progress line. */
        private const val TICK_MS = 200L

        /** Within this of the end a recording counts as played to its end. */
        private const val END_MS = 250L

        /**
         * Where Play starts: at [requestedMs] if one was asked for; else from the start once the
         * audio has played to its end ([positionMs] within [END_MS] of [durationMs]); else null,
         * on from where it is.
         */
        fun startPosition(requestedMs: Long?, positionMs: Long, durationMs: Long): Long? = when {
            requestedMs != null -> requestedMs.coerceIn(0L, maxOf(durationMs, 0L))
            durationMs > 0 && positionMs >= durationMs - END_MS -> 0L
            else -> null
        }
    }
}
