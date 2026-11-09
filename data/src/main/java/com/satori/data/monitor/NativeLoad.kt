package com.satori.data.monitor

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs [use] with what [load] makes on [context], an object that holds native memory (a
 * sherpa-onnx recognizer, the reader), and [release]s it when [use] ends, also when the caller is
 * cancelled while it loads.
 *
 * withContext throws away the result of a block whose caller was cancelled meanwhile, and a native
 * load can't be stopped half way: a recognizer loaded for a cancelled Transcribe again would then
 * keep its memory, 2.9 GB for Cohere Transcribe, until the garbage collector finalizes its small
 * Java object, which nothing prompts it to do. So the object is kept here as soon as the block has
 * made it, and the release sees it whatever happens next.
 *
 * With [stop], a load that can be stopped half way (the reader's: ONNX Runtime's load-cancellation
 * flag, D14) is told to stop the moment the caller is cancelled, rather than run to its end first;
 * this returns once [load] has, whatever it made then released as above.
 */
suspend fun <T : Any, R> withLoaded(
    context: CoroutineContext,
    load: () -> T,
    release: (T) -> Unit,
    stop: (() -> Unit)? = null,
    use: suspend (T) -> R
): R {
    var loaded: T? = null
    try {
        if (stop == null) {
            withContext(context) { loaded = load() }
        } else {
            stoppable(context, stop) { loaded = load() }
        }
        return use(checkNotNull(loaded))
    } finally {
        loaded?.let(release)
    }
}

/**
 * Runs [block], which blocks, on [context], and calls [stop] at once when the caller is cancelled
 * meanwhile: a native call that can be stopped (the reader's Run or load) then returns within
 * milliseconds instead of running to its end. This returns, or throws the cancel, only once
 * [block] has returned: nothing it uses is freed under it. A block that ends by throwing, a
 * cancel among others, is not stopped: it has returned already.
 */
suspend fun <T> stoppable(context: CoroutineContext, stop: () -> Unit, block: () -> T): T = coroutineScope {
    val running = async(context) { block() }
    try {
        running.await()
    } catch (e: CancellationException) {
        if (!running.isCompleted) stop()
        throw e
    }
}
