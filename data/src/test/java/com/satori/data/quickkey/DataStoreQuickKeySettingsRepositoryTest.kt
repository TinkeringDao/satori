package com.satori.data.quickkey

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the repository against a real Preferences DataStore in a temporary folder, opened the way
 * the app opens it: same file name and the same corruption handler ([quickKeyCorruptionHandler]).
 */
class DataStoreQuickKeySettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreQuickKeySettingsRepository

    @Before
    fun setUp() {
        file = File(tmp.root, "quick_key.preferences_pb")
        scope = newScope()
        dataStore = open(scope)
        repository = DataStoreQuickKeySettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + Job()).also { scopes += it }

    /** As preferencesDataStore in DataStoreQuickKeySettingsRepository.kt opens it. */
    private fun open(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = quickKeyCorruptionHandler,
            scope = scope,
        ) { file }

    @Test
    fun `an empty file gives voice notes and stop on a second press`() = runBlocking {
        assertEquals(
            QuickKeySettings(categoryId = "voice-note", stopOnSecondPress = true),
            repository.get()
        )
    }

    @Test
    fun `a press forwarded and the owner's word are stored, and nothing else changes`() = runBlocking {
        assertEquals(null, repository.get().lastPressAtMs)
        assertEquals(false, repository.get().setUpByHand)

        repository.recordPress(1_760_000_000_000L)
        repository.markSetUp()

        assertEquals(
            QuickKeySettings(categoryId = "voice-note", stopOnSecondPress = true, lastPressAtMs = 1_760_000_000_000L, setUpByHand = true),
            repository.get()
        )
        // The next press replaces the time.
        repository.recordPress(1_760_000_060_000L)
        assertEquals(1_760_000_060_000L, repository.get().lastPressAtMs)
    }

    @Test
    fun `the default category is one of the starter categories`() {
        assertTrue(QuickKeySettings.DEFAULT_CATEGORY_ID in CategoryRepository.STARTER_IDS)
    }

    @Test
    fun `keeps what was set`() = runBlocking {
        repository.setCategoryId("meeting")
        repository.setStopOnSecondPress(false)
        assertEquals(QuickKeySettings("meeting", stopOnSecondPress = false), repository.get())
    }

    @Test
    fun `a DataStore opened again on the file reads what was stored`() = runBlocking {
        repository.setCategoryId("idea")
        repository.setStopOnSecondPress(false)
        // Closes the first DataStore. Only one may be open per file, and the new one starts
        // without a cached value, so it has to read the file.
        scope.coroutineContext.job.cancelAndJoin()

        val reopened = DataStoreQuickKeySettingsRepository(open(newScope()))
        assertEquals(QuickKeySettings("idea", stopOnSecondPress = false), reopened.get())
    }

    @Test
    fun `a damaged file gives the defaults and can be written again`() = runBlocking {
        // Before the first read: DataStore reads the file only when first asked.
        file.writeText("not a proto")
        assertEquals(QuickKeySettings(), repository.get())

        repository.setCategoryId("meeting")
        assertEquals("meeting", repository.get().categoryId)
    }

    @Test
    fun `the damaged bytes do fail a DataStore without the handler`() {
        // Shows that the test above depends on the handler, not on bytes that happen to parse.
        val other = File(tmp.root, "no_handler.preferences_pb").apply { writeText("not a proto") }
        val bare = PreferenceDataStoreFactory.create(scope = newScope()) { other }
        assertThrows(CorruptionException::class.java) { runBlocking { bare.data.first() } }
    }

    @Test
    fun `the key records in English or German until Malayalam is set, which is stored as its code and read back`() = runBlocking {
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, repository.get().language)

        repository.setLanguage(RecordingLanguage.MALAYALAM)
        assertEquals(RecordingLanguage.MALAYALAM, repository.get().language)
        assertEquals("ml", dataStore.data.first()[stringPreferencesKey("language")])
        // Opened again, as after a restart.
        scope.coroutineContext.job.cancelAndJoin()
        val reopened = DataStoreQuickKeySettingsRepository(open(newScope()))
        assertEquals(QuickKeySettings("voice-note", stopOnSecondPress = true, language = RecordingLanguage.MALAYALAM), reopened.get())

        // English or German is stored as no language at all.
        reopened.setLanguage(RecordingLanguage.ENGLISH_OR_GERMAN)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, reopened.get().language)
    }

    @Test
    fun `a language this version doesn't know reads as English or German`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("language")] = "ta" }
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, repository.get().language)
    }

    @Test
    fun `a blank stored category falls back to the default`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("category_id")] = "  " }
        assertEquals(QuickKeySettings.DEFAULT_CATEGORY_ID, repository.get().categoryId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a blank category`() {
        runBlocking { repository.setCategoryId(" ") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `settings never hold a blank category`() {
        QuickKeySettings(categoryId = "")
    }

    @Test
    fun `observe emits the current value, then each change`() = runBlocking {
        repository.get() // loaded, so the collector below starts from the stored value
        val seen = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { repository.observe().take(2).toList() }
        }
        repository.setStopOnSecondPress(false)
        assertEquals(
            listOf(QuickKeySettings(), QuickKeySettings(stopOnSecondPress = false)),
            seen.await()
        )
    }
}
