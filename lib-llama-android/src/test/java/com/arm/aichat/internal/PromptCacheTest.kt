package com.arm.aichat.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The description of the settings Gemma 3 1B and Gemma 4 E2B load with since 0.6.1, a release build's too. */
private val TODAY = LoadSettings.FAST.describe(directIoApplies = false)

class PromptCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()
    private lateinit var dir: File
    private lateinit var modelFile: File
    private lateinit var model: PromptCache.Model

    @Before
    fun setUp() {
        dir = File(tmp.root, "llm-prompts")
        modelFile = tmp.newFile("model.gguf").apply {
            writeBytes(ByteArray(64))
            setLastModified(now - 3_600_000)
        }
        model = PromptCache.Model.of(modelFile.path)!!
    }

    /** A file of [bytes] bytes in the cache directory, last used [ageMs] ago. */
    private fun cached(name: String, bytes: Int, ageMs: Long): File = File(dir, name).apply {
        dir.mkdirs()
        writeBytes(ByteArray(bytes))
        setLastModified(now - ageMs)
    }

    @Test
    fun `the model is its file's path, length and lastModified as it is now`() {
        assertEquals(PromptCache.Model(modelFile.path, 64, modelFile.lastModified()), model)
        assertEquals(model, PromptCache.Model.of(modelFile.path))

        modelFile.appendBytes(ByteArray(1))
        modelFile.setLastModified(model.lastModified)
        assertNotEquals(model, PromptCache.Model.of(modelFile.path))

        val longer = PromptCache.Model.of(modelFile.path)
        modelFile.setLastModified(now - 60_000)
        assertNotEquals(longer, PromptCache.Model.of(modelFile.path))
    }

    @Test
    fun `a model file that is missing or not a file is no model`() {
        assertNull(PromptCache.Model.of(File(tmp.root, "gone.gguf").path))
        assertNull(PromptCache.Model.of(tmp.newFolder("models").path))
    }

    @Test
    fun `a model file that can't be read is no model`() {
        modelFile.setReadable(false)
        try {
            assumeFalse("readable anyway (run as root)", modelFile.canRead())
            assertNull(PromptCache.Model.of(modelFile.path))
        } finally {
            modelFile.setReadable(true)
        }
    }

    @Test
    fun `the same inputs give the same file, in the directory it creates`() {
        val path = PromptCache(dir, "6-1000").pathFor(model, TODAY, "Write the notes.")

        assertNotNull(path)
        assertEquals(path, PromptCache(dir, "6-1000").pathFor(model.copy(), TODAY, "Write the notes."))
        assertTrue(dir.isDirectory)
        assertEquals(dir, File(path!!).parentFile)
        assertTrue(File(path).name.matches(Regex("[0-9a-f]{64}\\.bin")))
        assertFalse(File(path).exists())
    }

    @Test
    fun `a changed prompt, build tag, model path, model length or model lastModified gives another file`() {
        val cache = PromptCache(dir, "6-1000")
        val path = cache.pathFor(model, TODAY, "Write the notes.")

        assertNotEquals(path, cache.pathFor(model, TODAY, "Write the notes!"))
        assertNotEquals(path, PromptCache(dir, "6-2000").pathFor(model, TODAY, "Write the notes."))
        assertNotEquals(path, PromptCache(dir, "7-1000").pathFor(model, TODAY, "Write the notes."))
        assertNotEquals(path, cache.pathFor(model.copy(path = "/models/other.gguf"), TODAY, "Write the notes."))
        assertNotEquals(path, cache.pathFor(model.copy(length = model.length + 1), TODAY, "Write the notes."))
        assertNotEquals(path, cache.pathFor(model.copy(lastModified = model.lastModified + 1), TODAY, "Write the notes."))
    }

    @Test
    fun `other load settings give another file, as a debug build can change them between loads`() {
        val cache = PromptCache(dir, "6-1000")
        val path = cache.pathFor(model, TODAY, "Write the notes.")

        assertEquals(path, cache.pathFor(model, LoadSettings.FAST.copy().describe(directIoApplies = false), "Write the notes."))
        assertNotEquals(path, cache.pathFor(model, LoadSettings.FAST.copy(kvQ8 = false).describe(directIoApplies = false), "Write the notes."))
        assertNotEquals(path, cache.pathFor(model, LoadSettings.FAST.copy(swaFull = true).describe(directIoApplies = false), "Write the notes."))
        assertNotEquals(path, cache.pathFor(model, LoadSettings.FAST.copy(nCtx = 2048).describe(directIoApplies = false), "Write the notes."))
        assertNotEquals(path, cache.pathFor(model, LoadSettings.FAST.copy(cold = true).describe(directIoApplies = false), "Write the notes."))
        // 0.6.0's settings, a state of a full SWA cache and an F16 one, never meet today's.
        assertNotEquals(path, cache.pathFor(model, LoadSettings.UNPACED.describe(directIoApplies = false), "Write the notes."))
    }

    @Test
    fun `fields don't run into each other`() {
        assertNotEquals(
            PromptCache(dir, "6-10").pathFor(model, TODAY, "00 notes"),
            PromptCache(dir, "6-1000").pathFor(model, TODAY, " notes"),
        )
    }

    @Test
    fun `afterUse keeps the most recently used files within the cap`() {
        val oldest = cached("a.bin", 40, ageMs = 40 * 60_000)
        val older = cached("b.bin", 40, ageMs = 30 * 60_000)
        val old = cached("c.bin", 40, ageMs = 20 * 60_000)
        val recent = cached("d.bin", 40, ageMs = 10 * 60_000)
        val used = cached("e.bin", 40, ageMs = 0)

        PromptCache(dir, "6-1000", maxBytes = 100).afterUse(used.path)

        assertFalse(oldest.exists())
        assertFalse(older.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(used.exists())
    }

    @Test
    fun `afterUse marks the file just used as the newest and keeps it, even alone over the cap`() {
        val used = cached("a.bin", 50, ageMs = 40 * 60_000)
        val newer = cached("b.bin", 5, ageMs = 10 * 60_000)

        PromptCache(dir, "6-1000", maxBytes = 10).afterUse(used.path)

        assertTrue(used.exists())
        assertTrue(used.lastModified() >= now)
        assertFalse(newer.exists())
    }

    @Test
    fun `afterUse leaves a directory within the cap as it is`() {
        val files = listOf(
            cached("a.bin", 30, ageMs = 40 * 60_000),
            cached("b.bin", 30, ageMs = 30 * 60_000),
            cached("c.bin", 30, ageMs = 0),
        )

        PromptCache(dir, "6-1000", maxBytes = 90).afterUse(files.last().path)

        assertTrue(files.all { it.exists() })
    }

    @Test
    fun `afterUse removes tmp files older than a minute, not newer ones, and counts those it keeps`() {
        val cutOff = cached("a.bin.tmp", 40, ageMs = 2 * 60_000)
        val writing = cached("b.bin.tmp", 40, ageMs = 10_000)
        val old = cached("c.bin", 40, ageMs = 30 * 60_000)
        val used = cached("d.bin", 40, ageMs = 0)

        PromptCache(dir, "6-1000", maxBytes = 100).afterUse(used.path)

        assertFalse(cutOff.exists())
        assertTrue(writing.exists())
        assertFalse(old.exists())
        assertTrue(used.exists())
    }

    @Test
    fun `afterUse trims states only, even when a save under way is the oldest file`() {
        val writing = cached("w.bin.tmp", 40, ageMs = 50_000)
        val old = cached("a.bin", 40, ageMs = 40_000)
        val recent = cached("b.bin", 40, ageMs = 30_000)
        val used = cached("c.bin", 40, ageMs = 0)

        PromptCache(dir, "6-1000", maxBytes = 120).afterUse(used.path)

        assertTrue(writing.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(used.exists())
    }

    @Test
    fun `afterUse after a save that didn't happen still trims the directory`() {
        val old = cached("a.bin", 40, ageMs = 30 * 60_000)
        val recent = cached("b.bin", 40, ageMs = 10 * 60_000)

        PromptCache(dir, "6-1000", maxBytes = 50).afterUse(File(dir, "c.bin").path)

        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertFalse(File(dir, "c.bin").exists())
    }

    @Test
    fun `clear deletes every state and tmp file and nothing else, and the cache goes on`() {
        val states = listOf(cached("a.bin", 40, ageMs = 0), cached("b.bin", 40, ageMs = 60_000))
        val writing = cached("c.bin.tmp", 40, ageMs = 0)
        val other = cached("notes.md", 40, ageMs = 0)
        val cache = PromptCache(dir, "6-1000")

        assertTrue(cache.clear())

        assertTrue(states.none { it.exists() })
        assertFalse(writing.exists())
        assertTrue(other.exists())
        assertNotNull(cache.pathFor(model, TODAY, "Write the notes."))
    }

    @Test
    fun `clear without a directory has nothing to delete`() {
        assertTrue(PromptCache(dir, "6-1000").clear())
        assertTrue(PromptCache(File(tmp.newFile("not-a-dir"), "llm-prompts"), "6-1000").clear())
    }

    @Test
    fun `clear says so when a state stays, and throws nothing`() {
        val state = cached("a.bin", 40, ageMs = 0)
        dir.setWritable(false)
        try {
            assumeFalse("writable anyway (run as root)", dir.canWrite())

            assertFalse(PromptCache(dir, "6-1000").clear())
            assertTrue(state.exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `clear says so when the directory can't be listed`() {
        val state = cached("a.bin", 40, ageMs = 0)
        dir.setReadable(false)
        try {
            assumeFalse("readable anyway (run as root)", dir.canRead())

            assertFalse(PromptCache(dir, "6-1000").clear())
        } finally {
            dir.setReadable(true)
        }
        assertTrue(state.exists())
    }

    @Test
    fun `nothing throws when the directory can't be created`() {
        val blocked = PromptCache(File(tmp.newFile("not-a-dir"), "llm-prompts"), "6-1000")

        assertNull(blocked.pathFor(model, TODAY, "Write the notes."))
        blocked.afterUse(File(tmp.root, "not-a-dir/llm-prompts/a.bin").path)
    }

    @Test
    fun `nothing throws when the directory can't be written`() {
        val old = cached("a.bin", 40, ageMs = 30 * 60_000)
        val used = cached("b.bin", 40, ageMs = 0)
        val cache = PromptCache(dir, "6-1000", maxBytes = 50)
        dir.setWritable(false)
        try {
            assumeFalse("writable anyway (run as root)", dir.canWrite())

            assertNotNull(cache.pathFor(model, TODAY, "Write the notes."))
            cache.afterUse(used.path)

            assertTrue(old.exists())
        } finally {
            dir.setWritable(true)
        }
    }
}
