package com.satori.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.satori.ui.design.ActionRow
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.DotProgress
import com.satori.ui.design.DotoNumber
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.FormField
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleDialog
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleState
import com.satori.ui.design.GrilleSwitch
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.Hairline
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.PillAction
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.RecIndicator
import com.satori.ui.design.RecTimer
import com.satori.ui.design.RecordGrille
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.formatDuration
import com.satori.ui.design.rememberNoticeState
import kotlinx.coroutines.launch

/**
 * Debug builds only: the shared Grille components in their states, for screenshots
 * (`DebugToolsActivity --es screen components`). Text is English on purpose; the screens
 * themselves are where the strings are checked.
 */
@Composable
internal fun ComponentGallery() {
    val notices = rememberNoticeState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = GrilleSpace.m)
        ) {
            Section("Numbers") {
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.l)) {
                    RecTimer(millis = 207_000L, live = true)
                    DotoNumber(formatDuration(5_050_000L), style = Grille.type.displayNumber)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.l)) {
                    DotoNumber("3:32")
                    DotoNumber("42%")
                    DotoNumber("42 %")
                    DotoNumber("0.5")
                }
                RecIndicator(text = "Recording", live = true)
            }
            Section("Pills") {
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s)) {
                    PillButton("Filled", onClick = {})
                    PillButton("Outline", onClick = {}, kind = PillKind.Outline)
                    PillButton("Quiet", onClick = {}, kind = PillKind.Quiet)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s)) {
                    PillButton("Filled", onClick = {}, enabled = false)
                    PillButton("Outline", onClick = {}, kind = PillKind.Outline, enabled = false)
                    PillButton("Quiet", onClick = {}, kind = PillKind.Quiet, enabled = false)
                }
                PillButton("Voice note", onClick = {}, kind = PillKind.Outline, trailingIcon = GrilleIcons.Expand)
            }
            Section("Action rows") {
                ActionRow(primary = PillAction("Rename") {}, secondary = PillAction("Cancel") {})
                ActionRow(primary = PillAction("Modell herunterladen") {}, secondary = PillAction("Vorerst überspringen") {})
                ActionRow(primary = PillAction("Get started") {})
            }
            Section("Choices") {
                var selected by remember { mutableIntStateOf(0) }
                SegmentedChoice(
                    options = listOf("Während der Aufnahme", "Nach der Aufnahme", "Auto"),
                    selectedIndex = selected,
                    onSelect = { selected = it }
                )
                var on by remember { mutableStateOf(false) }
                GrilleSwitch(checked = on, onCheckedChange = { on = it })
            }
            Section("Fields") {
                var text by remember { mutableStateOf("") }
                GrilleTextField(value = text, onValueChange = { text = it }, placeholder = "Name or topic")
                var filled by remember { mutableStateOf("Budget review with Anna") }
                GrilleTextField(value = filled, onValueChange = { filled = it })
            }
            Section("Dots") {
                DotProgress(progress = 0.4f)
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.l)) {
                    RecordGrille(state = GrilleState.Idle, label = "Record", onClick = {}, size = 88.dp)
                    RecordGrille(state = GrilleState.Recording, label = "Stop", onClick = {}, level = 0.5f, size = 88.dp)
                    RecordGrille(state = GrilleState.Saving, label = "Saving", onClick = {}, size = 88.dp)
                }
            }
            Text("Errors", style = Grille.type.heading, color = Grille.colors.ink, modifier = Modifier.padding(GrilleSpace.margin))
            ErrorNote(text = "The recording couldn't be started. Tap Record to try again.", onDismiss = {})
            ErrorNote(
                text = "The speech model is missing or damaged.",
                action = PillAction("Download again") {}
            )
            ErrorNote(title = "Download failed", text = "The download stopped before it finished. Check the internet connection and try again.")
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m))
            Section("Overlays") {
                Row(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Notice", onClick = { scope.launch { notices.show("File saved") } }, kind = PillKind.Outline)
                    PillButton("Confirm", onClick = { sheet = 1 }, kind = PillKind.Outline)
                    PillButton("Form", onClick = { sheet = 2 }, kind = PillKind.Outline)
                    PillButton("Dialog", onClick = { sheet = 3 }, kind = PillKind.Outline)
                }
            }
            Spacer(Modifier.height(96.dp))
        }
        NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }
    when (sheet) {
        1 -> ConfirmSheet(
            title = "Delete this recording?",
            text = "Its transcript and notes are removed from this phone. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { sheet = 0 },
            onDismiss = { sheet = 0 }
        )
        2 -> {
            var name by remember {
                mutableStateOf(TextFieldValue("Budget review with Anna", selection = TextRange(0, 23)))
            }
            FormSheet(title = "Rename recording", confirmLabel = "Rename", onConfirm = { sheet = 0 }, onDismiss = { sheet = 0 }) {
                FormField(label = "Name") {
                    GrilleTextField(value = name, onValueChange = { name = it }, autoFocus = true)
                }
            }
        }
        3 -> GrilleDialog(
            title = "Stop recording?",
            text = "The recording stops and you go back to the list.",
            confirmLabel = "Stop and go back",
            onConfirm = { sheet = 0 },
            onDismiss = { sheet = 0 },
            dismissLabel = "Keep recording"
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.s),
        verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
    ) {
        Text(title, style = Grille.type.heading, color = Grille.colors.ink)
        content()
    }
}
