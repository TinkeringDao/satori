package com.satori.data.finishing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the repository against a real Preferences DataStore in a temporary folder, opened the way
 * the app opens it: same file name and the same corruption handler
 * ([finishingModelCorruptionHandler]). The E2B file is a stand-in path, or null for "not on the phone".
 */
class DataStoreFinishingModelRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private var modelFile: String? = E2B_PATH
    private lateinit var repository: DataStoreFinishingModelRepository

    @Before
    fun setUp() {
        file = File(tmp.root, "finishing_model.preferences_pb")
        scope = newScope()
        repository = DataStoreFinishingModelRepository(open(scope)) { modelFile }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + Job()).also { scopes += it }

    /** As preferencesDataStore in DataStoreFinishingModelRepository.kt opens it. */
    private fun open(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = finishingModelCorruptionHandler,
            scope = scope,
        ) { file }

    @Test
    fun `E2B is switched on until it is switched off`() = runBlocking {
        assertTrue(repository.observeEnabled().first())
        assertEquals(E2B_PATH, repository.enabledModelPath())
    }

    @Test
    fun `switched off, the live model keeps the work even with the file on the phone`() = runBlocking {
        repository.setEnabled(false)

        assertFalse(repository.observeEnabled().first())
        assertNull(repository.enabledModelPath())
    }

    @Test
    fun `switched on without the file on the phone, the live model keeps the work`() = runBlocking {
        modelFile = null

        assertTrue(repository.observeEnabled().first())
        assertNull(repository.enabledModelPath())
    }

    @Test
    fun `switched on again, E2B takes the work back`() = runBlocking {
        repository.setEnabled(false)
        repository.setEnabled(true)

        assertEquals(E2B_PATH, repository.enabledModelPath())
    }

    @Test
    fun `a DataStore opened again on the file reads what was stored`() = runBlocking {
        repository.setEnabled(false)
        // Closes the first DataStore: only one may be open per file, and the new one has to read it.
        scope.coroutineContext.job.cancelAndJoin()

        val reopened = DataStoreFinishingModelRepository(open(newScope())) { modelFile }
        assertFalse(reopened.observeEnabled().first())
    }

    @Test
    fun `a damaged file gives the default and can be written again`() = runBlocking {
        // Before the first read: DataStore reads the file only when first asked.
        file.writeText("not a proto")
        assertTrue(repository.observeEnabled().first())

        repository.setEnabled(false)
        assertFalse(repository.observeEnabled().first())
    }

    private companion object {
        const val E2B_PATH = "/storage/models/gemma-4-E2B_q4_0-it.gguf"
    }
}
