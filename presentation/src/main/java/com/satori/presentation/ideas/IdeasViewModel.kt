package com.satori.presentation.ideas

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.repository.TodoRepository
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * ViewModel for the Ideas page: the ideas found in recordings, by recording, and what can be done
 * to one. Making an idea a to-do or deleting it takes it off the page at once, as the page lists
 * only what is stored as an idea; a delete is kept as dismissed, so Undo can bring it back.
 *
 * The changes run one at a time, in the order asked for: an Undo tapped while its delete is still
 * being stored would otherwise find the idea not yet deleted, do nothing, and lose the idea.
 *
 * @property todoActions What can be done to an idea; each ends in rescheduling reminders
 */
@HiltViewModel
class IdeasViewModel @Inject constructor(
    todoRepository: TodoRepository,
    getAllSessionsUseCase: GetAllSessionsUseCase,
    private val todoActions: TodoActions
) : ViewModel() {

    private companion object {
        const val TAG = "IdeasViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }

    private val error = MutableStateFlow<IdeasError?>(null)

    private val changes = Mutex()

    val uiState: StateFlow<IdeasUiState> = combine(
        todoRepository.observeIdeas().catch { e ->
            Log.e(TAG, "Failed to load ideas", e)
            error.value = IdeasError.Load
            emit(emptyList())
        },
        // Without the recordings every idea would read as one whose recording was deleted.
        getAllSessionsUseCase().catch { e ->
            Log.e(TAG, "Failed to load the recordings of the ideas", e)
            error.value = IdeasError.Load
            emit(emptyList())
        },
        error
    ) { ideas, recordings, failure ->
        IdeasUiState(groups = IdeasUiState.group(ideas, recordings), isLoading = false, error = failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), IdeasUiState())

    /** Makes the idea [id] an open to-do, which home lists from now on. */
    fun makeTodo(id: String) = change { todoActions.makeTodo(id, TodoSource.IDEAS) }

    /** Deletes the idea [id]; [restoreIdea] takes that back. */
    fun deleteIdea(id: String) = change { todoActions.dismissIdea(id, TodoSource.IDEAS) }

    /** Takes [deleteIdea] back (Undo): [id] is an idea again, where it was on the page. */
    fun restoreIdea(id: String) = change { todoActions.restoreIdea(id) }

    fun clearError() {
        error.value = null
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                changes.withLock { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change an idea", e)
                error.value = IdeasError.Change
            }
        }
    }
}
