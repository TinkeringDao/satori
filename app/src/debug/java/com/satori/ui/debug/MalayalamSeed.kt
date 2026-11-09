package com.satori.ui.debug

import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.TodoDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import com.satori.data.database.mapper.toEntity
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture

/**
 * Synthetic Malayalam recordings for the reader's checks on an emulator (0.8.0, R5 and R9), from
 * `DebugToolsActivity --es action seed-malayalam [--ei lines <n>] [--es kind backfill|new|both]`;
 * like seed, emulator only. The sentences are made up, of the shape of a spoken Malayalam line, a
 * fragment ("Okay."), which D5 empties by rule, and an English line, as a speaker who switches says
 * one, which the AI steps read as it is: with a toy reader, whose readings D5 empties, the notes
 * still have text enough to be asked for. [lines] lines a recording, cycling through them.
 *
 * - `backfill` (the default): two recordings as an older version left them, finished by the AI
 *   model reading the lines as they were said, without readings: [JUNK], named, noted and with a
 *   to-do by the app, none of it touched; and [EDITED], whose name the owner typed (Rename), whose
 *   note he saved in the editor, and one of whose two to-dos he changed a minute after it was found.
 *   Both are due to be made again, as the migration to version 13 marks an older version's
 *   Malayalam recordings; the backfill of readings queues both once the reader is on the phone; the
 *   first gets new notes, name and to-dos, the second keeps all of his.
 * - `new`: a recording just stopped, without notes, handed off to the finishing queue as the
 *   recorder hands one off ([FinishingQueue.handOff]), which finishes it through the Reading step.
 */
internal object MalayalamSeed {

    const val JUNK = "ml-seed-junk"
    const val EDITED = "ml-seed-edited"
    private const val NEW = "ml-seed-new"

    /** How many lines a recording gets without --ei lines, and at most. */
    const val DEFAULT_LINES = 400
    const val MAX_LINES = 3_000

    // Made up: "We will meet at the office tomorrow morning, everyone has to be on time." / "Bring
    // all the papers to the office, then the budget has to be checked." / "After that we go to the
    // market together and buy things." / "We decided to move the launch to March." / "Okay." / and
    // an English line.
    private val SENTENCES = listOf(
        "നമുക്ക് നാളെ രാവിലെ ഓഫീസിൽ കാണാം, എല്ലാവരും സമയത്ത് എത്തണം.",
        "പേപ്പറുകൾ എല്ലാം ഓഫീസിലേക്ക് കൊണ്ടുവരണം, പിന്നെ ബജറ്റ് നോക്കണം.",
        "അതിനുശേഷം നമ്മൾ ഒരുമിച്ച് മാർക്കറ്റിൽ പോയി സാധനങ്ങൾ വാങ്ങും.",
        "ലോഞ്ച് മാർച്ചിലേക്ക് മാറ്റാൻ നമ്മൾ തീരുമാനിച്ചു.",
        "ശരി.",
        "The budget meeting moves to Thursday afternoon, and the slides go out before then."
    )

    /** What it seeded: the recordings, and the lines of each. */
    data class Seeded(val sessionIds: List<String>, val lines: Int)

    suspend fun seed(
        kind: String,
        lines: Int,
        sessionDao: TranscriptionSessionDao,
        segmentDao: TranscriptionSegmentDao,
        insightDao: LlmInsightDao,
        todoDao: TodoDao,
        queue: FinishingQueue,
        now: Long = System.currentTimeMillis()
    ): Seeded {
        val count = lines.coerceIn(1, MAX_LINES)
        val ids = mutableListOf<String>()
        if (kind == "backfill" || kind == "both") {
            junk(JUNK, count, now - 2 * DAY_MS, sessionDao, segmentDao, insightDao, todoDao)
            junk(EDITED, count, now - DAY_MS, sessionDao, segmentDao, insightDao, todoDao)
            // The owner's: the name he typed, the note he saved, a to-do he changed a minute after it was found.
            sessionDao.updateName(EDITED, "My own name for it")
            insightDao.updateNote("$EDITED-ins0", "His title", "- What he wrote himself.", null, editedAt = now - DAY_MS + HOUR_MS)
            todoDao.upsert(todo(EDITED, "his", "Call the office about the papers", now - DAY_MS, changedAfterMs = 60_000L).toEntity())
            ids += listOf(JUNK, EDITED)
        }
        if (kind == "new" || kind == "both") {
            val id = "$NEW-$now"
            recording(id, name = null, count, now - count * LINE_MS, sessionDao, segmentDao)
            queue.handOff(id, recognizerFailed = false)
            ids += id
        }
        return Seeded(ids, count)
    }

    /** A recording as an older version finished it: the app's name, note and to-do, from the lines as they were said. */
    private suspend fun junk(
        id: String,
        lines: Int,
        createdAt: Long,
        sessionDao: TranscriptionSessionDao,
        segmentDao: TranscriptionSegmentDao,
        insightDao: LlmInsightDao,
        todoDao: TodoDao
    ) {
        // A seeding again replaces what an earlier one made: the insert's REPLACE takes the lines,
        // notes, readings and job with the old row, and the to-dos are written over by their ids.
        // Due to be made again over its readings, as the migration to version 13 marks such a one.
        recording(id, name = "Workflow Kettle Challenges", lines, createdAt, sessionDao, segmentDao, redoDue = true)
        insightDao.insert(
            LlmInsightEntity(
                id = "$id-ins0", sessionId = id, title = "Workflow challenges", content = "- The workflow has problems.\n- The kettle needs a plan.",
                timestamp = createdAt + lines * LINE_MS, sourceSegmentIds = "[\"$id-seg0\"]"
            )
        )
        todoDao.upsert(todo(id, "junk", "Fix the workflow kettle", createdAt + lines * LINE_MS, changedAfterMs = 0L).toEntity())
    }

    private suspend fun recording(
        id: String,
        name: String?,
        lines: Int,
        createdAt: Long,
        sessionDao: TranscriptionSessionDao,
        segmentDao: TranscriptionSegmentDao,
        redoDue: Boolean = false
    ) {
        sessionDao.insert(
            TranscriptionSessionEntity(
                id = id, name = name, createdAt = createdAt, lastModifiedAt = createdAt + lines * LINE_MS,
                inputLanguage = RecordingLanguage.MALAYALAM.code.orEmpty(), durationMs = lines * LINE_MS,
                transcriptModel = SpeechModelId.INDICCONFORMER_ML.id, language = RecordingLanguage.MALAYALAM.code,
                redoDue = redoDue
            )
        )
        repeat(lines) { i ->
            segmentDao.insert(
                TranscriptionSegmentEntity(
                    id = "$id-seg$i", sessionId = id, text = SENTENCES[i % SENTENCES.size],
                    timestamp = createdAt + (i + 1) * LINE_MS, isComplete = true
                )
            )
        }
    }

    private fun todo(sessionId: String, suffix: String, text: String, foundAt: Long, changedAfterMs: Long) = Todo(
        id = "$sessionId-todo-$suffix", sessionId = sessionId, text = text, capture = TodoCapture.INFERRED,
        sourceSegmentIds = listOf("$sessionId-seg1"), createdAt = foundAt, updatedAt = foundAt + changedAfterMs
    )

    private const val LINE_MS = 4_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
}
