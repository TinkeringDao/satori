package com.satori.data.audio

import android.content.Context
import android.util.Log
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.RecordingAudio
import com.satori.domain.repository.AudioFileInfo
import com.satori.domain.repository.RecordingAudioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The files that keep one recording's audio: the [Wav] file, or once it is compressed the AAC
 * [m4a] in its place, and the [AudioIndex] of both.
 */
class RecordingAudioFiles(
    val wav: File,
    val index: File,
    val m4a: File = File(wav.parentFile, "${wav.nameWithoutExtension}.m4a")
) {

    /** Where a compression writes the AAC until it is complete and takes its place. */
    val m4aInProgress: File get() = File("${m4a.path}.new")

    /** Deletes them all, with what a compression cut short left. @return true if none is left */
    fun delete(): Boolean {
        wav.delete()
        index.delete()
        m4a.delete()
        m4aInProgress.delete()
        return !wav.exists() && !index.exists() && !m4a.exists()
    }
}

/**
 * Keeps the audio of recordings in the app's own storage, `files/audio/`: `<sessionId>.wav` and
 * its index `<sessionId>.idx`, and once the WAV is compressed `<sessionId>.m4a` in its place. The
 * capture writes the WAV ([CaptureAudioWriter]); this reads, repairs, compresses and deletes them.
 * Everything in `files/` is left out of backup and device transfer
 * (res/xml/data_extraction_rules.xml), so the audio never leaves the phone unless it is shared.
 *
 * @param decodedDir Where compressed audio is decoded into a WAV while the speech recognizer
 *   reads it (`cache/transcribe/`)
 * @param info Where what was done is logged; never with what was said
 * @param warn Where problems are logged; never with what was said
 * @param syncToDisk Writes what a file holds through to the storage, so a power cut or a crash of
 *   the phone after it returns finds the file whole
 */
@Singleton
class RecordingAudioStore internal constructor(
    private val dir: File,
    private val decodedDir: File,
    private val compressor: AudioCompressor,
    private val decoder: AudioDecoder,
    private val info: (String) -> Unit,
    private val warn: (String, Throwable?) -> Unit,
    private val syncToDisk: (File) -> Unit = { file -> RandomAccessFile(file, "rw").use { it.fd.sync() } }
) : RecordingAudioRepository {

    // replaceLines rewrites the index from what it read: a keep entry setKept appended meanwhile
    // would be lost. A compression reads the keep entry under it before the WAV goes.
    private val indexLock = Mutex()

    // One compression at a time. The inventory removes what compressions cut short left behind,
    // except the copy of the one that runs now.
    private val compressLock = Mutex()
    @Volatile private var compressing: String? = null

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        dir = File(context.filesDir, DIR),
        decodedDir = File(context.cacheDir, DECODED_DIR),
        compressor = MediaCodecAudioCompressor(),
        decoder = MediaCodecAudioDecoder(),
        info = { message -> Log.i(TAG, message) },
        warn = { message, e -> Log.w(TAG, message, e) }
    )

    /**
     * The files of [sessionId]'s audio, or null for an id that can't name a file. Recording ids
     * are UUIDs; anything with more than letters, digits and dashes is refused, so no id reaches
     * outside the folder.
     */
    fun files(sessionId: String): RecordingAudioFiles? {
        if (!SAFE_ID.matches(sessionId)) {
            warn("No audio for a recording id that is not a file name", null)
            return null
        }
        return RecordingAudioFiles(File(dir, "$sessionId.wav"), File(dir, "$sessionId.idx"), File(dir, "$sessionId.m4a"))
    }

    /** The WAV while there is one, also next to an AAC copy a compression cut short; else the AAC. */
    override suspend fun find(sessionId: String): RecordingAudio? = withContext(Dispatchers.IO) {
        val files = files(sessionId) ?: return@withContext null
        try {
            when {
                files.wav.isFile -> findWav(sessionId, files)
                files.m4a.isFile -> findAac(sessionId, files)
                else -> null
            }
        } catch (e: IOException) {
            warn("The audio of a recording couldn't be read", e)
            null
        }
    }

    private fun findWav(sessionId: String, files: RecordingAudioFiles): RecordingAudio? {
        val dataBytes = Wav.repair(files.wav)
        if (dataBytes == null) {
            warn("The audio file of a recording is not one Satori wrote; ignoring it", null)
            return null
        }
        // Stopped before the first read: nothing to play, share or transcribe.
        if (dataBytes == 0L) return null
        val entries = entriesOf(files)
        return RecordingAudio(
            sessionId = sessionId,
            path = files.wav.path,
            durationMs = Wav.durationMs(dataBytes),
            runs = entries.runs,
            lines = entries.lines,
            kept = entries.kept,
            sizeBytes = files.wav.length() + indexBytes(files)
        )
    }

    private fun findAac(sessionId: String, files: RecordingAudioFiles): RecordingAudio? {
        val durationMs = decoder.durationMs(files.m4a)
        if (durationMs == null || durationMs <= 0L) {
            warn("The compressed audio of a recording can't be read; ignoring it", null)
            return null
        }
        val entries = entriesOf(files)
        return RecordingAudio(
            sessionId = sessionId,
            path = files.m4a.path,
            durationMs = durationMs,
            runs = entries.runs,
            lines = entries.lines,
            encoding = AudioEncoding.AAC,
            kept = entries.kept,
            sizeBytes = files.m4a.length() + indexBytes(files)
        )
    }

    override suspend fun hasAudio(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val files = files(sessionId) ?: return@withContext false
        holdsAudio(files)
    }

    /** Whether [files] hold audio, by their sizes alone: a WAV with more than a header, or a compressed copy. */
    private fun holdsAudio(files: RecordingAudioFiles): Boolean =
        (files.wav.isFile && files.wav.length() > Wav.HEADER_BYTES) || (files.m4a.isFile && files.m4a.length() > 0L)

    /**
     * Writes the index anew, with the captures it had, the new [lines] and whether the audio is
     * kept, to a temporary file that then replaces it, so a failure leaves the old index whole.
     */
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>) {
        withContext(NonCancellable + Dispatchers.IO) {
            val files = files(sessionId) ?: return@withContext
            indexLock.withLock {
                try {
                    val old = if (files.index.isFile) AudioIndex.parse(files.index.readText()) else NO_ENTRIES
                    val next = File(dir, "$sessionId.idx.new")
                    next.writeText(AudioIndex.format(old.runs, lines, old.kept))
                    if (!next.renameTo(files.index)) {
                        next.delete()
                        throw IOException("The new index didn't replace the old one")
                    }
                } catch (e: IOException) {
                    warn("Where the new lines were said wasn't stored; they are found by their times", e)
                }
            }
        }
    }

    /**
     * Adds a keep entry to the index, which is created if the audio has none. Nothing is written
     * for a recording that has neither an audio file nor an index.
     */
    override suspend fun setKept(sessionId: String, kept: Boolean) {
        withContext(NonCancellable + Dispatchers.IO) {
            val files = files(sessionId) ?: return@withContext
            indexLock.withLock {
                if (!files.wav.isFile && !files.m4a.isFile && !files.index.isFile) return@withLock
                try {
                    files.index.appendText(AudioIndex.keep(kept))
                } catch (e: IOException) {
                    warn("Whether to keep the audio of a recording wasn't stored", e)
                }
            }
        }
    }

    override suspend fun delete(sessionId: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            val files = files(sessionId) ?: return@withContext
            if (!files.delete()) warn("The audio of a deleted recording couldn't be deleted", null)
            changes.tryEmit(Unit)
        }
    }

    override suspend fun deleteAll() {
        withContext(NonCancellable + Dispatchers.IO) {
            dir.listFiles()?.forEach { file ->
                if (!file.delete()) warn("An audio file couldn't be deleted", null)
            }
            changes.tryEmit(Unit)
        }
    }

    /**
     * Reads every recording's audio for its size and age. A file that can't be opened is left out
     * and logged; compressed audio the media framework can't read is listed without its age. An
     * AAC copy that a compression cut short (the app was ended meanwhile) is deleted, unless that
     * compression still runs.
     */
    override suspend fun inventory(): List<AudioFileInfo> = withContext(Dispatchers.IO) {
        val names = dir.list()?.toSet() ?: return@withContext emptyList()
        for (name in names) {
            val leftBy = name.removeSuffix(M4A_IN_PROGRESS).takeIf { it != name } ?: continue
            if (leftBy != compressing && !File(dir, name).delete()) warn("What a compression left couldn't be deleted", null)
        }
        recordingIdsIn(names).mapNotNull { sessionId -> readInfo(sessionId) }
    }

    override suspend fun inventoryOf(sessionId: String): AudioFileInfo? = withContext(Dispatchers.IO) { readInfo(sessionId) }

    /** Reads the folder's sizes only, never a file: no header, index or compressed copy. */
    override suspend fun storedBytes(): Map<String, Long> = withContext(Dispatchers.IO) {
        val names = dir.list()?.toSet() ?: return@withContext emptyMap()
        recordingIdsIn(names).mapNotNull { sessionId ->
            val files = files(sessionId)?.takeIf { holdsAudio(it) } ?: return@mapNotNull null
            sessionId to listOf(files.wav, files.m4a, files.index).sumOf { if (it.isFile) it.length() else 0L }
        }.toMap()
    }

    /** The recordings whose audio [names], the folder's files, hold: a WAV or a compressed copy each. */
    private fun recordingIdsIn(names: Set<String>): Set<String> = names.mapNotNullTo(sortedSetOf()) { name ->
        (name.removeSuffix(".wav").takeIf { it != name } ?: name.removeSuffix(".m4a").takeIf { it != name })
            ?.takeIf { SAFE_ID.matches(it) }
    }

    /** The audio of [sessionId] as the inventory lists it; null without any, or if it can't be read. */
    private fun readInfo(sessionId: String): AudioFileInfo? {
        val files = files(sessionId) ?: return null
        return try {
            infoOf(sessionId, files)
        } catch (e: IOException) {
            warn("The audio of a recording couldn't be read", e)
            null
        }
    }

    private fun infoOf(sessionId: String, files: RecordingAudioFiles): AudioFileInfo? {
        val encoding: AudioEncoding
        val durationMs: Long?
        when {
            files.wav.isFile -> {
                // Read only: find() repairs the header of a capture cut short as the audio is used.
                val dataBytes = Wav.dataBytes(files.wav)
                if (dataBytes == null || dataBytes == 0L) return null
                encoding = AudioEncoding.WAV
                durationMs = Wav.durationMs(dataBytes)
            }
            files.m4a.isFile -> {
                encoding = AudioEncoding.AAC
                durationMs = decoder.durationMs(files.m4a)
            }
            // Deleted since the folder was listed, or never there.
            else -> return null
        }
        val entries = entriesOf(files)
        return AudioFileInfo(
            sessionId = sessionId,
            bytes = listOf(files.wav, files.m4a, files.index).sumOf { if (it.isFile) it.length() else 0L },
            encoding = encoding,
            lastCaptureEndMs = durationMs?.let { entries.lastCaptureEndMs(it) },
            kept = entries.kept
        )
    }

    /**
     * Encodes the WAV as AAC into `<id>.m4a.new`, writes the copy through to the storage, checks
     * with MediaExtractor that it plays as long ([CompressedLength]), puts it in place as
     * `<id>.m4a` and only then deletes the WAV; the index stays as it is. Any failure deletes the
     * copy and leaves the WAV as it was.
     *
     * Audio the owner keeps is not compressed. The keep entry is read before the encoding and
     * again just before the copy takes the WAV's place, under the lock [setKept] takes: "Keep this
     * audio" chosen while the encoder runs, which takes seconds to minutes, still counts. A WAV
     * that grew meanwhile (a capture added to it) stays as well, as the copy lacks what was added.
     */
    override suspend fun compress(sessionId: String): Result<Long> = withContext(NonCancellable + Dispatchers.IO) {
        val files = files(sessionId) ?: return@withContext Result.failure(IOException("Not a recording id"))
        compressLock.withLock {
            compressing = sessionId
            try {
                compressNow(files).onSuccess { freed -> if (freed != 0L) changes.tryEmit(Unit) }
            } finally {
                compressing = null
            }
        }
    }

    private suspend fun compressNow(files: RecordingAudioFiles): Result<Long> {
        if (!files.wav.isFile) return Result.success(0L)
        val copy = files.m4aInProgress
        return try {
            if (entriesOf(files).kept) return Result.success(0L)
            val dataBytes = Wav.repair(files.wav) ?: throw IOException("The audio file is not one Satori wrote")
            if (dataBytes == 0L) throw IOException("The audio holds no sound")
            val wavBytes = Wav.HEADER_BYTES + dataBytes
            copy.delete()
            compressor.encode(files.wav, dataBytes, copy)
            // Through to the storage before the WAV goes: the rename and the delete can reach it
            // before the copy's data does, and a power cut then would leave neither.
            syncToDisk(copy)
            val wavMs = Wav.durationMs(dataBytes)
            val aacMs = compressor.durationMs(copy) ?: throw IOException("The compressed audio can't be read back")
            if (!CompressedLength.matches(wavMs, aacMs)) {
                throw IOException("The compressed audio plays $aacMs ms, its WAV $wavMs ms")
            }
            val aacBytes = copy.length()
            if (!indexLock.withLock { replaceWav(files, copy, wavBytes) }) {
                copy.delete()
                return Result.success(0L)
            }
            info(
                "Compressed the audio of a recording: $wavMs ms of WAV (${mb(wavBytes)} MB) as $aacMs ms " +
                    "of AAC (${mb(aacBytes)} MB)"
            )
            Result.success(wavBytes - aacBytes)
        } catch (e: Exception) {
            // MediaCodec and MediaMuxer fail with IllegalStateException as well as IOException.
            copy.delete()
            warn("The audio of a recording couldn't be compressed; its WAV stays", e)
            Result.failure(e)
        }
    }

    /**
     * Puts the compressed [copy] in the place of the WAV, which was [wavBytes] long as it was
     * encoded, unless the owner keeps the audio by now. Called under [indexLock].
     *
     * @return false when the audio is kept: the WAV stays as it is
     * @throws IOException when the WAV changed since, or a file can't be moved; the WAV then stays
     */
    private fun replaceWav(files: RecordingAudioFiles, copy: File, wavBytes: Long): Boolean {
        if (entriesOf(files).kept) return false
        if (files.wav.length() != wavBytes) throw IOException("The WAV changed while it was compressed")
        if (!copy.renameTo(files.m4a)) throw IOException("The compressed audio didn't take its place")
        if (!files.wav.delete()) {
            // As it was: the WAV alone, which find() would have read before the copy anyway.
            files.m4a.delete()
            throw IOException("The WAV of compressed audio couldn't be deleted")
        }
        return true
    }

    override fun observeChanges(): Flow<Unit> = changes.asSharedFlow()

    /**
     * A WAV of [audio] that the speech recognizer can read, for Transcribe again of compressed
     * audio: decoded into `cache/transcribe/<id>.wav`, 16 kHz mono. The caller deletes it when
     * done. A cancel stops the decoding between two of its buffers. There is only ever one such
     * copy, as the recognizer reads one file at a time (SttRepositoryImpl's lock), so any other in
     * the folder was left by a process that ended while transcribing; it is deleted first.
     *
     * @throws IOException if the audio can't be decoded; nothing is left behind then
     */
    internal suspend fun decodedCopy(audio: RecordingAudio): File {
        // The id names the copy, so it must be one that can name a file.
        files(audio.sessionId) ?: throw IOException("Not a recording id")
        val copy = File(decodedDir, "${audio.sessionId}.wav")
        return withContext(Dispatchers.IO) {
            try {
                decodedDir.mkdirs()
                decodedDir.listFiles()?.forEach { it.delete() }
                decoder.decode(File(audio.path), copy) { !isActive }
                copy
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        }
    }

    private fun entriesOf(files: RecordingAudioFiles): AudioIndex.Entries =
        if (files.index.isFile) AudioIndex.parse(files.index.readText()) else NO_ENTRIES

    private fun indexBytes(files: RecordingAudioFiles): Long = if (files.index.isFile) files.index.length() else 0L

    private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)

    private companion object {
        const val TAG = "SatoriAudio"
        const val DIR = "audio"
        const val DECODED_DIR = "transcribe"
        const val M4A_IN_PROGRESS = ".m4a.new"
        val SAFE_ID = Regex("[A-Za-z0-9-]+")
        val NO_ENTRIES = AudioIndex.Entries(emptyList(), emptyMap())
    }
}
