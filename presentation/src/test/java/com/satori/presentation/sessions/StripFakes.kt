package com.satori.presentation.sessions

import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.NudgeLedger
import com.satori.domain.firstrun.NudgeState
import com.satori.domain.model.SessionListItem
import com.satori.domain.repository.TranscriptionRepository
import com.satori.presentation.ideas.ListedRecordings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Test doubles for home's status strip (Part Q4), beside home's own in HomeFakes.kt.

/**
 * A nudge ledger kept in [stored], as its SharedPreferences file keeps it: a store made again over
 * the same map is the app started again. [logged] has what the field log got for each change, in
 * order, and [lines] the lines given beside them.
 */
class StoredNudgeLedger(val stored: MutableMap<String, Any> = mutableMapOf()) : NudgeLedgerStore {

    private val state = MutableStateFlow(NudgeLedger.read(stored))
    override val ledger: StateFlow<NudgeLedger> = state

    val logged = mutableListOf<Pair<NudgeId, NudgeState>>()
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }

    override fun update(change: (NudgeLedger) -> NudgeLedger) {
        val before = state.value
        val after = change(before)
        if (after == before) return
        stored.putAll(after.stored())
        state.value = after
        logged += before.changes(after)
    }
}

/** A phone whose facts the test sets ([facts]), on the test's clock ([clock], epoch ms). */
class TestPhone(private val clock: () -> Long, @Volatile var facts: PhoneFacts = PhoneFacts()) : HomePhone {

    /** Android's notification window was asked for, by the ping offer. */
    var asked = false
        private set

    override fun nowMs(): Long = clock()

    override suspend fun read(): PhoneFacts = facts.copy(notificationsAsked = facts.notificationsAsked || asked)

    override fun markNotificationsAsked() {
        asked = true
    }

    /** The shared ask flag's changes, which a test sends as the to-do reminders' sheet answers. */
    val askChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun notificationAskChanges(): Flow<Unit> = askChanges
}

/** The recordings home lists, which the test changes as recordings are saved and finished. */
class ChangingRecordings(initial: List<SessionListItem> = emptyList()) : TranscriptionRepository by ListedRecordings(emptyList()) {
    val list = MutableStateFlow(initial)

    override fun getAllSessions(): Flow<List<SessionListItem>> = list
}
