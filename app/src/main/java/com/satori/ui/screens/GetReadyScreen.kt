package com.satori.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.satori.presentation.firstrun.FirstRunActions
import com.satori.presentation.firstrun.FirstRunFooter
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.ui.components.DockedFooter
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleSwitch
import com.satori.ui.design.TabularDigits
import com.satori.ui.design.Wordmark

/** [GrilleSwitch]'s width and the gap before it: the rows without a switch keep it free, so the sizes line up. */
private val SWITCH_SLOT = 52.dp + GrilleSpace.m

/**
 * Step 1, Get ready (design 3.4): the wordmark, what Satori does in one sentence, what downloads
 * with its size and when, the one switch (Malayalam), the network it uses, and the button that is
 * the consent ("Download 808 MB"). On mobile data the network line says so and the quiet "Wait for
 * Wi-Fi" is offered; short of space, the rows give way to the space needed and free, and the
 * button opens Android's storage settings. Nothing starts before the tap.
 *
 * Where it would scroll it gives way in steps ([FirstFit]): first the wordmark (design 3.3, rule
 * 9), then the rows' rhythm, as only the switch's row needs a touch target's height, and the quiet
 * link joins the network line it answers, so on the cover above the cameras the screen's one
 * decision on mobile data stays in view.
 *
 * @param acceptsTap The tap shield ([DockedFooter])
 */
@Composable
internal fun GetReadyScreen(state: FirstRunUiState, actions: FirstRunActions, acceptsTap: () -> Boolean) {
    val context = LocalContext.current
    val size: (Long) -> String = rememberSizeLabel()
    val copy = firstRunCopy(state, size)
    OnboardingStepLayout(
        modifier = Modifier.firstRunRoot(FirstRunTags.GET_READY),
        header = { compact -> FirstRunPageDots(state.page, compact) },
        footer = {
            DockedFooter(
                primary = copy.primary.text(),
                onPrimary = {
                    when (state.footer) {
                        FirstRunFooter.OPEN_STORAGE_SETTINGS -> openStorageSettings(context)
                        else -> actions.download()
                    }
                },
                acceptsTap = acceptsTap,
                primaryModifier = Modifier.testTag(FirstRunTags.PRIMARY)
            )
        }
    ) { space ->
        val gap = if (space.compact) GrilleSpace.s else GrilleSpace.m
        val body: @Composable (Boolean) -> Unit = { tight -> GetReadyBody(state, copy, size, actions, space.compact, tight) }
        FirstFit(
            viewport = space.viewport,
            variants = listOf(
                {
                    Wordmark()
                    Spacer(Modifier.height(gap))
                    body(false)
                },
                { body(false) },
                { body(true) }
            )
        )
    }
}

/**
 * What Get ready says under the wordmark. [tight] where it would scroll: the rows without a switch
 * are no taller than their text, and the quiet link sits at the end of the network line.
 */
@Composable
private fun GetReadyBody(
    state: FirstRunUiState,
    copy: FirstRunCopy,
    size: (Long) -> String,
    actions: FirstRunActions,
    compact: Boolean,
    tight: Boolean
) {
    copy.intro?.let { Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ink) }
    Spacer(Modifier.height(if (compact) GrilleSpace.s else GrilleSpace.l))
    val title = copy.title
    if (title != null) {
        Text(text = title.text(), style = Grille.type.title, color = Grille.colors.ink)
        copy.body?.let {
            Spacer(Modifier.height(GrilleSpace.s))
            Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash)
        }
    }
    copy.rows.forEach { row -> PartRowLine(row, size, onSwitch = actions::setMalayalam, tight = tight) }
    val network = copy.network
    val quiet = copy.quiet
    if (network != null) Spacer(Modifier.height(if (compact) GrilleSpace.s else GrilleSpace.m))
    if (tight && network != null && quiet != null) {
        // The decision beside the line it answers: "You are on mobile data." ... "Wait for Wi-Fi".
        Row(Modifier.fillMaxWidth().heightIn(min = GrilleSpace.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(text = network.text(), style = Grille.type.body, color = Grille.colors.ash, modifier = Modifier.weight(1f))
            QuietLink(label = quiet.text(), onClick = { quietTap(state.quiet, actions) }, alignEnd = true)
        }
    } else {
        network?.let { Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash) }
        quiet?.let { QuietLink(label = it.text(), onClick = { quietTap(state.quiet, actions) }) }
    }
}

/**
 * One part that downloads: its name and size, at least a touch target tall so the rows keep one
 * rhythm; where the screen is [tight], only Malayalam's, whose whole row is its switch. Its size
 * lines up with the others'. The caption under AI notes says when it downloads.
 */
@Composable
private fun PartRowLine(row: PartRow, size: (Long) -> String, onSwitch: (Boolean) -> Unit, tight: Boolean) {
    val switchedOn = row.switchedOn
    val toggle = if (switchedOn != null) {
        Modifier
            .toggleable(value = switchedOn, role = Role.Switch, onValueChange = onSwitch)
            .testTag(FirstRunTags.MALAYALAM)
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (tight && switchedOn == null) 0.dp else GrilleSpace.touch)
            .then(toggle),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label.text(),
                style = Grille.type.body,
                color = Grille.colors.ink,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = size(row.bytes),
                style = Grille.type.body.copy(fontFeatureSettings = TabularDigits),
                color = Grille.colors.ash
            )
            if (switchedOn != null) {
                Spacer(Modifier.width(GrilleSpace.m))
                // The row carries the switch's semantics; this is only its picture.
                Box(Modifier.clearAndSetSemantics {}) {
                    GrilleSwitch(checked = switchedOn, onCheckedChange = onSwitch)
                }
            } else {
                Spacer(Modifier.width(SWITCH_SLOT))
            }
        }
        row.caption?.let { Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash) }
    }
}
