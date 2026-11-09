package com.satori.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import com.satori.domain.firstrun.FirstRunPart
import com.satori.presentation.firstrun.FirstRunActions
import com.satori.presentation.firstrun.FirstRunFooter
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.SetupProgress
import com.satori.ui.R
import com.satori.ui.components.DockedFooter
import com.satori.ui.components.MicrophoneAsk
import com.satori.ui.components.MicrophoneRequest
import com.satori.ui.design.DotProgress
import com.satori.ui.design.DotoNumber
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.TabularDigits

/** Megabytes as the download screens count them (10^6 bytes). */
private const val BYTES_PER_MB = 1_000_000L

/**
 * Step 2, Getting ready (design 3.5), and its end, Ready (3.6): the one wait, made safe. A big
 * percent and the dots follow the bytes; the part under way and its megabytes, the time left or
 * the files being checked, and the one quiet control at the right end of that row. While it runs
 * the screen says it may be left, and the footer asks for the microphone, which recording needs
 * anyway; once that is allowed, Record holds its place, switched off, until the files are checked.
 * What goes wrong keeps the layout: the title and one sentence say it, the progress stays, and
 * the footer holds the one next action. Ready is the same screen finished: the dots all lit, one
 * sentence, "Look around first", and Record.
 *
 * Where it would scroll it gives way ([FirstFit]): its spaces close up, and while it waits for
 * Wi-Fi the part's bytes go, which its sentence says ("428 MB left"), so that "Use mobile data"
 * stays in view on the cover above the cameras.
 *
 * @param microphone Android's window and the app's settings page ([com.satori.ui.components.rememberMicrophoneRequest])
 * @param onRecord Record at Ready: the recorder opens and starts, as home's grille does
 * @param acceptsTap The tap shield ([DockedFooter])
 */
@Composable
internal fun GettingReadyScreen(
    state: FirstRunUiState,
    actions: FirstRunActions,
    microphone: MicrophoneRequest,
    onRecord: () -> Unit,
    acceptsTap: () -> Boolean
) {
    val context = LocalContext.current
    val copy = firstRunCopy(state, rememberSizeLabel())
    OnboardingStepLayout(
        modifier = Modifier.firstRunRoot(FirstRunTags.GETTING_READY),
        header = { compact -> FirstRunPageDots(state.page, compact) },
        footer = {
            val primaryTag = Modifier.testTag(FirstRunTags.PRIMARY)
            when (state.footer) {
                FirstRunFooter.ALLOW_MICROPHONE, FirstRunFooter.OPEN_APP_SETTINGS -> MicrophoneAsk(
                    state = state.microphone,
                    request = microphone,
                    acceptsTap = acceptsTap,
                    primaryModifier = primaryTag
                )
                else -> DockedFooter(
                    primary = copy.primary.text(),
                    onPrimary = {
                        when (state.footer) {
                            FirstRunFooter.RECORD -> onRecord()
                            // Space ran out during the download, which goes on once there is room.
                            FirstRunFooter.OPEN_STORAGE_SETTINGS -> openStorageSettings(context)
                            FirstRunFooter.TRY_AGAIN, FirstRunFooter.DOWNLOAD -> actions.retry()
                            // Asked by MicrophoneAsk above.
                            FirstRunFooter.ALLOW_MICROPHONE, FirstRunFooter.OPEN_APP_SETTINGS -> Unit
                        }
                    },
                    caption = copy.caption.map { it.text() },
                    enabled = state.primaryEnabled,
                    acceptsTap = acceptsTap,
                    primaryModifier = primaryTag
                )
            }
        }
    ) { space ->
        FirstFit(
            viewport = space.viewport,
            variants = listOf(
                { GettingReadyBody(state, copy, actions, space.compact, tight = false) },
                { GettingReadyBody(state, copy, actions, space.compact, tight = true) }
            )
        )
    }
}

/**
 * What Getting ready says over its footer. [tight] where it would scroll: the spaces around the
 * bar close up, and while it waits for Wi-Fi the part's bytes give way to "Use mobile data".
 */
@Composable
private fun GettingReadyBody(
    state: FirstRunUiState,
    copy: FirstRunCopy,
    actions: FirstRunActions,
    compact: Boolean,
    tight: Boolean
) {
    copy.title?.let { Text(text = it.text(), style = Grille.type.title, color = Grille.colors.ink) }
    // A problem's one sentence sits under the title; Ready's comes after its finished bar.
    if (!state.ready) {
        copy.body?.let {
            Spacer(Modifier.height(GrilleSpace.s))
            Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash)
        }
    }
    Spacer(Modifier.height(if (tight) GrilleSpace.s else if (compact) GrilleSpace.m else GrilleSpace.l))
    SetupBar(state)
    Spacer(Modifier.height(if (tight) GrilleSpace.s else GrilleSpace.m))
    if (state.ready) {
        copy.body?.let { Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash) }
        copy.quiet?.let { quiet ->
            Spacer(Modifier.height(GrilleSpace.s))
            QuietLink(label = quiet.text(), onClick = { quietTap(state.quiet, actions) })
        }
    } else {
        val progress = state.progress
        val part = copy.part
        // The sentence of "Waiting for Wi-Fi" says how much is left.
        val bytes = part != null && progress != null && !(tight && copy.outline != null)
        if (bytes) BytesRow(checkNotNull(part), checkNotNull(progress))
        val status = copy.status != null || copy.quiet != null
        if (status) {
            StatusRow(
                status = copy.status?.text(),
                quiet = copy.quiet?.text(),
                onQuiet = { quietTap(state.quiet, actions) }
            )
        }
        copy.outline?.let {
            if (bytes || status) Spacer(Modifier.height(GrilleSpace.s))
            PillButton(
                text = it.text(),
                onClick = actions::useMobileData,
                kind = PillKind.Outline,
                modifier = Modifier.testTag(FirstRunTags.OUTLINE)
            )
        }
        copy.notes.forEach {
            Spacer(Modifier.height(if (compact) GrilleSpace.s else GrilleSpace.m))
            Text(text = it.text(), style = Grille.type.body, color = Grille.colors.ash)
        }
    }
}

/**
 * The percent in Doto and the dots under it; at Ready the dots alone, all lit: the bar is complete,
 * not gone. TalkBack hears one node with a range and the part's name with its percent, in steps of
 * ten ([SpokenBar]); a polite live region, so the wait is heard wherever the focus is, at most
 * once per 10 %: the part named is the one the bar was on as it reached its ten.
 */
@Composable
private fun SetupBar(state: FirstRunUiState) {
    val progress = state.progress
    if (state.ready) {
        // Decoration at Ready: its title says the files are in.
        DotProgress(progress = 1f, modifier = Modifier.clearAndSetSemantics {})
        return
    }
    val percent = progress?.percent ?: 0
    val part = progress?.part ?: FirstRunPart.SPEECH
    // Made again only as the ten changes ([SpokenBar.next] does the same).
    val spoken = remember(spokenPercent(percent)) { SpokenBar.of(percent, part) }
    val description = stringResource(R.string.g_fr_progress_cd, stringResource(partLabel(spoken.part)), spoken.ten)
    Column(
        Modifier.clearAndSetSemantics {
            progressBarRangeInfo = ProgressBarRangeInfo(current = spoken.ten / 100f, range = 0f..1f)
            stateDescription = description
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        DotoNumber(text = stringResource(R.string.g_onb_percent, percent), style = Grille.type.displayNumber)
        Spacer(Modifier.height(GrilleSpace.s))
        DotProgress(progress = progress?.fraction ?: 0f)
    }
}

/** The part under way and its megabytes of the whole setup: "Speech recognition 276 / 808 MB". */
@Composable
private fun BytesRow(part: CopyRes, progress: SetupProgress) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = part.text(), style = Grille.type.body, color = Grille.colors.ink, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(
                R.string.g_onb_megabytes,
                (progress.bytesDone / BYTES_PER_MB).toInt(),
                (progress.bytesTotal / BYTES_PER_MB).toInt()
            ),
            // Digits of one width, so the readout holds still as it counts.
            style = Grille.type.body.copy(fontFeatureSettings = TabularDigits),
            color = Grille.colors.ash
        )
    }
}

/**
 * The time left, the files being checked or where a download went on from, with the quiet control
 * at the row's right end. A touch target tall whether or not the control shows, so the lines under
 * it stay where they are as it comes and goes.
 */
@Composable
private fun StatusRow(status: String?, quiet: String?, onQuiet: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GrilleSpace.touch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = status.orEmpty(), style = Grille.type.body, color = Grille.colors.ash, modifier = Modifier.weight(1f))
        if (quiet != null) QuietLink(label = quiet, onClick = onQuiet, alignEnd = true)
    }
}

/**
 * The percent TalkBack hears: [percent] down to its ten, so a download that counts up by one is
 * announced at most once per 10 % (design G4), and never more than is in.
 */
internal fun spokenPercent(percent: Int): Int = percent.coerceIn(0, 100) / 10 * 10

/**
 * What TalkBack hears of Getting ready's bar: its [ten] ([spokenPercent]) and the [part] named with
 * it, the one the bar was on as it reached that ten. A part that changes within a ten (speech
 * recognition ends at 83 % of the setup, then Malayalam runs) is named from the next ten on, so the
 * description changes once per ten, not twice in one (design G4).
 */
internal data class SpokenBar(val ten: Int, val part: FirstRunPart) {

    /** This bar once it is at [percent] of [part]: the same until the ten changes. */
    fun next(percent: Int, part: FirstRunPart): SpokenBar =
        if (spokenPercent(percent) == ten) this else of(percent, part)

    companion object {
        fun of(percent: Int, part: FirstRunPart) = SpokenBar(spokenPercent(percent), part)
    }
}
