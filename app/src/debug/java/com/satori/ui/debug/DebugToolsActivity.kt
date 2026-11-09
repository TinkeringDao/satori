package com.satori.ui.debug

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.InferenceEngine
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.TodoDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.audio.ApplyAudioRetentionUseCase
import com.satori.domain.usecase.audio.AudioRetentionRules
import com.satori.domain.usecase.category.EnsureStarterCategoriesUseCase
import com.satori.presentation.main.NotesReadyCue
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.R
import com.satori.ui.design.GrilleTheme
import com.satori.ui.design.rememberNoticeState
import com.satori.ui.screens.RecorderContent
import com.satori.ui.screens.openingTab
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Debug builds only. Twelve jobs, each started over adb:
 *
 * - Draws the recorder in a given state with made-up content, so every state can be
 *   screenshotted without models or a microphone:
 *   `adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es screen recording_live [--ei tab 1]`
 *   `--es screen components` shows the shared Grille components instead ([ComponentGallery]).
 *   Without a known `screen` it lists the states.
 * - Marks the app as set up on an emulator (`... --es action setup`), and also fills it with demo
 *   recordings and their mind map (`... --es action seed`). Both refuse to run on a real phone,
 *   so real recordings are never mixed with demo ones.
 * - Transcribes a recording again with a given speech model, for comparing models on the phone
 *   (`... --es action transcribe --es session <id> --es model <model id>`).
 * - Measures what loading an AI model costs the phone's memory (`... --es action memtest --es model
 *   e2b|1b`, [DebugMemtest]): a load, one fixed synthetic prompt and an unload, under the rules of
 *   the transcribe job. `~/satori-audit/tools/memtest.sh` runs it with a variant's llama.cpp settings.
 * - Probes the pace of a load without loading (`... --es action prewarm --es model
 *   e2b|1b|parakeet|cohere|ml --es rate <MB/s>`, [DebugPrewarm]): the model's files lose their cached
 *   pages and are read in at that rate, under the same rules.
 * - Arms every AI model load, the app's own work too, to take its settings from the debug
 *   properties over its model's own for some minutes (`... --es action armloads --es minutes
 *   <1-60>`, 0 ends it), for a whole recording measured with a variant
 *   ([InferenceEngine.armDebugLoadSettings]).
 * - Plans the rules for older audio as if N days had passed, and logs the plan under
 *   SatoriRetention without changing anything (`... --es retention_days_ahead N`), so the rules can
 *   be checked on recordings that are new. The rules are the stored ones;
 *   `--es retention_compress_days D` and `--es retention_delete_days D` (0 for never) plan with
 *   others instead, without storing them.
 * - The first run's tests: `--es action firstrun-reset`, `firstrun-stage`, `firstrun-source`,
 *   `notes-ready` and `dump-settings` ([FirstRunDebug]), and its gallery states,
 *   `--es screen firstrun_<state>` ([FirstRunGallery]; the first recording's coach is a recorder
 *   state).
 * - Runs a finishing step of a stored recording again, N times, at the default sampling or another
 *   temperature, keeping the raw answers and storing nothing unless asked (`... --es action rerun
 *   --es session <id> --es step notes|name|todos|reading [--es times 1-10] [--es temp 0-2] [--es from
 *   notes|transcript] [--es store true] [--es reader on|off] [--es model 1b|e2b] [--es window 1|2]`,
 *   [DebugRerunViewModel]), under the rules of the transcribe job, on the finishing's model or the
 *   one asked for; a reading on the reader alone, its lines in an answer file (0.8.0).
 * - The reader's tools (0.8.0, [DebugReaderViewModel]): its download on the phone (`... --es action
 *   reader-download [--es variant int8|fp16] [--es mobile true]`), the tokenizer selftest (V0b,
 *   `--es action tokenizer-selftest`) and the reader selftest (V1 and V2, `--es action
 *   reader-selftest [--es arm ...] [--ei beam 1-8] [--es worstcase true] [--es cold true]
 *   [--ei stopafter <ms> | --ei stopload <ms>]`), over pushed public text in `files/debug/reader`,
 *   under the memtest's rules.
 * - Seeds synthetic Malayalam recordings for the reader's checks, on an emulator only (`... --es
 *   action seed-malayalam [--ei lines <n>] [--es kind backfill|new|both]`, [MalayalamSeed]): two as
 *   an older version finished them, one with the owner's edits, for the backfill of readings, or one
 *   just stopped, handed off to the finishing queue.
 * - The backfill's safety for the owner's edits of before version 13, on the phone too: marks a
 *   recording's name and notes as his (`... --es action mark-owner-edits --es session <id>`), so a
 *   finishing made again leaves them, or puts back what one replaced (`... --es action
 *   restore-before-redo --es session <id>`).
 *
 * The screen has a task of its own, kept out of Recents (the debug manifest), so Satori from the
 * launcher or the quick key never shows it. The long tools (memtest, prewarm, rerun and the
 * reader's three) run in the process ([DebugRuns]), held by their view models while their screen
 * is in front ([DebugRunViewModel]), and their screen goes to the back as soon as the run has
 * started and the AI's service it holds is in the foreground ([followThenEnd]): on a locked phone
 * the owner never finds one in front of him when he unlocks it. `--es front true` keeps it in
 * front, for a run on an emulator or one watched; Back there cancels the run, as before. Every tool
 * ends its screen and the task with it ([end]), so the next tool's adb start makes a new one.
 */
@AndroidEntryPoint
class DebugToolsActivity : ComponentActivity() {

    @Inject lateinit var sessionDao: TranscriptionSessionDao
    @Inject lateinit var segmentDao: TranscriptionSegmentDao
    @Inject lateinit var insightDao: LlmInsightDao
    @Inject lateinit var todoDao: TodoDao
    @Inject lateinit var mindMap: MindMapRepository
    @Inject lateinit var ensureStarterCategories: EnsureStarterCategoriesUseCase
    @Inject lateinit var applyAudioRetention: ApplyAudioRetentionUseCase
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var inferenceEngine: InferenceEngine
    @Inject lateinit var modelDownloadManager: ModelDownloadManager
    @Inject lateinit var notesReadyCue: NotesReadyCue
    @Inject lateinit var processingService: LlmProcessingServiceController
    @Inject lateinit var finishingQueue: FinishingQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra(RETENTION_DAYS_AHEAD)) {
            planRetentionThenFinish()
            return
        }
        when (intent.getStringExtra("action")) {
            "setup" -> { setUpThenFinish(withDemoData = false); return }
            "seed" -> { setUpThenFinish(withDemoData = true); return }
            "transcribe" -> { transcribeThenFinish(); return }
            "memtest" -> { memtestThenFinish(firstLaunch = savedInstanceState == null); return }
            "prewarm" -> { prewarmThenFinish(firstLaunch = savedInstanceState == null); return }
            "armloads" -> { armLoadsThenFinish(); return }
            "rerun" -> { rerunThenFinish(firstLaunch = savedInstanceState == null); return }
            DebugReaderViewModel.DOWNLOAD, DebugReaderViewModel.TOKENIZER, DebugReaderViewModel.READER -> {
                readerThenFinish(intent.getStringExtra("action").orEmpty(), firstLaunch = savedInstanceState == null)
                return
            }
            "seed-malayalam" -> { seedMalayalamThenFinish(); return }
            "mark-owner-edits" -> { markOwnerEditsThenFinish(); return }
            "restore-before-redo" -> { restoreBeforeRedoThenFinish(); return }
            "firstrun-reset" -> { firstRunResetThenEnd(); return }
            "firstrun-stage" -> { firstRunStageThenEnd(); return }
            "firstrun-source" -> { firstRunSourceThenFinish(); return }
            "notes-ready" -> { notesReadyThenFinish(); return }
            "dump-settings" -> { dumpSettingsThenFinish(); return }
        }
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen").orEmpty()
        // Without a tab given, the one the recorder opens on (a Translate recording's translation).
        val tab = intent.getIntExtra("tab", -1)
        val level = MutableStateFlow(intent.getFloatExtra("level", 0.55f))
        setContent {
            GrilleTheme {
                val state = GalleryStates.find(screen)
                if (screen == COMPONENTS) {
                    ComponentGallery()
                } else if (screen in FirstRunGallery.names) {
                    FirstRunGalleryScreen(screen, ::starterName)
                } else if (state == null) {
                    GalleryIndex()
                } else {
                    // The starter categories in the app's language, so the chip and "Record as"
                    // read as on the phone; a pick switches the drawn recording, with its notice.
                    var shown by remember { mutableStateOf(state.copy(categories = GalleryStates.starters(::starterName))) }
                    var choosingType by remember { mutableStateOf(GalleryStates.opensTypeSheet(screen)) }
                    val notices = rememberNoticeState()
                    val scope = rememberCoroutineScope()
                    RecorderContent(
                        uiState = shown,
                        levelFlow = level,
                        selectedTab = if (tab >= 0) tab else openingTab(shown),
                        onSelectTab = {},
                        onGrilleClick = {},
                        onBack = {},
                        notices = notices,
                        holdToStop = GalleryStates.locked(screen),
                        onHoldHint = { scope.launch { notices.show(getString(R.string.g_rec_hold_to_stop)) } },
                        onChangeTypeHint = { scope.launch { notices.show(getString(R.string.g_rec_hold_to_change)) } },
                        choosingType = choosingType,
                        onChooseType = { choosingType = it },
                        onRecordAs = { category ->
                            choosingType = false
                            shown = shown.copy(sessionCategoryId = category.id, sessionMode = category.mode)
                            scope.launch { notices.show(getString(R.string.g_rec_recording_as, category.name)) }
                        },
                        onRecordIn = { language ->
                            choosingType = false
                            shown = shown.copy(language = language)
                            val notice = if (language == RecordingLanguage.MALAYALAM) R.string.g_rec_recording_in_ml else R.string.g_rec_recording_in_en_de
                            scope.launch { notices.show(getString(notice)) }
                        }
                    )
                }
            }
        }
    }

    private fun setUpThenFinish(withDemoData: Boolean) {
        if (!isEmulator()) {
            Log.w(TAG, "Refusing to set up demo state: this is not an emulator")
            end()
            return
        }
        lifecycleScope.launch {
            if (withDemoData) {
                DemoData.seed(
                    this@DebugToolsActivity, sessionDao, segmentDao, insightDao, todoDao, mindMap,
                    ensureStarterCategories
                )
            }
            // Placeholder files so the app counts as set up. They are not models: recording
            // in the real app will fail, so record only through the gallery states.
            val sttDir = File(getExternalFilesDir(null), "models/stt").apply { mkdirs() }
            SpeechModels.PARAKEET_V3.specs.forEach { spec ->
                File(sttDir, spec.name).writeText("placeholder")
            }
            getSharedPreferences("onboarding_prefs", MODE_PRIVATE).edit()
                .putBoolean("onboarding_complete", true)
                .apply()
            Log.i(TAG, if (withDemoData) "Set up with demo recordings" else "Set up without recordings")
            end()
        }
    }

    /** Synthetic Malayalam recordings, on an emulator only ([MalayalamSeed]). */
    private fun seedMalayalamThenFinish() {
        if (!isEmulator()) {
            Log.w(TAG, "Refusing to seed Malayalam recordings: this is not an emulator")
            end()
            return
        }
        val kind = intent.getStringExtra("kind")?.trim()?.lowercase() ?: "backfill"
        if (kind !in listOf("backfill", "new", "both")) {
            Log.w(TAG, "seed-malayalam: --es kind is backfill, new or both, not $kind")
            end()
            return
        }
        val lines = intent.getIntExtra("lines", MalayalamSeed.DEFAULT_LINES)
        lifecycleScope.launch {
            val seeded = withContext(Dispatchers.IO) {
                MalayalamSeed.seed(kind, lines, sessionDao, segmentDao, insightDao, todoDao, finishingQueue)
            }
            Log.i(TAG, "Seeded ${seeded.sessionIds.size} Malayalam recordings of ${seeded.lines} lines: ${seeded.sessionIds.joinToString()}")
            end()
        }
    }

    /** Transcribe again of one recording with one speech model ([DebugTranscribeViewModel]). */
    private fun transcribeThenFinish() {
        val session = intent.getStringExtra("session").orEmpty()
        val model = intent.getStringExtra("model").orEmpty()
        val transcriber: DebugTranscribeViewModel by viewModels()
        lifecycleScope.launch {
            transcriber.run(session, model)
            end()
        }
    }

    /**
     * One memtest of the model given, `e2b` or `1b` ([DebugMemtestViewModel]). The run is held by
     * the view model, so a fold or a turn, which makes this activity again (it declares no
     * configChanges), neither cuts it short nor starts a second load from the same intent: the new
     * activity waits for the same run. An activity restored after its process ended
     * ([firstLaunch] false) starts none.
     */
    private fun memtestThenFinish(firstLaunch: Boolean) {
        val model = intent.getStringExtra("model").orEmpty()
        val memtest: DebugMemtestViewModel by viewModels()
        val run = memtest.start(model, firstLaunch)
        if (run == null) {
            Log.w(TAG, "Memtest $model not started again: its screen came back after the app's process ended")
            end()
            return
        }
        followThenEnd("memtest", run)
    }

    /** The pace probe of the model given at the rate given ([DebugPrewarmViewModel]), run as the memtest is. */
    private fun prewarmThenFinish(firstLaunch: Boolean) {
        val model = intent.getStringExtra("model").orEmpty()
        val rate = intent.getStringExtra("rate").orEmpty()
        val prewarm: DebugPrewarmViewModel by viewModels()
        val run = prewarm.start(model, rate, firstLaunch)
        if (run == null) {
            Log.w(TAG, "Prewarm $model not started again: its screen came back after the app's process ended")
            end()
            return
        }
        followThenEnd("prewarm", run)
    }

    /**
     * The rerun of a finishing step ([DebugRerunViewModel]), run as the memtest is: in the view
     * model's scope, so a fold or a turn neither cuts it short nor starts it twice, and not again
     * for an activity restored after its process ended. The temperature may come as a string or a
     * float extra.
     */
    private fun rerunThenFinish(firstLaunch: Boolean) {
        val extras = listOf("session", "step", "times", "from", "store", "reader", "model", "window").associateWith { intent.getStringExtra(it) } +
            ("temp" to temperatureExtra(intent.getStringExtra("temp"), intent.hasExtra("temp")) { intent.getFloatExtra("temp", Float.NaN) })
        val rerun: DebugRerunViewModel by viewModels()
        val run = rerun.start(extras, firstLaunch)
        if (run == null) {
            Log.w(TAG, "Debug rerun not started again: its screen came back after the app's process ended")
            end()
            return
        }
        followThenEnd("rerun", run)
    }

    /**
     * One of the reader's tools ([DebugReaderViewModel]), run as the memtest is. Its counts may come
     * as string or int extras (`--es beam 4` or `--ei beam 4`).
     */
    private fun readerThenFinish(action: String, firstLaunch: Boolean) {
        // Read by type, so a count given as --ei is no warning of a wrong type in the log.
        @Suppress("DEPRECATION")
        val extras = listOf("variant", "mobile", "input", "out", "arm", "worstcase", "cold", "firststep", "beam", "lines", "dir", "stopafter", "stopload")
            .associateWith { name -> intent.extras?.get(name)?.toString() }
        val tool: DebugReaderViewModel by viewModels()
        val run = tool.start(action, extras, firstLaunch)
        if (run == null) {
            Log.w(TAG, "$action not started again: its screen came back after the app's process ended")
            end()
            return
        }
        followThenEnd(action, run)
    }

    /**
     * Waits for [run], the long tool [action]'s run, then ends this screen and its task. Unless the
     * intent says `--es front true`, the screen goes to the back (`moveTaskToBack`) as soon as the
     * run has started and the AI's service it holds is in the foreground, waited for at most
     * [DebugMemtest.SERVICE_WAIT_MS] after the start: Android starts a foreground service only while
     * the app is in front, and the service keeps the process in the foreground group once the screen
     * has gone. Logged once a run: `<action> runs with its screen in the back`. Without the service
     * in the foreground, or when Android doesn't move the task, the screen stays in front, as before
     * 0.8.0-dev2, with `W <action> keeps its screen in front: <why>` ([DebugRun.toTheBackOnceStarted]).
     * Android ends a screen sent to the back itself (seen on the emulator's Android 15), its task
     * being out of Recents (trimmed, as the events log says); the run goes on in the process
     * ([DebugRuns]).
     *
     * Nothing on the run's path waits on this screen: the run is the process's, held by the view
     * model while the screen is in front, so it goes on while the activity is stopped, unfocused or
     * behind the keyguard; only Back while in front cancels it.
     */
    private fun followThenEnd(action: String, run: DebugRun) {
        lifecycleScope.launch {
            val back = if (front()) {
                null
            } else {
                launch {
                    run.toTheBackOnceStarted(
                        action,
                        processingService.protected,
                        DebugMemtest.SERVICE_WAIT_MS,
                        log = { Log.i(TAG, it) },
                        warn = { Log.w(TAG, it) }
                    ) { moveTaskToBack(true) }
                }
            }
            run.job.join()
            back?.cancel()
            end()
        }
    }

    // `--es front true` (or `--ez`): the screen stays in front while its tool runs.
    @Suppress("DEPRECATION")
    private fun front(): Boolean = keepsScreenInFront(intent.extras?.get(FRONT))

    /**
     * Ends this screen, and its task with it when the screen is the task's root, as an adb start
     * makes it (its own task affinity): no empty task stays behind, and the next tool's adb start
     * makes a new one rather than bringing this one back.
     */
    private fun end() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    /**
     * Arms every AI model load for the minutes given to take its settings from the debug
     * properties over its model's own ([InferenceEngine.armDebugLoadSettings]), 0 ends it: the
     * memory session's run of a whole recording with a variant, whose finishing loads Gemma 4 E2B as
     * the app's own work. The window is held in memory, so it ends by itself or with the process.
     */
    private fun armLoadsThenFinish() {
        val given = intent.getStringExtra("minutes")
        val minutes = given?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_ARM_MINUTES }
        if (minutes == null) {
            Log.w(TAG, "Arm loads: minutes must be 0-$MAX_ARM_MINUTES, not $given")
        } else {
            inferenceEngine.armDebugLoadSettings(minutes * 60_000L)
            Log.i(TAG, if (minutes == 0) "Debug load settings no longer armed" else "Debug load settings armed for every AI model load for $minutes min")
        }
        end()
    }

    /** firstrun-reset ([FirstRunDebug.reset]), then the process ends: the next start is a first run. */
    private fun firstRunResetThenEnd() {
        if (!isEmulator()) {
            Log.w(FirstRunDebug.TAG, "Refusing firstrun-reset: this is not an emulator")
            end()
            return
        }
        Log.i(FirstRunDebug.TAG, FirstRunDebug.reset(this))
        endProcess()
    }

    /** firstrun-stage --es stage <STAGE> ([FirstRunDebug.setStage]), then the process ends, so the next start opens there. */
    private fun firstRunStageThenEnd() {
        if (!isEmulator()) {
            Log.w(FirstRunDebug.TAG, "Refusing firstrun-stage: this is not an emulator")
            end()
            return
        }
        val stage = FirstRunDebug.setStage(this, intent.getStringExtra("stage"))
        if (stage == null) {
            Log.w(FirstRunDebug.TAG, "firstrun-stage needs --es stage GET_READY, DOWNLOADING, READY or DONE")
            end()
            return
        }
        Log.i(FirstRunDebug.TAG, "First run stage stored: $stage")
        endProcess()
    }

    /** firstrun-source --es url <test server>|off ([FirstRunDebug.setSource]). */
    private fun firstRunSourceThenFinish() {
        if (!isEmulator()) {
            Log.w(FirstRunDebug.TAG, "Refusing firstrun-source: this is not an emulator")
            end()
            return
        }
        val url = intent.getStringExtra("url")
        lifecycleScope.launch {
            val line = withContext(Dispatchers.IO) { FirstRunDebug.setSource(this@DebugToolsActivity, url) }
            Log.i(FirstRunDebug.TAG, line)
            end()
        }
    }

    /**
     * notes-ready ([FirstRunDebug.notesReadyAfterSeconds]): after the delay given, the notes of the
     * recording given are ready, as the finisher says once a recording's notes are stored. The
     * delay runs in the process on the main thread, not in this activity, which ends at once, so
     * the tester can leave Satori first.
     */
    private fun notesReadyThenFinish() {
        if (!isEmulator()) {
            Log.w(FirstRunDebug.TAG, "Refusing notes-ready: this is not an emulator")
            end()
            return
        }
        val session = intent.getStringExtra("session")?.trim()?.takeIf { it.isNotEmpty() } ?: FirstRunDebug.NOTES_READY_SESSION
        val afterS = FirstRunDebug.notesReadyAfterSeconds(intent.getIntExtra("after_s", 0))
        if (afterS == null) {
            Log.w(FirstRunDebug.TAG, "notes-ready: after_s must be 0-${FirstRunDebug.NOTES_READY_MAX_S}")
            end()
            return
        }
        Log.i(FirstRunDebug.TAG, "notes-ready for $session in $afterS s")
        val cue = notesReadyCue
        Handler(Looper.getMainLooper()).postDelayed({ cue.notesReady(session) }, afterS * 1_000L)
        end()
    }

    /**
     * mark-owner-edits (0.8.0, design R9): the name and notes of the recording given are the
     * owner's, as he says, though version 12 recorded no rename and no note edit: name_by_owner and
     * edited_at are set, so the backfill's finishing made again over its readings leaves them as they
     * are. For the phone too, before Satori's main screen first starts on the version that backfills.
     * Ids and counts only in the log.
     */
    private fun markOwnerEditsThenFinish() {
        val session = intent.getStringExtra("session")?.trim().orEmpty()
        lifecycleScope.launch {
            val marked = withContext(Dispatchers.IO) {
                if (sessionDao.getSession(session).first() == null) null else insightDao.markOwnersEdits(session, System.currentTimeMillis())
            }
            if (marked == null) {
                Log.w(TAG, "mark-owner-edits: no recording $session")
            } else {
                Log.i(TAG, "mark-owner-edits: $marked of the name and notes of $session marked as the owner's")
            }
            end()
        }
    }

    /**
     * restore-before-redo (0.8.0, design R9): puts back the name and notes the recording given had
     * before the backfill's finishing made it again over its readings, as they were kept then
     * (LlmInsightDao.restoreBeforeRedo), marked the owner's, for one he says had a name or notes of
     * his. Not while the finishing queue works, which could be making that very recording again.
     * For the phone too. Ids and counts only in the log.
     */
    private fun restoreBeforeRedoThenFinish() {
        val session = intent.getStringExtra("session")?.trim().orEmpty()
        if (finishingQueue.busy.value) {
            Log.w(TAG, "restore-before-redo refused: the finishing queue is at work")
            end()
            return
        }
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { insightDao.restoreBeforeRedo(session, System.currentTimeMillis()) }
            if (restored == null) {
                Log.w(TAG, "restore-before-redo: nothing was kept of $session")
            } else {
                Log.i(TAG, "restore-before-redo: $session has the name and the $restored notes it had before it was made again")
            }
            end()
        }
    }

    /** dump-settings ([FirstRunDebug.dump]): read only, so on a phone too. */
    private fun dumpSettingsThenFinish() {
        lifecycleScope.launch {
            val settings = settingsRepository.getSettings().first()
            val lines = withContext(Dispatchers.IO) { FirstRunDebug.dump(this@DebugToolsActivity, settings, modelDownloadManager) }
            lines.forEach { Log.i(FirstRunDebug.TAG, "dump-settings $it") }
            val file = withContext(Dispatchers.IO) { FirstRunDebug.write(this@DebugToolsActivity, lines) }
            Log.i(FirstRunDebug.TAG, "dump-settings: ${lines.size} lines, also in ${file.name} under the app's files/debug")
            end()
        }
    }

    /** Ends the app's process, so its next start reads what was just stored. */
    private fun endProcess() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.startsWith("sdk") || Build.MODEL.startsWith("sdk_gphone")

    /**
     * The dry run of the rules for older audio ([ApplyAudioRetentionUseCase.plan]): which audio a
     * run would compress or delete, and which recordings it would delete whole, N days from now.
     * It changes no audio, recording or setting; only what a compression cut short left behind
     * goes, as with every inventory of the audio. Logs ids, counts and MB.
     */
    private fun planRetentionThenFinish() {
        val daysAhead = intent.getStringExtra(RETENTION_DAYS_AHEAD)?.toIntOrNull()
        if (daysAhead == null || daysAhead < 0) {
            Log.w(RETENTION_TAG, "Retention dry run: $RETENTION_DAYS_AHEAD needs a number of days, 0 or more")
            end()
            return
        }
        lifecycleScope.launch {
            val stored = settingsRepository.getSettings().first()
            val compressDays = daysExtra(RETENTION_COMPRESS_DAYS, stored.audioCompressAfterDays)
            val deleteDays = daysExtra(RETENTION_DELETE_DAYS, stored.audioDeleteAfterDays)
            val atMs = System.currentTimeMillis() + daysAhead * AudioRetentionRules.DAY_MS
            val plan = applyAudioRetention.plan(compressDays, deleteDays, atMs)
            val steps = listOf(
                "compress" to plan.compress,
                "delete audio" to plan.deleteAudio,
                "delete recording" to plan.deleteRecording
            )
            Log.i(
                RETENTION_TAG,
                "Retention dry run, $daysAhead days ahead, compress after ${compressDays ?: "never"}, " +
                    "delete after ${deleteDays ?: "never"}: " +
                    steps.joinToString(", ") { (what, files) -> "$what ${files.size} (${mb(files.sumOf { it.bytes })} MB)" }
            )
            // A line each, as a line of logcat holds a few kilobytes.
            for ((what, files) in steps) {
                files.forEach { Log.i(RETENTION_TAG, "Retention dry run: $what ${it.sessionId} (${mb(it.bytes)} MB)") }
            }
            end()
        }
    }

    private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)

    /** The name a fresh install gives the starter category [id], in the app's language. */
    private fun starterName(id: String): String = getString(
        when (id) {
            "meeting" -> R.string.g_category_meeting
            "idea" -> R.string.g_category_idea
            "todo" -> R.string.g_category_todo
            else -> R.string.g_category_voice_note
        }
    )

    /** The age given as [name] (0 for never), or [stored] without it. */
    private fun daysExtra(name: String, stored: Int?): Int? {
        val given = intent.getStringExtra(name)?.toIntOrNull() ?: return stored
        return given.takeIf { it > 0 }
    }

    companion object {
        private const val TAG = "DebugTools"
        private const val RETENTION_TAG = "SatoriRetention"
        private const val RETENTION_DAYS_AHEAD = "retention_days_ahead"
        private const val RETENTION_COMPRESS_DAYS = "retention_compress_days"
        private const val RETENTION_DELETE_DAYS = "retention_delete_days"
        private const val MAX_ARM_MINUTES = 60

        /** The extra that keeps a long tool's screen in front while it runs: `--es front true`. */
        private const val FRONT = "front"
    }
}

/** The `screen` that shows [ComponentGallery]. */
private const val COMPONENTS = "components"

@androidx.compose.runtime.Composable
private fun GalleryIndex() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(GrilleSpace.margin)
    ) {
        Text("Gallery states", style = Grille.type.title, color = Grille.colors.ink)
        (GalleryStates.byName.keys + FirstRunGallery.names + COMPONENTS).forEach { name ->
            Text(name, style = Grille.type.body, color = Grille.colors.ash)
        }
    }
}
