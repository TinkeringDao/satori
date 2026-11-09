package com.satori.ui.debug

import android.content.Context
import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.TodoDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.data.database.entity.LlmInsightEntity
import com.satori.data.database.entity.TranscriptionSegmentEntity
import com.satori.data.database.entity.TranscriptionSessionEntity
import com.satori.data.database.mapper.toEntity
import com.satori.ui.ensureIn
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.usecase.category.EnsureStarterCategoriesUseCase
import com.satori.domain.usecase.todo.TodoReminderTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Made-up recordings for screenshots on an emulator: every mode, both strategies, English and
 * German, short and long names, dates from today back to last year, recordings in categories, a
 * to-do list with every kind of due line, and a mind map learned from the recordings (see
 * [demoThemes]). Seeding it again replaces what it made before.
 */
internal object DemoData {

    private data class Demo(
        val name: String?,
        val mode: String,
        val language: String,
        val daysAgo: Int,
        val hour: Int,
        val durationMs: Long,
        val lines: List<String>,
        val insights: List<Triple<String?, String, List<String>>> = emptyList(),
        val strategy: String = "REAL_TIME",
        val overall: Triple<String?, String, List<String>>? = null,
        val categoryId: String? = null
    )

    private val demos = listOf(
        Demo(
            name = "Heating repair and rent", mode = "SIMPLE_LISTENING", language = "en", daysAgo = 0, hour = 9,
            durationMs = 212_000L, categoryId = "voice-note",
            lines = listOf(
                "Okay, quick notes from the call with the landlord.",
                "He agreed to fix the heating before the end of the month.",
                "The rent stays the same until March, then it goes up by forty euros.",
                "I still need to send him the photos of the bathroom window.",
                "And ask whether the garage spot is still free."
            ),
            insights = listOf(
                Triple(
                    "Heating repair and rent",
                    "The landlord will fix the heating this month. The rent stays the same until March, then rises by 40 euros.",
                    emptyList()
                )
            )
        ),
        // No name: the home list calls it by its category.
        Demo(
            name = null, mode = "SIMPLE_LISTENING", language = "en", daysAgo = 0, hour = 8, durationMs = 18_000L,
            categoryId = "idea",
            lines = listOf("Testing, one, two, three.", "This is a short test of the recorder.")
        ),
        Demo(
            name = "Budget review with Anna", mode = "SHORT_MEETING", language = "en", daysAgo = 1, hour = 16,
            durationMs = 1_450_000L, categoryId = "meeting",
            lines = listOf(
                "Let's start with where we are on the budget for the fourth quarter.",
                "Marketing is about twelve percent over, mostly because of the trade fair.",
                "Engineering is under budget, the two open positions are still not filled.",
                "Anna suggested moving part of the travel budget to training.",
                "We agreed to freeze new subscriptions until January.",
                "I will check the licence renewals and send a list by Friday.",
                "Anna takes the hiring plan to the next leadership meeting.",
                "Next review is in two weeks, same time."
            ),
            insights = listOf(
                Triple(
                    "Q4 budget status",
                    "Marketing is 12% over budget because of the trade fair; engineering is under because two positions are open.",
                    emptyList()
                ),
                Triple(
                    "Spending decisions",
                    "Part of the travel budget moves to training. New subscriptions are frozen until January.",
                    listOf("Check the licence renewals and send a list by Friday", "Take the hiring plan to the leadership meeting")
                ),
                Triple("Next steps", "The next review is in two weeks at the same time.", listOf("Book the follow-up review"))
            )
        ),
        Demo(
            name = "Umzug nach Köln planen", mode = "SIMPLE_LISTENING", language = "de", daysAgo = 3, hour = 19,
            durationMs = 125_000L, categoryId = "voice-note",
            lines = listOf(
                "Also, der Umzug nach Köln ist jetzt für den fünfzehnten November geplant.",
                "Den Transporter muss ich noch diese Woche reservieren.",
                "Die Kartons bekomme ich von Jonas, er hat noch zwanzig Stück im Keller.",
                "Und ich darf nicht vergessen, die Adresse bei der Bank zu ändern."
            ),
            insights = listOf(
                Triple(
                    "Umzug am 15. November",
                    "Der Umzug nach Köln ist für den 15. November geplant. Kartons kommen von Jonas.",
                    listOf("Transporter reservieren", "Adresse bei der Bank ändern")
                )
            )
        ),
        Demo(
            name = "Quarterly planning workshop with the extended product and design team",
            mode = "LONG_MEETING", language = "en", daysAgo = 14, hour = 10, durationMs = 5_050_000L,
            strategy = "END_OF_SESSION", categoryId = "meeting",
            lines = (1..12).map { i ->
                listOf(
                    "We looked at the roadmap for the next quarter, item $i.",
                    "The team discussed the effort and the open risks for item $i.",
                    "There was agreement to revisit item $i after the user interviews."
                )[i % 3]
            },
            insights = listOf(
                Triple("Roadmap overview", "The team went through the roadmap items for next quarter and their effort.", emptyList()),
                Triple("Risks and dependencies", "Several items depend on the user interviews planned for next month.", emptyList()),
                Triple("Follow-ups", "Items without clear owners will be revisited after the interviews.", listOf("Plan the user interviews"))
            ),
            overall = Triple(
                "Next quarter's roadmap",
                "The workshop reviewed the roadmap for next quarter, weighed effort and risks, and parked items that depend on the user interviews.",
                listOf("Plan the user interviews", "Assign owners to open roadmap items")
            )
        ),
        // A translation has no category.
        Demo(
            name = "Lieferantengespräch übersetzt", mode = "REAL_TIME_TRANSLATION", language = "de", daysAgo = 330, hour = 14,
            durationMs = 340_000L,
            lines = listOf(
                "Thanks for joining. We can deliver the first batch in three weeks.",
                "The price includes shipping to your warehouse in Hamburg."
            ),
            insights = listOf(
                Triple(
                    null,
                    "Danke für die Teilnahme. Wir können die erste Lieferung in drei Wochen liefern. Der Preis enthält den Versand zu Ihrem Lager in Hamburg.",
                    emptyList()
                )
            )
        ),
        // Nothing kept and under two seconds: the home list hides it.
        Demo(name = null, mode = "SIMPLE_LISTENING", language = "en", daysAgo = 2, hour = 22, durationMs = 1_000L, lines = emptyList()),
        // Older recordings that come back to the themes of the ones above, so the map has themes
        // with more than one recording.
        Demo(
            name = "Heating bill", mode = "SIMPLE_LISTENING", language = "en", daysAgo = 24, hour = 10,
            durationMs = 64_000L, categoryId = "voice-note",
            lines = listOf(
                "The heating bill for last winter came today.",
                "It is almost two hundred euros more than the year before.",
                "I want to ask the landlord whether the boiler was serviced this year."
            ),
            insights = listOf(
                Triple(
                    "Heating bill",
                    "Last winter's heating bill is almost 200 euros higher than the year before. The landlord should say whether the boiler was serviced.",
                    listOf("Ask the landlord about the boiler service")
                )
            )
        ),
        Demo(
            name = "Budget planning for Q4", mode = "SHORT_MEETING", language = "en", daysAgo = 45, hour = 11,
            durationMs = 1_260_000L, categoryId = "meeting",
            lines = listOf(
                "The first draft of the fourth quarter budget is due at the end of next week.",
                "Travel costs went up a lot this year.",
                "Training was cut last year, and the team misses it.",
                "All software subscriptions get a review before they renew."
            ),
            insights = listOf(
                Triple(
                    "Q4 budget draft",
                    "The first draft of the Q4 budget is due at the end of next week. Travel costs rose a lot this year.",
                    listOf("Write the first draft of the Q4 budget")
                ),
                Triple(
                    "Training and subscriptions",
                    "Training was cut last year and is missed. Software subscriptions are reviewed before they renew.",
                    emptyList()
                )
            )
        ),
        Demo(
            name = "Wohnung in Köln", mode = "SIMPLE_LISTENING", language = "de", daysAgo = 9, hour = 18,
            durationMs = 96_000L, categoryId = "voice-note",
            lines = listOf(
                "Die Wohnung in Köln hat zwei Zimmer und einen Balkon.",
                "Die Kaution sind drei Monatsmieten, die muss ich bis Ende Oktober überweisen.",
                "Für die Anmeldung brauche ich noch einen Termin beim Bürgeramt."
            ),
            insights = listOf(
                Triple(
                    "Wohnung in Köln",
                    "Die Wohnung in Köln hat zwei Zimmer und einen Balkon. Die Kaution von drei Monatsmieten ist bis Ende Oktober fällig.",
                    listOf("Kaution überweisen", "Termin beim Bürgeramt machen")
                )
            )
        ),
        // No notes: its topics come from the transcript.
        Demo(
            name = "Knocking in the heating", mode = "SIMPLE_LISTENING", language = "en", daysAgo = 100, hour = 22,
            durationMs = 31_000L, categoryId = "voice-note",
            lines = listOf(
                "The heating makes a loud knocking noise at night again.",
                "Probably air in the pipes, I should bleed the radiators this weekend."
            )
        )
    )

    /**
     * A theme of the demo map, keyed in [demoThemes] by the end of its id. [summary] is what the
     * AI wrote after demo recording [summaryAfter] was mapped.
     */
    private data class DemoTheme(
        val label: String,
        val matchKey: String,
        val summary: String? = null,
        val summaryAfter: Int? = null,
        val stale: Boolean = false,
        val pinned: Boolean = false
    )

    /**
     * A topic the AI named in demo recording [demo], under the theme [theme] (a key of
     * [demoThemes]), with its points and the lines it came from. Topics with the same [key] are
     * one topic node linked to each of their recordings.
     */
    private data class DemoTopic(
        val demo: Int,
        val theme: String,
        val key: String,
        val label: String,
        val matchKey: String,
        val points: List<String>,
        val lines: List<Int>
    )

    /**
     * The demo map's themes. Together they show every state of the map: themes with one to three
     * recordings, Core (the pinned roadmap plus the seven with the most recordings) apart from
     * All, Recent (the last 7 days), labels longer than the canvas shows, German themes, a fresh,
     * a stale and a missing summary. Two recordings stay unmapped, the microphone test and the
     * translation, so the map offers "Map 2 older recordings".
     *
     * The match keys are written by hand in the matcher's normal form (content words, lower case,
     * umlauts folded, endings stripped). Demo data only goes on emulators, which have no model,
     * so no mapping ever matches against them.
     */
    private val demoThemes = mapOf(
        "heating" to DemoTheme(
            label = "Heating", matchKey = "heating", summaryAfter = 0,
            summary = "A loud knocking at night, probably air in the pipes, started it. Last winter's bill was almost 200 euros higher than the year before, and whether the boiler was serviced is still open. The landlord has now agreed to fix the heating before the end of the month."
        ),
        "rent" to DemoTheme(
            label = "Rent", matchKey = "rent", summaryAfter = 0,
            summary = "The rent stays the same until March. After that it goes up by 40 euros."
        ),
        "garage" to DemoTheme(
            label = "Garage spot", matchKey = "garage spot", summaryAfter = 0,
            summary = "It is still open whether the garage spot is free. The landlord is to be asked."
        ),
        "budget" to DemoTheme(
            label = "Budget", matchKey = "budget", summaryAfter = 2,
            summary = "The first draft of the Q4 budget was due when travel costs had gone up a lot. At the review marketing was about 12% over because of the trade fair, and engineering was under with two positions open. Part of the travel budget now moves to training."
        ),
        "subscriptions" to DemoTheme(
            label = "Subscriptions", matchKey = "subscription", summaryAfter = 2,
            summary = "Software subscriptions are reviewed before they renew. New subscriptions are frozen until January, and a list of the licence renewals is due by Friday."
        ),
        "hiring" to DemoTheme(
            label = "Hiring plan", matchKey = "hiring plan", summaryAfter = 2,
            summary = "The hiring plan goes to the next leadership meeting. Nothing about it has been decided yet."
        ),
        "training" to DemoTheme(
            label = "Training", matchKey = "training", summaryAfter = 8,
            summary = "Training was cut last year, and the team misses it. Whether it comes back is still open."
        ),
        // Written after the flat was mapped; the move joined later and its rewrite failed.
        "cologne" to DemoTheme(
            label = "Wohnung in Köln", matchKey = "wohnung koln", summaryAfter = 9, stale = true,
            summary = "Die Wohnung in Köln hat zwei Zimmer und einen Balkon. Die Kaution von drei Monatsmieten ist bis Ende Oktober zu überweisen, und für die Anmeldung fehlt noch ein Termin beim Bürgeramt."
        ),
        // Its summary could not be written yet.
        "bank" to DemoTheme(label = "Adresse bei der Bank", matchKey = "adress bank"),
        "roadmap" to DemoTheme(
            label = "Next quarter's roadmap", matchKey = "next quarter roadmap", summaryAfter = 4, pinned = true,
            summary = "The workshop went through next quarter's roadmap and weighed the effort and risks of each item. Items without clear owners wait until after the user interviews."
        ),
        "interviews" to DemoTheme(
            label = "User interviews", matchKey = "user interview", summaryAfter = 4,
            summary = "Several roadmap items depend on the user interviews planned for next month. The interviews themselves still have to be planned."
        )
    )

    /** The demo map's topics, each recording's in the order the AI named them. */
    private val demoTopics = listOf(
        DemoTopic(
            demo = 0, theme = "heating", key = "heating-repair", label = "Heating repair", matchKey = "heating repair",
            points = listOf("The landlord agreed to fix the heating before the end of the month."), lines = listOf(1)
        ),
        DemoTopic(
            demo = 0, theme = "rent", key = "rent", label = "Rent", matchKey = "rent",
            points = listOf("The rent stays the same until March.", "From March it goes up by forty euros."), lines = listOf(2)
        ),
        DemoTopic(
            demo = 0, theme = "garage", key = "garage-spot", label = "Garage spot", matchKey = "garage spot",
            points = listOf("Ask whether the garage spot is still free."), lines = listOf(4)
        ),
        DemoTopic(
            demo = 2, theme = "budget", key = "q4-budget", label = "Q4 budget", matchKey = "q4 budget",
            points = listOf(
                "Marketing is about twelve percent over because of the trade fair.",
                "Engineering is under budget while two positions are open.",
                "Part of the travel budget moves to training."
            ),
            lines = listOf(0, 2, 3)
        ),
        DemoTopic(
            demo = 2, theme = "subscriptions", key = "subscription-freeze", label = "Subscription freeze",
            matchKey = "subscription freeze",
            points = listOf("New subscriptions are frozen until January.", "Check the licence renewals and send a list by Friday."),
            lines = listOf(4)
        ),
        DemoTopic(
            demo = 2, theme = "hiring", key = "hiring-plan", label = "Hiring plan", matchKey = "hiring plan",
            points = listOf("The hiring plan goes to the next leadership meeting."), lines = listOf(6)
        ),
        DemoTopic(
            demo = 3, theme = "cologne", key = "umzug", label = "Umzug nach Köln", matchKey = "umzug koln",
            points = listOf("Der Umzug ist für den 15. November geplant.", "Den Transporter noch diese Woche reservieren."),
            lines = listOf(0)
        ),
        DemoTopic(
            demo = 3, theme = "bank", key = "bank-address", label = "Adresse bei der Bank", matchKey = "adress bank",
            points = listOf("Die Adresse bei der Bank ändern."), lines = listOf(3)
        ),
        DemoTopic(
            demo = 4, theme = "roadmap", key = "roadmap", label = "Next quarter's roadmap", matchKey = "next quarter roadmap",
            points = listOf(
                "The team went through the roadmap items and their effort.",
                "Items without clear owners are revisited after the interviews."
            ),
            lines = listOf(2, 5, 8, 11)
        ),
        DemoTopic(
            demo = 4, theme = "interviews", key = "user-interviews", label = "User interviews", matchKey = "user interview",
            points = listOf("Several roadmap items depend on the user interviews next month."), lines = listOf(1, 4, 7, 10)
        ),
        DemoTopic(
            demo = 7, theme = "heating", key = "heating-bill", label = "Heating bill", matchKey = "heating bill",
            points = listOf(
                "Last winter's heating bill is almost two hundred euros higher.",
                "Ask the landlord whether the boiler was serviced."
            ),
            lines = listOf(0)
        ),
        DemoTopic(
            demo = 8, theme = "budget", key = "q4-budget-draft", label = "Q4 budget draft", matchKey = "q4 budget draft",
            points = listOf("The first draft of the Q4 budget is due at the end of next week.", "Travel costs went up a lot this year."),
            lines = listOf(0)
        ),
        DemoTopic(
            demo = 8, theme = "training", key = "training-budget", label = "Training budget", matchKey = "training budget",
            points = listOf("Training was cut last year, and the team misses it."), lines = listOf(2)
        ),
        DemoTopic(
            demo = 8, theme = "subscriptions", key = "software-subscriptions", label = "Software subscriptions",
            matchKey = "software subscription",
            points = listOf("All software subscriptions get a review before they renew."), lines = listOf(3)
        ),
        DemoTopic(
            demo = 9, theme = "cologne", key = "flat", label = "Wohnung in Köln", matchKey = "wohnung koln",
            points = listOf("Die Wohnung hat zwei Zimmer und einen Balkon.", "Für die Anmeldung fehlt ein Termin beim Bürgeramt."),
            lines = listOf(0)
        ),
        DemoTopic(
            demo = 9, theme = "cologne", key = "deposit", label = "Kaution", matchKey = "kaution",
            points = listOf("Die Kaution von drei Monatsmieten bis Ende Oktober überweisen."), lines = listOf(1)
        ),
        DemoTopic(
            demo = 10, theme = "heating", key = "heating-noise", label = "Heating noise", matchKey = "heating noise",
            points = listOf("The heating makes a loud knocking noise at night.", "Bleed the radiators this weekend."),
            lines = listOf(0)
        )
    )

    /**
     * A to-do of the demo list: [day] days from today at [time] (or on that day only when null),
     * from line [line] of the demo recording [demo].
     */
    private data class DemoTodo(
        val text: String,
        val demo: Int?,
        val line: Int,
        val day: Int?,
        val time: LocalTime?,
        val status: TodoStatus = TodoStatus.OPEN,
        val kind: TodoKind = TodoKind.TASK,
        val capture: TodoCapture = TodoCapture.COMMITMENT
    )

    private val todos = listOf(
        // Due later today: the time is set when seeding, so it is always still ahead.
        DemoTodo("Send the landlord the bathroom photos", demo = 0, line = 3, day = 0, time = null),
        DemoTodo("Book the follow-up review", demo = 2, line = 7, day = 1, time = LocalTime.of(14, 30)),
        DemoTodo("Check the licence renewals and send a list", demo = 2, line = 5, day = 3, time = LocalTime.of(9, 0)),
        // Overdue: yesterday at 17:00.
        DemoTodo("Ask whether the garage spot is still free", demo = 0, line = 4, day = -1, time = LocalTime.of(17, 0)),
        // Due on a day only.
        DemoTodo("Transporter reservieren", demo = 3, line = 1, day = 5, time = null),
        // No due date.
        DemoTodo("Adresse bei der Bank ändern", demo = 3, line = 3, day = null, time = null),
        // Suggestions: an appointment someone else asked for, and a task the AI only inferred.
        DemoTodo(
            "Meet Anna about the hiring plan", demo = 2, line = 6, day = 1, time = LocalTime.of(10, 0),
            status = TodoStatus.SUGGESTED, kind = TodoKind.APPOINTMENT, capture = TodoCapture.ASKED_BY_OTHER
        ),
        DemoTodo(
            "Plan the user interviews", demo = 4, line = 2, day = null, time = null,
            status = TodoStatus.SUGGESTED, capture = TodoCapture.INFERRED
        ),
        DemoTodo(
            "Take the hiring plan to the leadership meeting", demo = 2, line = 6, day = -2, time = LocalTime.of(11, 0),
            status = TodoStatus.DONE
        )
    )

    suspend fun seed(
        context: Context,
        sessionDao: TranscriptionSessionDao,
        segmentDao: TranscriptionSegmentDao,
        insightDao: LlmInsightDao,
        todoDao: TodoDao,
        mindMap: MindMapRepository,
        ensureStarterCategories: EnsureStarterCategoriesUseCase
    ) {
        // The same seeding the app does at its start, so the demo categories are the real ones.
        ensureStarterCategories.ensureIn(context)
        val startTimes = demos.mapIndexed { index, demo -> startedAt(demo, index) }
        demos.forEachIndexed { index, demo ->
            val id = "demo-$index"
            val createdAt = startTimes[index]
            val step = if (demo.lines.isEmpty()) 0L else demo.durationMs / (demo.lines.size + 1)
            sessionDao.insert(
                TranscriptionSessionEntity(
                    id = id, name = demo.name, createdAt = createdAt, lastModifiedAt = createdAt + demo.durationMs,
                    mode = demo.mode, inputLanguage = demo.language, outputLanguage = demo.language,
                    durationMs = demo.durationMs, insightStrategy = demo.strategy,
                    topic = demo.name, categoryId = demo.categoryId
                )
            )
            val segmentIds = demo.lines.mapIndexed { i, text ->
                val segmentId = "$id-seg$i"
                segmentDao.insert(
                    TranscriptionSegmentEntity(
                        id = segmentId, sessionId = id, text = text,
                        timestamp = createdAt + (i + 1) * step, isComplete = true
                    )
                )
                segmentId
            }
            val perInsight = (segmentIds.size / demo.insights.size.coerceAtLeast(1)).coerceAtLeast(1)
            demo.insights.forEachIndexed { i, (title, content, tasks) ->
                val sources = segmentIds.drop(i * perInsight).take(perInsight)
                insightDao.insert(
                    LlmInsightEntity(
                        id = "$id-ins$i", sessionId = id, title = title, content = content,
                        tasks = tasks.takeIf { it.isNotEmpty() }?.let(::jsonArray),
                        timestamp = createdAt + (i + 1) * (demo.durationMs / (demo.insights.size + 1)),
                        sourceSegmentIds = jsonArray(sources)
                    )
                )
            }
            demo.overall?.let { (title, content, tasks) ->
                insightDao.insert(
                    LlmInsightEntity(
                        id = "$id-overall", sessionId = id, title = title, content = content,
                        tasks = tasks.takeIf { it.isNotEmpty() }?.let(::jsonArray),
                        timestamp = createdAt + demo.durationMs + 60_000L,
                        sourceSegmentIds = "[]"
                    )
                )
            }
        }
        // After the recordings: replacing a recording drops its links and detaches its to-dos.
        seedMindMap(mindMap, startTimes)
        todos.forEachIndexed { index, demoTodo -> todoDao.upsert(todoOf(index, demoTodo).toEntity()) }
    }

    /**
     * Stores the demo map through the repository, the way the mapping step stores it: recording
     * by recording, oldest first, each theme made with its first topic and every topic linked to
     * its recording in the order the AI named them. A node is made when its first recording
     * finished, a link when its recording did, and a summary right after the recording named in
     * its [DemoTheme.summaryAfter].
     *
     * Replacing the demo recordings dropped their links, so [MindMapRepository.cleanup] first
     * removes the demo nodes of an earlier seeding, and they are made anew with their summaries
     * and pins.
     */
    private suspend fun seedMindMap(mindMap: MindMapRepository, startTimes: List<Long>) {
        fun finishedAt(demo: Int): Long = startTimes[demo] + demos[demo].durationMs + 20_000L
        mindMap.cleanup()
        val made = mutableSetOf<String>()
        demoTopics.sortedBy { startTimes[it.demo] }.forEach { topic ->
            val at = finishedAt(topic.demo)
            val themeId = "demo-theme-${topic.theme}"
            if (made.add(themeId)) {
                val theme = demoThemes.getValue(topic.theme)
                val summaryAt = theme.summaryAfter?.let { finishedAt(it) + 10_000L }
                mindMap.upsertNode(
                    MindNode(
                        id = themeId, kind = MindNodeKind.THEME, label = theme.label, matchKey = theme.matchKey,
                        summary = theme.summary, summaryUpdatedAt = summaryAt, summaryStale = theme.stale,
                        pinned = theme.pinned, createdAt = at, updatedAt = summaryAt ?: at
                    )
                )
            }
            val topicId = "demo-topic-${topic.key}"
            if (made.add(topicId)) {
                mindMap.upsertNode(
                    MindNode(
                        id = topicId, kind = MindNodeKind.TOPIC, label = topic.label, matchKey = topic.matchKey,
                        parentId = themeId, createdAt = at, updatedAt = at
                    )
                )
            }
            mindMap.link(
                topicId = topicId,
                sessionId = "demo-${topic.demo}",
                points = topic.points,
                segmentIds = topic.lines.map { "demo-${topic.demo}-seg$it" },
                at = at
            )
        }
    }

    private fun startedAt(demo: Demo, index: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -demo.daysAgo)
        set(Calendar.HOUR_OF_DAY, demo.hour)
        set(Calendar.MINUTE, 7 + index * 6)
    }.timeInMillis

    /** The to-do with its due time and reminder worked out the way the app works them out. */
    private fun todoOf(index: Int, demoTodo: DemoTodo): Todo {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val date = demoTodo.day?.let { LocalDate.now(zone).plusDays(it.toLong()) }
        // Later today means a time that is still ahead, at most 23:30.
        val time = demoTodo.time ?: if (demoTodo.day == 0) laterToday(now, zone) else null
        val dueAt = date?.let { TodoReminderTime.dueAt(it, time, zone) }
        val hasTime = date != null && time != null
        val remindAt = TodoReminderTime.of(dueAt, hasTime, demoTodo.kind, now, zone)
        val sessionId = demoTodo.demo?.let { "demo-$it" }
        return Todo(
            id = "demo-todo-$index",
            sessionId = sessionId,
            text = demoTodo.text,
            kind = demoTodo.kind,
            status = demoTodo.status,
            certainty = TodoCertainty.CONFIRMED,
            capture = demoTodo.capture,
            dueAt = dueAt,
            dueHasTime = hasTime,
            remindAt = remindAt,
            sourceSegmentIds = listOfNotNull(sessionId?.let { "$it-seg${demoTodo.line}" }),
            createdAt = now - index * 60_000L,
            updatedAt = now,
            completedAt = now.takeIf { demoTodo.status == TodoStatus.DONE }
        )
    }

    /** Two hours from now, or half past eleven in the evening if that is sooner. */
    private fun laterToday(now: Long, zone: ZoneId): LocalTime {
        val soon = Instant.ofEpochMilli(now).plusSeconds(2 * 3600L).atZone(zone)
        val limit = LocalTime.of(23, 30)
        return if (soon.toLocalDate() == LocalDate.now(zone) && soon.toLocalTime() < limit) {
            soon.toLocalTime().withSecond(0).withNano(0)
        } else {
            limit
        }
    }

    private fun jsonArray(items: List<String>): String =
        org.json.JSONArray().apply { items.forEach { put(it) } }.toString()
}
