package com.satori.presentation.firstrun

import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.reading.ReaderAvailability
import com.satori.domain.reading.ReaderStatus
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's availability for the finisher of recordings (design D7, R5's port), as the first
 * run has it (design R7):
 * - [ReaderStatus.PRESENT] once its files are on the phone and checked ([FirstRunFiles.checked]:
 *   whole, and each found once with its pinned SHA-256), wherever they came from;
 * - [ReaderStatus.ON_ITS_WAY] while the first run has its part planned and neither done nor dropped
 *   ([com.satori.domain.firstrun.FirstRunSnapshot.readerPending]): it may wait for Wi-Fi, for the AI
 *   model ahead of it, or for AI notes to be switched on. Its files on the phone whose SHA-256 is
 *   still to be read are on their way too while the part is planned: the first run reads it before
 *   it would download anything;
 * - [ReaderStatus.NOT_COMING] otherwise: never planned (Malayalam not wanted, or no Malayalam model
 *   on the phone), or dropped after two failed downloads, until the status strip's Try again.
 *
 * [changes] follows the reader's part in the plan, so a job that waits for it goes on as the part
 * is done or dropped, and each time the first run finds the reader's files
 * ([com.satori.domain.firstrun.FirstRunSnapshot.readerFound]), also when the part was done before
 * and after (a part done whose record went, planned again and found at once: the snapshots between
 * may never be seen); files put on the phone another way are seen at the finisher's own checks,
 * every 30 s while a job waits.
 *
 * The coordinator is asked lazily: it is made with the process, and the finisher, which asks this,
 * is made for the first time before the coordinator has started.
 */
@Singleton
class FirstRunReaderAvailability internal constructor(
    private val files: FirstRunFiles,
    private val firstRun: () -> FirstRunCoordinator,
    private val io: CoroutineDispatcher
) : ReaderAvailability {

    @Inject constructor(files: FirstRunFiles, firstRun: Lazy<FirstRunCoordinator>) : this(files, firstRun::get, Dispatchers.IO)

    override suspend fun status(): ReaderStatus = withContext(io) {
        when {
            files.checked(FirstRunPart.READER) -> ReaderStatus.PRESENT
            firstRun().state.value.readerPending -> ReaderStatus.ON_ITS_WAY
            else -> ReaderStatus.NOT_COMING
        }
    }

    override val changes: Flow<Unit>
        get() = firstRun().state.map { it.plan.item(FirstRunPart.READER)?.status to it.readerFound }.distinctUntilChanged().map { }
}
