package com.satori.ui.screens

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.TranscriptionSegment
import com.satori.presentation.firstrun.MicrophoneState
import com.satori.presentation.main.LatestTranslation
import com.satori.presentation.main.MainUiState
import com.satori.presentation.main.MainViewModel
import com.satori.presentation.main.RecorderExit
import com.satori.presentation.main.RecorderNotice
import com.satori.presentation.main.RecorderPhase
import com.satori.presentation.main.TranslationLanguages
import com.satori.ui.R
import com.satori.ui.components.LanguageKey
import com.satori.ui.components.LanguageKeyInput
import com.satori.ui.components.RecordAsChip
import com.satori.ui.components.RecordingLanguageRow
import com.satori.ui.components.RecordingTypeSheet
import com.satori.ui.components.languageKeyInput
import com.satori.ui.components.languageKeyLabel
import com.satori.ui.components.recordingLanguageNameRes
import com.satori.ui.design.CameraBand
import com.satori.ui.design.CameraBandRow
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleDialog
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleState
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.Hairline
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.NoticeState
import com.satori.ui.design.RecIndicator
import com.satori.ui.design.RecTimer
import com.satori.ui.design.RecordGrille
import com.satori.ui.design.TextTabs
import com.satori.ui.design.bandForGrille
import com.satori.ui.design.cameraBand
import com.satori.ui.design.clearOf
import com.satori.ui.design.formatDuration
import com.satori.ui.design.freeHeight
import com.satori.ui.design.modeLabel
import com.satori.ui.design.rememberNoticeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Recording screen. The inner screen shows the timer, one status line, tabs for the transcript
 * and the notes, and the grille docked at the bottom; the cover screen shows a compact recorder.
 *
 * A recording is not left on a "stopped" screen: Stop saves it within about a second and the
 * recorder goes home ([onFinished]), where the recording's row shows its notes, name, to-dos and
 * map being written in the background.
 *
 * Over the lock screen the grille stops a recording only on a long press, and a tap says so: the
 * screen stays on while recording, also in a pocket.
 *
 * While it records, the label at the top (on the cover beside the timer) is a chip that opens
 * "Record as": another category switches the recording at once, and a notice says what it is
 * recorded as now ([MainViewModel.recordAs]); so does the other language, whose speech model then
 * takes over between two sentences ([MainViewModel.recordIn]), Malayalam only while its model is on
 * the phone, else a link to its download. Over the lock screen the chip opens only on a long press
 * too, and a tap says so. Beside the chip the language key switches it to the other language with
 * one tap ([LanguageKey]), over the lock screen only on a long press as well.
 *
 * @param onNavigateBack Go back to the screen the recorder was opened from (home)
 * @param onNothingSaid Called just before [onNavigateBack] when the recording was deleted because
 *   nothing was said in it, so the list it goes back to can say why
 * @param onFinished The recording is saved and handed over to be finished: go home, dropping the
 *   recorder from the back stack
 * @param onOpenSettings Open the settings where models are downloaded
 */
@Composable
fun MainScreen(
    onNavigateBack: () -> Unit = {},
    onNothingSaid: () -> Unit = {},
    onFinished: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notices = rememberNoticeState()
    // The owner's own choice of tab; until there is one, a Translate recording opens on its
    // translation, what the owner reads, and any other on its transcript.
    var chosenTab by rememberSaveable { mutableIntStateOf(NO_TAB) }
    val selectedTab = if (chosenTab != NO_TAB) chosenTab else openingTab(uiState)
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }
    // The "Record as" sheet, open from the label at the top while recording.
    var choosingType by rememberSaveable { mutableStateOf(false) }
    val holdHint = stringResource(R.string.g_rec_hold_to_stop)
    val changeHint = stringResource(R.string.g_rec_hold_to_change)

    // The recording is saved, or gone because nothing was said in it: the recorder leaves. Not
    // while a quick-key start waits for it, which replaces the recorder itself.
    LaunchedEffect(uiState.exit) {
        when (uiState.exit) {
            RecorderExit.HOME -> onFinished()
            RecorderExit.BACK -> onNavigateBack()
            RecorderExit.NOTHING_SAID -> {
                onNothingSaid()
                onNavigateBack()
            }
            RecorderExit.REPLACED, null -> Unit
        }
    }
    // Leaving without Stop deletes a recording in which nothing was said as well.
    val leave: () -> Unit = {
        viewModel.leave { nothingSaid ->
            if (nothingSaid) onNothingSaid()
            onNavigateBack()
        }
    }
    // A stop from the notification closes a question about stopping.
    LaunchedEffect(uiState.isRecording) {
        if (!uiState.isRecording) showStopConfirm = false
    }
    // Any stop closes the sheet that switches the recording too: it can't be switched any more.
    LaunchedEffect(uiState.opensRecordAs) {
        if (!uiState.opensRecordAs) choosingType = false
    }
    // What a switch to another category or language came to, once.
    val resources = context.resources
    LaunchedEffect(viewModel, resources) {
        viewModel.notices.collect { notice ->
            notices.show(
                when (notice) {
                    is RecorderNotice.RecordingAs -> resources.getString(R.string.g_rec_recording_as, notice.name)
                    RecorderNotice.SwitchFailed -> resources.getString(R.string.g_rec_switch_failed)
                    is RecorderNotice.RecordingIn -> resources.getString(
                        if (notice.language == RecordingLanguage.MALAYALAM) R.string.g_rec_recording_in_ml else R.string.g_rec_recording_in_en_de
                    )
                    is RecorderNotice.LanguageRefused -> resources.getString(
                        R.string.g_rec_language_refused,
                        resources.getString(recordingLanguageNameRes(notice.wanted)),
                        resources.getString(recordingLanguageNameRes(notice.instead))
                    )
                }
            )
        }
    }
    // Read as the screen comes back: shown over the lock screen, the recorder resumes as the
    // screen turns on. An unlock while it is on show (the fingerprint on the power key) doesn't
    // resume it again, so it is read once Android says the phone is unlocked too.
    var keyguardLocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        keyguardLocked = isKeyguardLocked(context)
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val unlocked = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                keyguardLocked = isKeyguardLocked(context)
            }
        }
        // Sent by Android itself, which reaches a receiver that isn't exported.
        ContextCompat.registerReceiver(
            context,
            unlocked,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(unlocked) }
    }
    // Only while the microphone is live and the phone unlocked, on the inner and the cover screen
    // alike: a fold moves this same window between them (RecordingScreenOn).
    KeepScreenOn(RecordingScreenOn.keeps(uiState.isRecording, uiState.settings.keepScreenOnWhileRecording, keyguardLocked))

    // Back asks first while recording. While saving it goes back once the recording is saved,
    // which takes well under a second; leaving at once would cut off the last words.
    val onBack: () -> Unit = {
        when {
            uiState.saving -> viewModel.leaveWhenSaved()
            uiState.isLeaving -> Unit
            uiState.isRecording -> showStopConfirm = true
            else -> leave()
        }
    }
    BackHandler(onBack = onBack)

    // RECORD_AUDIO only, and only on a tap on the recorder's own ask, which says why first.
    // Notifications are asked for once, later, from home: the recorder doesn't need them to record.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onMicrophoneAnswered(granted, canAskAgain = microphoneRefusedBefore(context))
    }

    // A one-tap start runs only while this screen is visible: started from the background (the
    // phone locked or the flip closed while the models load), Android refuses the recording
    // service, and the recording would run without its notification and wake lock.
    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStarted()
        onStopOrDispose { viewModel.onScreenStopped() }
    }

    // The microphone, read as the recorder opens and on every return to it: allowed in the app's
    // settings page meanwhile, the recorder gets ready without another tap. Not allowed, it says so
    // in place and stays, and so does its recording.
    LifecycleResumeEffect(viewModel) {
        viewModel.onMicrophoneRead(microphoneGranted(context), refusedBefore = microphoneRefusedBefore(context))
        onPauseOrDispose { }
    }

    if (showStopConfirm) {
        GrilleDialog(
            title = stringResource(R.string.g_stop_title),
            text = stringResource(R.string.g_stop_body),
            confirmLabel = stringResource(R.string.g_stop_confirm),
            onConfirm = {
                showStopConfirm = false
                viewModel.stopStreaming()
            },
            dismissLabel = stringResource(R.string.g_keep_recording),
            onDismiss = { showStopConfirm = false }
        )
    }

    RecorderContent(
        uiState = uiState,
        levelFlow = viewModel.audioLevel,
        selectedTab = selectedTab,
        onSelectTab = { chosenTab = it },
        onGrilleClick = {
            if (uiState.isRecording) viewModel.stopStreaming() else viewModel.startStreaming()
        },
        onBack = onBack,
        onAction = { action ->
            when (action) {
                RecorderAction.TryAgain -> viewModel.retry()
                RecorderAction.OpenSettings -> onOpenSettings()
                RecorderAction.TurnOnAi -> viewModel.turnOnAi()
                // The recorder stays: back from there, it reads the microphone again.
                RecorderAction.OpenAppSettings -> openAppSettings(context)
                RecorderAction.AllowMicrophone -> {
                    viewModel.onMicrophoneAsked()
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                RecorderAction.Leave -> leave()
            }
        },
        notices = notices,
        holdToStop = uiState.isRecording && keyguardLocked,
        onHoldHint = { scope.launch { notices.show(holdHint) } },
        onChangeTypeHint = { scope.launch { notices.show(changeHint) } },
        choosingType = choosingType,
        onChooseType = { choosingType = it },
        onRecordAs = { category ->
            choosingType = false
            viewModel.recordAs(category.id)
        },
        onRecordIn = { language ->
            // Closed as for a category: the notice says what the language means for the notes.
            choosingType = false
            viewModel.recordIn(language)
        }
    )
}

/**
 * The recorder without its ViewModel, so it can also be drawn with made-up state (debug
 * gallery): the inner layout, or the compact one on the cover screen. A microphone that isn't
 * allowed is asked for in place, where an error would be, with the one way on.
 *
 * @param holdToStop Whether the grille stops a recording only on a long press (over the lock
 *   screen); a tap calls [onHoldHint] then. The Record as chip opens only on a long press then,
 *   and a tap on it calls [onChangeTypeHint] ([recordAsChipInput]); so does the language key
 *   ([languageKeyInput]), whose switch is a pick of the other language ([onRecordIn])
 * @param choosingType Whether the "Record as" sheet is open; it shows only while the recording can
 *   be switched ([MainUiState.opensRecordAs]), whose label opens it with [onChooseType]
 * @param onRecordAs Another category was picked in the sheet; the caller closes it
 * @param onRecordIn Another language was picked in the sheet; the caller closes it, so the notice
 *   of the switch is seen. The sheet has its language row only with another language to pick
 *   ([MainUiState.canChangeLanguage]), and no link to the Malayalam model's download
 */
@Composable
internal fun RecorderContent(
    uiState: MainUiState,
    levelFlow: StateFlow<Float>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onGrilleClick: () -> Unit,
    onBack: () -> Unit,
    onAction: (RecorderAction) -> Unit = {},
    notices: NoticeState = rememberNoticeState(),
    holdToStop: Boolean = false,
    onHoldHint: () -> Unit = {},
    onChangeTypeHint: () -> Unit = {},
    choosingType: Boolean = false,
    onChooseType: (Boolean) -> Unit = {},
    onRecordAs: (RecordingCategory) -> Unit = {},
    onRecordIn: (RecordingLanguage) -> Unit = {}
) {
    val grilleState = grilleStateOf(uiState)
    val grille = GrilleInput(onGrilleClick, holdToStop, onHoldHint)
    // The label at the top opens "Record as" while the recording can be switched.
    val typeChip = recordAsChipInput(uiState.opensRecordAs, holdToStop)?.let { input ->
        TypeChip(open = { onChooseType(true) }, holdToOpen = input == ChipInput.HOLD, onHoldHint = onChangeTypeHint)
    }
    // Beside it, the language key switches the recording to the other language.
    val languageKey = languageKeyInput(uiState.canChangeLanguage, holdToStop)?.let { input ->
        LanguageKeyAction(
            switch = { uiState.language?.let { onRecordIn(it.other) } },
            holdToSwitch = input == LanguageKeyInput.HOLD,
            onHoldHint = onChangeTypeHint
        )
    }
    val lines = rememberTranscriptLines(uiState)
    // The cover's camera band holds the grille beside the cameras; a band that can't hold it (on a
    // side of the cover) is kept clear, and the recorder lays itself out as it does without one.
    val cameras = cameraBand()
    val band = bandForGrille(cameras, BAND_GRILLE, Grille.type.label.lineHeight)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .clearOf(cameras.takeIf { band == null })
            .systemBarsPadding()
    ) {
        // The cover's camera band is part of the window only to hold the grille, in the cover's
        // compact layout.
        if (band != null || maxHeight < COMPACT_HEIGHT) {
            CompactRecorder(
                uiState = uiState,
                lines = lines,
                grilleState = grilleState,
                band = band,
                levelFlow = levelFlow,
                grille = grille,
                onAction = onAction,
                typeChip = typeChip,
                languageKey = languageKey
            )
        } else {
            FullRecorder(
                uiState = uiState,
                lines = lines,
                grilleState = grilleState,
                levelFlow = levelFlow,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                grille = grille,
                onBack = onBack,
                onAction = onAction,
                typeChip = typeChip,
                languageKey = languageKey
            )
        }
        // Above the camera band, when the cover gives the window its whole height.
        NoticeHost(
            notices,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (band != null && !band.atTop) band.freeHeight() else 0.dp)
        )
    }

    if (choosingType && uiState.opensRecordAs) {
        val language = uiState.language?.takeIf { uiState.canChangeLanguage }
        RecordingTypeSheet(
            title = stringResource(R.string.g_record_as_title),
            choices = uiState.typeChoices,
            currentId = uiState.sessionCategoryId,
            onPick = onRecordAs,
            onDismiss = { onChooseType(false) },
            language = language?.let { current ->
                { RecordingLanguageRow(current, uiState.malayalamOnPhone, onPick = onRecordIn, onGetMalayalam = null) }
            }
        )
    }
}

/** What the grille does when it is touched; see [RecordGrille]. */
private class GrilleInput(val onClick: () -> Unit, val holdToStop: Boolean, val onHoldHint: () -> Unit)

/** The Record as chip at the top: [open] opens the sheet, only on a hold with [holdToOpen], when a tap calls [onHoldHint]. */
private class TypeChip(val open: () -> Unit, val holdToOpen: Boolean, val onHoldHint: () -> Unit)

/** The language key at the top: [switch] records in the other language, only on a hold with [holdToSwitch], when a tap calls [onHoldHint]. */
private class LanguageKeyAction(val switch: () -> Unit, val holdToSwitch: Boolean, val onHoldHint: () -> Unit)

/** How the recorder's Record as chip takes input ([recordAsChipInput]). */
internal enum class ChipInput {
    /** A tap opens "Record as". */
    TAP,

    /** Only a hold of at least half a second opens it, and a tap says so. */
    HOLD
}

/**
 * How the recorder's Record as chip takes input: none while the recording can't be switched
 * ([MainUiState.opensRecordAs]), else a tap opens "Record as"; over the lock screen ([holdToStop])
 * only a hold does, and a tap says so, as the grille stops a recording there only on a hold. The
 * screen stays on while recording, also in a pocket, where two brushes, one on the chip and one on
 * a row of the sheet, would otherwise file the recording under another category.
 */
internal fun recordAsChipInput(opensRecordAs: Boolean, holdToStop: Boolean): ChipInput? = when {
    !opensRecordAs -> null
    holdToStop -> ChipInput.HOLD
    else -> ChipInput.TAP
}

@Composable
private fun FullRecorder(
    uiState: MainUiState,
    lines: List<TranscriptionSegment>,
    grilleState: GrilleState,
    levelFlow: StateFlow<Float>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    grille: GrilleInput,
    onBack: () -> Unit,
    onAction: (RecorderAction) -> Unit,
    typeChip: TypeChip?,
    languageKey: LanguageKeyAction?
) {
    Column(Modifier.fillMaxSize()) {
        GrilleTopBar(
            title = sessionTitle(uiState),
            onBack = onBack,
            backDescription = stringResource(R.string.g_back),
            titleContent = typeChip?.let { chip -> { RecorderTypeChip(uiState, chip) } },
            actions = { languageKey?.let { key -> RecorderLanguageKey(uiState, key) } }
        )

        Column(Modifier.padding(horizontal = GrilleSpace.margin)) {
            Timer(uiState, Grille.type.timer)
            Spacer(Modifier.height(GrilleSpace.s))
            StatusLine(uiState)
        }

        if (uiState.microphone != MicrophoneState.ALLOWED) {
            Spacer(Modifier.height(GrilleSpace.s))
            MicrophoneNote(uiState.microphone, onAction)
        }
        uiState.error?.let { error ->
            Spacer(Modifier.height(GrilleSpace.s))
            RecorderErrorNote(error, onAction)
        }

        Spacer(Modifier.height(GrilleSpace.l))
        TextTabs(
            tabs = listOf(
                stringResource(R.string.g_tab_transcript),
                // A Translate recording's notes are its translation.
                stringResource(if (uiState.translatesInto != null) R.string.g_rec_tab_translation else R.string.g_tab_insights)
            ),
            selectedIndex = selectedTab,
            onSelect = onSelectTab
        )
        Hairline()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                // The first recording's coach line heads its transcript, as the words begin under it.
                TAB_TRANSCRIPT -> Column(Modifier.fillMaxSize()) {
                    if (uiState.showsCoach) {
                        CoachLine(Modifier.padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.l))
                    }
                    TranscriptionSection(
                        segments = lines,
                        isRecording = uiState.isRecording,
                        emptyHint = transcriptHint(uiState),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
                else -> RecorderNotes(uiState, onAction, Modifier.fillMaxSize())
            }
        }

        Hairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GrilleSpace.l, bottom = GrilleSpace.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LevelGrille(state = grilleState, levelFlow = levelFlow, input = grille)
            // The caption's room is kept whether it is shown or not, so the record button never
            // moves under a finger: not on the notes tab, which says it itself with the same
            // action, not while saving, and not when the AI model turns out to be missing
            // while the models load or fails during a recording. Nor while the microphone is to
            // be allowed, the one thing to do then.
            AiCaptionSlot {
                val problem = uiState.aiProblem
                if (problem != null && !uiState.isFinalizingSession && selectedTab == TAB_TRANSCRIPT &&
                    uiState.microphone == MicrophoneState.ALLOWED
                ) {
                    AiCaption(problem, onAction, coming = uiState.aiModelComing)
                }
            }
        }
    }
}

/**
 * Cover screen: timer, status line, the transcript (or what went wrong), and the grille on the
 * right, or beside the cameras when the window takes in their [band]. The column is aligned to
 * the top, so the timer stays where it is whatever the text below it does. The transcript takes
 * the rest of the column, the full width when the grille is in the band, and follows the speaker
 * as on the inner screen; an error and its action scroll instead when they need more room. While
 * the recording can be switched, the Record as chip sits at the end of the timer's row, and the
 * language key after it while its language can.
 */
@Composable
private fun CompactRecorder(
    uiState: MainUiState,
    lines: List<TranscriptionSegment>,
    grilleState: GrilleState,
    band: CameraBand?,
    levelFlow: StateFlow<Float>,
    grille: GrilleInput,
    onAction: (RecorderAction) -> Unit,
    typeChip: TypeChip?,
    languageKey: LanguageKeyAction?
) {
    val drawGrille: @Composable () -> Unit = {
        LevelGrille(state = grilleState, levelFlow = levelFlow, input = grille, size = BAND_GRILLE)
    }
    Column(Modifier.fillMaxSize()) {
        if (band?.atTop == true) CameraBandRow(band) { drawGrille() }
        CompactRecorderMain(
            uiState,
            lines,
            grilleBeside = if (band == null) drawGrille else null,
            onAction,
            typeChip,
            languageKey,
            Modifier.weight(1f)
        )
        if (band != null && !band.atTop) CameraBandRow(band) { drawGrille() }
    }
}

/**
 * The timer, the status and the transcript of [CompactRecorder], with [grilleBeside] on their
 * right, and the Record as chip at the end of the timer's row while [typeChip] is set, the
 * language key after it while [languageKey] is. The timer's 52 sp are taller than the chip, so the
 * chip moves nothing below it; past an hour, with the grille beside the column, the chip has the
 * width the timer and the key leave, and its name ends in an ellipsis there before the key moves.
 */
@Composable
private fun CompactRecorderMain(
    uiState: MainUiState,
    lines: List<TranscriptionSegment>,
    grilleBeside: (@Composable () -> Unit)?,
    onAction: (RecorderAction) -> Unit,
    typeChip: TypeChip?,
    languageKey: LanguageKeyAction?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Timer(uiState, Grille.type.timerCompact)
                if (typeChip != null) {
                    Spacer(Modifier.width(GrilleSpace.m))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        RecorderTypeChip(uiState, typeChip)
                    }
                }
                if (languageKey != null) {
                    Spacer(Modifier.width(GrilleSpace.s))
                    RecorderLanguageKey(uiState, languageKey)
                }
            }
            Spacer(Modifier.height(GrilleSpace.s))
            StatusLine(uiState)
            Spacer(Modifier.height(GrilleSpace.m))
            if (uiState.showsCoach) {
                CoachLine()
                Spacer(Modifier.height(GrilleSpace.s))
            }
            // The microphone to be allowed is the one thing to do: no AI caption beside it.
            val microphoneAllowed = uiState.microphone == MicrophoneState.ALLOWED
            val problem = uiState.aiProblem?.takeUnless { uiState.isFinalizingSession || !microphoneAllowed }
            val error = uiState.error
            if (!microphoneAllowed) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    MicrophoneNote(uiState.microphone, onAction, horizontalMargin = 0.dp)
                }
            } else if (error != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    RecorderErrorNote(error, onAction, horizontalMargin = 0.dp)
                    if (problem != null) {
                        AiCaption(problem, onAction, Modifier.padding(top = GrilleSpace.s), Alignment.Start, coming = uiState.aiModelComing)
                    }
                }
            } else {
                // A Translate recording shows its newest translation, once there is one: the cover
                // has no room for the inner screen's tabs, and the translation is what is read.
                val latest = remember(uiState.insights, uiState.completedSegments, uiState.translatesInto) { coverTranslation(uiState) }
                if (latest != null) {
                    CoverTranslation(
                        latest,
                        partial = livePartial(uiState),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    TranscriptionSection(
                        segments = lines,
                        isRecording = uiState.isRecording,
                        // Before the first words the status line already says what is going on.
                        emptyHint = if (uiState.phase == RecorderPhase.Recording) {
                            stringResource(R.string.g_transcript_listening)
                        } else {
                            sessionTitle(uiState)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp)
                    )
                }
                // Under the transcript, which makes room for it, so it never covers the newest line.
                if (problem != null) {
                    AiCaption(problem, onAction, Modifier.padding(top = GrilleSpace.s), Alignment.Start, coming = uiState.aiModelComing)
                }
            }
        }
        if (grilleBeside != null) {
            Spacer(Modifier.width(GrilleSpace.m))
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { grilleBeside() }
        }
    }
}

/** The timer, read as a length ("Length 3:27") rather than a time of day. */
@Composable
private fun Timer(uiState: MainUiState, style: TextStyle) {
    val description = stringResource(R.string.g_rec_timer_cd, formatDuration(uiState.recordingDurationMillis))
    RecTimer(
        millis = uiState.recordingDurationMillis,
        live = uiState.isRecording,
        style = style,
        modifier = Modifier.semantics { contentDescription = description }
    )
}

/**
 * The first recording's one coach line ([MainUiState.showsCoach]): talk for a minute, and the notes
 * follow after Stop. Ash, as a hint is, so it doesn't read as a line of the transcript.
 */
@Composable
private fun CoachLine(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.g_fr_coach),
        style = Grille.type.body,
        color = Grille.colors.ash,
        modifier = modifier
    )
}

/** The one status line. Its space is kept when there is nothing to say, so nothing below it moves. */
@Composable
private fun StatusLine(uiState: MainUiState) {
    Box(Modifier.heightIn(min = STATUS_LINE_HEIGHT)) {
        statusText(uiState)?.let { RecIndicator(text = it, live = uiState.isRecording) }
    }
}

/** Collects the level here so its ~10 Hz updates only recompose the grille. */
@Composable
private fun LevelGrille(
    state: GrilleState,
    levelFlow: StateFlow<Float>,
    input: GrilleInput,
    size: Dp = 112.dp
) {
    val level by levelFlow.collectAsStateWithLifecycle()
    // While the recorder prepares or saves, the status line says it and the grille keeps
    // only the name for a screen reader.
    val label = stringResource(
        when (state) {
            GrilleState.Recording -> R.string.g_stop
            GrilleState.Preparing -> R.string.g_rec_preparing
            GrilleState.Saving -> R.string.g_rec_saving
            else -> R.string.g_record
        }
    )
    RecordGrille(
        state = state,
        label = label,
        onClick = input.onClick,
        level = level,
        size = size,
        showLabel = state != GrilleState.Preparing && state != GrilleState.Saving,
        holdToStop = input.holdToStop,
        onHoldHint = input.onHoldHint
    )
}

/**
 * The transcript's lines for both layouts, worked out again only when the recorder's lines
 * change, so the timer's ticks leave the list alone. [TranscriptLines] says which line it keeps
 * on screen while a finished line is being saved.
 */
@Composable
private fun rememberTranscriptLines(uiState: MainUiState): List<TranscriptionSegment> {
    // What the last applied composition showed. Written only once a composition is applied, so
    // one that is thrown away changes nothing.
    val shown = remember { ShownLines() }
    val next = remember(uiState.completedSegments, uiState.currentPartialSegment, uiState.isRecording) {
        shown.lines.next(uiState.allSegments, uiState.isRecording)
    }
    SideEffect { shown.lines = next }
    return next.lines
}

/** The [TranscriptLines] on screen, for [rememberTranscriptLines]. */
private class ShownLines {
    var lines: TranscriptLines = TranscriptLines.None
}

/**
 * Keeps the screen from timing out while [on]: View.keepScreenOn, which holds the window's
 * FLAG_KEEP_SCREEN_ON while this view is shown. The power key still turns the screen off.
 */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Whether the phone is locked now, so the recorder shows over the lock screen and stops only on a long press. */
private fun isKeyguardLocked(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

/** The record button's state. */
private fun grilleStateOf(uiState: MainUiState): GrilleState = when (uiState.phase) {
    RecorderPhase.Preparing -> GrilleState.Preparing
    RecorderPhase.Ready -> GrilleState.Idle
    RecorderPhase.Recording -> GrilleState.Recording
    RecorderPhase.Saving -> GrilleState.Saving
    RecorderPhase.Blocked -> GrilleState.Disabled
}

/**
 * What the recorder calls the recording, as home does: its name, else the name of its category,
 * else the name of its mode.
 */
@Composable
private fun sessionTitle(uiState: MainUiState): String =
    uiState.sessionName?.takeIf { it.isNotBlank() }
        ?: uiState.recordingAs?.name
        ?: uiState.sessionMode?.let { modeLabel(it) }
        ?: ""

/** The recorder's Record as chip of [chip]: on one line, as it shares its row with the timer or the back button. */
@Composable
private fun RecorderTypeChip(uiState: MainUiState, chip: TypeChip) {
    RecordAsChip(
        label = typeLabel(uiState),
        onClick = chip.open,
        holdToOpen = chip.holdToOpen,
        onHoldHint = chip.onHoldHint,
        singleLine = true
    )
}

/**
 * The recorder's language key of [key], which says the recording's language as its transcript
 * shows it ([languageKeyLabel]), worked out again only when its language or its lines change.
 */
@Composable
private fun RecorderLanguageKey(uiState: MainUiState, key: LanguageKeyAction) {
    val language = uiState.language ?: return
    val label = remember(language, uiState.completedSegments) { languageKeyLabel(language, uiState.completedSegments) }
    LanguageKey(label = label, onSwitch = key.switch, holdToSwitch = key.holdToSwitch, onHoldHint = key.onHoldHint)
}

/**
 * What the Record as chip says the recording is recorded as: its category, else its mode. Its
 * language is in the status line ([statusText]): beside the timer on the cover screen the chip has
 * no room for both, and would cut the language off.
 */
@Composable
private fun typeLabel(uiState: MainUiState): String =
    uiState.recordingAs?.name
        ?: uiState.sessionMode?.let { modeLabel(it) }
        ?: ""

/**
 * What the recorder is doing, in a few words; null at rest, when there is nothing to say. While
 * the speech model still loads at the start (its files read in at a pace since 0.7.1, so the
 * phone's memory isn't pressed), the microphone already records and the status line says "Getting
 * ready" until the model is in: only the live text waits for it. A recording in Malayalam says so
 * ("Recording in Malayalam"), as English or German is the categories' own. A Translate recording
 * says which language goes in and which comes out ([translationStatus]).
 */
@Composable
private fun statusText(uiState: MainUiState): String? {
    val languages = uiState.translatesInto?.let { target ->
        remember(uiState.completedSegments, target) { TranslationLanguages.of(uiState.completedSegments, target) }
    }
    val status = recorderStatus(uiState, languages) ?: return null
    if (status.args.isEmpty()) return stringResource(status.text)
    return stringResource(status.text, *status.args.map { stringResource(it) }.toTypedArray())
}

/** A status line's words: [text], with the strings [args] put into it in order. */
internal data class StatusLineRes(val text: Int, val args: List<Int> = emptyList())

/**
 * What the status line says ([statusText]); null at rest. A Translate recording, whose
 * [languages] are known, says them ([translationStatus]); one in Malayalam says it is recorded in
 * Malayalam ("Recording in Malayalam"), as English or German is the categories' own.
 */
internal fun recorderStatus(uiState: MainUiState, languages: TranslationLanguages?): StatusLineRes? = when (uiState.phase) {
    RecorderPhase.Saving -> StatusLineRes(R.string.g_rec_saving)
    RecorderPhase.Recording -> when {
        uiState.listening -> StatusLineRes(R.string.g_rec_preparing)
        languages != null -> translationStatus(languages)
        uiState.language == RecordingLanguage.MALAYALAM -> StatusLineRes(R.string.g_rec_live_ml)
        else -> StatusLineRes(R.string.g_rec_live)
    }
    RecorderPhase.Preparing -> StatusLineRes(R.string.g_rec_preparing)
    RecorderPhase.Ready, RecorderPhase.Blocked -> null
}

/**
 * A Translate recording's status line: "German into English" once the language that goes in is
 * known from what was said ([TranslationLanguages]), "Into English" until then, and "Already
 * English: nothing to translate" when what is said is in the language it is translated into.
 */
private fun translationStatus(languages: TranslationLanguages): StatusLineRes {
    val into = languageName(languages.target)
    val spoken = languages.spoken
    return when {
        languages.same -> StatusLineRes(R.string.g_rec_translate_same, listOf(into))
        spoken == LanguageDetector.ENGLISH || spoken == LanguageDetector.GERMAN ->
            StatusLineRes(R.string.g_rec_translate_from_into, listOf(languageName(spoken), into))
        else -> StatusLineRes(R.string.g_rec_translate_into, listOf(into))
    }
}

/** The name of a language a recording can be translated into or from: English, German. */
private fun languageName(code: String): Int =
    if (code == LanguageDetector.GERMAN) R.string.g_rec_language_de else R.string.g_rec_language_en

/**
 * What the cover shows in place of the transcript: a Translate recording's newest translation,
 * with the lines it came from, once there is one; null for any other recording and before the
 * first translation, when the transcript shows.
 */
internal fun coverTranslation(uiState: MainUiState): LatestTranslation? =
    if (uiState.translatesInto == null) null else LatestTranslation.of(uiState.insights, uiState.completedSegments)

/** The line being said now, as the speech model has it so far; null between lines. */
internal fun livePartial(uiState: MainUiState): String? = uiState.currentPartialSegment?.text?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The cover's view of a Translate recording: the lines the newest translation was translated
 * from, in the caption style and grey, cut after two lines, and the translation under them, which
 * scrolls when it needs more room than there is, from its top again for each new one. Under it, on
 * one grey line, what is being said now ([partial]), so the screen shows that the speech is heard.
 */
@Composable
private fun CoverTranslation(latest: LatestTranslation, partial: String?, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(latest.text) { scroll.scrollTo(0) }
    Column(modifier) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll)
        ) {
            if (latest.source.isNotEmpty()) {
                Text(
                    text = latest.source,
                    style = Grille.type.caption,
                    color = Grille.colors.ash,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(GrilleSpace.s))
            }
            Text(text = latest.text, style = Grille.type.body, color = Grille.colors.ink)
        }
        if (partial != null) {
            Spacer(Modifier.height(GrilleSpace.s))
            Text(
                text = partial,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * What the empty transcript says: "Listening" while recording. Nothing before that, when the
 * status line and the sweeping grille already say "Getting ready", and nothing while an error or
 * the microphone dialog is on screen: they are the message.
 */
@Composable
private fun transcriptHint(uiState: MainUiState): String? = when (uiState.phase) {
    RecorderPhase.Recording -> stringResource(R.string.g_transcript_listening)
    RecorderPhase.Preparing, RecorderPhase.Ready, RecorderPhase.Saving, RecorderPhase.Blocked -> null
}

/** Whether the app may use the microphone now. */
private fun microphoneGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Whether Android says the microphone was refused before and would still ask
 * (shouldShowRequestPermissionRationale): after a second refusal, or a "don't ask again", it says no.
 */
private fun microphoneRefusedBefore(context: Context): Boolean {
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
}

/** The activity [this] context belongs to, if any. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens Android's settings page of this app, where the microphone can be allowed. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // A phone without a settings page for apps: the user finds the permission by hand.
    }
}

/**
 * The tab the recorder opens on, until the owner picks one: a Translate recording's translation,
 * what the owner reads, and any other recording's transcript.
 */
internal fun openingTab(uiState: MainUiState): Int = if (uiState.translatesInto != null) TAB_NOTES else TAB_TRANSCRIPT

/** The tab that shows the transcript; the other one shows the notes. */
internal const val TAB_TRANSCRIPT = 0

/** The tab that shows the notes, a Translate recording's translation. */
internal const val TAB_NOTES = 1

/** No tab chosen by the owner yet. */
private const val NO_TAB = -1

/** Below this height (the razr cover screen) the compact recorder is used. */
private val COMPACT_HEIGHT = 560.dp

/** The cover's grille: in the camera band beside the cameras, or beside the transcript. */
private val BAND_GRILLE = 104.dp

/** The height of the status line, which is kept free even when it says nothing. */
private val STATUS_LINE_HEIGHT = 20.dp
