package com.satori.feature.reader

import android.util.Log
import com.satori.data.monitor.NativeHeap
import com.satori.data.monitor.Purge
import com.satori.domain.model.ReaderModelId
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.LineNotReadException
import com.satori.domain.repository.LoadStoppedForMemoryException
import com.satori.domain.repository.ReaderFailedException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import com.satori.domain.repository.ReaderRepository
import com.satori.domain.repository.ReaderStoppedException
import com.satori.reader.ReaderEngine
import com.satori.reader.ReaderException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.satori.domain.repository.LoadedReader as LinesReader

/**
 * The reader as the finishing uses it (the domain's [ReaderRepository], 0.8.0 R5): the int8 build
 * of IndicTrans2 ([ReaderModelId.INT8]) through [ReaderDataSource], at beam 4 (design D2) in the
 * design's arm ([ReaderArm.DEFAULT], D3 and D4), each line read whole by [LineReader] (D15).
 *
 * Before each load it purges the native heap: the AI model the Reading step unloaded just before
 * frees its memory into the allocator, which keeps it until its next activity (about 50 MB after a
 * 1B release on the phone, the dev1 verification's V6), so the reader's room and peak are measured
 * as V1 measured them. The data source purges again after its release.
 *
 * What the data source throws becomes the domain's: a refusal before the load
 * [ReaderNotLoadedException], a stop for a capture that opened [ReaderStoppedException], a
 * failure of the native reader [ReaderFailedException]. A line the native reader refuses or fails
 * on alone (its source, or a Run that gave no finite logits) is [LineNotReadException], and the
 * reader stays loaded for the next one. The paced load's stop for memory is the domain's own
 * already. A cancel of the caller stays a cancel. Lines (SatoriReader): counts and ids only,
 * `W Native heap not purged before the load of <model>: <why>`.
 *
 * The reader is on the phone ([isOnPhone]) once its files are whole and each was found once with
 * its pinned SHA-256 ([verified], design R7): the first run reads that once, for files that came
 * another way than its download too; until then the finishing waits for it while the first run has
 * it on its way, as for a reader still downloading.
 *
 * @param files The build's files, checked before every load: the downloaded build's, or a test's
 * @param verified Whether the build's files were found with their pinned SHA-256, as they are now
 */
class ReaderRepositoryImpl(
    private val dataSource: ReaderDataSource,
    private val heap: NativeHeap,
    private val build: ReaderModelId = ReaderModelId.INT8,
    private val files: () -> ReaderFiles = { dataSource.files(build) },
    private val verified: () -> Boolean = { dataSource.verified(build) },
    private val beam: Int = ReaderEngine.DEFAULT_BEAM,
    private val io: CoroutineContext = Dispatchers.IO,
    private val warn: (String) -> Unit = { Log.w(ReaderDataSource.TAG, it) }
) : ReaderRepository {

    override val model: String = build.id

    override suspend fun isOnPhone(): Boolean = withContext(io) { files().check() == ModelFileState.Intact && verified() }

    override suspend fun <R> withReader(use: suspend (LinesReader) -> R): R {
        withContext(io) { purge() }
        // What [use] throws is its own, but for the native reader's failures.
        var loaded = false
        return try {
            dataSource.withReader(files()) { reader ->
                loaded = true
                use(Lines(reader))
            }
        } catch (e: ReaderRefusedException) {
            throw ReaderNotLoadedException(whyOf(e.refusal), "The reader $model was not loaded: ${e.refusal.because}")
        } catch (e: ReaderYieldedException) {
            throw ReaderStoppedException("The reader $model stopped for a capture that opened")
        } catch (e: ReaderException) {
            // The native message is the library's; the class says what failed.
            throw ReaderFailedException("The reader $model failed (${e.javaClass.simpleName})", e)
        } catch (e: LoadStoppedForMemoryException) {
            // The paced load's stop for memory, an IOException too: the caller tries again later.
            throw e
        } catch (e: IOException) {
            // A file that couldn't be read whole as the reader loaded: the reader failed.
            if (loaded) throw e
            throw ReaderFailedException("The reader $model failed as it loaded (${e.javaClass.simpleName})", e)
        } catch (e: IllegalArgumentException) {
            // A vocabulary that isn't one (its size passed the check): the reader failed.
            if (loaded) throw e
            throw ReaderFailedException("The reader $model failed as it loaded (${e.javaClass.simpleName})", e)
        }
    }

    /** What the AI model's unload left in the allocator, given back before the reader's room is measured. Never throws. */
    private fun purge() {
        val purged = try {
            heap.purge()
        } catch (e: RuntimeException) {
            warn("Native heap not purged before the load of $model: ${e.javaClass.simpleName}")
            return
        } catch (e: LinkageError) {
            warn("Native heap not purged before the load of $model: ${e.javaClass.simpleName}")
            return
        }
        if (purged == Purge.NONE) warn("Native heap not purged before the load of $model: mallopt knows neither M_PURGE_ALL nor M_PURGE")
    }

    /** One line at a time on the loaded reader, at [beam]. */
    private inner class Lines(private val loaded: LoadedReader) : LinesReader {
        override suspend fun read(line: String): ReaderOutput {
            val reading = try {
                loaded.read(line, beam)
            } catch (e: ReaderException.BadInput) {
                throw LineNotReadException("The reader $model refused a line (BadInput)", e)
            } catch (e: ReaderException.RunFailed) {
                throw LineNotReadException("The reader $model failed on a line (RunFailed)", e)
            }
            return ReaderOutput(reading.text, reading.pieces, reading.tokens, reading.meanLogProb, reading.capped)
        }
    }

    private fun whyOf(refusal: ReaderRefusal): ReaderNotLoadedException.Why = when (refusal) {
        ReaderRefusal.NOT_DOWNLOADED, ReaderRefusal.DAMAGED, ReaderRefusal.UNREADABLE -> ReaderNotLoadedException.Why.NOT_ON_PHONE
        ReaderRefusal.CAPTURE -> ReaderNotLoadedException.Why.CAPTURE
        ReaderRefusal.SPEECH_MODEL -> ReaderNotLoadedException.Why.SPEECH_MODEL
        ReaderRefusal.AI_MODEL -> ReaderNotLoadedException.Why.AI_MODEL
    }
}
