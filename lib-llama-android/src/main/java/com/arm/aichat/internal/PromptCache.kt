package com.arm.aichat.internal

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The files in which ai_chat.cpp keeps a system prompt's encoded state from one model load to the
 * next, and their upkeep.
 *
 * On the phone's CPU Gemma 4 E2B takes about 3 s to encode a system prompt of about 1,000
 * characters, 1.5-5 s across the app's prompts (150-450 tokens). Each AI step after Stop has its
 * own system prompt, the model is loaded for each finishing run and freed afterwards, and the app
 * encodes the prompt it used last again after each load, so without a file every run encodes the
 * same prompts again: the in-memory reuse in ai_chat.cpp only helps consecutive calls with the
 * same prompt. With a file it is a read of a few MB.
 *
 * A file's name is a hash of what a state depends on that can change between two runs of the
 * same build, so only the setup that wrote a file reads it:
 * - the model file as its weights were loaded ([Model]): its path, length and lastModified, so a
 *   model replaced at the same path differs in one of them;
 * - [buildTag], the app's versionCode and lastUpdateTime: an install changes lastUpdateTime even
 *   when the versionCode stays the same, and an install is what brings a new native build or
 *   llama.cpp version, whose state may not fit an older file;
 * - the load's settings ([LoadSettings.describe]: the KV cache's type and size, the SWA cache,
 *   flash attention, how the weights were read), which a debug build can change from one load to
 *   the next: a state from a q8_0 KV cache or a windowed SWA cache must not meet another context;
 * - the prompt.
 *
 * Left out on purpose: the batch size (512) is a constant in ai_chat.cpp, so changing it is a new
 * build; the thread count changes no byte of a state; and llama.cpp rejects a state whose key and
 * value types, value layout, layer count or cell count don't fit the context anyway. The native
 * side also checks a file's tokens and checksum before it uses one.
 *
 * Plain JVM, and never throws: without a usable cache the prompt is encoded as before.
 *
 * @param dir The cache directory. Android may clear it at any time, which costs one encode.
 * @param maxBytes What [afterUse] keeps the directory within. ai_chat.cpp neither writes nor reads
 *   a file larger than the default (its PROMPT_CACHE_MAX_SIZE).
 */
internal class PromptCache(
    private val dir: File,
    private val buildTag: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /**
     * A model file as its weights were loaded, which is what a state is computed from. A download
     * that lands renames its file over the old one, so the file at a path can change while the
     * old weights are still in memory.
     */
    data class Model(val path: String, val length: Long, val lastModified: Long) {
        companion object {
            /** The file at [path] as it is now; null when it isn't a readable file. */
            fun of(path: String): Model? = try {
                val file = File(path)
                if (file.isFile && file.canRead()) Model(path, file.length(), file.lastModified()) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * The file for [prompt]'s state under [model] loaded with [settings] ([LoadSettings.describe]),
     * creating the directory; null when that fails.
     */
    fun pathFor(model: Model, settings: String, prompt: String): String? = try {
        if (dir.isDirectory || dir.mkdirs()) File(dir, key(model, settings, prompt) + BIN).path else null
    } catch (e: Exception) {
        null
    }

    /**
     * Keeps the directory within [maxBytes] after the file at [path] (from [pathFor]) was used.
     * [path] becomes the most recently used file (its lastModified is set to now), the least
     * recently used state files are deleted first, and [path] itself stays even when it alone is
     * over the cap. A `.tmp` file older than a minute goes too: the native side writes
     * `<path>.tmp` and renames it, so an old one is a save that a process death cut off.
     */
    fun afterUse(path: String) {
        try {
            val now = System.currentTimeMillis()
            val used = File(path).absoluteFile
            used.setLastModified(now)

            val files = dir.listFiles()?.filter { it.isFile } ?: return
            val remaining = mutableListOf<File>()
            for (file in files) {
                val cutOff = file.name.endsWith(TMP) && now - file.lastModified() > STALE_TMP_MS
                if (!cutOff || !file.delete()) remaining += file
            }

            var total = remaining.sumOf { it.length() }
            val oldestFirst = remaining
                .filter { it.name.endsWith(BIN) && it.absoluteFile != used }
                .map { it to it.lastModified() }
                .sortedBy { (_, lastModified) -> lastModified }
            for ((file, _) in oldestFirst) {
                if (total <= maxBytes) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        } catch (e: Exception) {
            // The directory stays as it is until the next use tries again.
        }
    }

    /**
     * Deletes every state and every `.tmp` file, when a recording is deleted. The app's system
     * prompts hold nothing of a recording, but a file's name doesn't tell which prompt it holds,
     * so all of them go; each prompt then costs one encode again. Returns whether none is left.
     */
    fun clear(): Boolean {
        return try {
            // A directory that can't be listed may still hold states
            val files = dir.listFiles() ?: return !dir.isDirectory
            files.filter { it.isFile && (it.name.endsWith(BIN) || it.name.endsWith(TMP)) }
                .map { it.delete() }
                .all { it }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * SHA-256 in hex over the fields, each text preceded by its length in bytes and each number
     * written as 8 bytes, so no two different sets of fields hash the same bytes.
     */
    private fun key(model: Model, settings: String, prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun number(value: Long) = digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            number(bytes.size.toLong())
            digest.update(bytes)
        }
        text(model.path)
        number(model.length)
        number(model.lastModified)
        text(buildTag)
        text(settings)
        text(prompt)
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        /** About 20 states of Gemma 4 E2B's system prompts: 18 KiB a token, 3-8 MB a prompt. */
        const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024
        const val BIN = ".bin"
        const val TMP = ".tmp"
        const val STALE_TMP_MS = 60_000L
    }
}
