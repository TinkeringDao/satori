package com.satori.domain.usecase.mindmap

import com.satori.domain.model.MindNode
import com.satori.domain.repository.MindMapRepository
import kotlinx.coroutines.flow.Flow

/**
 * [map] with what the store of a recording's map asks for besides it: the mark of a recording read
 * without topics ("skip s1 41", with the length its transcript had when read) and the transaction
 * around the store ("begin", then "commit", or "rollback" when the block throws), logged in the
 * map's own [FakeMindMapRepository.calls] so their order with its writes shows. It keeps no state
 * of its own, so a rollback undoes nothing here: that the database does is
 * MindMapRepositoryImplTest's (instrumented). Inside the transaction the nodes are read once
 * ("nodes"); reading the flow there fails, as it would read outside the transaction.
 */
class TransactionalMindMap(val map: FakeMindMapRepository) : MindMapRepository by map {

    private var inTransaction = false

    override suspend fun markReadWithoutTopics(sessionId: String, chars: Int) {
        map.calls += "skip $sessionId $chars"
    }

    override suspend fun <R> inTransaction(block: suspend () -> R): R {
        map.calls += "begin"
        inTransaction = true
        try {
            return block().also { map.calls += "commit" }
        } catch (e: Throwable) {
            map.calls += "rollback"
            throw e
        } finally {
            inTransaction = false
        }
    }

    override suspend fun allNodesNow(): List<MindNode> {
        map.calls += "nodes"
        return map.allNodesNow()
    }

    override fun allNodes(): Flow<List<MindNode>> {
        check(!inTransaction) { "A flow read inside the transaction" }
        return map.allNodes()
    }
}
