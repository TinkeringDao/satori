package com.satori.presentation.sessiondetails

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.place.PlaceLabel
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.place.DescribeRecordingPlaceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the recording was made, on its details page: how the place reads under the date
 * ([DescribeRecordingPlaceUseCase]) and removing it. It follows the stored place, so a place that
 * arrives after the page has opened (the fix came after Stop) shows as it is stored.
 *
 * @property savedStateHandle Navigation arguments: `sessionId`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordingPlaceViewModel @Inject constructor(
    private val transcriptionRepository: TranscriptionRepository,
    private val describePlace: DescribeRecordingPlaceUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) { "Session ID is required" }

    private val error = MutableStateFlow<PlaceError?>(null)

    val uiState: StateFlow<RecordingPlaceUiState> = combine(
        transcriptionRepository.getSession(sessionId)
            .map { it?.place }
            .distinctUntilChanged()
            .mapLatest { place -> place?.let { describePlace(it) } }
            // The details page reports a recording that can't be read; its place just doesn't show.
            .catch { e ->
                Log.e(TAG, "Failed to read the place of the recording", e)
                emit(null)
            },
        error
    ) { label, error -> RecordingPlaceUiState(label, error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecordingPlaceUiState())

    /** Forgets where the recording was made. A failure is reported as [PlaceError.RemoveFailed]. */
    fun removePlace() {
        viewModelScope.launch {
            transcriptionRepository.setPlace(sessionId, null)
                .onSuccess { error.value = null }
                .onFailure { e ->
                    Log.e(TAG, "Failed to remove the place of the recording", e)
                    error.value = PlaceError.RemoveFailed
                }
        }
    }

    /** The screen found no app to show the place in. */
    fun reportNoMapsApp() {
        error.value = PlaceError.NoMapsApp
    }

    fun clearError() {
        error.value = null
    }

    private companion object {
        const val TAG = "RecordingPlaceVM"

        /** How long the flows keep running after the screen is gone, to survive a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * @property label How the place reads; null while the recording has none, and for the moment
 *   its first lookup takes
 * @property error What went wrong with the place; null if nothing did
 */
data class RecordingPlaceUiState(
    val label: PlaceLabel? = null,
    val error: PlaceError? = null
)

/** What went wrong with the place of a recording. The screen words it, in the app's language. */
enum class PlaceError {
    /** The place couldn't be removed; it is still stored. */
    RemoveFailed,

    /** No app on the phone shows places on a map. */
    NoMapsApp
}
