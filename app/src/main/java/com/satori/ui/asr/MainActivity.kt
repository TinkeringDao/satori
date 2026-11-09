package com.satori.ui

import android.app.KeyguardManager
import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.satori.ui.design.GrilleTheme
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunScreen
import com.satori.domain.model.DownloadState
import com.satori.domain.model.ThemeMode
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.category.EnsureStarterCategoriesUseCase
import com.satori.domain.usecase.finish.DeleteSilentRecordingsUseCase
import com.satori.domain.usecase.todo.MoveFoundIdeasUseCase
import com.satori.domain.usecase.todo.RecheckFoundTodosUseCase
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.quickkey.QuickRecordingViewModel
import com.satori.presentation.settings.SettingsViewModel
import com.satori.quickkey.QuickKeyRequest
import com.satori.service.RecordingService
import com.satori.ui.screens.*
import com.satori.ui.screens.mindmap.MindMapScreen
import com.satori.presentation.mindmap.MindMapViewModel
import com.satori.presentation.main.ReadingBackfill
import com.satori.presentation.mindmap.RecordingOpening
import com.satori.presentation.sessiondetails.DetailsTab
import com.satori.presentation.firstrun.FirstRunActions
import com.satori.presentation.firstrun.FirstRunUiState
import com.satori.presentation.firstrun.FirstRunViewModel
import com.satori.presentation.setup.AiModelDownloadViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.satori.ui.export.MarkdownExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.satori.ui.design.ClearOfCameraBand
import com.satori.ui.design.windowScrollLine
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var ensureStarterCategories: EnsureStarterCategoriesUseCase
    @Inject lateinit var recheckFoundTodos: RecheckFoundTodosUseCase
    @Inject lateinit var moveFoundIdeas: MoveFoundIdeasUseCase
    @Inject lateinit var deleteSilentRecordings: DeleteSilentRecordingsUseCase
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var finishingQueue: FinishingQueue
    @Inject lateinit var firstRunCoordinator: FirstRunCoordinator
    @Inject lateinit var readingBackfill: ReadingBackfill

    // Quick key presses, tapped reminders and tapped "Your notes are ready", in the order they came,
    // until SatoriApp handles them.
    private val launches = Channel<AppLaunch>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Markdown copies handed to the share sheet are private transcripts: drop old ones.
        lifecycleScope.launch { MarkdownExport.pruneShared(applicationContext) }
        lifecycleScope.launch { ensureStarterCategories.ensureIn(this@MainActivity) }
        moveFoundIdeasOnce(after = recheckFoundTodosOnce())
        deleteSilentRecordingsOnce()
        // Posts reminders that came due while the app was stopped and arms the next one.
        lifecycleScope.launch { reminderScheduler.reschedule() }
        // A recreated activity (a fold, a restored process) is handed its old intent again.
        if (savedInstanceState == null) receive(intent)
        followThemeInWindow()
        setContent {
            // Read theme mode from settings
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            val isDark = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Re-apply transparent nav bar whenever theme mode changes,
            // so the correct icon color (light/dark) is set.
            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            GrilleTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SatoriApp(launches)
                }
            }
        }
        logWindowScroll()
    }

    override fun onStart() {
        super.onStart()
        // Takes up the recordings a process that is gone left to be finished; once per process.
        finishingQueue.resume(mayStartForeground = true)
        // The first run follows the files on disk again and goes on with its downloads, with their
        // service back in front if Android took it away while the app was out of sight.
        firstRunCoordinator.start()
        // The Malayalam recordings whose lines were never read get them once the reader is on the
        // phone, through the finishing queue; once per process (design R9).
        readingBackfill.start()
    }

    /**
     * Shows Satori over the lock screen, or stops doing so. Only for a recorder that a quick key
     * press started while the phone was locked: it records at once, on the cover screen too, and
     * SatoriApp hides it again as soon as the recorder closes, so the recordings and everything
     * else still need an unlock.
     */
    fun showOverLockScreen(show: Boolean) {
        setShowWhenLocked(show)
        setTurnScreenOn(show)
    }

    /**
     * Once per install: the to-dos found before the to-do gate existed go through it (see
     * RecheckFoundTodosUseCase), which clears the fragments an earlier build turned into to-dos.
     * Off the main thread, as the gate reads the to-dos' sentences as the app starts.
     *
     * @return The recheck while it runs, or null when it ran at an earlier start
     */
    private fun recheckFoundTodosOnce(): Job? {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TODO_GATE_DONE, false)) return null
        return lifecycleScope.launch(Dispatchers.Default) {
            try {
                val changed = recheckFoundTodos()
                Log.i("MainActivity", "To-do recheck changed $changed to-dos")
                prefs.edit().putBoolean(KEY_TODO_GATE_DONE, true).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "To-do recheck failed; it runs again at the next start", e)
            }
        }
    }

    /**
     * Once per install: the to-dos an earlier build found that are ideas move to the ideas (see
     * MoveFoundIdeasUseCase), after the to-do recheck [after], which changes to-dos too. Home says
     * how many moved, once ([IdeasNotice]). Logs only how many. Off the main thread, as the move
     * reads the sentences around each to-do as the app starts.
     */
    private fun moveFoundIdeasOnce(after: Job?) {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IDEAS_DONE, false)) return
        lifecycleScope.launch(Dispatchers.Default) {
            after?.join()
            try {
                val moved = moveFoundIdeas()
                Log.i("MainActivity", "Moved $moved to-dos to the ideas")
                if (moved > 0) IdeasNotice.postMoved(this@MainActivity, moved)
                prefs.edit().putBoolean(KEY_IDEAS_DONE, true).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "Moving the ideas among the to-dos failed; it runs again at the next start", e)
            }
        }
    }

    /**
     * Once per install: the recordings in which nothing was said that an earlier version kept go,
     * as far as nothing is lost with them (see DeleteSilentRecordingsUseCase). Logs only how many.
     */
    private fun deleteSilentRecordingsOnce() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SILENT_RECORDINGS_DONE, false)) return
        lifecycleScope.launch {
            try {
                val deleted = deleteSilentRecordings()
                Log.i("MainActivity", "Deleted $deleted recordings in which nothing was said")
                prefs.edit().putBoolean(KEY_SILENT_RECORDINGS_DONE, true).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "Deleting recordings in which nothing was said failed; it runs again at the next start", e)
            }
        }
    }

    /** Whether the phone is locked, so a quick start must show over the lock screen. */
    fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Queues what [intent] asks for: a quick key press, a tapped reminder or a tapped "Your notes are ready". */
    private fun receive(intent: Intent?) {
        if (intent == null) return
        QuickKeyRequest.from(intent)?.let {
            launches.trySend(AppLaunch.QuickKey(it))
            return
        }
        // Recents replays the task's first intent, which can be an old reminder or notes tap.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        NotificationTaps.launchesOf { intent.getStringExtra(it) }.forEach { launches.trySend(it) }
    }

    /**
     * Logs it under SatoriCover when Android draws the window off its top ([windowScrollLine]), on
     * the frame it happens and once more when it is back at the top. Android scrolls a window to
     * show a focused control it thinks covered: on 0.6.0, with the cover upside down, the grille
     * in the camera band, which moved every screen 291 px down.
     */
    private fun logWindowScroll() {
        val content = findViewById<View>(android.R.id.content) ?: return
        val location = IntArray(2)
        var drawnAt = 0
        window.decorView.viewTreeObserver.addOnDrawListener {
            content.getLocationInWindow(location)
            if (location[1] != drawnAt) {
                drawnAt = location[1]
                Log.w(COVER_TAG, windowScrollLine(drawnAt))
            }
        }
    }

    /**
     * Hands the app's theme to Android, which keeps it for this app across starts. The window
     * behind Compose and the splash screen then match the theme (values-night/themes.xml) even
     * when it differs from the system's. Only stored values are applied, never the default
     * the settings screen starts with, so a start doesn't flip the mode back and forth.
     * MainActivity handles uiMode changes itself (manifest), so switching the theme doesn't
     * recreate it.
     */
    private fun followThemeInWindow() {
        val uiModeManager = getSystemService(UiModeManager::class.java) ?: return
        lifecycleScope.launch {
            settingsRepository.getSettings()
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect { mode ->
                    uiModeManager.setApplicationNightMode(
                        when (mode) {
                            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                        }
                    )
                }
        }
    }
}

/**
 * Main navigation composable for Satori.
 *
 * Manages navigation flow:
 * 1. The first run (a fresh install): Get ready, then Getting ready, which turns into Ready. Where
 *    the app opens and when it goes on to home follow the first run's stage
 *    ([FirstRunViewModel.screen]), stored before any screen shows it, so a restart opens where the
 *    first run stood. Ready's Record opens the recorder over home.
 * 2. Sessions screen (list of all transcription sessions)
 * 3. Recording screen (active transcription for a session)
 * 4. Session details screen (view completed session)
 * 5. Settings screen (configuration), with Advanced settings and the categories below it
 */
@Composable
fun SatoriApp(launches: ReceiveChannel<AppLaunch>) {
    val navController = rememberNavController()
    val firstRun: FirstRunViewModel = hiltViewModel()
    val aiModel: AiModelDownloadViewModel = hiltViewModel()
    val quickRecording: QuickRecordingViewModel = hiltViewModel()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()

    // Anything but the recorder hides Satori behind the lock screen again (see showOverLockScreen),
    // except while a quick-key start replaces one recorder with the next: the lock screen would
    // flash between the two.
    DisposableEffect(navController, activity, quickRecording) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (destination.route != RECORDING_ROUTE && !quickRecording.startInFlight.value) {
                activity?.showOverLockScreen(false)
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    val llmDownloadState by aiModel.llmDownloadState.collectAsStateWithLifecycle()

    // Where the app opens, read once: the stored stage is there synchronously.
    val startDestination = remember { if (firstRun.screen.value == FirstRunScreen.HOME) HOME_ROUTE else FIRST_RUN_ROUTE }

    // The first run's screen follows its stage: home once it is over or left with Look around
    // first, and Get ready again only when the speech files went, never from under a recorder. No
    // timer moves a screen on.
    LaunchedEffect(navController) {
        firstRun.screen.collect { screen ->
            val route = navController.currentDestination?.route
            when {
                screen == FirstRunScreen.HOME && route == FIRST_RUN_ROUTE ->
                    navController.navigate("sessions") { popUpTo(FIRST_RUN_ROUTE) { inclusive = true } }
                screen == FirstRunScreen.GET_READY && route == HOME_ROUTE ->
                    navController.navigate(FIRST_RUN_ROUTE) { popUpTo(HOME_ROUTE) { inclusive = true } }
            }
        }
    }

    // Ready's Record does what home's grille does: the recording, in what the one-tap Record
    // records as, and its recorder, which starts at once. A second tap meanwhile makes no second one.
    var recordStarting by remember { mutableStateOf(false) }
    val recordFirst: () -> Unit = {
        if (!recordStarting) {
            recordStarting = true
            scope.launch {
                try {
                    val settings = activity?.settingsRepository?.getSettings()?.first()
                    val sessionId = settings?.let { quickRecording.createRecording(it.homeCategoryId, it.homeLanguage) }
                    if (sessionId != null) navController.openRecorder(sessionId)
                } finally {
                    recordStarting = false
                }
            }
        }
    }

    // One at a time and in order, so each press sees what the one before it did.
    LaunchedEffect(launches) {
        for (launch in launches) {
            when (launch) {
                is AppLaunch.QuickKey -> handleQuickKey(
                    // The microphone is read as the key is pressed: it may have been taken back.
                    launch.request, firstRun.quickKeyReady(), navController, quickRecording,
                    // A start while locked shows the recorder over the lock screen, so it records now.
                    showOverLockScreen = { activity?.takeIf { it.isLocked() }?.showOverLockScreen(true) },
                    hideOverLockScreen = { activity?.showOverLockScreen(false) }
                ) {
                    context.startService(
                        Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_REQUEST_STOP)
                    )
                }
                is AppLaunch.OpenTodo -> if (firstRun.setUp()) openTodo(launch.todoId, navController, quickRecording)
                is AppLaunch.OpenRecording -> if (firstRun.setUp()) openNotesReady(launch.sessionId, navController, quickRecording)
            }
        }
    }

    // On the cover in Motorola's full-screen mode the window takes in the band with the cameras:
    // home and the recorder lay themselves out around it, every other screen keeps clear of it in
    // its own ClearOfCameraBand, so a screen going away while the next comes in stays in place.
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // The first run's screens: Get ready, Getting ready and Ready.
        composable(FIRST_RUN_ROUTE) {
            val state by firstRun.uiState.collectAsStateWithLifecycle()
            ClearOfCameraBand {
                FirstRunScreens(state = state, actions = firstRun, onRecord = recordFirst)
            }
        }

        // Home: to-dos, recordings and the grille. todoId opens that to-do's sheet once (a tapped
        // reminder); "sessions" alone matches this route too.
        composable(
            route = HOME_ROUTE,
            arguments = listOf(
                navArgument("todoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            SessionsScreen(
                openTodoId = entry.arguments?.getString("todoId"),
                takeNothingSaidNotice = {
                    NothingSaidNotice.takeOnceSeen(
                        entry.savedStateHandle,
                        nowMs = SystemClock::elapsedRealtime,
                        isLocked = { activity?.isLocked() == true }
                    )
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onNavigateToCategories = {
                    navController.navigate("settings/categories")
                },
                onNavigateToSpeechModels = {
                    navController.navigate("settings/advanced")
                },
                onNavigateToSessionDetails = { sessionId ->
                    navController.navigate("session_details/$sessionId")
                },
                onNavigateToRecording = { sessionId, autostart ->
                    navController.navigate("recording/$sessionId?autostart=$autostart")
                },
                onNavigateToSearch = {
                    navController.navigate("search")
                },
                onNavigateToMindMap = {
                    navController.navigate(MINDMAP_ROUTE)
                },
                onNavigateToIdeas = {
                    navController.navigate(IDEAS_ROUTE)
                }
            )
        }

        // Search screen
        composable("search") {
            ClearOfCameraBand {
                SearchScreen(
                    onNavigateBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                    onNavigateToSession = { sessionId, highlightId, initialTab ->
                        val route = buildString {
                            append("session_details/$sessionId?")
                            if (highlightId != null) append("highlightId=$highlightId&")
                            append("initialTab=$initialTab")
                        }
                        navController.navigate(route)
                    }
                )
            }
        }

        // Recording screen for a specific session. autostart=true (one tap on home) starts
        // recording as soon as the models are ready.
        composable(
            route = RECORDING_ROUTE,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("autostart") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            // A recorder leaves only while it is on top: a quick-key start may have replaced it
            // already, and its late navigation would then close the new recorder.
            val onTop = { navController.currentBackStackEntry === entry }
            MainScreen(
                onNavigateBack = {
                    if (onTop() && navController.previousBackStackEntry != null) navController.popBackStack()
                },
                // Home, which the recorder goes back to, says why the recording is not in its list.
                onNothingSaid = {
                    if (onTop()) {
                        navController.previousBackStackEntry?.savedStateHandle?.let { home ->
                            NothingSaidNotice.post(home, SystemClock.elapsedRealtime())
                        }
                    }
                },
                // A saved recording is finished in the background, and its row on home says how
                // that goes: the owner chose home over the recording's page.
                onFinished = {
                    if (onTop()) navController.popBackStack(HOME_ROUTE, inclusive = false)
                },
                onOpenSettings = { navController.navigate("settings/advanced") }
            )
        }

        // LLM download screen (can be accessed after onboarding if user skipped)
        composable("llm_download_standalone") {
            // Pop back only when a download started FROM this screen completes.
            // llmDownloadState may already be Completed (leftover from onboarding),
            // so we guard with a local flag set by onStartDownload / onRetry. Saveable, so a
            // fold or unfold during the download still returns to the recorder.
            var downloadStartedHere by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(llmDownloadState) {
                if (downloadStartedHere &&
                    llmDownloadState is DownloadState.Completed &&
                    navController.currentDestination?.route == "llm_download_standalone"
                ) {
                    navController.popBackStack()
                }
            }

            // A Completed left by an earlier download is not news to this screen.
            val shownState = if (downloadStartedHere || llmDownloadState !is DownloadState.Completed) {
                llmDownloadState
            } else {
                DownloadState.Idle
            }
            val leave: () -> Unit = {
                if (navController.previousBackStackEntry != null) navController.popBackStack()
            }

            ClearOfCameraBand {
                LlmDownloadScreen(
                    downloadState = shownState,
                    variant = aiModel.recommendedLlmVariant,
                    onStartDownload = {
                        downloadStartedHere = true
                        // The model that suits this phone; the others are chosen in Advanced settings.
                        aiModel.startLlmDownload()
                    },
                    onSkip = leave,
                    onRetry = {
                        downloadStartedHere = true
                        aiModel.retryLlmDownload()
                    },
                    onContinue = leave,
                    isStandalone = true
                )
            }
        }

        // Session details screen — optional query params for search deep-linking and initial tab.
        // Without an initial tab (-1) the page opens on the notes when the recording has some.
        composable(
            route = "session_details/{sessionId}?highlightId={highlightId}&initialTab={initialTab}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("highlightId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("initialTab") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) {
            ClearOfCameraBand {
                SessionDetailsScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    },
                    onNavigateToLlmDownload = {
                        navController.navigate("llm_download_standalone")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    },
                    onNavigateToSpeechModels = {
                        navController.navigate("settings/advanced")
                    },
                    onNavigateToMindMap = { sessionId ->
                        navController.navigate("mindmap/session/$sessionId")
                    },
                    onNavigateToIdeas = {
                        navController.navigate(IDEAS_ROUTE)
                    }
                )
            }
        }

        // The ideas found in recordings, from home's to-dos and from a recording's notes. An idea
        // opens its recording at the line it was said in.
        composable(IDEAS_ROUTE) {
            ClearOfCameraBand {
                IdeasScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    },
                    onOpenRecording = { sessionId, segmentId ->
                        navController.navigate(recordingRoute(sessionId, segmentId))
                    }
                )
            }
        }

        // The mind map: its top level, the view of a theme or topic, and a recording's own map.
        // Going into a node opens its view over the one before; between two of them there is no
        // cross-fade, as the drawing unfolds by itself.
        composable(
            route = MINDMAP_ROUTE,
            enterTransition = mindMapEnter,
            exitTransition = mindMapExit,
            popEnterTransition = mindMapEnter,
            popExitTransition = mindMapExit
        ) {
            MindMapDestination(navController)
        }

        composable(
            route = MINDMAP_NODE_ROUTE,
            arguments = listOf(
                navArgument(MindMapViewModel.ARG_NODE) { type = NavType.StringType },
                navArgument(MindMapViewModel.ARG_TRAIL) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
            enterTransition = mindMapEnter,
            exitTransition = mindMapExit,
            popEnterTransition = mindMapEnter,
            popExitTransition = mindMapExit
        ) {
            MindMapDestination(navController)
        }

        composable(
            route = MINDMAP_SESSION_ROUTE,
            arguments = listOf(
                navArgument(MindMapViewModel.ARG_SESSION) { type = NavType.StringType },
                navArgument(MindMapViewModel.ARG_TRAIL) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
            enterTransition = mindMapEnter,
            exitTransition = mindMapExit,
            popEnterTransition = mindMapEnter,
            popExitTransition = mindMapExit
        ) {
            MindMapDestination(navController)
        }

        composable("settings") {
            ClearOfCameraBand {
                SettingsScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    },
                    onNavigateToAdvanced = { navController.navigate("settings/advanced") },
                    onNavigateToCategories = { navController.navigate("settings/categories") },
                    onNavigateToLicenses = { navController.navigate("licenses") },
                    onNavigateToLlmDownload = { navController.navigate("llm_download_standalone") },
                    onNavigateToModels = { navController.navigate(MODELS_ROUTE) }
                )
            }
        }

        // The models on the phone and the cleanup; home's hint about unused models opens it too.
        composable(MODELS_ROUTE) {
            ClearOfCameraBand {
                ModelStorageScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    }
                )
            }
        }

        // Models, notes, translation, microphone and voice detection. Also where the recorder
        // sends the user when a model is missing or damaged.
        composable("settings/advanced") {
            ClearOfCameraBand {
                AdvancedSettingsScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    }
                )
            }
        }

        // Reachable from Settings and from the home screen's "Edit categories".
        composable("settings/categories") {
            ClearOfCameraBand {
                CategoriesScreen(
                    onNavigateBack = {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                    }
                )
            }
        }

        composable("licenses") {
            ClearOfCameraBand {
                LicensesScreen(
                    onNavigateBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * The home route. Navigating to "sessions" matches it, as todoId is optional; it carries the id of
 * a to-do whose sheet should open, for a tapped reminder.
 */
private const val HOME_ROUTE = "sessions?todoId={todoId}"

private const val RECORDING_ROUTE = "recording/{sessionId}?autostart={autostart}"

/** The first run's screens: Get ready, Getting ready and Ready. */
private const val FIRST_RUN_ROUTE = "firstrun"

/** The SharedPreferences of what runs once per install at start, and of IdeasNotice. */
internal const val STARTUP_PREFS = "startup_once"
private const val KEY_TODO_GATE_DONE = "todo_gate_v1"
private const val KEY_SILENT_RECORDINGS_DONE = "silent_recordings_v1"
private const val KEY_IDEAS_DONE = "ideas_v1"

private const val QUICK_KEY_TAG = "SatoriQuickKey"

/** The tag of the line that says what a tapped "Your notes are ready" opened, beside FirstRunCues' own. */
private const val NOTES_READY_TAG = "SatoriFirstRun"

/** The tag of the line that says Android drew the window off its top (logWindowScroll). */
private const val COVER_TAG = "SatoriCover"

/**
 * How long a quick-key start waits for the open recorder to save its recording: its drain guard
 * of 15 s, and a second for the hand-off.
 */
private const val SAVING_WAIT_MS = 16_000L

/** The mind map's top level. */
private const val MINDMAP_ROUTE = "mindmap"

/** A theme or a topic in the centre; trail is the way it was reached, for its breadcrumb. */
private const val MINDMAP_NODE_ROUTE = "mindmap/{nodeId}?trail={trail}"

/** A recording's own map, from a theme's or topic's view or from the recording's menu. */
private const val MINDMAP_SESSION_ROUTE = "mindmap/session/{sessionId}?trail={trail}"

/** The ideas found in recordings. */
private const val IDEAS_ROUTE = "ideas"

/** The page of the recording [sessionId]: on its transcript at the line [segmentId], or as it opens for null. */
private fun recordingRoute(sessionId: String, segmentId: String?): String =
    if (segmentId != null) {
        "session_details/$sessionId?highlightId=$segmentId&initialTab=${DetailsTab.TRANSCRIPT}"
    } else {
        "session_details/$sessionId"
    }

private fun NavBackStackEntry.isMindMap(): Boolean = destination.route?.startsWith(MINDMAP_ROUTE) == true

/** Into a view of the map from another one: no cross-fade. Null keeps the NavHost's own. */
private val mindMapEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition? = {
    if (initialState.isMindMap()) EnterTransition.None else null
}

private val mindMapExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition? = {
    if (targetState.isMindMap()) ExitTransition.None else null
}

/** One view of the mind map, with where its nodes, crumbs and recordings lead. */
@Composable
private fun MindMapDestination(navController: NavHostController) {
    ClearOfCameraBand {
        MindMapScreen(
            onNavigateBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
            onOpenNode = { nodeId, trail -> navController.navigate("mindmap/$nodeId?trail=$trail") },
            onOpenRecordingMap = { sessionId, trail -> navController.navigate("mindmap/session/$sessionId?trail=$trail") },
            onOpenTopLevel = { navController.navigate(MINDMAP_ROUTE) },
            onGoBack = { screens -> repeat(screens) { navController.popBackStack() } },
            onOpenRecording = { sessionId, segmentId ->
                val previous = navController.previousBackStackEntry
                val behind = previous?.arguments?.getString("sessionId")
                    ?.takeIf { previous.destination.route?.startsWith("session_details/") == true }
                when (val opening = RecordingOpening.of(behind, sessionId, segmentId)) {
                    RecordingOpening.Back -> navController.popBackStack()
                    is RecordingOpening.Open -> navController.navigate(recordingRoute(sessionId, opening.segmentId))
                }
            }
        )
    }
}

/** What an intent asked MainActivity to do, handled by [SatoriApp]. */
sealed interface AppLaunch {
    /** A quick key press (QuickKeyActivity). */
    data class QuickKey(val request: QuickKeyRequest) : AppLaunch

    /** A tapped reminder: open its to-do. */
    data class OpenTodo(val todoId: String) : AppLaunch

    /** A tapped "Your notes are ready": open the page of the recording whose notes they are. */
    data class OpenRecording(val sessionId: String) : AppLaunch
}

/**
 * Carries out a quick key press as QuickKeyRequest describes. The state is read and STOP or
 * CANCEL_START carried out without suspending in between, so a recorder can't start meanwhile.
 *
 * A START counts as under way from the press until it has navigated to its recorder
 * ([QuickRecordingViewModel.startInFlight]): the recorder it replaces doesn't go home meanwhile,
 * and Satori stays over the lock screen throughout. One that comes while the open recorder is
 * still saving its recording waits for that, at most [SAVING_WAIT_MS]: the hand-off takes well
 * under a second, at most the recorder's drain guard of 15 s.
 */
private suspend fun handleQuickKey(
    request: QuickKeyRequest,
    setUp: Boolean,
    navController: NavHostController,
    quickRecording: QuickRecordingViewModel,
    showOverLockScreen: () -> Unit,
    hideOverLockScreen: () -> Unit,
    requestStop: () -> Unit
) {
    val recorder = quickRecording.recorder.value
    val state = recorderStateOf(setUp, recorder)
    val action = request.actionFor(state)
    Log.i(QUICK_KEY_TAG, "Press while $state: $action")
    when (action) {
        QuickKeyRequest.Action.SHOW -> Unit
        QuickKeyRequest.Action.STOP -> requestStop()
        QuickKeyRequest.Action.CANCEL_START -> {
            navController.popBackStack(RECORDING_ROUTE, inclusive = true)
            // The closed recorder stops counting at once, though it only closes once the screen is
            // on. One that never opened (the phone stayed locked) can't delete its empty recording.
            recorder?.let { quickRecording.cancelStart(it.sessionId) }
        }
        QuickKeyRequest.Action.START -> {
            quickRecording.setStartInFlight(true)
            try {
                val replaced = if (state == QuickKeyRequest.RecorderState.SAVING) {
                    showOverLockScreen()
                    val waitStart = SystemClock.elapsedRealtime()
                    val after = quickRecording.awaitNotSaving(SAVING_WAIT_MS)
                    val waitedMs = SystemClock.elapsedRealtime() - waitStart
                    if (after != null && after.activity != ActiveRecorder.Activity.IDLE) {
                        Log.w(QUICK_KEY_TAG, "Recorder still ${after.activity} after $waitedMs ms: SHOW")
                        return
                    }
                    Log.i(QUICK_KEY_TAG, "Recording saved after $waitedMs ms: START")
                    after
                } else {
                    recorder
                }
                startRecording(request, replaced, navController, quickRecording, showOverLockScreen)
            } finally {
                quickRecording.setStartInFlight(false)
                // Only the recorder shows over the lock screen: a start that went nowhere hides it.
                if (navController.currentDestination?.route != RECORDING_ROUTE) hideOverLockScreen()
            }
        }
    }
}

/** What Satori is doing for a press: see QuickKeyRequest.RecorderState. */
private fun recorderStateOf(setUp: Boolean, recorder: ActiveRecorder.Snapshot?): QuickKeyRequest.RecorderState = when {
    !setUp -> QuickKeyRequest.RecorderState.NOT_SET_UP
    recorder == null -> QuickKeyRequest.RecorderState.IDLE
    else -> when (recorder.activity) {
        ActiveRecorder.Activity.IDLE -> QuickKeyRequest.RecorderState.IDLE
        ActiveRecorder.Activity.STARTING -> QuickKeyRequest.RecorderState.STARTING
        ActiveRecorder.Activity.RECORDING -> QuickKeyRequest.RecorderState.RECORDING
        ActiveRecorder.Activity.SAVING -> QuickKeyRequest.RecorderState.SAVING
    }
}

/**
 * Starts a quick recording (QuickKeyRequest.Action.START) in place of [replaced], the idle
 * recorder that is open, if any. That recorder stays on show while the recording is created, and
 * the navigation to the new one replaces it in one step: popped first, it would leave home on
 * show meanwhile, over the lock screen too. Satori is shown over the lock screen before the new
 * recorder comes, so the lock screen doesn't flash between the two.
 */
private suspend fun startRecording(
    request: QuickKeyRequest,
    replaced: ActiveRecorder.Snapshot?,
    navController: NavHostController,
    quickRecording: QuickRecordingViewModel,
    showOverLockScreen: () -> Unit
) {
    val sessionId = quickRecording.createRecording(request.categoryId, request.language) ?: return
    if (!ActiveRecorder.mayReplace(replaced, quickRecording.recorder.value)) {
        // Another recorder opened meanwhile (the record button), or the one to be replaced started
        // recording itself: leave it be.
        quickRecording.discard(sessionId)
        return
    }
    // Starting from now on, also while the phone is locked and the recorder can't open yet.
    quickRecording.expectRecorder(sessionId)
    showOverLockScreen()
    // Above home, in place of the recorder that is replaced. The new one doesn't wait for that one
    // to close, which waits while the screen is off: a closing recorder leaves the models to a
    // newer one (ActiveRecorder.mayRelease).
    navController.openRecorder(sessionId)
}

/**
 * Opens the recorder of [sessionId], which records at once, over home as the only recorder. From
 * the first run's screens home takes their place first, in the same step, so Stop and Back lead
 * home, where the recording is listed and where a recording with nothing said is explained.
 */
private fun NavHostController.openRecorder(sessionId: String) {
    homeInPlaceOfFirstRun()
    navigate("recording/$sessionId?autostart=true") { popUpTo(HOME_ROUTE) }
}

/** From the first run's screens, home takes their place, so what opens next has home under it. */
private fun NavHostController.homeInPlaceOfFirstRun() {
    val overFirstRun = runCatching { getBackStackEntry(FIRST_RUN_ROUTE) }.isSuccess
    if (overFirstRun) navigate("sessions") { popUpTo(FIRST_RUN_ROUTE) { inclusive = true } }
}

/** Opens a tapped reminder's to-do on the home screen, unless a recorder is busy ([NotificationTaps]). */
private fun openTodo(todoId: String, navController: NavHostController, quickRecording: QuickRecordingViewModel) {
    if (!NotificationTaps.mayOpen(quickRecording.recorder.value?.activity)) return
    navController.navigate("sessions?todoId=$todoId") {
        popUpTo(HOME_ROUTE) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Opens the page of the recording [sessionId] over home for a tapped "Your notes are ready", unless
 * a recorder is busy ([NotificationTaps]): the page opens as from the recording's row, so Back leads
 * home. Logs which, ids only.
 */
private fun openNotesReady(sessionId: String, navController: NavHostController, quickRecording: QuickRecordingViewModel) {
    if (!NotificationTaps.mayOpen(quickRecording.recorder.value?.activity)) {
        Log.i(NOTES_READY_TAG, "Notes ready tapped for $sessionId: a recorder is busy, nothing opens")
        return
    }
    Log.i(NOTES_READY_TAG, "Notes ready tapped for $sessionId: its page opens")
    navController.homeInPlaceOfFirstRun()
    navController.navigate(recordingRoute(sessionId, segmentId = null)) { popUpTo(HOME_ROUTE) }
}

/**
 * The first run's screens, Get ready, Getting ready and Ready, drawn from [state]; their controls
 * ask [actions], and Ready's Record opens the recorder ([onRecord]).
 */
@Composable
private fun FirstRunScreens(state: FirstRunUiState, actions: FirstRunActions, onRecord: () -> Unit) {
    OnboardingScreen(state = state, actions = actions, onRecord = onRecord)
}
