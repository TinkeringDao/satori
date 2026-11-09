package com.satori.feature.stt.datasource

import android.util.Log
import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.LoadSupersededException
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * One capture's hold on the shared live recognizer ([LiveRecognizer]): the model of the
 * recording's language ([CaptureLanguage.current], [RecordingLanguage.liveModel]) loaded as the
 * capture begins, and changed between two utterances when the language is switched while it
 * records ([LiveSpeech.switchDue], [LiveSpeech.switchTo]). Generic over the recognizer, so these
 * rules are tested without a speech model.
 *
 * Every load and change holds [decodeLock], the lock every decode takes: from the release of the
 * one model to the end of the other's load no decode runs, so none finds a recognizer half gone,
 * and one that waited decodes with the new model. Only the newest capture changes the model
 * ([isNewest]): one that a newer capture cut short leaves it to that one.
 *
 * A model that didn't load is not tried again in this capture until the language has asked for
 * another model in between, so a failing load isn't retried at every read; the recorder is told
 * ([CaptureLanguage.refused]) and takes the switch back.
 *
 * @property recognizer The live recognizer the captures share
 * @property language The recording's language, as its recorder holds it
 * @property decodeLock The lock every decode on [recognizer] holds
 * @property isNewest Whether this capture is the newest one
 * @property io Where the loads run: they block, for seconds
 * @property warn Where a model that didn't load is told: the field log
 * @property log Where a model no longer wanted before it loaded is told: the field log
 * @property stopsAtStop Whether the capture's own load stops now, as Stop came and the recording
 *   keeps its audio, which the finisher transcribes after Stop ([load]); never for a recording
 *   that keeps none, whose queue only the live model can transcribe
 */
internal class CaptureSpeechModel<T : Any>(
    private val recognizer: LiveRecognizer<SpeechModelId, T>,
    private val language: CaptureLanguage,
    private val decodeLock: Mutex,
    private val isNewest: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String, Throwable) -> Unit = { message, e -> Log.w(TAG, message, e) },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val stopsAtStop: () -> Boolean = { false }
) {
    // The model this capture transcribes with, once it is in; null before its load.
    @Volatile private var inUse: SpeechModelId? = null

    // A model that didn't load in this capture.
    @Volatile private var refused: SpeechModelId? = null

    /**
     * Loads the model of the recording's language unless it is in memory; one of another language
     * is freed first. The capture transcribes with it from now on.
     *
     * A language switched while the model's files are read in, before its native load begins
     * (ModelLoadPacing reads Parakeet v3's 670 MB in at 120 MB/s, about 5.6 s from flash), stops
     * that load, and the new language's model loads in its place: the one no longer wanted never
     * takes its memory, and the two are never in memory together. Should the new one not load, the
     * one the recording began with is loaded after all and the recorder is told, as after a switch
     * ([switchTo]), so the recording goes on. One switched during the native load, which nothing can
     * stop half way, is changed as soon as the load ends, before any audio is decoded ([LiveCapture]).
     *
     * Stop that comes before the native load begins, in a recording that keeps its audio
     * ([stopsAtStop]), stops the load too and loads nothing: the capture ends at once, and what was
     * recorded is transcribed from the audio file after Stop, instead of the hand-off waiting for
     * Parakeet v3's files, its room and its load, up to seconds ([StoppedBeforeModelException]).
     *
     * @throws StoppedBeforeModelException when Stop stopped the load
     * @throws Throwable when the model of the recording's language doesn't load: the capture can't
     *   go on (also when the model a switch stopped doesn't load either)
     */
    suspend fun load(): T {
        // The model whose load a switch stopped: the one to go back to should the next not load.
        var stopped: SpeechModelId? = null
        while (true) {
            val model = language.current.liveModel
            val outcome: Pair<SpeechModelId, T>? = decodeLock.withLock {
                withContext(io) {
                    val job = currentCoroutineContext().job
                    try {
                        model to recognizer.get(model) { whyStop(job, model) }
                    } catch (e: LoadSupersededException) {
                        if (e.why == LoadStop.STOPPED) throw StoppedBeforeModelException(model)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val before = stopped ?: throw e
                        before to backOn(before, failed = model, e)
                    }
                }
            }
            // A capture cancelled meanwhile stopped the load too: it ends here.
            currentCoroutineContext().ensureActive()
            if (outcome != null) {
                inUse = outcome.first
                return outcome.second
            }
            log("Live speech model ${model.id} not loaded, as the recording switched to ${language.current.liveModel.id} first")
            stopped = model
        }
    }

    /**
     * Why the capture's load of [model] stops now, if it does ([LoadStop]): the capture ended
     * ([job] is no longer active), Stop came in a recording that keeps its audio, or the recording's
     * language asks for another model. Asked at each piece of the load's pre-read and room.
     */
    private fun whyStop(job: Job, model: SpeechModelId): LoadStop? = when {
        !job.isActive -> LoadStop.ENDED
        stopsAtStop() -> LoadStop.STOPPED
        language.current.liveModel != model -> LoadStop.SWITCHED
        else -> null
    }

    /** The model the capture transcribes with: the one [load] is to load until it has. */
    fun model(): SpeechModelId = inUse ?: language.current.liveModel

    /**
     * The model to change to now: the live model of the recording's language when it isn't the one
     * in use, and hasn't just failed to load. Null before the capture's own load, and for a capture
     * that isn't the newest.
     */
    fun switchDue(): SpeechModelId? {
        val model = inUse ?: return null
        if (!isNewest()) return null
        val wanted = language.current.liveModel
        if (wanted != refused) refused = null
        return wanted.takeIf { it != model && it != refused }
    }

    /**
     * Frees the model in use and loads [model] in its place. When [model] doesn't load, the one
     * before is loaded again, the recorder is told, and this returns false.
     *
     * @return true once [model] is in
     * @throws Throwable when the model before doesn't load again either: none is in memory, and the
     *   capture can't go on
     */
    suspend fun switchTo(model: SpeechModelId): Boolean = decodeLock.withLock {
        withContext(io) {
            // Due only once the capture's own model is in (switchDue).
            val before = inUse ?: return@withContext false
            try {
                recognizer.get(model) // frees the model before, then loads this one
                inUse = model
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                backOn(before, failed = model, e)
                inUse = before
                false
            }
        }
    }

    /**
     * [failed] didn't load ([e]): [before] is loaded again and returned, the recorder is told and
     * takes the switch back, and [failed] isn't tried again until the language has asked for
     * another model in between. Inside the decode lock, on [io].
     *
     * @throws Throwable when [before] doesn't load again either: none is in memory then
     */
    private fun backOn(before: SpeechModelId, failed: SpeechModelId, e: Throwable): T {
        warn("Live speech model ${failed.id} failed to load (${e.javaClass.simpleName}); loading ${before.id} again", e)
        refused = failed
        val again = recognizer.get(before)
        val wanted = RecordingLanguage.ofLiveModel(failed)
        val instead = RecordingLanguage.ofLiveModel(before)
        if (wanted != null && instead != null) language.refused(wanted, instead)
        return again
    }

    private companion object {
        // The recognizer's tag, which the field log is read by.
        const val TAG = "SherpaOnnxDataSource"
    }
}

/**
 * Stop came while the capture's speech model [model] was still being read in, and the recording
 * keeps its audio: the load stopped and nothing is in memory for the capture
 * ([CaptureSpeechModel.load]). The capture ends at once, its audio untranscribed in the audio file,
 * for the finisher to transcribe ([LiveCapture]).
 */
internal class StoppedBeforeModelException(val model: SpeechModelId) : Exception("Stop came before ${model.id} was in")
