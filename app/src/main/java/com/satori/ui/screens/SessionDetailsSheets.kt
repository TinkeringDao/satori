package com.satori.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.llm.NoteText
import com.satori.domain.usecase.stt.ModelReadiness
import com.satori.domain.usecase.stt.TranscribeOption
import com.satori.presentation.sessiondetails.AiOffer
import com.satori.ui.R
import com.satori.ui.design.FormField
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.Hairline
import com.satori.ui.design.LocalSheetBandClearance
import com.satori.ui.design.MenuRow
import com.satori.ui.design.scrollCue
import org.json.JSONObject

// The sheets of the recording's page. The edit sheets open with their field focused and the
// keyboard up, so an edit starts typing at once.

/** One row of the overflow menu. */
private class MenuItem(
    val text: String,
    val onClick: () -> Unit,
    val supporting: String? = null,
    val enabled: Boolean = true
)

/**
 * The overflow menu, one verb per row. The rows that need something are left out when it is
 * missing instead of shown disabled: nothing to export in an empty recording, no notes without
 * an AI to write them, and nothing to play or transcribe again in a recording without audio.
 * Rows that only wait for the AI to be free, or for the recording to be finished, show switched
 * off, saying when they are available: the owner sees them come back.
 *
 * On the cover the list runs on into the camera band beside the cameras, to the bottom of the
 * screen, and the line at its right edge says it goes on ([scrollCue]): on the phone on 2026-10-14
 * it ended 380 px above the bottom, the row after "Delete audio" cut to 28 px with no text showing,
 * so nothing said that Rewrite notes, Find to-dos and ideas again, Change type, Mind map, Rename and
 * Delete followed, and a swipe in the band did nothing. Its last row scrolls clear of the cameras
 * ([LocalSheetBandClearance]).
 *
 * @param canExport Whether the recording has a transcript or notes to share, save or copy.
 * @param canShareAudio Whether it kept its audio, which can then be shared as a file.
 * @param audioCompressed Whether that audio is stored compressed, which the file shared says.
 * @param canTranscribe Whether its transcript can be made again from that audio: a speech model is
 *   on the phone and nothing else of the page runs. The row opens [TranscribeChoiceSheet].
 * @param canWriteNotes Whether the AI can write notes for it: AI notes on, a model installed, and
 *   enough text.
 * @param canFindAgain Whether the AI can look for its to-dos and ideas again: AI notes on, a model
 *   installed, and something was said.
 * @param aiWaits Whether the AI is in use elsewhere, by the finishing of a recording above all:
 *   the three rows of the AI's work above wait for it.
 * @param waitsForFinishing Whether they wait for the finishing of recordings, which they say, or
 *   for other work that holds the AI (the mind map's, another recording page's).
 * @param hasNotes Whether it has notes already. Then the row rewrites them; otherwise it writes
 *   the first ones.
 * @param copiesNotes Whether the copy row copies what the open tab shows, the notes (or the
 *   translation), rather than the transcript.
 * @param translation Whether it is a Translate recording, whose notes are its translation.
 * @param canOpenMap Whether the recording's own mind map can open: it has something in it, and no
 *   rewrite of its notes runs, which needs the AI the map's own work would use.
 * @param keptAudio Whether its audio is kept out of the settings that compress or delete older
 *   audio; null without audio to keep.
 * @param canDeleteAudio Whether its audio can be deleted: there is some, and Transcribe again
 *   doesn't read it.
 * @param deleteAudioWaits Whether that waits for the recording to be finished, whose steps read
 *   the audio.
 * @param changeType How "Change type" is offered: not for a Translate recording, switched off
 *   while it waits, which it says ([changeTypeWaitsForFinishing]); ready, it names the type the
 *   recording has ([currentType]).
 */
@Composable
internal fun DetailsMenu(
    canExport: Boolean,
    canShareAudio: Boolean,
    audioCompressed: Boolean,
    canTranscribe: Boolean,
    canWriteNotes: Boolean,
    canFindAgain: Boolean,
    aiWaits: Boolean,
    waitsForFinishing: Boolean,
    hasNotes: Boolean,
    copiesNotes: Boolean,
    translation: Boolean,
    canOpenMap: Boolean,
    keptAudio: Boolean?,
    canDeleteAudio: Boolean,
    deleteAudioWaits: Boolean,
    changeType: AiOffer,
    changeTypeWaitsForFinishing: Boolean,
    currentType: String,
    onShare: () -> Unit,
    onShareAudio: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onTranscribe: () -> Unit,
    onKeepAudio: (Boolean) -> Unit,
    onDeleteAudio: () -> Unit,
    onWriteNotes: () -> Unit,
    onFindAgain: () -> Unit,
    onChangeType: () -> Unit,
    onOpenMap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val shareText = stringResource(R.string.g_details_share_md)
    val shareHint = stringResource(
        if (translation) R.string.g_details_share_md_hint_translation else R.string.g_details_share_md_hint
    )
    val shareAudioText = stringResource(R.string.g_audio_share)
    val shareAudioHint = stringResource(if (audioCompressed) R.string.g_audio_share_hint_compressed else R.string.g_audio_share_hint)
    val saveText = stringResource(R.string.g_details_save_md)
    val copyText = stringResource(
        when {
            !copiesNotes -> R.string.g_details_copy_transcript
            translation -> R.string.g_details_copy_translation
            else -> R.string.g_details_copy_notes
        }
    )
    val transcribeText = stringResource(R.string.g_audio_transcribe)
    val transcribeHint = stringResource(R.string.g_audio_transcribe_hint)
    val keepText = stringResource(R.string.g_audio_keep_this)
    val keepHint = stringResource(R.string.g_audio_keep_this_desc)
    val keepStopText = stringResource(R.string.g_audio_keep_stop)
    val deleteAudioText = stringResource(R.string.g_audio_delete_this)
    val rewriteText = stringResource(R.string.g_details_rewrite)
    val rewriteHint = stringResource(R.string.g_details_rewrite_hint)
    val writeText = stringResource(R.string.g_details_write_notes)
    val findText = stringResource(R.string.g_details_find_again)
    val changeTypeText = stringResource(R.string.g_details_change_type)
    val changeTypeWaitsText = aiWaitText(changeTypeWaitsForFinishing)
    val aiWaitsText = aiWaitText(waitsForFinishing)
    // Deleting the audio waits for the finishing of this recording alone, whose steps read it.
    val finishingText = aiWaitText(forFinishing = true)
    val mapText = stringResource(R.string.g_map_title)
    val mapHint = stringResource(R.string.g_map_details_hint)
    val renameText = stringResource(R.string.g_details_rename_action)
    val deleteText = stringResource(R.string.g_details_delete)
    // The AI's work waits, switched off and saying why, while the AI is in use elsewhere.
    fun aiItem(text: String, onClick: () -> Unit, hint: String? = null) =
        if (aiWaits) MenuItem(text, onClick, aiWaitsText, enabled = false) else MenuItem(text, onClick, hint)
    val items = buildList {
        if (canExport) add(MenuItem(shareText, onShare, shareHint))
        if (canShareAudio) add(MenuItem(shareAudioText, onShareAudio, shareAudioHint))
        if (canExport) {
            add(MenuItem(saveText, onSave))
            add(MenuItem(copyText, onCopy))
        }
        if (canTranscribe) add(aiItem(transcribeText, onTranscribe, transcribeHint))
        when (keptAudio) {
            null -> Unit
            true -> add(MenuItem(keepStopText, { onKeepAudio(false) }))
            false -> add(MenuItem(keepText, { onKeepAudio(true) }, keepHint))
        }
        if (canDeleteAudio) {
            add(
                if (deleteAudioWaits) MenuItem(deleteAudioText, onDeleteAudio, finishingText, enabled = false)
                else MenuItem(deleteAudioText, onDeleteAudio)
            )
        }
        if (canWriteNotes) add(if (hasNotes) aiItem(rewriteText, onWriteNotes, rewriteHint) else aiItem(writeText, onWriteNotes))
        if (canFindAgain) add(aiItem(findText, onFindAgain))
        when (changeType) {
            AiOffer.NONE -> Unit
            AiOffer.WAITS -> add(MenuItem(changeTypeText, onChangeType, changeTypeWaitsText, enabled = false))
            AiOffer.READY -> add(MenuItem(changeTypeText, onChangeType, currentType))
        }
        if (canOpenMap) add(MenuItem(mapText, onOpenMap, mapHint))
        add(MenuItem(renameText, onRename))
        add(MenuItem(deleteText, onDelete))
    }
    GrilleSheet(onDismiss = onDismiss, throughBand = true) {
        // A dozen rows with their lines are taller than the cover screen, at a large font size
        // even more so.
        val scroll = rememberScrollState()
        val clearance = LocalSheetBandClearance.current
        Column(
            Modifier
                .weight(1f, fill = false)
                .scrollCue(scroll, endInset = clearance)
                .verticalScroll(scroll)
                .padding(bottom = clearance)
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                MenuRow(text = item.text, supporting = item.supporting, enabled = item.enabled, onClick = item.onClick)
            }
        }
    }
}

/**
 * Picks the speech model for Transcribe again: the models on the phone that know the recording's
 * language, and the Malayalam model for any recording, each with about how long it takes. The one
 * that made the transcript there is now is marked; one that can't run now says why and can't be
 * chosen. Choosing one starts it, and the line under the title says what that replaces and what
 * stays ([transcribeBodyRes]). A model's line says the language the recording is then in, and
 * that its name, notes, to-dos and ideas are made again when its transcript is in another
 * language and [textFollows]: the page makes them again ([optionNoteRes]).
 */
@Composable
internal fun TranscribeChoiceSheet(
    options: List<TranscribeOption>,
    onChoose: (SpeechModelId) -> Unit,
    onDismiss: () -> Unit,
    textFollows: Boolean = false
) {
    GrilleSheet(onDismiss = onDismiss, throughBand = true) {
        val scroll = rememberScrollState()
        val clearance = LocalSheetBandClearance.current
        Column(
            Modifier
                .weight(1f, fill = false)
                .scrollCue(scroll, endInset = clearance)
                .verticalScroll(scroll)
                .padding(bottom = clearance)
        ) {
            Text(
                text = stringResource(R.string.g_speech_choose_title),
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Text(
                text = stringResource(transcribeBodyRes(options, textFollows)),
                style = Grille.type.caption,
                color = Grille.colors.ash,
                modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
            )
            Spacer(Modifier.height(GrilleSpace.s))
            options.forEachIndexed { index, option ->
                if (index > 0) Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                MenuRow(
                    text = speechModelName(option.model),
                    supporting = optionFacts(option, textFollows),
                    enabled = option.readiness == ModelReadiness.Ready,
                    onClick = { onChoose(option.model) }
                )
            }
        }
    }
}

/**
 * About how long [option] takes, or why it can't run now, then what else its line says
 * ([optionNoteRes]): "About 2 min. Made this transcript.", "Under a minute. Then in Malayalam. Name,
 * notes, to-dos and ideas are made again. To-dos edited, marked done or kept stay."
 */
@Composable
private fun optionFacts(option: TranscribeOption, textFollows: Boolean): String {
    val first = when (option.readiness) {
        ModelReadiness.AiModelLoaded -> stringResource(R.string.g_speech_ai_busy)
        ModelReadiness.NotEnoughMemory -> stringResource(R.string.g_speech_low_memory)
        // The sheet lists neither a model that isn't on the phone nor one for another language.
        ModelReadiness.Ready, ModelReadiness.NotInstalled, ModelReadiness.WrongLanguage -> duration(option.estimatedMs)
    }
    return (listOf(first) + optionNoteRes(option, textFollows).map { stringResource(it) }).joinToString(" ")
}

/**
 * The line under the Transcribe again sheet's title: that no model fits, or what a new transcript
 * replaces and what stays. The notes, the name and the to-dos stay, but where a model's transcript
 * is in another language and has them made again ([textFollows] and an option that
 * [TranscribeOption.makesTextAgain]): then the line says they stay unless a model's line says
 * otherwise, and that model's line says so.
 */
internal fun transcribeBodyRes(options: List<TranscribeOption>, textFollows: Boolean): Int = when {
    options.isEmpty() -> R.string.g_speech_none_fits
    textFollows && options.any { it.makesTextAgain } -> R.string.g_audio_transcribe_body_text_again
    else -> R.string.g_audio_transcribe_body
}

/**
 * What [option]'s line says after its time:
 * - whether the transcript there is now is its own;
 * - the language the recording is then in, where that changes it ([TranscribeOption.changesLanguage]):
 *   Malayalam for the Malayalam model, English or German for the others; on a recording in
 *   Malayalam already the Malayalam model's line doesn't say it again;
 * - for another model on a recording in Malayalam, that it is for speech that wasn't Malayalam:
 *   the way back for a recording made in Malayalam by mistake, whose Malayalam transcript it
 *   replaces;
 * - that the recording's name, notes, to-dos and ideas are made again, but for the to-dos the owner
 *   changed, where its transcript is in another language ([TranscribeOption.makesTextAgain]) and
 *   the page makes them again ([textFollows]), which they otherwise aren't.
 *
 * None of it says in which language the notes are written (the owner, 2026-10-15).
 */
internal fun optionNoteRes(option: TranscribeOption, textFollows: Boolean): List<Int> {
    val malayalamModel = option.model == RecordingLanguage.MALAYALAM.liveModel
    return listOfNotNull(
        R.string.g_speech_made_transcript.takeIf { option.madeTranscript },
        when {
            !option.changesLanguage -> null
            malayalamModel -> R.string.g_speech_then_ml
            else -> R.string.g_speech_then_en_de
        },
        R.string.g_speech_not_for_ml.takeIf { option.changesLanguage && !malayalamModel },
        R.string.g_speech_text_again.takeIf { textFollows && option.makesTextAgain }
    )
}

/** A time estimate in whole minutes, rounded up: "Under a minute.", "About 3 min." */
@Composable
private fun duration(estimatedMs: Long): String {
    val minutes = estimateMinutes(estimatedMs) ?: return stringResource(R.string.g_speech_under_minute)
    return stringResource(R.string.g_speech_minutes, minutes)
}

/**
 * The whole minutes the sheet says for [estimatedMs], rounded up, so a little over rather than
 * under; null for an estimate under a minute, the only one it says "Under a minute." for.
 * Rounded up, a recording the sheet puts at the minutes the help of the better transcript after
 * Stop names ([afterStopLimitMinutes]) or fewer is within that limit; with a limit of whole
 * minutes, as today's three, one it puts at a minute more is over it.
 */
internal fun estimateMinutes(estimatedMs: Long): Int? =
    if (estimatedMs < MINUTE_MS) null else ((estimatedMs + MINUTE_MS - 1) / MINUTE_MS).toInt()

private const val MINUTE_MS = 60_000L

/**
 * Opens with the whole name selected, so typing replaces it. A blank name clears it; the
 * recording then shows [fallbackName], its mode, which the field shows as its placeholder. The
 * keyboard's Done renames, as the pill does.
 */
@Composable
internal fun DetailsRenameSheet(
    currentName: String,
    fallbackName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length)))
    }
    FormSheet(
        title = stringResource(R.string.g_details_rename_title),
        confirmLabel = stringResource(R.string.g_details_rename_action),
        onConfirm = { onRename(draft.text.trim()) },
        onDismiss = onDismiss
    ) {
        Spacer(Modifier.height(GrilleSpace.m))
        GrilleTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = fallbackName,
            keyboardActions = KeyboardActions(onDone = { onRename(draft.text.trim()) }),
            autoFocus = true
        )
    }
}

/** Opens with the cursor at the end of the line. */
@Composable
internal fun DetailsSegmentSheet(
    segment: TranscriptionSegment,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable(segment.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(segment.text, TextRange(segment.text.length)))
    }
    FormSheet(
        title = stringResource(R.string.g_details_edit_segment_title),
        confirmLabel = stringResource(R.string.g_details_save),
        onConfirm = { onSave(draft.text.trim()) },
        onDismiss = onDismiss,
        // A line can be corrected, not emptied.
        confirmEnabled = draft.text.isNotBlank()
    ) {
        Spacer(Modifier.height(GrilleSpace.m))
        GrilleTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            textStyle = Grille.type.transcript,
            imeAction = ImeAction.Default,
            autoFocus = true
        )
    }
}

/**
 * Opens with the cursor at the end of the text, the part most edits are about.
 *
 * @param translation Whether the note is part of a Translate recording's translation.
 */
@Composable
internal fun DetailsInsightSheet(
    insight: LlmInsight,
    translation: Boolean,
    onSave: (title: String, content: String, actionItems: String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialItems = remember(insight.tasks) { NoteText.actionItems(insight.tasks).joinToString("\n") }
    var titleDraft by rememberSaveable(insight.id) { mutableStateOf(insight.title.orEmpty()) }
    var textDraft by rememberSaveable(insight.id, stateSaver = TextFieldValue.Saver) {
        val text = summaryText(insight.content)
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var itemsDraft by rememberSaveable(insight.id) { mutableStateOf(initialItems) }
    FormSheet(
        title = stringResource(
            if (translation) R.string.g_details_edit_translation_title else R.string.g_details_edit_insight_title
        ),
        confirmLabel = stringResource(R.string.g_details_save),
        onConfirm = { onSave(titleDraft.trim(), patchSummary(insight.content, textDraft.text), itemsDraft) },
        onDismiss = onDismiss
    ) {
        FormField(label = stringResource(R.string.g_details_field_title)) {
            GrilleTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                textStyle = Grille.type.heading,
                imeAction = ImeAction.Next
            )
        }
        FormField(label = stringResource(R.string.g_details_field_summary)) {
            GrilleTextField(
                value = textDraft,
                onValueChange = { textDraft = it },
                singleLine = false,
                imeAction = ImeAction.Default,
                autoFocus = true
            )
        }
        // Action items are editable where the AI wrote some; other notes have none.
        if (initialItems.isNotEmpty()) {
            FormField(label = stringResource(R.string.g_details_field_tasks)) {
                GrilleTextField(
                    value = itemsDraft,
                    onValueChange = { itemsDraft = it },
                    singleLine = false,
                    imeAction = ImeAction.Default
                )
            }
        }
    }
}

/** Puts an edited text back, keeping the JSON shape of older notes. */
private fun patchSummary(original: String, summary: String): String {
    if (!original.trimStart().startsWith("{")) return summary
    return try {
        val json = JSONObject(original)
        if (json.has("summary")) json.put("summary", summary).toString() else summary
    } catch (e: Exception) {
        summary
    }
}
