package com.satori.domain.usecase.sync

import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.usecase.llm.AnswerChecks
import com.satori.domain.usecase.llm.AnswerDrop
import com.satori.domain.usecase.llm.EarlierNotes
import com.satori.domain.usecase.llm.InsightFormat
import com.satori.domain.usecase.llm.InsightOutputParser
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.NotesTopic
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.llm.PromptRules
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates periodic LLM inference based on accumulated transcription.
 * This is the CORE use case connecting STT streaming with LLM inference.
 *
 * Non-blocking strategy:
 * - STT flow uses unlimited buffer to never block audio processing
 * - LLM inference runs in separate coroutine launched per interval
 * - Errors in LLM don't crash STT stream
 *
 * De-duplication strategy:
 * - Maintains list of completed segments (isComplete=true) - finalized phrases
 * - Maintains current partial segment (isComplete=false) - replaces previous partial
 * - Sends to LLM: "completed1 completed2 ... currentPartial" without repetitions
 * - Example: Instead of "A  A B  A B C", LLM receives "A B C"
 *
 * REFACTORED: No longer calls startStreaming() internally.
 * Instead accepts a Flow<TranscriptionSegment> to avoid duplicate streams.
 *
 * The engine is shared ([LiveNoteTurn]): a note is written only when its turn is free at once.
 * Otherwise the interval is skipped and what was said in it is kept, so the first note after the
 * engine is free again reads all of it, as far as it fits into one prompt. A note cites only the
 * complete segments its prompt holds whole: one cut off at the front was read in part, and left
 * uncited it stays in the tail that the Notes step reads after Stop. A turn can be refused for a
 * whole recording (AI notes off, no model), so a refused interval costs no pass over what was
 * said, and only what a note can still read is kept ([HeardSinceNote]).
 *
 * The recording can be switched to another kind while it is recorded ("Record as", a meeting
 * where it began as a voice note): from the next interval on the notes are of the new mode, with
 * its interval, its length of answer and its prompt in the notes' language. Nothing else changes:
 * the transcript is read on as it comes, and what was said since the last note stays for the
 * next one. A note being written as the switch comes is written to its end in the mode it began
 * in. A translation is never switched, nor switched into: its notes are of another kind.
 *
 * A translation reads finished lines only, each once: the line the recognizer is on is left for
 * the interval after it ends. On 2026-10-14 a German meeting's three live translations each read
 * the line being said as well, which the recognizer then gave out finished in other words, so it
 * was read again whole: the second translated the first one's sentences again, the third repeated
 * the second's end. A translation so cites every line it reads, and the final one after Stop reads
 * the lines none cites.
 *
 * No live note is written while the recording is in a language the small live model reads poorly,
 * Malayalam ([RecordingLanguage.writesLiveNotes]): its lines are kept for none, and the Notes step
 * after Stop writes the notes of all of them, from the lines no note cites. Switched to Malayalam
 * while it records, the notes stop from their next interval, the one being written finishing as it
 * began; switched back, they go on with what is said from then on, and with what was said in English
 * or German before the switch. A line in Malayalam script that the Malayalam model gives out just
 * after a switch back, as it finishes the utterance it was on, is no note's either.
 *
 * What of a note's answer repeats its call's instructions (a topic's sentence, the order to read
 * a text in another language) is left out, as after Stop ([PromptEcho]), and since 0.7.3 what
 * gives back the rules of its system prompt ([PromptRules]) or is in another script than the
 * Latin one ([WrongScript]), and a point that says what an earlier point of the note says. A note
 * that mostly repeats an earlier live note of the recording loses what that note says and keeps
 * what is new ([EarlierNotes]); with nothing new left it is none, and what it read stays for the
 * last note after Stop, as no note cites it.
 *
 * @property llmRepository Repository for LLM operations
 * @property settingsRepository Repository for app settings
 * @property echoLog Where the count of a note's lines left out as echoes goes
 * @property notesLog Where the counts of its lines left out for the other reasons go, as after Stop
 */
class SyncSttLlmUseCase(
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val resourceProvider: ResourceProvider,
    private val echoLog: PromptEchoLog = PromptEchoLog.NONE,
    private val notesLog: NotesLog = NotesLog.NONE
) {
    companion object {
        // Max tokens the LLM is allowed to generate per inference call, by mode.
        // Translation needs fewer tokens (just the translated text).
        // Analysis modes need more tokens for a thorough, proportional response.
        private const val MAX_TOKENS_SIMPLE_LISTENING = 512
        private const val MAX_TOKENS_SHORT_MEETING    = 600
        private const val MAX_TOKENS_LONG_MEETING     = 768
        private const val MAX_TOKENS_TRANSLATION      = 256

        // Minimum new word count required before triggering LLM inference.
        // Prevents wasting inference calls on near-empty content (e.g. a single word
        // captured between intervals). Not applied to REAL_TIME_TRANSLATION because
        // even short phrases benefit from immediate translation.
        private const val MIN_NEW_WORDS_FOR_INFERENCE = 5

        // For intervals >= this threshold (10 min), trigger LLM 30 s early so the
        // insight is ready by the time the declared interval elapses. Thermal throttling
        // is also disabled for these modes because users expect insights on schedule.
        private const val LONG_INTERVAL_THRESHOLD_MS = 10 * 60 * 1000L
        private const val LONG_INTERVAL_EARLY_TRIGGER_MS = 30_000L

        // Maximum characters of what was said sent in one user prompt.
        // Gemma 3 1B has a 4096-token context window. Budget breakdown:
        //   system prompt             : ~300 tokens (a meeting's notes prompt)
        //   what was said             : ~2 900 tokens  (12 000 chars ÷ 4.14*)
        //   output budget (LONG_MTG)  :   768 tokens
        //   total                     : ~3 968 / 4 096 — ~128-token safety margin
        // * 4.14 chars/token measured on real Italian session logs.
        // Previous value was 6 000 (~1 450 tokens) which truncated the first
        // ~15-20 segments of a dense 20-min session.
        private const val MAX_USER_PROMPT_CHARS = 12_000
    }

    /**
     * Invoke the use case to start synchronized STT-LLM processing.
     *
     * @param transcriptionFlow Shared flow of transcription segments from STT
     * @param sessionId The session ID for this recording
     * @param mode The recording mode (Simple, Short, Long, Translation) it starts in
     * @param outputLanguage Translation mode: the target language BCP-47 code (null for the
     *   Settings default). Other modes: the language to start with, null for the phone's;
     *   [notesLanguage] replaces it from the first interval on.
     * @param topic Optional session topic, put above what was said in each note's user message
     *   ([NotesTopic]), so the system prompt is the same for every recording. A translation gets
     *   none.
     * @param notesLanguage Names the language ("en" or "de") of the notes for the text one
     *   interval reads, so each inference gets the prompt, the JSON field names and the grammar
     *   of the language that was spoken. Not used in translation mode, which keeps its target
     *   language. If it throws or is null the language stays as it was.
     * @param turn Each note's turn at the shared engine; [LiveNoteTurn.ALWAYS] when there is none
     *   to share it with
     * @param onInferenceError Called when one interval's inference fails; the recording and the
     *   later intervals go on. The domain layer has no logger, so this is the caller's only trace.
     * @param modeChanges The recording's mode each time it is switched ("Record as"), which the
     *   notes follow from their next interval; the mode it already has changes nothing, and
     *   neither does a switch into or out of a translation
     * @param recordingLanguage The language the recording starts in; no live note is written while
     *   it is one that gets none ([RecordingLanguage.writesLiveNotes])
     * @param recordingLanguageChanges The recording's language each time it is switched ("Record
     *   as"), which the notes follow from their next interval
     * @return Flow of LLM insights
     */
    operator fun invoke(
        transcriptionFlow: Flow<TranscriptionSegment>,
        sessionId: String,
        mode: RecordingMode,
        outputLanguage: String?,
        topic: String? = null,
        notesLanguage: (suspend (text: String) -> String)? = null,
        turn: LiveNoteTurn = LiveNoteTurn.ALWAYS,
        onInferenceError: (Exception) -> Unit = {},
        modeChanges: Flow<RecordingMode> = emptyFlow(),
        recordingLanguage: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN,
        recordingLanguageChanges: Flow<RecordingLanguage> = emptyFlow()
    ): Flow<LlmInsight> = channelFlow {
        var latestSettings = settingsRepository.getSettings().first()
        // The mode of the next note: the one it started in, then each it was switched to.
        var currentMode = mode
        var throttle: ThermalThrottle = ThermalThrottle.Normal
        var intervalMs = 0L
        var language = outputLanguage
        var keys = InsightFormat.keys(resourceProvider, language)

        // The interval of the current mode under the latest settings, scaled while the phone runs
        // hot: ThermalThrottle.Reduced extends the interval so the LLM fires less frequently,
        // protecting battery. For long intervals (>= 10 min) thermal throttling is intentionally
        // skipped: users who chose a 20-min interval expect insights on schedule and would not
        // accept a 30-min delay caused by thermal conditions.
        fun retime() {
            val baseIntervalMs = calculateIntervalMs(currentMode, latestSettings)
            val thermalFactor = when {
                baseIntervalMs >= LONG_INTERVAL_THRESHOLD_MS -> 1.0f // never throttle long modes
                else -> (throttle as? ThermalThrottle.Reduced)?.factor ?: 1.0f
            }
            intervalMs = (baseIntervalMs * thermalFactor).toLong()
        }
        retime()

        // Monitor settings changes (the interval and the system prompt are reactive). Nothing is
        // set on the engine here: every note sends its own system prompt with it, and the engine
        // may be another one's at this moment.
        launch {
            settingsRepository.getSettings().collect { updatedSettings ->
                latestSettings = updatedSettings
                retime()
            }
        }

        // Monitor device thermal state and scale the inference interval accordingly.
        launch {
            llmRepository.thermalThrottleFlow.collect { updated ->
                throttle = updated
                retime()
            }
        }

        // Whether live notes are written: not while the recording is in Malayalam. A switch takes
        // effect at the next interval; the note being written finishes.
        var liveNotes = recordingLanguage.writesLiveNotes
        launch {
            recordingLanguageChanges.collect { switched -> liveNotes = switched.writesLiveNotes }
        }

        // A switch takes effect at the next interval; the note being written keeps its mode.
        launch {
            modeChanges.collect { switched ->
                val translation = RecordingMode.REAL_TIME_TRANSLATION
                if (switched == currentMode || switched == translation || currentMode == translation) return@collect
                currentMode = switched
                retime()
            }
        }

        // The complete segments said since the last note, each with the words of it that no note
        // has read yet. Only finalized segments are kept by id: they are guaranteed to be
        // persisted in DB, while a partial segment's id would be an orphan reference.
        val heard = HeardSinceNote(MAX_USER_PROMPT_CHARS)
        var currentPartialSegment = ""
        var lastPartialSent = ""
        var lastInferenceTime = System.currentTimeMillis()
        // Guard against concurrent LLM calls: if the previous call is still running,
        // skip the current interval to avoid queuing inference requests on slow devices.
        // AtomicBoolean ensures visibility between the collect coroutine and the launch
        // child coroutine, which may run on different threads in the Default pool.
        val isLlmBusy = AtomicBoolean(false)
        // The live notes sent, which a later note must not mostly repeat ([EarlierNotes]).
        val sent = EarlierNotes()

        transcriptionFlow
            .collect { segment ->
                // No note while the recording is in Malayalam, and no Malayalam line in a note: the
                // Notes step after Stop reads them, as no note cites them. A line on screen when
                // the notes stopped is read by none either, so none is read in part.
                if (!liveNotes || (segment.text.isNotBlank() && LanguageDetector.isMalayalam(segment.text))) {
                    currentPartialSegment = ""
                    if (segment.isComplete) lastPartialSent = ""
                    return@collect
                }
                if (segment.text.isNotBlank()) {
                    if (segment.isComplete) {
                        // The words the last note read as its partial line are not read again.
                        val newPart = if (lastPartialSent.isNotBlank() && segment.text.startsWith(lastPartialSent)) {
                            segment.text.substring(lastPartialSent.length).trim()
                        } else {
                            segment.text
                        }
                        heard.add(HeardSegment(segment.id, newPart))
                        currentPartialSegment = ""
                        lastPartialSent = ""
                    } else {
                        currentPartialSegment = segment.text
                    }
                }

                val now = System.currentTimeMillis()
                val timeSinceLastInference = now - lastInferenceTime

                // For long intervals (>= 10 min) fire 30 s early so LLM generation
                // completes by the time the declared interval elapses for the user.
                val effectiveIntervalMs = if (intervalMs >= LONG_INTERVAL_THRESHOLD_MS)
                    intervalMs - LONG_INTERVAL_EARLY_TRIGGER_MS
                else
                    intervalMs

                if (timeSinceLastInference >= effectiveIntervalMs) {
                    // Skip if a previous LLM call is still running — prevents queuing
                    // inference requests on slow devices and wastes battery.
                    if (isLlmBusy.get()) return@collect

                    // Counted as the lines come, so an interval that is not written makes no pass
                    // over what was said: a turn can be refused at every line of a long recording.
                    // A translation reads no line before it is finished.
                    val translating = currentMode == RecordingMode.REAL_TIME_TRANSLATION
                    val words = heard.words + if (translating) 0 else wordsIn(currentPartialSegment)

                    // Skip if not enough new content (except REAL_TIME_TRANSLATION,
                    // where even short phrases should be translated immediately).
                    if (currentMode != RecordingMode.REAL_TIME_TRANSLATION && words < MIN_NEW_WORDS_FOR_INFERENCE) return@collect

                    // The engine is someone else's now: this interval is skipped, and what was
                    // said in it, with its segment ids, stays for the next note. Taken only once
                    // the interval is to be written, and nothing up to the launch below can throw
                    // or suspend, so a turn taken is always released.
                    if (words > 0 && turn.tryTake()) {
                        lastInferenceTime = now
                        // The note is written to its end in the mode it begins in, whatever the
                        // recording is switched to meanwhile.
                        val noteMode = currentMode
                        val maxTokens = maxTokensForMode(noteMode)
                        // Finished lines only for a translation, so each is translated once.
                        val partial = if (noteMode == RecordingMode.REAL_TIME_TRANSLATION) "" else currentPartialSegment
                        val input = NoteInput.of(heard.segments, partial, MAX_USER_PROMPT_CHARS)
                        val sourceIds = input.sourceIds
                        lastPartialSent = partial
                        heard.clear()

                        // Capture timestamp before LLM call so the insight
                        // reflects when generation started, not when it finished.
                        val insightTimestamp = System.currentTimeMillis()

                        val finalPrompt = input.prompt

                        isLlmBusy.set(true)
                        // Started at once rather than dispatched: a coroutine cancelled before it
                        // runs never reaches its finally, which gives the turn back.
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                // Proactively switch to conservative threads if this context size
                                // has previously caused RAM pressure on this device (adaptive
                                // threshold). Only inside the turn: the next model load may be
                                // another one's otherwise.
                                if (llmRepository.isLargeContext(finalPrompt.length)) {
                                    llmRepository.useConservativeThreads()
                                }
                                // Signal inference start before reloadModel so isGenerating covers
                                // the full window — including LONG_MEETING lazy model load (5-15 s).
                                llmRepository.beginInference()
                                // Write in the language of what was said: the prompt, the JSON field
                                // names and the grammar must come from the same language to match.
                                // Told from what the note reads, the tail after a long wait.
                                if (notesLanguage != null && noteMode != RecordingMode.REAL_TIME_TRANSLATION) {
                                    val chosen = try {
                                        notesLanguage(finalPrompt)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (chosen != null && chosen != language) {
                                        language = chosen
                                        keys = InsightFormat.keys(resourceProvider, chosen)
                                    }
                                }
                                // The live model, which whoever had the engine since the last note
                                // may have replaced or forgotten.
                                turn.prepare()
                                // Reload model if it was unloaded after the previous LONG_MEETING
                                // inference. No-op when the model is already in memory.
                                llmRepository.reloadModel()
                                // Post-load check: model is in memory so RAM reading is accurate.
                                llmRepository.checkAndCacheMemoryConstraint()

                                val insightBuilder = StringBuilder()
                                // The prompt, the grammar and the reading of the answer all of the
                                // note's mode, the prompt in the language just chosen.
                                val systemPrompt = buildSystemPrompt(noteMode, latestSettings, language)
                                val grammar = InsightFormat.grammar(noteMode, keys)
                                // The topic in the language just chosen; what was said alone
                                // decided that language.
                                val prompt = NotesTopic.prompt(finalPrompt, topic, noteMode, language, resourceProvider)
                                llmRepository.generateInsight(prompt.text, systemPrompt, maxTokens, grammar)
                                    .collect { token -> insightBuilder.append(token) }

                                val rawOutput = insightBuilder.toString().trim()

                                // A blank answer, or one with nothing in it but what its prompt said, makes no note.
                                val checks = AnswerChecks(prompt.instructions, PromptRules.of(systemPrompt), spoken = finalPrompt)
                                val parsed = InsightOutputParser.parse(rawOutput, noteMode, keys, checks)
                                logDrops(sessionId, parsed)
                                // A note that mostly repeats one sent before keeps what is new, or is none;
                                // a translation is never held to it.
                                val note = when {
                                    parsed.isEmpty -> null
                                    noteMode == RecordingMode.REAL_TIME_TRANSLATION -> parsed
                                    else -> keptOf(sessionId, parsed, sent)
                                }
                                if (note != null) {
                                    send(LlmInsight(
                                        id = UUID.randomUUID().toString(),
                                        sessionId = sessionId,
                                        title = note.title,
                                        content = note.content,
                                        tasks = note.tasks,
                                        timestamp = insightTimestamp,
                                        sourceSegmentIds = sourceIds
                                    ))
                                }
                            } catch (e: Exception) {
                                // Re-throw CancellationException so the coroutine machinery
                                // can propagate cancellation correctly. Swallowing it would
                                // prevent the parent channelFlow from cancelling cleanly and
                                // could leave the LLM in an inconsistent state when Stop is
                                // pressed while inference is in progress.
                                if (e is CancellationException) throw e
                                onInferenceError(e)
                            } finally {
                                // NonCancellable ensures cleanup always runs even when this
                                // coroutine is being cancelled (e.g. user pressed Stop mid-inference).
                                // Without it, the suspend calls inside finally would immediately
                                // throw CancellationException, skipping cleanup() and leaving the
                                // llama.cpp context in an inconsistent state for the next caller.
                                withContext(NonCancellable) {
                                    try {
                                        // Reset isGenerating here as a fallback for the case where the
                                        // coroutine was cancelled before generateInsight() was ever
                                        // collected (e.g. during reloadModel). In that path onCompletion
                                        // never fires, so isGenerating would otherwise stay true forever.
                                        llmRepository.endInference()
                                        // Record the inference size for adaptive threshold learning.
                                        llmRepository.recordConstrainedInference(finalPrompt.length)
                                        if (noteMode == RecordingMode.LONG_MEETING && llmRepository.isMemoryConstrained()) {
                                            // Free ~700 MB immediately; the model will be reloaded before
                                            // the next inference. On devices with sufficient RAM this branch
                                            // is never taken and the model stays warm between inferences.
                                            // Inside the turn, as it frees the model.
                                            llmRepository.cleanup()
                                        }
                                    } finally {
                                        isLlmBusy.set(false)
                                        // Last: the engine is free for others once this note is done with it.
                                        turn.release()
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    /**
     * [parsed], a live note, as [sent] (the live notes sent before) keeps it, telling [notesLog]
     * what of it went as a repeat of one of those: the note, or its pieces an earlier note says.
     */
    private fun keptOf(sessionId: String, parsed: InsightOutputParser.ParsedInsight, sent: EarlierNotes): InsightOutputParser.ParsedInsight? {
        val (notesBefore, linesBefore) = sent.repeatedNotes to sent.repeatedLines
        val kept = sent.keep(parsed)
        if (sent.repeatedNotes > notesBefore) notesLog.log(NotesLog.RepeatedNotes(sessionId, sent.repeatedNotes - notesBefore))
        if (sent.repeatedLines > linesBefore) notesLog.log(NotesLog.RepeatedNoteLines(sessionId, sent.repeatedLines - linesBefore))
        return kept
    }

    /** Tells [echoLog] and [notesLog] how many lines of one live note's answer were left out, and why; nothing when none. */
    private fun logDrops(sessionId: String, parsed: InsightOutputParser.ParsedInsight) {
        if (parsed.echoes > 0) echoLog.dropped(sessionId, parsed.echoes, PromptEchoLog.What.NOTE_LINES)
        if (parsed.genericTitles > 0) notesLog.log(NotesLog.GenericTitles(sessionId, parsed.genericTitles))
        if (parsed.emptyLines > 0) notesLog.log(NotesLog.EmptyLines(sessionId, parsed.emptyLines))
        if (parsed.ruleEchoes > 0) notesLog.log(NotesLog.Dropped(sessionId, parsed.ruleEchoes, NotesLog.Piece.NOTE_LINES, AnswerDrop.RULES))
        if (parsed.otherScript > 0) {
            notesLog.log(NotesLog.Dropped(sessionId, parsed.otherScript, NotesLog.Piece.NOTE_LINES, AnswerDrop.OTHER_SCRIPT))
        }
        if (parsed.repeatedLines > 0) notesLog.log(NotesLog.RepeatedLines(sessionId, parsed.repeatedLines))
    }

    // ─── Interval ───────────────────────────────────────────────────────────────

    private fun calculateIntervalMs(mode: RecordingMode, settings: AppSettings): Long {
        return when (mode) {
            RecordingMode.SIMPLE_LISTENING     -> settings.simpleListeningIntervalSeconds * 1000L
            RecordingMode.SHORT_MEETING        -> settings.shortMeetingIntervalSeconds * 1000L
            RecordingMode.LONG_MEETING         -> settings.longMeetingIntervalMinutes * 60 * 1000L
            RecordingMode.REAL_TIME_TRANSLATION -> settings.translationIntervalSeconds * 1000L
        }
    }

    // ─── Max tokens per mode ─────────────────────────────────────────────────────

    private fun maxTokensForMode(mode: RecordingMode): Int = when (mode) {
        RecordingMode.SIMPLE_LISTENING      -> MAX_TOKENS_SIMPLE_LISTENING
        RecordingMode.SHORT_MEETING         -> MAX_TOKENS_SHORT_MEETING
        RecordingMode.LONG_MEETING          -> MAX_TOKENS_LONG_MEETING
        RecordingMode.REAL_TIME_TRANSLATION -> MAX_TOKENS_TRANSLATION
    }

    // ─── System prompt ───────────────────────────────────────────────────────────

    /**
     * Returns the system prompt for the given mode, substituting {target_language} with the
     * name of the target language in the prompt's own language, the app's
     * ([ResourceProvider.getTranslationTargetName]): "English" in the English prompt, "Englisch"
     * in the German one, which says "auf Englisch übersetzen".
     *
     * It holds nothing of the recording: the topic goes with what was said ([NotesTopic]).
     *
     * The stored value is always a BCP-47 code (e.g. "en").
     */
    private fun buildSystemPrompt(
        mode: RecordingMode,
        settings: AppSettings,
        targetLangCode: String?
    ): String {
        return when (mode) {
            RecordingMode.REAL_TIME_TRANSLATION -> {
                val code = targetLangCode ?: settings.translationTargetLanguage
                // The target's name in the prompt's own language ("auf Englisch"), the raw code if unknown.
                settings.translationSystemPrompt.replace("{target_language}", resourceProvider.getTranslationTargetName(code))
            }
            else -> {
                if (!targetLangCode.isNullOrEmpty()) {
                    // The notes are written in this language: the app's prompt in it.
                    resourceProvider.getPromptForMode(mode, targetLangCode)
                } else {
                    // No language known yet: the app's prompt in the app language, as the
                    // settings hold it.
                    when (mode) {
                        RecordingMode.SHORT_MEETING -> settings.shortMeetingSystemPrompt
                        RecordingMode.LONG_MEETING  -> settings.longMeetingSystemPrompt
                        else                        -> settings.simpleListeningSystemPrompt
                    }
                }
            }
        }
    }
}

/**
 * A complete segment said since the last note: its id, and the words of it that no note has read
 * yet. [text] is empty when the last note read all of it, as the line the recognizer was on.
 */
internal class HeardSegment(val id: String, val text: String)

/**
 * The complete segments said since the last note ([HeardSegment]), in spoken order, as far as a
 * note can still read them, with their words counted as they come ([words]).
 *
 * A note's turn can be refused at every line of a long recording (AI notes off, no model, an older
 * recording being finished), so neither the word count nor what is kept may grow with what was
 * said. A note reads at most the last [maxChars] characters ([NoteInput]), and a segment that lies
 * wholly before them is neither read nor cited, whatever is said after it: the segments are only
 * added to at the end. Such segments are dropped, at most once for every [maxChars] characters
 * said, so that it costs nothing per line, and the Notes step after Stop reads them from the
 * recording. A segment whose words an earlier note read in full (its text is blank) is kept, as the
 * next note cites it.
 */
internal class HeardSinceNote(private val maxChars: Int) {
    private val kept = mutableListOf<HeardSegment>()

    // The kept texts' length as NoteInput joins them, and the length at which the segments no note
    // can read are dropped next.
    private var joinedLength = 0
    private var dropAt = 2 * maxChars

    /** The segments a note can still read, in spoken order. */
    val segments: List<HeardSegment>
        get() = kept

    /** How many words [segments] hold, counted as the interval's word check counts them ([wordsIn]). */
    var words = 0
        private set

    /** Adds the next complete segment. */
    fun add(segment: HeardSegment) {
        kept += segment
        if (segment.text.isBlank()) return
        joinedLength += segment.text.length + 1
        words += wordsIn(segment.text)
        if (joinedLength >= dropAt) dropUnreadable()
    }

    /** A note has read them all. */
    fun clear() {
        kept.clear()
        joinedLength = 0
        words = 0
        dropAt = 2 * maxChars
    }

    /**
     * Drops the segments before the last one whose words begin at or before the start of the last
     * [maxChars] characters, as NoteInput joins and trims the segments: the rest is then still at
     * least [maxChars] long, so every later note reads and cites exactly what it would have.
     */
    private fun dropUnreadable() {
        val texts = kept.map { it.text }.filter { it.isNotBlank() }
        val trailing = texts.last().let { it.length - it.trimEnd().length }
        var fromHere = -1
        var first = 0
        for (i in texts.indices.reversed()) {
            fromHere += texts[i].length + 1
            if (fromHere - (texts[i].length - texts[i].trimStart().length) - trailing >= maxChars) {
                first = i
                break
            }
        }
        var dropped = 0
        val iterator = kept.iterator()
        while (dropped < first && iterator.hasNext()) {
            if (iterator.next().text.isNotBlank()) {
                iterator.remove()
                dropped++
            }
        }
        joinedLength = kept.sumOf { if (it.text.isBlank()) 0 else it.text.length + 1 }
        words = kept.sumOf { wordsIn(it.text) }
        dropAt = joinedLength + maxChars
    }
}

private val WHITESPACE = Regex("\\s+")

/** How many words [text] holds: the runs of anything but whitespace. */
internal fun wordsIn(text: String): Int =
    if (text.isBlank()) 0 else text.trim().split(WHITESPACE).count { it.isNotEmpty() }

/**
 * What one interval sends the model, and the segments its note cites.
 *
 * @property prompt What is sent: what was said since the last note, as it was said, with no label
 *   or wrapper (the new words of each complete segment, then the line the recognizer is on), or
 *   its tail when that is too long for the context window, since the tail is what just happened.
 *   The notes prompts end with their own lead-in to it, and a translation gets the text alone,
 *   since a wrapper makes a small model echo it ("Context:") instead of translating. Earlier lines
 *   are not added as context. They used to be the interval's own last lines in nearly every
 *   interval, read twice, and lines of the interval before would have the model note again what
 *   the previous note has.
 * @property sourceIds The complete segments whose new words [prompt] holds whole, in spoken order.
 *   One whose words were all read by an earlier note counts as well; one cut off at the front of
 *   the tail doesn't, so the Notes step after Stop still reads it.
 */
internal class NoteInput private constructor(
    val prompt: String,
    val sourceIds: List<String>
) {
    companion object {
        /** The input of a note on [heard] and the [partial] line, sending at most [maxChars]. */
        fun of(heard: List<HeardSegment>, partial: String, maxChars: Int): NoteInput {
            val joined = (heard.map { it.text } + partial).filter { it.isNotBlank() }.joinToString(" ")
            val content = joined.trim()
            val prompt = content.takeLast(maxChars)
            // Where the prompt starts in what was joined: past the whitespace trimmed off its
            // front, and past what the tail leaves out.
            val promptStart = (joined.length - joined.trimStart().length) + (content.length - prompt.length)
            var offset = 0
            val sourceIds = heard.mapNotNull { segment ->
                if (segment.text.isBlank()) return@mapNotNull segment.id
                val wordsStart = offset + (segment.text.length - segment.text.trimStart().length)
                offset += segment.text.length + 1
                segment.id.takeIf { wordsStart >= promptStart }
            }
            return NoteInput(prompt, sourceIds)
        }
    }
}
