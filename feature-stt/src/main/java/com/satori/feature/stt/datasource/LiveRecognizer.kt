package com.satori.feature.stt.datasource

import com.satori.data.monitor.LoadStop
import com.satori.data.monitor.ModelMemoryLines
import com.satori.data.monitor.ReleasePurge
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The live speech model's recognizer, which the recordings share ([SherpaOnnxDataSource]): one at a
 * time, of the model a recording's language transcribes with (Parakeet v3, or the Malayalam model),
 * loaded by the first [get] that finds none or another model's, freed by [release], and [loaded]
 * from the one to the other. The finisher of recordings waits on [loaded] before it loads Gemma 4
 * E2B, so a flag that stayed set would hold every finishing 5 s, and one never set would let E2B
 * load beside the speech model. Generic, so these rules are tested without a speech model.
 *
 * The load is locked: initialize() and a capture can both find none at once (both on IO), and
 * each would load its own recognizer, one of which would never be freed. A [get] of another model
 * frees the one in memory before it loads the new one, so the two are never in memory together:
 * a switch of the recording's language while it records lowers the memory or raises it by the
 * difference of the two models, never by the new one's whole size. Its caller holds the decode
 * lock, as [release]'s does, so no decode runs on the recognizer it frees; [release] takes no lock
 * of its own and frees only a recognizer whose load has ended.
 *
 * @property load Loads the recognizer of a model, which takes seconds, and may stop before the
 *   model loads, with an exception, once the check it is given says why the model isn't wanted any
 *   more ([LoadStop])
 * @property free Frees the native memory of one, of the model it was loaded for
 */
internal class LiveRecognizer<M : Any, T : Any>(private val load: (M, () -> LoadStop?) -> T, private val free: (M, T) -> Unit) {

    /** A recognizer and the model it was loaded for. */
    private class Loaded<M, T>(val model: M, val recognizer: T)

    @Volatile private var current: Loaded<M, T>? = null
    private val lock = Any()
    private val _loaded = MutableStateFlow(false)

    /** Whether a recognizer is in memory: set as its load ends, cleared as it is freed. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The model whose recognizer is in memory, or null. */
    val model: M?
        get() = current?.model

    /**
     * The recognizer of [model], loaded first if none is. One of another model is freed first, and
     * then the caller must hold the decode lock. When the load fails, none is in memory afterwards,
     * also when it stopped as [stop] said why [model] isn't wanted any more.
     */
    fun get(model: M, stop: () -> LoadStop? = { null }): T {
        current?.takeIf { it.model == model }?.let { return it.recognizer }
        return synchronized(lock) {
            current?.takeIf { it.model == model }?.let { return@synchronized it.recognizer }
            release()
            load(model, stop).also {
                current = Loaded(model, it)
                _loaded.value = true
            }
        }
    }

    /**
     * The recognizer if one is loaded, without loading one; with [model], only one of that model:
     * a capture decodes with its own model alone.
     */
    fun loadedOrNull(model: M? = null): T? = current?.takeIf { model == null || it.model == model }?.recognizer

    /** Frees the recognizer, if one is loaded; the next [get] loads a new one. */
    fun release() {
        current?.let { free(it.model, it.recognizer) }
        current = null
        _loaded.value = false
    }

    /**
     * [release], on [dispatcher] once no decode holds [decodeLock]: Stop calls this from the main
     * thread, and the native free of some 670 MB, with its memory line in a debug build (/proc read
     * twice), need not run there.
     */
    suspend fun releaseWhenIdle(decodeLock: Mutex, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        withContext(dispatcher) { decodeLock.withLock { release() } }
    }
}

/**
 * The recognizer of the live speech models ([SpeechModelId.LIVE], or the Malayalam model in its
 * place): each load and release of one logs its memory line in a debug build ([ModelMemoryLines]),
 * named by the model's id, so the switch of a recording's language shows the release of the one and
 * the load of the other; a load's line has its peak RssAnon, and a load stopped before its model
 * loaded, as the recording no longer wanted it, says so. Each release purges the native heap after
 * the free, in every build ([purge], [speechModelReleased]), before the recognizer is marked out of
 * memory: so the finisher's wait for it ([LiveRecognizer.loaded]) and a switch's next load come
 * after the purge.
 */
internal fun <T : Any> liveSpeechRecognizer(
    memoryLines: ModelMemoryLines,
    load: (SpeechModelId, () -> LoadStop?) -> T,
    free: (T) -> Unit,
    purge: ReleasePurge = ReleasePurge.NONE
): LiveRecognizer<SpeechModelId, T> =
    LiveRecognizer(
        load = { model, stop -> memoryLines.measured(ModelMemoryLines.Event.LOAD, model.id, peak = true) { load(model, stop) } },
        free = { model, recognizer -> speechModelReleased(memoryLines, purge, model) { free(recognizer) } }
    )

/**
 * The release of a speech model's recognizer, the live one's or a whole-file run's own: [free], then
 * the native heap purged as [purge] plans it (since 0.7.2: ONNX Runtime's freed blocks otherwise
 * stayed with the allocator until its next burst of allocations, about 500 MB of Parakeet v3 for
 * 8-11 s), with the release's memory line in a debug build ([ModelMemoryLines.released]).
 *
 * Never during the released recognizer's own decode or load, but the purge is the process's: a
 * whole-file run that a new capture skips releases its own while the capture's live model loads or
 * decodes, and the live model's release as the app's screen goes can come during a whole-file run.
 * The purge touches only freed memory and holds the allocator's locks for tens of ms, so the other
 * recognizer waits that long at most, at an allocation, and the run's memory is back before the new
 * model's parse.
 */
internal fun speechModelReleased(memoryLines: ModelMemoryLines, purge: ReleasePurge, model: SpeechModelId, free: () -> Unit) {
    memoryLines.released(model.id, purge.plan(), free)
}
