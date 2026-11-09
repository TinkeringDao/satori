package com.satori.ui.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.satori.domain.model.AudioEncoding
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.SessionWithDetails
import java.io.File

/**
 * Shares the kept audio of a recording as the file it is kept in, a WAV, or an MPEG-4 audio file
 * (.m4a) once it is compressed, through the FileProvider of the Markdown export. The file is
 * handed over where it is (files/audio, res/xml/export_paths.xml), read-only and to the app the
 * user picks, under the name the recording's Markdown file has: a long recording is never copied,
 * and nothing is left behind to clean up.
 */
object AudioShare {

    /** The type of a WAV file, the audio as the capture writes it. */
    const val MIME_TYPE = "audio/wav"

    /** The type of compressed audio: AAC in an MPEG-4 file. */
    const val AAC_MIME_TYPE = "audio/mp4"

    /** The type to share [audio] as. */
    fun mimeType(audio: RecordingAudio): String = when (audio.encoding) {
        AudioEncoding.WAV -> MIME_TYPE
        AudioEncoding.AAC -> AAC_MIME_TYPE
    }

    /** The name [audio] is shared under: the recording's [stem] and its file's extension. */
    fun fileName(stem: String, audio: RecordingAudio): String = when (audio.encoding) {
        AudioEncoding.WAV -> "$stem.wav"
        AudioEncoding.AAC -> "$stem.m4a"
    }

    /** The content URI of [audio], named after the recording, e.g. "2026-10-09 22-39 Budget review.wav". */
    fun uri(context: Context, details: SessionWithDetails, audio: RecordingAudio): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.exports",
            File(audio.path),
            fileName(MarkdownExport.fileStem(context, details), audio)
        )
}
