package com.satori.data.datasource

import android.util.Log
import com.satori.data.config.PinnedModelFile
import java.io.File
import java.io.IOException

/**
 * A model folder's record of the files found with their pinned SHA-256 (0.8.0, design R7): a line a
 * file, its name, its size, its last change (ms) and the SHA-256 it was found with, in [NAME] in the
 * folder. A file counts as checked only while it is as it was then ([covers]): a file written since,
 * or one of another size, is read again. The record holds no model's contents, and goes with its
 * folder.
 *
 * Written by the reader's download, as each file it fetched is found the pinned one, and by the one
 * read of the files that came another way ([ModelDownloadManager.verifyReaderModel]); a write is a
 * new file renamed over the old one, so a process that ends meanwhile leaves the old record or the
 * new one, whole. Not thread-safe: its callers hold their own lock.
 */
internal object VerifiedFiles {

    /** The record's file name in the model's folder. */
    const val NAME = ".verified"

    private const val TAG = "ModelDownloadManager"

    /** One line of the record: the file as it was when it was found with [sha256]. */
    data class Entry(val bytes: Long, val lastModified: Long, val sha256: String)

    /** The record of [dir], by file name; empty when there is none or it can't be read. */
    fun read(dir: File): Map<String, Entry> {
        val file = File(dir, NAME)
        if (!file.isFile) return emptyMap()
        return try {
            file.readLines().mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 4) return@mapNotNull null
                val bytes = fields[1].toLongOrNull() ?: return@mapNotNull null
                val lastModified = fields[2].toLongOrNull() ?: return@mapNotNull null
                fields[0] to Entry(bytes, lastModified, fields[3])
            }.toMap()
        } catch (e: IOException) {
            Log.w(TAG, "The record of checked files in ${dir.name} could not be read (${e.javaClass.simpleName})")
            emptyMap()
        }
    }

    /**
     * Whether [record] holds [file], of [dir], as it is now: the same size and last change as when
     * it was found with its SHA-256, and that SHA-256 its pin.
     */
    fun covers(record: Map<String, Entry>, dir: File, file: PinnedModelFile): Boolean {
        val entry = record[file.spec.name] ?: return false
        val onDisk = File(dir, file.spec.name)
        return onDisk.isFile && entry.sha256 == file.sha256 && entry.bytes == onDisk.length() && entry.lastModified == onDisk.lastModified()
    }

    /**
     * Adds [file] of [dir], as it is now, found with [sha256], to the record, in place of an older
     * line of it. A record that can't be written is logged and left: the file is read again at the
     * next check.
     */
    fun add(dir: File, file: PinnedModelFile, sha256: String) {
        val onDisk = File(dir, file.spec.name)
        val record = read(dir) + (file.spec.name to Entry(onDisk.length(), onDisk.lastModified(), sha256))
        val text = record.entries.sortedBy { it.key }.joinToString("") { (name, entry) ->
            "$name\t${entry.bytes}\t${entry.lastModified}\t${entry.sha256}\n"
        }
        val target = File(dir, NAME)
        val partial = File(dir, "$NAME.partial")
        try {
            partial.writeText(text)
            if (!partial.renameTo(target)) {
                target.writeText(text)
                partial.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "The record of checked files in ${dir.name} could not be written (${e.javaClass.simpleName})")
        }
    }
}
