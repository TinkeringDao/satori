package com.satori.ui.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.llm.InsightGroups
import com.satori.domain.usecase.llm.NoteText
import com.satori.domain.usecase.llm.groupInsights
import com.satori.ui.R
import com.satori.ui.design.clockTime
import com.satori.ui.design.formatDuration
import com.satori.ui.design.modeLabelRes
import com.satori.ui.screens.PlaceText
import com.satori.ui.screens.notesTabRes
import com.satori.ui.screens.summaryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A recording as a Markdown file: its name, when and where it was made and how long it is, the
 * notes, then the transcript with the time of each line. Headings follow the app language.
 *
 * The file is built on the phone. It only leaves the phone if the user shares it. It reads the
 * notes the way the details page does: same titles, same summary, same action items, and the
 * place as it reads under the date there: its name ("Near Köln"), or the coordinates of a place
 * without one.
 */
object MarkdownExport {

    const val MIME_TYPE = "text/markdown"

    /**
     * Folder in the app cache for files handed to the share sheet, one subfolder per recording;
     * see `res/xml/export_paths.xml`.
     */
    private const val SHARE_DIR = "exports"

    /** Shared copies are kept this long, so an app that reads its attachment late still finds it. */
    private const val SHARE_KEEP_MS = 24 * 60 * 60 * 1000L

    /** Longest part of a file name taken from the recording's name. */
    private const val MAX_NAME_CHARS = 60

    private val UNSAFE_IN_FILE_NAME = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")

    /** @param place Where it was made, as its page reads it; null when it has no place */
    fun build(context: Context, details: SessionWithDetails, place: String?): String {
        val session = details.session
        val locale = context.resources.configuration.locales[0]
        val created = Date(session.createdAt)
        val date = DateFormat.getDateInstance(DateFormat.LONG, locale).format(created)
        val recordingTitle = title(context, details)

        return buildString {
            appendLine("# $recordingTitle")
            appendLine()
            appendLine("- ${context.getString(R.string.g_export_recorded)}: $date, ${clockTime(context, session.createdAt)}")
            if (session.durationMs > 0L) {
                appendLine("- ${context.getString(R.string.g_export_length)}: ${formatDuration(session.durationMs)}")
            }
            if (place != null) {
                appendLine(PlaceText.markdownItem(context.getString(R.string.g_place_label), place))
            }

            val groups = groupInsights(details.insights, session)
            if (groups.all.isNotEmpty()) {
                appendLine()
                appendLine("## ${context.getString(notesTabRes(session.mode))}")
                groups.all.forEach { insight ->
                    val heading = noteHeading(context, groups, insight, recordingTitle)
                    appendLine()
                    if (heading != null) {
                        appendLine("### $heading")
                        appendLine()
                    }
                    appendLine(summaryText(insight.content).trim())
                    val actionItems = groups.actionItems(insight)
                    if (actionItems.isNotEmpty()) {
                        appendLine()
                        appendLine("**${context.getString(R.string.g_details_action_items)}**")
                        appendLine()
                        actionItems.forEach { appendLine("- [ ] $it") }
                    }
                }
            }

            appendLine()
            appendLine("## ${context.getString(R.string.g_tab_transcript)}")
            appendLine()
            val lines = transcriptLines(details)
            if (lines.isEmpty()) {
                appendLine("_${context.getString(R.string.g_details_empty)}_")
            } else {
                lines.forEachIndexed { index, segment ->
                    if (index > 0) appendLine()
                    appendLine("**${clockTime(context, segment.timestamp)}** ${segment.text.trim()}")
                }
            }
        }
    }

    /** "2026-10-09 22-39 Budget review.md": sorts by date, safe on every file system. */
    fun fileName(context: Context, details: SessionWithDetails): String = "${fileStem(context, details)}.md"

    /**
     * "2026-10-09 22-39 Budget review": the name of the recording's files without the type, also
     * that of its shared audio.
     */
    fun fileStem(context: Context, details: SessionWithDetails): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date(details.session.createdAt))
        val name = title(context, details)
            .replace(UNSAFE_IN_FILE_NAME, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(MAX_NAME_CHARS)
            .trim()
        return if (name.isEmpty()) stamp else "$stamp $name"
    }

    /**
     * Writes the file into the app cache and returns a content URI the share sheet can read.
     *
     * @param place Where it was made, as for [build]
     */
    suspend fun prepareShare(context: Context, details: SessionWithDetails, place: String?): Uri = withContext(Dispatchers.IO) {
        pruneShared(context)
        val dir = File(File(context.cacheDir, SHARE_DIR), details.session.id).apply { mkdirs() }
        val file = File(dir, fileName(context, details))
        file.writeText(build(context, details, place))
        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    /** Deletes shared copies older than [SHARE_KEEP_MS], and folders left empty. */
    suspend fun pruneShared(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles()?.filter { now - it.lastModified() > SHARE_KEEP_MS }?.forEach { it.delete() }
                if (entry.listFiles().isNullOrEmpty()) entry.delete()
            } else if (now - entry.lastModified() > SHARE_KEEP_MS) {
                entry.delete()
            }
        }
    }

    /**
     * Deletes the shared copies of one recording; called when the recording is deleted. Runs to
     * the end even if the screen that asked for it closes meanwhile.
     */
    suspend fun forgetShared(context: Context, sessionId: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            File(File(context.cacheDir, SHARE_DIR), sessionId).deleteRecursively()
        }
    }

    /** The share sheet for a prepared file, a Markdown one unless [mimeType] says otherwise. */
    fun shareIntent(context: Context, uri: Uri, subject: String, chooserTitle: String, mimeType: String = MIME_TYPE): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, subject)
            // The grant travels with the ClipData to the app the user picks.
            clipData = ClipData.newUri(context.contentResolver, subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /**
     * Writes the file into the Downloads folder, where any file manager finds it. It needs no
     * permission because the app makes the file itself. A file of the same name stays; Android
     * gives the new one " (1)" after its name.
     *
     * The file stays hidden from other apps until it is complete (IS_PENDING), and is removed
     * again if writing it fails, so no empty file is left behind.
     *
     * @param place Where it was made, as for [build]
     */
    suspend fun saveToDownloads(context: Context, details: SessionWithDetails, place: String?) {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName(context, details))
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: error("Downloads did not create the file")
            try {
                val stream = resolver.openOutputStream(uri, "wt") ?: error("No output stream for the new file")
                stream.use { it.write(build(context, details, place).toByteArray(Charsets.UTF_8)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    /**
     * The transcript as plain text for the clipboard: a line for each utterance, without times,
     * after the place it was made ([PlaceText.withPlace]) when there is one.
     *
     * @param place Where it was made, as for [build]
     */
    fun plainTranscript(context: Context, details: SessionWithDetails, place: String?): String =
        PlaceText.withPlace(
            label = context.getString(R.string.g_place_label),
            place = place,
            text = transcriptLines(details).joinToString("\n") { it.text.trim() }
        )

    /**
     * The notes as plain text for the clipboard, to paste into a chat or a mail: after the place
     * the recording was made, if it has one ([PlaceText.withPlace]), the notes in the order of
     * the page, each with its heading as in the file, its text, and its action items as "- item",
     * without the file's heading marks and check boxes. A blank line between notes.
     *
     * @param place Where it was made, as for [build]
     */
    fun plainNotes(context: Context, details: SessionWithDetails, place: String?): String {
        val recordingTitle = title(context, details)
        val groups = groupInsights(details.insights, details.session)
        val notes = groups.all
            .map { insight ->
                buildList {
                    noteHeading(context, groups, insight, recordingTitle)?.let(::add)
                    summaryText(insight.content).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    val actionItems = groups.actionItems(insight)
                    if (actionItems.isNotEmpty()) {
                        add(context.getString(R.string.g_details_action_items))
                        actionItems.forEach { add("- $it") }
                    }
                }.joinToString("\n")
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return PlaceText.withPlace(context.getString(R.string.g_place_label), place, notes)
    }

    /**
     * A note's heading: "Summary" for the overall one, with its title if it has one of its own,
     * else that title. Null for a note without one, which follows on without a heading.
     */
    private fun noteHeading(
        context: Context,
        groups: InsightGroups,
        insight: LlmInsight,
        recordingTitle: String
    ): String? {
        val name = NoteText.visibleTitle(insight.title, recordingTitle)
        return when {
            insight in groups.pinned && name != null -> "${context.getString(R.string.g_details_overall)}: $name"
            insight in groups.pinned -> context.getString(R.string.g_details_overall)
            else -> name
        }
    }

    /** The name shown for a recording: its own, or else its mode, as on the home list. */
    fun title(context: Context, details: SessionWithDetails): String =
        details.session.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(modeLabelRes(details.session.mode))

    /** The lines worth exporting, in the order they were spoken. */
    private fun transcriptLines(details: SessionWithDetails): List<TranscriptionSegment> =
        details.segments
            .filter { it.isComplete && it.text.isNotBlank() }
            .sortedBy { it.timestamp }
}
