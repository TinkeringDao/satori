package com.satori.domain.model

/**
 * The two levels of the mind map, the map of thoughts the AI builds from the recordings.
 *
 * It follows Hindsight's memory bank at a phone's scale: a recording is a document, the topics the
 * AI finds in it are its memory units, a theme with its summary is a mental model ("core memory"),
 * and the dates of the recordings linked to a theme tell what is recent. Which theme a topic
 * belongs to is decided in code, not by the model, so the map stays the same from one visit to the
 * next and reading it costs no model call.
 */
enum class MindNodeKind {
    /**
     * A thought that comes back across recordings, such as "Heating": the map's top level. It has
     * no parent, gathers [TOPIC]s, and holds the summary the AI learns from their recordings.
     */
    THEME,

    /**
     * What recordings said under a theme, such as "Heating repair". It is always under one theme,
     * and every recording that speaks of it links to it with its own points ([MindLink]).
     */
    TOPIC
}

/**
 * A theme or a topic of the mind map. How many recordings speak of it and how lately is not
 * stored here but read from its links ([MindNodeItem]), so it can't fall out of step with them.
 *
 * @property id Unique id (UUID)
 * @property kind Theme or topic
 * @property label What it is called, 1 to 4 words, as the AI named it in the recording that made it
 * @property matchKey The label's content words in normal form (stop words removed, lower case,
 *   umlauts folded, plural and case endings stripped), as the mapping step writes them: a new
 *   topic is matched against themes and topics by it, in code and without the model
 * @property parentId The theme a topic is under; null for a theme
 * @property summary What the AI learned about a theme from its recordings: 2 to 4 plain sentences
 *   in the notes language. Null until one is written, and always null for a topic.
 * @property summaryUpdatedAt When [summary] was written (epoch ms), or null
 * @property summaryStale True when the theme got or lost a recording after [summary] was written.
 *   Such a summary is rewritten the next time a recording finishes while the model is loaded, or
 *   when the user asks for it on the theme's page. A theme without a summary needs one whatever
 *   this says.
 * @property pinned The user keeps the theme in Core, whatever its number of recordings
 * @property createdAt When it was made (epoch ms)
 * @property updatedAt When its label, key or parent were last stored, or its summary written (epoch ms)
 */
data class MindNode(
    val id: String,
    val kind: MindNodeKind,
    val label: String,
    val matchKey: String,
    val parentId: String? = null,
    val summary: String? = null,
    val summaryUpdatedAt: Long? = null,
    val summaryStale: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * What one recording says about one topic. A topic has one link for each recording that speaks of
 * it; links join topics only, never themes, and a theme reaches its recordings through its topics.
 *
 * @property topicId The topic
 * @property sessionId The recording
 * @property points What the recording says about the topic: up to 3 short sentences of at most 20
 *   words each, trimmed and without repeats, in the order the AI gave them
 * @property segmentIds The transcript segments the topic came from (those whose text holds the
 *   words of its label), in spoken order; empty when no segment does
 * @property createdAt When the link was made (epoch ms)
 * @property recordedAt When the recording was made (its createdAt, epoch ms). It is read from the
 *   recording, not stored with the link. The map's dates, its order and "recent" go by it, so
 *   mapping older recordings today doesn't make their themes recent.
 */
data class MindLink(
    val topicId: String,
    val sessionId: String,
    val points: List<String>,
    val segmentIds: List<String> = emptyList(),
    val createdAt: Long,
    val recordedAt: Long
)

/**
 * A node with how much the recordings speak of it, as the map lists it. The map sizes and
 * brightens nodes by these numbers, and its Core and Recent filters choose themes by them.
 *
 * @property node The theme or topic
 * @property recordingCount How many recordings are linked to it; for a theme, to any of its topics,
 *   each recording counted once. Always at least 1: a node no recording is linked to isn't listed.
 * @property lastRecordedAt When the newest of those recordings was made (epoch ms)
 */
data class MindNodeItem(
    val node: MindNode,
    val recordingCount: Int,
    val lastRecordedAt: Long
)

/**
 * One topic of one recording, as the recording's own map shows it: the recording in the centre,
 * its topics around it, and each topic's points around the topic.
 *
 * @property topic The topic
 * @property theme The theme it is under
 * @property link The recording's link to it, with the recording's points
 */
data class MindSessionTopic(
    val topic: MindNode,
    val theme: MindNode,
    val link: MindLink
)
