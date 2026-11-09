package com.satori.domain.repository

import com.satori.domain.model.MindLink
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeItem
import com.satori.domain.model.MindSessionTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Stores the mind map (see [MindNode] and [MindLink]); every change is visible through the flows.
 *
 * The map is a two-level tree, themes at the top and topics under them, and links join each topic
 * to the recordings that speak of it. How many recordings speak of a node and how lately
 * ([MindNodeItem]) is read from the links and their recordings whenever it is asked for, so it is
 * never stored and never out of date.
 *
 * Deleting a recording (TranscriptionRepository.deleteSession or deleteAllSessions) drops its
 * links through their foreign key (CASCADE). In the same transaction the data layer marks the
 * themes that lose a recording stale and removes what [cleanup] removes: a topic without links
 * goes, and a theme without topics goes. Doing it there keeps the map whole whichever way a
 * recording is deleted.
 */
interface MindMapRepository {

    /**
     * Every theme at least one recording is linked to, through its topics. Most recordings first,
     * then the most recently recorded, then by label and id, so the order is the same on every
     * visit. The map's Core (pinned themes plus the first 7) and Recent (recorded in the last 7
     * days) are chosen from this list.
     */
    fun themes(): Flow<List<MindNodeItem>>

    /**
     * The topics under the theme [parentId] that a recording is linked to, in the order of
     * [themes]. Empty for a topic or an unknown id.
     */
    fun children(parentId: String): Flow<List<MindNodeItem>>

    /** The node [id]; null when there is none, because it was never made or went in a [cleanup]. */
    fun node(id: String): Flow<MindNode?>

    /**
     * The links of [nodeId]: for a theme those of all its topics, for a topic its own. Newest
     * recording first ([MindLink.recordedAt]); the links of one recording in the order they were
     * stored. Empty for an unknown id.
     */
    fun links(nodeId: String): Flow<List<MindLink>>

    /**
     * Each topic [sessionId] is linked to, with its theme and the recording's link, in the order
     * the links were stored (which is the order the AI named the topics). Empty for a recording
     * that isn't mapped. The recording's own map is drawn from this.
     */
    fun nodesForSession(sessionId: String): Flow<List<MindSessionTopic>>

    /**
     * Every node, themes and topics, in the order they were made (by createdAt, then the order
     * they were stored). The mapping step matches new topics against them.
     */
    fun allNodes(): Flow<List<MindNode>>

    /**
     * Stores [node] as new, or updates the node with its id: its label, match key, parent and
     * updatedAt. For a node that exists, its kind, createdAt, summary fields and pin stay as
     * stored whatever [node] carries: the summary fields belong to [setSummary] and [markStale],
     * and the pin to [setPinned], so a copy read before a pin or a summary was written can't undo
     * it. A topic whose theme no longer exists isn't stored.
     */
    suspend fun upsertNode(node: MindNode)

    /**
     * Links the topic [topicId] to the recording [sessionId] with what the recording says about
     * it, replacing the link between the two if there is one. Nothing is stored when the topic or
     * the recording no longer exists (deleted meanwhile).
     *
     * @param points Up to 3 short sentences, in the order the AI gave them
     * @param segmentIds The transcript segments the topic came from, in spoken order; may be empty
     * @param at When the link is made (epoch ms)
     */
    suspend fun link(
        topicId: String,
        sessionId: String,
        points: List<String>,
        segmentIds: List<String>,
        at: Long
    )

    /**
     * Stores the summary of the theme [nodeId] written at [at] (epoch ms), which also becomes its
     * updatedAt, and clears its stale mark. Does nothing for an unknown id.
     */
    suspend fun setSummary(nodeId: String, summary: String, at: Long)

    /**
     * Marks the summaries of the themes [nodeIds] stale ([MindNode.summaryStale]), so they are
     * rewritten. Unknown ids are skipped; nothing else changes.
     */
    suspend fun markStale(nodeIds: Collection<String>)

    /** Pins or unpins the theme [nodeId] ([MindNode.pinned]). Nothing else changes. */
    suspend fun setPinned(nodeId: String, pinned: Boolean)

    /**
     * The recordings waiting to be mapped, newest first, so a run cut short has mapped the recent
     * ones: those with no link whose complete transcript segments, each with the spaces at its
     * ends removed, add up to at least [MIN_TRANSCRIPT_CHARS] characters.
     *
     * A recording the AI read without finding a topic that held up ([markReadWithoutTopics]) is
     * left out while its complete transcript, counted that way, has the length it had when the AI
     * read it: read again it would cost the model's time for the same answer. A transcript that
     * changed since (made again, an edit) most often counts differently, and the recording is
     * offered again; a change that keeps the length is not told apart.
     */
    fun unmappedSessionIds(): Flow<List<String>>

    /**
     * Drops every link of the recording [sessionId] and marks the themes it was linked to stale.
     * The nodes stay, so a recording mapped again finds its themes and topics again, with their
     * pins and summaries; call [cleanup] once its new links are stored. A mark of
     * [markReadWithoutTopics] goes too.
     */
    suspend fun forgetSession(sessionId: String)

    /** Removes the topics without links, then the themes without topics. */
    suspend fun cleanup()

    /**
     * Notes that the AI read the recording [sessionId] and found no topic in it that held up, so
     * [unmappedSessionIds] stops offering it until its transcript changes. Storing a link for it,
     * [forgetSession] and deleting it remove the mark. Does nothing for a recording that is gone,
     * nor for one whose transcript changed while the AI read it (its complete transcript no longer
     * counts [chars]): that one is offered again.
     *
     * @param chars The length its complete transcript had when the AI read the recording,
     *   counted as [unmappedSessionIds] counts it
     */
    suspend fun markReadWithoutTopics(sessionId: String, chars: Int) {}

    /**
     * Runs [block] as one transaction: its writes are stored together, or not at all when it
     * throws, and what it reads is the map as the transaction sees it. Reads in it go through
     * one-shot reads such as [allNodesNow]: a flow would read outside it.
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R = block()

    /** [allNodes], read once: for reads inside [inTransaction]. */
    suspend fun allNodesNow(): List<MindNode> = allNodes().first()

    companion object {
        /**
         * The fewest characters of complete transcript a recording is mapped from. Below it there
         * is too little to find a topic in, and such recordings would make themes out of
         * microphone tests. [unmappedSessionIds] leaves them out by this rule and the mapping
         * step skips them by the same one, so they are never offered and never read.
         */
        const val MIN_TRANSCRIPT_CHARS = 40
    }
}
