package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.model.DownloadFailure
import com.satori.domain.reading.ReaderStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reader's availability for the finisher's wait (design D7), from the first run (R7): present
 * with its files checked, wherever they came from; on its way while its part is planned and neither
 * done nor dropped; not coming otherwise. Its changes follow the reader's part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunReaderAvailabilityTest {

    private val files = FakeFiles()
    private val firstRun = FakeFirstRunCoordinator()
    private val bytes = PART_BYTES.getValue(FirstRunPart.READER)

    private fun availability(io: CoroutineDispatcher) = FirstRunReaderAvailability(files, { firstRun }, io)

    private fun planned(plan: FirstRunPlan) {
        firstRun.snapshot.value = FirstRunSnapshot(plan = plan)
    }

    @Test
    fun `present with its files checked, on its way while planned, not coming when never planned or dropped`() = runTest {
        val availability = availability(StandardTestDispatcher(testScheduler))
        assertEquals(ReaderStatus.NOT_COMING, availability.status())

        planned(FirstRunPlan(emptyList()).withReader(bytes))
        assertEquals(ReaderStatus.ON_ITS_WAY, availability.status())

        // Its files whole but their SHA-256 not read yet: still on its way, as the first run reads it.
        files.checked += FirstRunPart.READER
        files.unread += FirstRunPart.READER
        assertEquals(ReaderStatus.ON_ITS_WAY, availability.status())

        files.unread.clear()
        assertEquals(ReaderStatus.PRESENT, availability.status())

        // Files put on the phone without the part planned count too.
        planned(FirstRunPlan(emptyList()))
        assertEquals(ReaderStatus.PRESENT, availability.status())

        files.checked.clear()
        val dropped = FirstRunPlan(emptyList()).withReader(bytes).failed(FirstRunPart.READER, DownloadFailure.SERVER)
            .failed(FirstRunPart.READER, DownloadFailure.SERVER)
        planned(dropped)
        assertEquals(ReaderStatus.NOT_COMING, availability.status())
    }

    @Test
    fun `its changes follow the reader's part, not every snapshot`() = runTest {
        val availability = availability(StandardTestDispatcher(testScheduler))
        var changes = 0
        availability.changes.onEach { changes++ }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, changes)

        // Another part's progress: nothing for the reader.
        firstRun.snapshot.value = firstRun.snapshot.value.copy(lookedAround = true)
        runCurrent()
        assertEquals(1, changes)

        planned(FirstRunPlan(emptyList()).withReader(bytes))
        runCurrent()
        assertEquals(2, changes)

        planned(FirstRunPlan(emptyList()).withReader(bytes).done(FirstRunPart.READER))
        runCurrent()
        assertEquals(3, changes)

        // Its record went, and the first run planned it again and found it at once: done before
        // and after, and a change all the same.
        firstRun.snapshot.value = firstRun.snapshot.value.copy(readerFound = 1)
        runCurrent()
        assertEquals(4, changes)
    }
}
