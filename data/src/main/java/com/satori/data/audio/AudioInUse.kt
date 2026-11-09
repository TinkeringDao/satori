package com.satori.data.audio

import com.satori.domain.repository.AudioUsage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioUsage] for the whole app: how many readers each recording's audio has, in memory. A
 * process that dies reads nothing any more, so nothing needs to outlast it.
 */
@Singleton
class AudioInUse @Inject constructor() : AudioUsage {

    private val readers = HashMap<String, Int>()

    @Synchronized
    override fun begin(sessionId: String) {
        readers[sessionId] = (readers[sessionId] ?: 0) + 1
    }

    @Synchronized
    override fun end(sessionId: String) {
        val count = readers[sessionId] ?: return
        if (count > 1) readers[sessionId] = count - 1 else readers.remove(sessionId)
    }

    @Synchronized
    override fun inUse(): Set<String> = readers.keys.toSet()
}
