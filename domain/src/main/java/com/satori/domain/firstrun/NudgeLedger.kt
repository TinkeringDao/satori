package com.satori.domain.firstrun

/**
 * One nudge's line in the [NudgeLedger].
 *
 * @property shownAtMs When it first reached the screen (epoch ms), from which it goes after a day;
 *   null while it never has
 */
data class NudgeEntry(val state: NudgeState = NudgeState.NEVER_SHOWN, val shownAtMs: Long? = null)

/**
 * The nudge ledger (design 4.1): where each of home's one-time offers, and the first notes'
 * confirmation, stands, one entry per [NudgeId]. A nudge is [NudgeState.SHOWN] from the first time
 * it reaches the screen, and stays on show until it is answered or a day has passed
 * ([SHOWN_FOR_MS]); after that it never shows again, whatever was done with it. It is stored as it
 * changes ([stored]), so a restart neither loses nor repeats one.
 *
 * @property malayalamRetried The strip's one Try again for a Malayalam the first run dropped was
 *   tapped: the strip doesn't offer it again, also if it is dropped again; Settings keeps its
 *   download
 * @property readerRetried The same strip's one Try again for a reader the first run dropped (0.8.0):
 *   the strip doesn't offer it again for the reader either
 */
data class NudgeLedger(
    val entries: Map<NudgeId, NudgeEntry> = emptyMap(),
    val malayalamRetried: Boolean = false,
    val readerRetried: Boolean = false
) {
    /** Where [id] stands. */
    fun state(id: NudgeId): NudgeState = entries[id]?.state ?: NudgeState.NEVER_SHOWN

    /**
     * Whether [id] may be on show at [nowMs]: it never was, or it is on show since less than
     * [SHOWN_FOR_MS] ([onShow]). Answered, gone or faded, it may not.
     */
    fun open(id: NudgeId, nowMs: Long): Boolean = state(id) == NudgeState.NEVER_SHOWN || onShow(id, nowMs)

    /** Whether [id] reached the screen and is on show at [nowMs], neither answered nor faded. */
    fun onShow(id: NudgeId, nowMs: Long): Boolean = state(id) == NudgeState.SHOWN && !fadedBy(id, nowMs)

    /** [id] reached the screen at [atMs]; one that did before keeps its first time. */
    fun shown(id: NudgeId, atMs: Long): NudgeLedger =
        if (state(id) == NudgeState.NEVER_SHOWN) with(id, NudgeEntry(NudgeState.SHOWN, atMs)) else this

    /**
     * [id] was answered at [atMs]: its offer taken ([accepted]), or Not now. A tap can come before
     * the strip counted as seen, so a nudge never shown counts as shown then. One answered or gone
     * stays as it was.
     */
    fun answered(id: NudgeId, accepted: Boolean, atMs: Long): NudgeLedger {
        val state = state(id)
        if (state != NudgeState.NEVER_SHOWN && state != NudgeState.SHOWN) return this
        val answer = if (accepted) NudgeState.ACCEPTED else NudgeState.DECLINED
        return with(id, NudgeEntry(answer, entries[id]?.shownAtMs ?: atMs))
    }

    /**
     * [id], on show, goes without an answer: the first notes' confirmation, once a recording was
     * opened. With [unseen] one that never reached the screen goes too, as what it would say was
     * seen another way: the first notes, written and then opened while the confirmation waited
     * behind the ping. One answered or gone stays as it was.
     */
    fun gone(id: NudgeId, unseen: Boolean = false): NudgeLedger {
        val entry = entries[id] ?: NudgeEntry()
        val goes = entry.state == NudgeState.SHOWN || (unseen && entry.state == NudgeState.NEVER_SHOWN)
        return if (goes) with(id, entry.copy(state = NudgeState.GONE)) else this
    }

    /** Every nudge on show for [SHOWN_FOR_MS] by [nowMs] is gone. */
    fun faded(nowMs: Long): NudgeLedger =
        copy(entries = entries.mapValues { (id, entry) -> if (fadedBy(id, nowMs)) entry.copy(state = NudgeState.GONE) else entry })

    /** When the first nudge on show fades (epoch ms), or null while none is on show. */
    val nextFadeAtMs: Long?
        get() = entries.values.filter { it.state == NudgeState.SHOWN }.mapNotNull { it.shownAtMs }.minOrNull()?.plus(SHOWN_FOR_MS)

    /** The strip's Try again for a dropped Malayalam was used. */
    fun malayalamRetryUsed(): NudgeLedger = copy(malayalamRetried = true)

    /** The strip's Try again for a dropped reader was used. */
    fun readerRetryUsed(): NudgeLedger = copy(readerRetried = true)

    /** The nudges whose state [after] changed, with their new state, in [NudgeId]'s order: what the field log says. */
    fun changes(after: NudgeLedger): List<Pair<NudgeId, NudgeState>> =
        NudgeId.entries.filter { state(it) != after.state(it) }.map { it to after.state(it) }

    /**
     * The ledger as it is stored: for each nudge that has an entry, its state's name under the id's
     * name and, once shown, the time under the id's name with [SHOWN_AT]; [MALAYALAM_RETRIED] and
     * [READER_RETRIED].
     */
    fun stored(): Map<String, Any> = buildMap {
        for ((id, entry) in this@NudgeLedger.entries) {
            put(id.name, entry.state.name)
            entry.shownAtMs?.let { put(id.name + SHOWN_AT, it) }
        }
        put(MALAYALAM_RETRIED, malayalamRetried)
        put(READER_RETRIED, readerRetried)
    }

    /** A nudge on show without the time it was shown, which nothing writes, counts as faded: it goes rather than stays for ever. */
    private fun fadedBy(id: NudgeId, nowMs: Long): Boolean {
        val entry = entries[id] ?: return false
        if (entry.state != NudgeState.SHOWN) return false
        val shownAt = entry.shownAtMs ?: return true
        return nowMs - shownAt >= SHOWN_FOR_MS
    }

    private fun with(id: NudgeId, entry: NudgeEntry): NudgeLedger = copy(entries = entries + (id to entry))

    companion object {
        /** How long a nudge stays on show without an answer (ms): a day. */
        const val SHOWN_FOR_MS = 24 * 60 * 60 * 1_000L

        /** The stored key of [malayalamRetried]. */
        const val MALAYALAM_RETRIED = "malayalam_retried"

        /** The stored key of [readerRetried]. */
        const val READER_RETRIED = "reader_retried"

        /** After a nudge's id, the stored key of when it was shown. */
        const val SHOWN_AT = "_shown_at"

        /**
         * The ledger [stored] gave. A state or an id it doesn't know, one a later version wrote,
         * reads as never shown.
         */
        fun read(values: Map<String, *>): NudgeLedger = NudgeLedger(
            entries = NudgeId.entries.mapNotNull { id ->
                val state = NudgeState.entries.firstOrNull { it.name == values[id.name] } ?: return@mapNotNull null
                id to NudgeEntry(state, values[id.name + SHOWN_AT] as? Long)
            }.toMap(),
            malayalamRetried = values[MALAYALAM_RETRIED] as? Boolean ?: false,
            readerRetried = values[READER_RETRIED] as? Boolean ?: false
        )
    }
}
