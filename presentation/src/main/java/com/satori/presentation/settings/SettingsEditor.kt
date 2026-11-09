package com.satori.presentation.settings

import com.satori.domain.model.AppSettings
import com.satori.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes one change to the settings at a time, each on top of the settings as they are stored
 * when it runs. A screen's own copy can be old (the stored settings load after it opens, and a
 * finished download stores a model meanwhile), and two quick changes that each started from the
 * same copy would undo one another.
 */
internal class SettingsEditor(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val lock = Mutex()

    /** Applies [change] to the stored settings and stores the result. */
    fun edit(change: (AppSettings) -> AppSettings) {
        scope.launch { update(change) }
    }

    /**
     * Like [edit], for a caller that has to know the result is stored before it goes on, for
     * example inside a block that must not be cut short when the screen is left.
     */
    suspend fun update(change: (AppSettings) -> AppSettings) {
        lock.withLock {
            repository.updateSettings(change(repository.getSettings().first()))
        }
    }

    /**
     * Forgets the stored AI model path, in turn with the other changes: one that started from
     * the old settings could otherwise store the path again.
     */
    suspend fun clearLlmModelPath() {
        lock.withLock {
            repository.clearLlmModelPath()
        }
    }
}
