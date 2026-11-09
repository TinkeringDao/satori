package com.satori.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton

/**
 * The first run's footer (design 3.3, rule R7): the one primary pill, full width, docked under the
 * screen's content in the same place in every state, with the one or two lines that say why right
 * above it. The content scrolls above the footer; the footer itself never scrolls. A disabled pill
 * keeps its place, so the next button never moves, and its caption says why it is off. The
 * recorder's microphone ask ([MicrophoneAsk]) is drawn in it too, so it sits where step 2 has it.
 *
 * @param caption The lines over the pill, in ash; none for a pill that says enough itself
 * @param enabled Off while the pill can't be used yet: Record, until the files are checked
 * @param acceptsTap The tap shield: false for a moment after the screen changed by itself, while a
 *   tap is still meant for what was there before. The tap then does nothing, and the pill looks
 *   the same ([com.satori.presentation.firstrun.FirstRunUiState.withTapShield]).
 * @param primaryModifier For the pill, its test tag
 */
@Composable
fun DockedFooter(
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    caption: List<String> = emptyList(),
    enabled: Boolean = true,
    acceptsTap: () -> Boolean = { true },
    primaryModifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        caption.forEach { line ->
            Text(text = line, style = Grille.type.body, color = Grille.colors.ash)
        }
        if (caption.isNotEmpty()) Spacer(Modifier.height(GrilleSpace.s))
        PillButton(
            text = primary,
            onClick = { if (acceptsTap()) onPrimary() },
            enabled = enabled,
            modifier = primaryModifier.fillMaxWidth()
        )
    }
}
