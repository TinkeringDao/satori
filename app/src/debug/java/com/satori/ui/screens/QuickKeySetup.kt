package com.satori.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.satori.quickkey.QuickKeyActivity
import com.satori.ui.R
import com.satori.ui.design.FormField
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/**
 * Debug builds only: the power key's setup from a computer, at the foot of its sheet
 * ([QuickKeySheet]). Release builds have neither this nor its strings (the owner's decision of
 * 2026-10-15); the debug build keeps the adb commands for the main session and the emulator, behind
 * one quiet row, "Set up on a computer", so the sheet stays as calm as a release build's until
 * they are wanted. Opened, they are in a monospace face, as a terminal shows them, where each space
 * and quote counts, one to a line that doesn't wrap: a long one scrolls sideways rather than
 * breaking inside a word. They are selectable, so one line can be copied on its own, and Copy and
 * Share take them all. The commands are made here, for this install ([QuickKeyAdbSetup]), so no
 * code of a release build makes them.
 *
 * @param onCopied The commands are on the clipboard; Settings says so where Android doesn't
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickKeySetup(onCopied: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val commands = remember(context.packageName) {
        QuickKeyAdbSetup.commands(
            applicationId = context.packageName,
            activityClass = QuickKeyActivity::class.java.name,
            action = QuickKeyActivity.ACTION_QUICK_KEY
        )
    }
    val onCopyCommands = {
        clipboard.setText(AnnotatedString(commands))
        onCopied()
    }
    val onShareCommands = {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, commands), null)
        )
    }
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        Spacer(Modifier.height(GrilleSpace.s))
        PillButton(
            text = stringResource(R.string.g_quickkey_setup_open),
            onClick = { open = true },
            kind = PillKind.Quiet,
            contentPadding = PaddingValues(0.dp)
        )
    } else {
        FormField(stringResource(R.string.g_quickkey_setup_title)) {
            Text(
                text = stringResource(R.string.g_quickkey_setup_desc),
                style = Grille.type.caption,
                color = Grille.colors.ash
            )
            SelectionContainer {
                Text(
                    text = commands.trimEnd(),
                    style = Grille.type.caption.copy(fontFamily = FontFamily.Monospace),
                    color = Grille.colors.ink,
                    softWrap = false,
                    modifier = Modifier
                        .padding(top = GrilleSpace.m)
                        .horizontalScroll(rememberScrollState())
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = GrilleSpace.m),
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
            ) {
                PillButton(
                    text = stringResource(R.string.g_quickkey_copy),
                    onClick = onCopyCommands,
                    kind = PillKind.Outline
                )
                PillButton(
                    text = stringResource(R.string.g_quickkey_share),
                    onClick = onShareCommands,
                    kind = PillKind.Outline
                )
            }
        }
    }
}
