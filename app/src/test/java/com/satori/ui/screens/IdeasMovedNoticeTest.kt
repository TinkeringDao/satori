package com.satori.ui.screens

import androidx.compose.runtime.snapshots.Snapshot
import com.satori.ui.design.NoticeResult
import com.satori.ui.design.NoticeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** When home says how many to-dos the one-time move put among the ideas. */
class IdeasMovedNoticeTest {

    /** The notice left by the move, taken once as IdeasNotice.takeMoved takes it. */
    private var left: Int? = 4

    private val take = { left.also { left = null } }

    @Test
    fun `an unlocked phone takes the notice at once`() = runBlocking {
        var looks = 0

        assertEquals(4, takeOnceUnlocked(isLocked = { looks++; false }, take = take, pollMs = 1L))
        assertEquals(1, looks)
        assertNull(takeOnceUnlocked(isLocked = { false }, take = take, pollMs = 1L))
    }

    @Test
    fun `an unlock that Android reports after home has started still takes it`() = runBlocking {
        // The fingerprint unlock starts home a few looks before the phone reads as unlocked.
        var looks = 0

        assertEquals(4, takeOnceUnlocked(isLocked = { looks++ < 3 }, take = take, pollMs = 1L))
        assertEquals(4, looks)
    }

    @Test
    fun `home stopped behind the lock screen leaves the notice for the next start`() = runBlocking {
        val behind = launch(start = CoroutineStart.UNDISPATCHED) {
            takeOnceUnlocked(isLocked = { true }, take = take, pollMs = 10_000L)
        }
        behind.cancelAndJoin()

        assertEquals(4, left)
        assertEquals(4, takeOnceUnlocked(isLocked = { false }, take = take, pollMs = 1L))
    }

    @Test
    fun `the notice waits for one on show, such as a Done with its Undo, to end`() = runBlocking {
        val notices = NoticeState()
        val done = async(start = CoroutineStart.UNDISPATCHED) { notices.show("Done", "Undo") }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { awaitNoNotice(notices) }

        yield()
        assertFalse(waiting.isCompleted)

        notices.current?.performAction()
        assertEquals(NoticeResult.ActionPerformed, done.await())
        // Compose hands on changes of state as a frame does.
        Snapshot.sendApplyNotifications()
        waiting.join()
    }
}
