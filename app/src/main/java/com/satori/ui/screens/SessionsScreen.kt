package com.satori.ui.screens

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.model.RecordTarget
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.Todo
import com.satori.domain.usecase.todo.TodoSource
import com.satori.presentation.firstrun.StripState
import com.satori.presentation.sessions.SessionsViewModel
import com.satori.ui.R
import com.satori.ui.STARTUP_PREFS
import com.satori.ui.components.RecordAsChip
import com.satori.ui.components.RecordAsSheet
import com.satori.ui.components.TodoSheet
import com.satori.ui.components.TodoSuggestionsSheet
import com.satori.ui.components.openNotificationSettings
import com.satori.ui.components.recordingTitleText
import com.satori.ui.components.rememberMinuteClock
import com.satori.ui.components.rememberReminderPrompt
import com.satori.ui.design.CameraBandRow
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleState
import com.satori.ui.design.Hairline
import com.satori.ui.design.NoticeResult
import com.satori.ui.design.NoticeState
import com.satori.ui.design.RecordGrille
import com.satori.ui.design.Wordmark
import com.satori.ui.design.bandForGrille
import com.satori.ui.design.cameraBand
import com.satori.ui.design.clearOf
import com.satori.ui.design.modeLabel
import com.satori.ui.design.rememberNoticeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Home: what there is to do, what was recorded, and the grille. One tap on the grille records
 * as whatever was recorded as last, in the language it was recorded in; the chip under it names
 * that (with "In Malayalam" under it for Malayalam) and opens "Record as", where the language is
 * chosen above the categories.
 *
 * The to-dos come first, because they are why the recordings were made. Completing one, in the
 * list or in its sheet, shows a notice with Undo; tapping its text opens its sheet, from which a
 * to-do that is only an idea can be moved to the ideas. A row under them opens the ideas and says
 * how many are new until the owner has seen it ([IdeasRowSightings]), and a recording being
 * finished in the background says its step under its title.
 *
 * The status strip above the list shows the AI model's download after the first recording, the
 * first notes' confirmation and the one-time offers ([com.satori.presentation.sessions.HomeStrip]).
 * Its pills ask the view model, or open Android's notification window for the ping and its storage
 * settings for want of room; an offer counts as shown once it was seen ([StripSightings]), and
 * opening a recording ends the first notes' confirmation.
 *
 * @param openTodoId The to-do whose sheet to open once, for a tapped reminder
 * @param takeNothingSaidNotice Whether to say, once home is seen, that the recorder deleted its
 *   recording because nothing was said in it ([NothingSaidNotice.takeOnceSeen]); asked each time
 *   home starts, and waits while the phone is locked
 * @param onNavigateToCategories Opens Settings > Categories, from "Edit categories"
 * @param onNavigateToSpeechModels Opens Advanced settings, where the Malayalam model downloads,
 *   from the link "Record as" has while it isn't on the phone
 * @param onNavigateToIdeas Opens the ideas found in recordings, from the to-dos
 */
@Composable
fun SessionsScreen(
    openTodoId: String? = null,
    takeNothingSaidNotice: suspend () -> Boolean = { false },
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCategories: () -> Unit = {},
    onNavigateToSpeechModels: () -> Unit = {},
    onNavigateToSessionDetails: (String) -> Unit = {},
    onNavigateToRecording: (sessionId: String, autostart: Boolean) -> Unit = { _, _ -> },
    onNavigateToSearch: () -> Unit = {},
    onNavigateToMindMap: () -> Unit = {},
    onNavigateToIdeas: () -> Unit = {},
    viewModel: SessionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetTodo by viewModel.sheetTodo.collectAsStateWithLifecycle()
    val now by rememberMinuteClock()
    val context = LocalContext.current
    val notices = rememberNoticeState()
    val scope = rememberCoroutineScope()
    val doneText = stringResource(R.string.g_todo_done)
    val undoText = stringResource(R.string.g_todo_undo)
    val nothingSaidText = stringResource(R.string.g_home_nothing_said)

    // The recorder that went back here may have deleted its recording, which then is not in the
    // list: say why, once home is seen. Each start asks, as one behind the lock screen is not seen;
    // the wait for the unlock ends when home stops, and the notice, once taken, is shown in full.
    val takeNothingSaid by rememberUpdatedState(takeNothingSaidNotice)
    LifecycleStartEffect(Unit) {
        val asking = scope.launch {
            if (takeNothingSaid()) scope.launch { notices.show(nothingSaidText) }
        }
        onStopOrDispose { asking.cancel() }
    }
    IdeasMovedNotice(notices)

    // Above the layouts below, which a fold or a turn of the screen swaps: the list keeps its
    // scroll, and the ideas row stays seen or unseen.
    val listState = rememberLazyListState()
    IdeasRowSightings(
        listState = listState,
        newIdeas = uiState.ideasRow?.newCount,
        onStarted = { viewModel.homeStarted() },
        onStopped = { viewModel.homeStopped() },
        onSeen = viewModel::ideasRowSeen
    )

    var showRecordAs by rememberSaveable { mutableStateOf(false) }
    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    // Not saved: leaving the screen folds the list back to five.
    var showAllTodos by remember { mutableStateOf(false) }

    // A tapped reminder links here with the id of its to-do. Its sheet opens once: the id that was
    // opened is saved, so a rotation or a return to this screen doesn't open it again.
    var openedTodoId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openTodoId) {
        if (openTodoId != null && openTodoId != openedTodoId) {
            openedTodoId = openTodoId
            viewModel.openTodoById(openTodoId)
        }
    }

    // Guards against a second tap while the session row is being created; a failed start lifts it,
    // so the next tap tries again.
    var starting by remember { mutableStateOf(false) }
    // The language picked in Record as, or none for the one-tap Record, which records in the stored one.
    val record: (RecordTarget, RecordingLanguage?) -> Unit = { target, language ->
        if (!starting) {
            starting = true
            viewModel.startRecording(
                target = target,
                language = language,
                onSessionCreated = { id -> onNavigateToRecording(id, true) },
                onFailed = { starting = false }
            )
        }
    }

    // One ask at a time on home: the strip's ping waits while this sheet asks, and the sheet waits,
    // as the strip's offers do, while a recorder is live and for a minute after a Stop.
    val reminderPrompt = rememberReminderPrompt(
        hasReminders = uiState.hasPendingReminders,
        mayAsk = !uiState.asksQuiet,
        onAllowed = viewModel::notificationsAllowed
    )

    // The strip: its offers count as shown once seen, and its pills ask the view model or Android.
    StripSightings(strip = uiState.strip, onSeen = viewModel::stripSeen)
    val notificationWindow = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        viewModel.notificationsAnswered(allowed)
    }
    val openRecording: (String) -> Unit = { sessionId ->
        viewModel.recordingOpened()
        onNavigateToSessionDetails(sessionId)
    }
    val stripTap: (StripTap) -> Unit = { tap ->
        when (tap) {
            is StripTap.Retry -> viewModel.retryDownload(tap.part)
            is StripTap.UseMobileData -> viewModel.useMobileData(tap.part)
            StripTap.Resume -> viewModel.resumeDownload()
            StripTap.OpenStorageSettings -> openStorageSettings(context)
            StripTap.RetryMalayalam -> viewModel.retryMalayalam()
            is StripTap.OpenFirstNotes -> {
                viewModel.firstNotesOpened()
                // The page opens on the notes, as the recording has some.
                onNavigateToSessionDetails(tap.sessionId)
            }
            is StripTap.Answer -> {
                val answered = viewModel.offerAnswered(tap.id, tap.accepted)
                // The ping is offered only where Android's window can ask (Android 13 and later).
                if (answered && tap.accepted && tap.id == NudgeId.NOTIFY_PING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationWindow.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    val completeTodo: (Todo, TodoSource) -> Unit = { todo, source ->
        viewModel.completeTodo(todo.id, source)
        scope.launch {
            if (notices.show(doneText, undoText) == NoticeResult.ActionPerformed) viewModel.reopenTodo(todo.id)
        }
    }

    // The cover's camera band holds the dock beside the cameras; a band that can't hold it (on a
    // side of the cover) is kept clear, and home lays itself out as it does without one.
    val cameras = cameraBand()
    val band = bandForGrille(cameras, BAND_GRILLE, Grille.type.label.lineHeight)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .clearOf(cameras.takeIf { band == null })
            .systemBarsPadding()
    ) {
        // The cover's camera band is part of the window only to hold the dock.
        val compact = maxHeight - (band?.depth ?: 0.dp) < COMPACT_HEIGHT
        val list: @Composable (Modifier) -> Unit = { modifier ->
            HomeList(
                state = uiState,
                compact = compact,
                listState = listState,
                nowMs = now,
                showAllTodos = showAllTodos,
                reminderPrompt = reminderPrompt,
                notices = notices,
                onShowAllTodos = { showAllTodos = true },
                onShowSuggestions = { showSuggestions = true },
                onOpenIdeas = {
                    viewModel.ideasRowTapped()
                    onNavigateToIdeas()
                },
                onTurnOnReminders = { openNotificationSettings(context) },
                onCompleteTodo = completeTodo,
                onOpenTodo = viewModel::openTodo,
                onOpenRecording = openRecording,
                onDismissError = viewModel::clearError,
                onStripTap = stripTap,
                modifier = modifier
            )
        }
        // Beside the cameras the dock lies on its side: the band is wide and low.
        val bandDock: @Composable () -> Unit = {
            RecordDock(
                target = uiState.target,
                language = uiState.language,
                onRecord = { record(uiState.target, null) },
                onChange = { showRecordAs = true },
                grilleSize = BAND_GRILLE,
                horizontal = true
            )
        }
        Column(Modifier.fillMaxSize()) {
            if (band?.atTop == true) CameraBandRow(band) { bandDock() }
            HomeHeader(
                showSearch = !uiState.isLoading && !uiState.isEmpty,
                // The map is made of recordings; with none it would only say so.
                showMindMap = !uiState.isLoading && uiState.recordings.isNotEmpty(),
                onSearch = onNavigateToSearch,
                onMindMap = onNavigateToMindMap,
                onSettings = onNavigateToSettings
            )
            if (band != null) {
                list(Modifier.weight(1f).fillMaxWidth())
                if (!band.atTop) CameraBandRow(band) { bandDock() }
            } else if (compact) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    list(Modifier.weight(1f).fillMaxHeight())
                    RecordDock(
                        target = uiState.target,
                        language = uiState.language,
                        onRecord = { record(uiState.target, null) },
                        onChange = { showRecordAs = true },
                        grilleSize = 96.dp,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = GrilleSpace.m)
                    )
                }
            } else {
                list(Modifier.weight(1f).fillMaxWidth())
                Hairline()
                RecordDock(
                    target = uiState.target,
                    language = uiState.language,
                    onRecord = { record(uiState.target, null) },
                    onChange = { showRecordAs = true },
                    grilleSize = 112.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = GrilleSpace.l, bottom = GrilleSpace.m)
                )
            }
        }
    }

    if (showRecordAs) {
        RecordAsSheet(
            categories = uiState.categories,
            translateTarget = uiState.translateTarget,
            language = uiState.language,
            malayalamOnPhone = uiState.malayalamOnPhone,
            onPick = { target, language ->
                showRecordAs = false
                record(target, language)
            },
            onEditCategories = {
                showRecordAs = false
                onNavigateToCategories()
            },
            onGetMalayalam = {
                showRecordAs = false
                onNavigateToSpeechModels()
            },
            onDismiss = { showRecordAs = false }
        )
    }

    if (showSuggestions) {
        TodoSuggestionsSheet(
            suggestions = uiState.suggestions,
            titles = uiState.titles,
            nowMs = now,
            onKeep = { viewModel.keepSuggestions(listOf(it)) },
            onDismissSuggestion = { viewModel.dismissSuggestion(it.id) },
            onKeepAll = viewModel::keepSuggestions,
            onClose = { showSuggestions = false },
            onMoveToIdeas = { viewModel.moveSuggestionToIdeas(it.id) }
        )
    }

    sheetTodo?.let { todo ->
        TodoSheet(
            todo = todo,
            sourceTitle = todo.sessionId?.let(uiState.titles::get)?.let { recordingTitleText(it) },
            onOpenSource = {
                viewModel.closeTodo()
                todo.sessionId?.let(openRecording)
            },
            onSave = viewModel::saveOpenTodo,
            onComplete = {
                viewModel.closeTodo()
                completeTodo(todo, TodoSource.HOME)
            },
            onDelete = viewModel::deleteOpenTodo,
            onDismiss = viewModel::closeTodo,
            onMoveToIdeas = viewModel::moveOpenTodoToIdeas
        )
    }
}

/**
 * Says once how many to-dos the one-time move put among the ideas ([IdeasNotice]), where the owner
 * sees it: as home starts with the phone unlocked, or once it reads as unlocked
 * ([takeOnceUnlocked]). A notice shown behind the lock screen would be taken without being seen,
 * so home stopping there leaves it for a later start. MainActivity runs the move at start, beside
 * home's first showing, so while home is started a notice left afterwards is taken as it arrives.
 * A notice on show, such as a Done with its Undo, ends first: showing this one would replace it.
 */
@Composable
private fun IdeasMovedNotice(notices: NoticeState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved, as a count taken waits here while another notice shows, also if home is left meanwhile.
    var moved by rememberSaveable { mutableStateOf<Int?>(null) }
    LifecycleStartEffect(Unit) {
        val prefs = context.getSharedPreferences(STARTUP_PREFS, Context.MODE_PRIVATE)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        var taking: Job? = null
        val take = {
            if (taking?.isActive != true) {
                taking = scope.launch {
                    takeOnceUnlocked(
                        isLocked = { keyguard?.isKeyguardLocked == true },
                        take = { IdeasNotice.takeMoved(context) }
                    )?.let { moved = it }
                }
            }
        }
        // Any change of the startup marks may be the notice; taking it when there is none does nothing.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> take() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        take()
        onStopOrDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            taking?.cancel()
        }
    }
    moved?.let { count ->
        val text = pluralStringResource(R.plurals.g_ideas_moved, count, count)
        LaunchedEffect(count) {
            awaitNoNotice(notices)
            moved = null
            // From the screen's scope, as clearing the count ends this effect; at once, so that
            // nothing shows in between.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { notices.show(text) }
        }
    }
}

/**
 * Waits until [notices] shows none: NoticeState.show replaces the notice on show, which would take
 * the Undo of a Done away.
 */
internal suspend fun awaitNoNotice(notices: NoticeState) {
    snapshotFlow { notices.current }.first { it == null }
}

/**
 * Takes the notice of the one-time move with [take] once home can be seen: at once while the
 * phone is unlocked, else as soon as it reads as unlocked, looking every [pollMs]. An unlock
 * starts home before the phone reads as unlocked (see [NothingSaidNotice]), so looking only as
 * home starts would miss it. Cancelled as home stops behind the lock screen, it takes nothing.
 *
 * @return What [take] gave, or null when there was no notice
 */
internal suspend fun takeOnceUnlocked(
    isLocked: () -> Boolean,
    take: () -> Int?,
    pollMs: Long = NothingSaidNotice.UNLOCK_POLL_MS
): Int? {
    while (isLocked()) delay(pollMs)
    return take()
}

/**
 * Tells home's ViewModel how far the owner has seen the ideas row (IdeasSighting): when home
 * starts and stops, and when the row has been seen. It is seen once it has been on screen in full
 * ([showsInFull]) while home is resumed and the phone unlocked, for [IDEAS_ROW_SEEN_MS]
 * ([awaitIdeasRowSeen]). A row only composed ahead of a scroll, one behind the lock screen, as
 * when the recorder over it went back home, and one shown for a blink are not seen. This sits
 * above home's layouts, so a fold or a turn of the screen, which swaps them, is no leave.
 *
 * @param newIdeas How many ideas the row counts as new; null for none, when nothing is watched
 * @param onSeen The row has been seen, saying what it said at the time given (epoch ms)
 */
@Composable
private fun IdeasRowSightings(
    listState: LazyListState,
    newIdeas: Int?,
    onStarted: () -> Unit,
    onStopped: () -> Unit,
    onSeen: (sinceMs: Long) -> Unit
) {
    val context = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    val stopped by rememberUpdatedState(onStopped)
    val seen by rememberUpdatedState(onSeen)
    LifecycleStartEffect(Unit) {
        started()
        onStopOrDispose { stopped() }
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val rowOnScreen by remember(listState) { derivedStateOf { listState.layoutInfo.showsInFull(IDEAS_ROW_KEY) } }
    val watching = newIdeas != null && rowOnScreen && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Started again when the count changes, as the row then says something not seen yet.
    LaunchedEffect(watching, newIdeas) {
        if (!watching) return@LaunchedEffect
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        seen(awaitIdeasRowSeen(nowMs = System::currentTimeMillis, isLocked = { keyguard?.isKeyguardLocked == true }))
    }
}

/**
 * Waits, while the ideas row is on screen and home resumed, until the owner has seen it: until the
 * phone has read as unlocked for [seenMs], looking every [pollMs]. Behind the lock screen the row
 * is not seen; an unlock reads as one some 60 ms after home has resumed (see
 * [NothingSaidNotice]), and the wait starts again if the phone locks meanwhile.
 *
 * @return When the row began to be seen, on the clock of [nowMs]
 */
internal suspend fun awaitIdeasRowSeen(
    nowMs: () -> Long,
    isLocked: () -> Boolean,
    seenMs: Long = IDEAS_ROW_SEEN_MS,
    pollMs: Long = NothingSaidNotice.UNLOCK_POLL_MS
): Long {
    var since: Long? = null
    while (true) {
        val now = nowMs()
        since = if (isLocked()) null else since ?: now
        if (since != null && now - since >= seenMs) return since
        delay(pollMs)
    }
}

/** How long the ideas row is on screen, with the phone unlocked, before it counts as seen (ms). */
internal const val IDEAS_ROW_SEEN_MS = 1_000L

/**
 * Tells home's view model when the strip's offer or its first notes' confirmation has been seen, so
 * the nudge ledger counts it as shown from then, once: on screen while home is resumed and the phone
 * unlocked, for [IDEAS_ROW_SEEN_MS], as the ideas row is ([awaitIdeasRowSeen]). One behind the lock
 * screen, as when the recorder over it went back home, and one on screen for a blink are not seen;
 * the ledger keeps them for their one showing. The strip's downloads need no sighting.
 *
 * @param onSeen The strip shown has been seen since the time given (epoch ms)
 */
@Composable
private fun StripSightings(strip: StripState?, onSeen: (StripState, Long) -> Unit) {
    val context = LocalContext.current
    val seen by rememberUpdatedState(onSeen)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val watched = strip?.takeIf { it is StripState.Nudge || it is StripState.FirstNotes }
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(watched, resumed) {
        if (watched == null || !resumed) return@LaunchedEffect
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        seen(watched, awaitIdeasRowSeen(nowMs = System::currentTimeMillis, isLocked = { keyguard?.isKeyguardLocked == true }))
    }
}

@Composable
private fun HomeHeader(
    showSearch: Boolean,
    showMindMap: Boolean,
    onSearch: () -> Unit,
    onMindMap: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.s, top = GrilleSpace.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark(Modifier.weight(1f))
        if (showSearch) {
            CircleIconButton(icon = GrilleIcons.Search, contentDescription = stringResource(R.string.g_search), onClick = onSearch)
        }
        if (showMindMap) {
            CircleIconButton(icon = GrilleIcons.MindMap, contentDescription = stringResource(R.string.g_map_title), onClick = onMindMap)
        }
        CircleIconButton(icon = GrilleIcons.Settings, contentDescription = stringResource(R.string.g_settings), onClick = onSettings)
    }
}

/**
 * The grille and the chip that names what it records as and changes that: the chip under the
 * grille, or beside it when [horizontal] (in the cover's camera band). While the one-tap Record
 * records in Malayalam a quiet line under the chip says so ("In Malayalam"), as English or German
 * is the categories' own and a translation has none: in the cover's narrow column the chip has no
 * room for the category and the language both.
 */
@Composable
private fun RecordDock(
    target: RecordTarget,
    language: RecordingLanguage,
    onRecord: () -> Unit,
    onChange: () -> Unit,
    grilleSize: Dp,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false
) {
    val grille: @Composable () -> Unit = {
        RecordGrille(
            state = GrilleState.Idle,
            label = stringResource(R.string.g_record),
            onClick = onRecord,
            size = grilleSize
        )
    }
    val chip: @Composable (Modifier) -> Unit = { chipModifier ->
        val label = when (target) {
            is RecordTarget.Category -> target.category.name
            RecordTarget.Translate -> stringResource(R.string.g_mode_translate)
            RecordTarget.VoiceNote -> modeLabel(RecordingMode.SIMPLE_LISTENING)
        }
        Column(chipModifier, horizontalAlignment = if (horizontal) Alignment.Start else Alignment.CenterHorizontally) {
            RecordAsChip(label = label, onClick = onChange)
            if (language == RecordingLanguage.MALAYALAM && target != RecordTarget.Translate) {
                Spacer(Modifier.height(GrilleSpace.xs))
                Text(
                    text = stringResource(R.string.g_record_as_in_ml),
                    style = Grille.type.caption,
                    color = Grille.colors.ash,
                    textAlign = if (horizontal) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.padding(horizontal = GrilleSpace.m)
                )
            }
        }
    }
    if (horizontal) {
        Row(
            modifier = modifier.padding(horizontal = GrilleSpace.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GrilleSpace.m)
        ) {
            grille()
            chip(Modifier.weight(1f, fill = false))
        }
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            grille()
            Spacer(Modifier.height(12.dp))
            chip(Modifier)
        }
    }
}

/** Below this height (dp) the home screen is the cover screen's: the dock sits beside the list. */
private val COMPACT_HEIGHT = 560.dp

/** The grille of the dock in the cover's camera band, beside the cameras. */
private val BAND_GRILLE = 96.dp
