package com.satori.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.presentation.sessiondetails.RecordingPlayer
import com.satori.ui.R
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.TabularDigits
import com.satori.ui.design.formatDuration
import kotlinx.coroutines.flow.StateFlow

/**
 * The player of a recording's kept audio, docked under its page: play or pause, the time played,
 * a hairline that fills as it plays and takes a tap or a drag to move, and the length; under it
 * how much room the audio takes on the phone, and whether it is compressed, for the owner deciding
 * what to keep. Quiet on purpose: the page is for reading, the times are Space Grotesk at caption
 * size (Doto is for numbers of 28 sp and more), and red stays the microphone's.
 *
 * It collects [playback] itself, so the position ticking redraws the bar and nothing else.
 *
 * @param durationMs The length of the audio, until the player has read its own
 * @param sizeBytes How much room the audio takes, its index included; null while that isn't known
 * @param compressed Whether the audio is stored compressed, which takes about an eighth of the room
 * @param enabled False while a recorder needs the microphone
 */
@Composable
internal fun RecordingAudioBar(
    playback: StateFlow<RecordingPlayer.State>,
    durationMs: Long,
    sizeBytes: Long?,
    compressed: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val state by playback.collectAsStateWithLifecycle()
    val length = if (state.sessionId != null && state.durationMs > 0) state.durationMs else durationMs
    val readout = Grille.type.caption.copy(fontFeatureSettings = TabularDigits)
    val facts = audioFacts(sizeBytes, compressed)
    Column(
        modifier
            .fillMaxWidth()
            .background(Grille.colors.void)
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = GrilleSpace.s, end = GrilleSpace.margin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIconButton(
                icon = if (state.playing) GrilleIcons.Pause else GrilleIcons.Play,
                contentDescription = stringResource(if (state.playing) R.string.g_audio_pause else R.string.g_audio_play),
                onClick = onToggle,
                enabled = enabled
            )
            // The seek line says both times to TalkBack.
            Text(
                text = formatDuration(state.positionMs),
                style = readout,
                color = Grille.colors.ash,
                modifier = Modifier.clearAndSetSemantics {}
            )
            SeekLine(
                positionMs = state.positionMs,
                durationMs = length,
                onSeek = onSeek,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GrilleSpace.s)
            )
            Text(
                text = formatDuration(length),
                style = readout,
                color = Grille.colors.ash,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
        if (facts != null) {
            // Under the time played, where the line about the file starts.
            Text(
                text = facts,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                modifier = Modifier.padding(start = GrilleSpace.s + GrilleSpace.touch, end = GrilleSpace.margin, bottom = GrilleSpace.s)
            )
        }
    }
}

/** "4.2 MB", "4.2 MB · Compressed" or "Compressed", or null when there is nothing to say. */
@Composable
private fun audioFacts(sizeBytes: Long?, compressed: Boolean): String? {
    val context = LocalContext.current
    val size = sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    val compressedText = if (compressed) stringResource(R.string.g_audio_compressed) else null
    return when {
        size != null && compressedText != null -> stringResource(R.string.g_home_meta, size, compressedText)
        else -> size ?: compressedText
    }
}

/**
 * The progress as a hairline: the part played in ink, the rest unlit. The touch area is the full
 * 48 dp; a tap or a drag along it moves the audio there, and TalkBack can set it like a slider.
 */
@Composable
private fun SeekLine(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val label = stringResource(R.string.g_audio_seek)
    val where = stringResource(R.string.g_audio_position_cd, formatDuration(positionMs), formatDuration(durationMs))
    val played = Grille.colors.ink
    val rest = Grille.colors.unlit
    // The gestures run once; they read the latest length and callback.
    val currentSeek by rememberUpdatedState(onSeek)
    val currentDuration by rememberUpdatedState(durationMs)
    fun seekToX(x: Float, width: Int) {
        if (width > 0 && currentDuration > 0) currentSeek(((x / width).coerceIn(0f, 1f) * currentDuration).toLong())
    }
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) { detectTapGestures { offset -> seekToX(offset.x, size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> seekToX(change.position.x, size.width) } }
    }
    Box(
        modifier
            .height(GrilleSpace.touch)
            .then(gestures)
            .semantics {
                contentDescription = label
                stateDescription = where
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        currentSeek((target.coerceIn(0f, 1f) * currentDuration).toLong())
                        true
                    }
                }
            }
            .drawBehind {
                // Exactly one physical pixel, as every hairline.
                val y = (size.height / 2).toInt().toFloat()
                drawRect(rest, topLeft = Offset(0f, y), size = Size(size.width, 1f))
                drawRect(played, topLeft = Offset(0f, y), size = Size(size.width * fraction, 1f))
            }
    )
}
