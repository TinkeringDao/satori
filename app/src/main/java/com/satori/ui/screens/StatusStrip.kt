package com.satori.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.satori.ui.R
import com.satori.ui.design.DotProgress
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/**
 * Home's status strip (design 4.1): what [copy] says, from the top. A download's name with its
 * percent at the right, its dots, and the line under them; an offer's title and sentence; the
 * pills, side by side, outline ones and the quiet Try now and Continue. Grille's own pieces only:
 * the Filled pill stays home's grille, and red stays the live microphone's.
 *
 * Its test tag is [FirstRunTags.STRIP], shown to the emulator's checks as a resource id.
 *
 * @param acceptsTap Whether a tap on a pill counts now: not for a moment after the strip changed
 *   by itself ([com.satori.presentation.firstrun.StripTapShield])
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun StatusStrip(copy: StripCopy, acceptsTap: () -> Boolean, onTap: (StripTap) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { testTagsAsResourceId = true }
            .testTag(FirstRunTags.STRIP)
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m),
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        if (copy.title != null || copy.percent != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = copy.title?.let { text(it) }.orEmpty(),
                    style = Grille.type.body,
                    color = Grille.colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                copy.percent?.let {
                    Text(text = stringResource(R.string.g_onb_percent, it), style = Grille.type.body, color = Grille.colors.ink)
                }
            }
        }
        copy.fraction?.let { fraction ->
            // TalkBack reads what downloads and how far, in steps of ten so it doesn't chatter.
            val spoken = stringResource(R.string.g_fr_progress_cd, copy.title?.let { text(it) }.orEmpty(), (copy.percent ?: 0) / 10 * 10)
            DotProgress(
                progress = fraction,
                modifier = Modifier.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    stateDescription = spoken
                }
            )
        }
        copy.lines.forEach { line ->
            Text(text = text(line.text), style = Grille.type.body, color = if (line.ink) Grille.colors.ink else Grille.colors.ash)
        }
        if (copy.pills.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
            ) {
                copy.pills.forEach { pill ->
                    PillButton(
                        text = text(pill.label),
                        onClick = { if (acceptsTap()) onTap(pill.tap) },
                        kind = if (pill.quiet) PillKind.Quiet else PillKind.Outline
                    )
                }
            }
        }
    }
}

@Composable
private fun text(res: CopyRes): String = stringResource(res.id, *res.args.toTypedArray())
