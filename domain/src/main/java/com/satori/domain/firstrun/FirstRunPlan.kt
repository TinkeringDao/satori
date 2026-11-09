package com.satori.domain.firstrun

import com.satori.domain.model.DownloadFailure

/**
 * One download the first run plans.
 *
 * @property beforeFirstRecording Part of the setup: Get ready's button downloads it and Getting
 *   ready waits for it. The others download after the first recording is saved.
 * @property droppable A part that may not hold up the first recording: it is dropped after
 *   [FirstRunPlan.DROP_AFTER] failures that are not the network's, and offered again later
 */
enum class FirstRunPart(val beforeFirstRecording: Boolean, val droppable: Boolean) {
    /** Speech recognition (Parakeet v3), which every recording needs. */
    SPEECH(beforeFirstRecording = true, droppable = false),

    /** The Malayalam speech model, when Get ready's switch is on. */
    MALAYALAM(beforeFirstRecording = true, droppable = true),

    /** The AI model behind AI notes, the one that suits the phone (Gemma 3 1B on the owner's). */
    AI_MODEL(beforeFirstRecording = false, droppable = false),

    /** The bigger AI model (Gemma 4 E2B), only once its offer was taken. */
    BIGGER_AI_MODEL(beforeFirstRecording = false, droppable = false),

    /**
     * The reader (0.8.0, design D13): IndicTrans2, which reads a Malayalam recording's lines into
     * English for its AI steps. A part of its own, after the first recording and after the AI
     * model, planned only while Malayalam is wanted; droppable, so a reader that fails twice is
     * dropped alone, and the Malayalam speech model never goes with it.
     */
    READER(beforeFirstRecording = false, droppable = true);

    /** One of the AI models, which only AI notes use: neither downloads while AI notes are off. */
    val aiModel: Boolean get() = this == AI_MODEL || this == BIGGER_AI_MODEL

    /**
     * A part only AI notes use, which doesn't download while they are off ([FirstRunQueue.next]): the
     * AI models, and the reader, which reads Malayalam for the AI steps alone.
     */
    val forAiNotes: Boolean get() = aiModel || this == READER
}

/**
 * What the first run downloads, in order: speech recognition, then Malayalam when it is wanted,
 * before the first recording; the AI model once the first recording is saved, and after it the
 * reader when Malayalam is wanted (0.8.0); the bigger AI model once its offer was taken. One writer,
 * the first run's coordinator, changes it with the functions here and stores it; the download
 * queue takes [next], the screens read it.
 *
 * @property items The parts, in the order they download
 */
data class FirstRunPlan(val items: List<Item>) {

    /**
     * One part of the plan.
     *
     * @property bytes Its size, from the registry
     * @property damaged How many times in a row it arrived damaged (its SHA-256 or its length was
     *   wrong, [DownloadFailure.OTHER])
     * @property serverErrors How many times in a row the server answered it with an error
     */
    data class Item(
        val part: FirstRunPart,
        val bytes: Long,
        val status: Status = Status.PENDING,
        val damaged: Int = 0,
        val serverErrors: Int = 0
    ) {
        /**
         * Whether it waits for a tap on Try again instead of being fetched again by itself: it
         * arrived damaged [DROP_AFTER] times in a row. A server error is always tried again by
         * itself, a few minutes later.
         */
        val waitsForTap: Boolean get() = status == Status.PENDING && damaged >= DROP_AFTER
    }

    enum class Status {
        /** Still to download, or under way. */
        PENDING,

        /** On the phone and checked. */
        DONE,

        /**
         * Given up for now, so it can't hold up the first recording: a [FirstRunPart.droppable]
         * part failed [DROP_AFTER] times in a row for a reason that is not the network. The status
         * strip offers it again ([retried]).
         */
        DROPPED
    }

    /** The item of [part], or null when the plan doesn't hold it. */
    fun item(part: FirstRunPart): Item? = items.firstOrNull { it.part == part }

    /** The parts of the setup ([FirstRunPart.beforeFirstRecording]). */
    val setup: List<Item> get() = items.filter { it.part.beforeFirstRecording }

    /** The bytes of the setup: the size on Get ready's button, and where Getting ready's bar ends. */
    val setupBytes: Long get() = setup.sumOf { it.bytes }

    /** Whether nothing of the setup is left to download, so Ready may show. */
    val setupDone: Boolean get() = setup.none { it.status == Status.PENDING }

    /** Whether [part] is planned and not on the phone yet; a dropped part isn't pending. */
    fun pending(part: FirstRunPart): Boolean = item(part)?.status == Status.PENDING

    /**
     * The part to download next: the first pending one, a part after the first recording only once
     * that is saved ([firstRecordingSaved]); null when there is none. The plan runs in order: when
     * this one waits for a tap ([Item.waitsForTap]), the queue holds instead of skipping it.
     */
    fun next(firstRecordingSaved: Boolean): Item? = items.firstOrNull {
        it.status == Status.PENDING && (it.part.beforeFirstRecording || firstRecordingSaved)
    }

    /** [part] is on the phone and checked. */
    fun done(part: FirstRunPart): FirstRunPlan =
        update(part) { it.copy(status = Status.DONE, damaged = 0, serverErrors = 0) }

    /**
     * [part] failed for [failure]. A damaged file and a server error count against it, each in a
     * row; the network and a full disk don't, as they are no fault of the file. A droppable part
     * (Malayalam, the reader) is dropped after [DROP_AFTER] of either; speech recognition and the
     * AI models wait for Try again after [DROP_AFTER] damaged files instead ([Item.waitsForTap]).
     */
    fun failed(part: FirstRunPart, failure: DownloadFailure): FirstRunPlan = update(part) { item ->
        val counted = when (failure) {
            DownloadFailure.OTHER -> item.copy(damaged = item.damaged + 1)
            DownloadFailure.SERVER -> item.copy(serverErrors = item.serverErrors + 1)
            DownloadFailure.NO_CONNECTION, DownloadFailure.BLOCKED, DownloadFailure.NOT_ENOUGH_STORAGE -> item
        }
        val drop = item.part.droppable && item.status == Status.PENDING &&
            (counted.damaged >= DROP_AFTER || counted.serverErrors >= DROP_AFTER)
        if (drop) counted.copy(status = Status.DROPPED) else counted
    }

    /**
     * Try again for [part]: a dropped part is planned again, and the failures counted against it
     * are forgotten, so it is fetched by itself again.
     */
    fun retried(part: FirstRunPart): FirstRunPlan = update(part) {
        it.copy(status = if (it.status == Status.DROPPED) Status.PENDING else it.status, damaged = 0, serverErrors = 0)
    }

    /** [part] is [bytes] large, as its download says: for a model the registry knows only roughly. */
    fun sized(part: FirstRunPart, bytes: Long): FirstRunPlan = update(part) { it.copy(bytes = bytes) }

    /** The bigger AI model's offer was taken: it downloads after whatever is still ahead of it. */
    fun withBiggerModel(bytes: Long): FirstRunPlan =
        if (item(FirstRunPart.BIGGER_AI_MODEL) != null) this
        else copy(items = items + Item(FirstRunPart.BIGGER_AI_MODEL, bytes))

    /**
     * The reader is wanted and not on the phone (design R7: the Malayalam model is, and AI notes are
     * on): a plan without it gets it after whatever is still ahead of it, and one that had it done,
     * whose files went since, plans it again with its failures forgotten. A pending reader stays as
     * it is, and so does a dropped one, which the status strip offers again; but with [dropped] (the
     * owner got the Malayalam model himself, through Settings) a dropped one is planned again too,
     * its failures forgotten, as nothing else would ever plan it again.
     */
    fun withReader(bytes: Long, dropped: Boolean = false): FirstRunPlan {
        val item = item(FirstRunPart.READER) ?: return copy(items = items + Item(FirstRunPart.READER, bytes))
        if (item.status != Status.DONE && !(dropped && item.status == Status.DROPPED)) return this
        return update(FirstRunPart.READER) { it.copy(status = Status.PENDING, damaged = 0, serverErrors = 0) }
    }

    private fun update(part: FirstRunPart, change: (Item) -> Item): FirstRunPlan =
        copy(items = items.map { if (it.part == part) change(it) else it })

    companion object {
        /** Room kept free beyond a download: its temporary file, and the recordings to come. */
        const val HEADROOM_BYTES = 300_000_000L

        /** Failures in a row, of one kind and not the network's, that drop Malayalam or the reader, or wait for a tap. */
        const val DROP_AFTER = 2

        /**
         * The plan of a fresh install. [malayalamBytes] is null when Get ready's switch is off, and
         * [aiModelBytes] when AI notes are off or the AI model is on the phone already; [readerBytes]
         * when the switch is off, AI notes are off, or the reader is on the phone already. The reader
         * comes after the AI model.
         */
        fun initial(speechBytes: Long, malayalamBytes: Long?, aiModelBytes: Long?, readerBytes: Long? = null): FirstRunPlan = FirstRunPlan(
            listOfNotNull(
                Item(FirstRunPart.SPEECH, speechBytes),
                malayalamBytes?.let { Item(FirstRunPart.MALAYALAM, it) },
                aiModelBytes?.let { Item(FirstRunPart.AI_MODEL, it) },
                readerBytes?.let { Item(FirstRunPart.READER, it) }
            )
        )

        /** The free space a download of [payloadBytes] needs before it starts: its bytes and [HEADROOM_BYTES]. */
        fun neededSpace(payloadBytes: Long): Long = payloadBytes + HEADROOM_BYTES

        /** Whether [freeBytes] are enough for a download of [payloadBytes] ([neededSpace]). */
        fun fits(freeBytes: Long, payloadBytes: Long): Boolean = freeBytes >= neededSpace(payloadBytes)
    }
}

/**
 * Not enough free space for a download: Get ready says so before anything is fetched, and a
 * download held for it ([com.satori.domain.model.PauseReason.NOT_ENOUGH_STORAGE]) says so too.
 *
 * @property neededBytes What the download needs free ([FirstRunPlan.neededSpace])
 * @property freeBytes What is free
 */
data class SpaceShortfall(val neededBytes: Long, val freeBytes: Long)
