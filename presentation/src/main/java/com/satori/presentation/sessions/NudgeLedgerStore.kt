package com.satori.presentation.sessions

import android.content.Context
import android.util.Log
import com.satori.domain.firstrun.NudgeLedger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the nudge ledger ([NudgeLedger]) is kept: one for the app, read once and written as it
 * changes, so a restart neither loses nor repeats a nudge. Each change goes to the field log.
 */
interface NudgeLedgerStore {

    /** The ledger as it stands. */
    val ledger: StateFlow<NudgeLedger>

    /** Changes the ledger with [change] and stores it; a change that changes nothing stores nothing. */
    fun update(change: (NudgeLedger) -> NudgeLedger)

    /**
     * A field log line beside the ledger's own, under the same tag: why the ping came, and what
     * Android's window answered. Ids and counts only. The default logs nothing.
     */
    fun log(line: String) {}
}

/**
 * [NudgeLedgerStore] in SharedPreferences, file [FILE]. The file is small and read synchronously as
 * the store is made; a change is in memory at once and written behind it. The field log gets a line
 * per change under [TAG]: "id=E2B_OFFER state=SHOWN", ids and states only; and the lines [log] is
 * given.
 */
@Singleton
class PrefsNudgeLedgerStore @Inject constructor(
    @ApplicationContext context: Context
) : NudgeLedgerStore {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(NudgeLedger.read(preferences.all))

    override val ledger: StateFlow<NudgeLedger> = state.asStateFlow()

    @Synchronized
    override fun update(change: (NudgeLedger) -> NudgeLedger) {
        val before = state.value
        val after = change(before)
        if (after == before) return
        val editor = preferences.edit()
        for ((key, value) in after.stored()) {
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
        state.value = after
        for ((id, nudgeState) in before.changes(after)) Log.i(TAG, "id=$id state=$nudgeState")
    }

    override fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        /** The SharedPreferences file of the ledger. */
        const val FILE = "nudge_ledger"

        const val TAG = "SatoriNudge"
    }
}
