package com.satori.data.place

import android.util.Log
import com.satori.domain.place.Gazetteer
import com.satori.domain.place.LocationSource
import com.satori.domain.place.PhoneCountry
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.place.SaveRecordingPlaceUseCase
import com.satori.domain.usecase.place.SaveRecordingPlaceUseCase.Outcome
import com.satori.domain.usecase.place.TakeLocationFixUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Reads where each recording starts and stores it with the recording, while "Save the place" is
 * on ([com.satori.domain.model.AppSettings.savePlace]) and location is allowed.
 *
 * The recorder calls [onRecordingStarted] as its microphone opens, however it was started: on the
 * inner screen, on the cover or with the quick key over the lock screen. The recording never
 * waits for the position, which can take half a minute ([TakeLocationFixUseCase]). The work runs
 * in a scope of its own rather than the recorder's, so it goes on after the recording has
 * stopped, until the position comes or the wait for it is over.
 *
 * Android serves positions only to an app on screen or to a foreground service that holds the
 * location type. While a position is read [reading] is true, and the recording service holds the
 * type until it turns false, also once its recording has ended (RecordingService): a recording
 * started with the quick key, or stopped, with the screen turned off at once still gets its
 * place. The last positions the phone has are read at the start too, while the recorder is on
 * screen. If Android refuses the type, a new position comes only while Satori is on screen.
 *
 * @param saveRecordingPlace Reads and stores the place of a recording ([SaveRecordingPlaceUseCase])
 * @param info Where the field log line of each recording goes; never with a position or a name
 * @param warn Where failures are logged
 */
@Singleton
class RecordingPlaceCapture internal constructor(
    private val locationSource: LocationSource,
    private val saveRecordingPlace: suspend (sessionId: String, startedAtNanos: Long) -> Outcome,
    private val info: (String, Throwable?) -> Unit,
    private val warn: (String, Throwable?) -> Unit
) {

    @Inject
    constructor(
        locationSource: LocationSource,
        gazetteer: Gazetteer,
        transcriptionRepository: TranscriptionRepository,
        phoneCountry: PhoneCountry
    ) : this(
        locationSource = locationSource,
        saveRecordingPlace = SaveRecordingPlaceUseCase(
            takeLocationFix = TakeLocationFixUseCase(locationSource),
            gazetteer = gazetteer,
            transcriptionRepository = transcriptionRepository,
            phoneCountry = phoneCountry
        )::invoke,
        info = { message, e -> Log.i(TAG, message, e) },
        warn = { message, e -> Log.w(TAG, message, e) }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The reads in progress, by recording. Guarded by itself.
    private val inFlight = mutableMapOf<String, Job>()

    private val _reading = MutableStateFlow(false)

    /** Whether the place of a recording is being read now. */
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    /**
     * Starts reading where the recording [sessionId] starts, and returns at once. Does nothing
     * when [savePlace] is off, location is not allowed, or the place of this recording is being
     * read already (a start again after an error); a recording that has a place keeps it. Sets
     * [reading] before it returns, so a recording service started next can see it.
     */
    fun onRecordingStarted(sessionId: String, savePlace: Boolean) {
        if (!savePlace || !locationSource.isAllowed()) return
        val startedAt = locationSource.elapsedRealtimeNanos()
        val read = synchronized(inFlight) {
            if (sessionId in inFlight) return
            _reading.value = true
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    log(sessionId, saveRecordingPlace(sessionId, startedAt), startedAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("The place of recording $sessionId was not read", e)
                }
            }.also { inFlight[sessionId] = it }
        }
        // Runs however the read ends, also when it is cancelled before it began.
        read.invokeOnCompletion {
            synchronized(inFlight) {
                inFlight.remove(sessionId)
                _reading.value = inFlight.isNotEmpty()
            }
        }
        read.start()
    }

    /**
     * Stops reading the place of [sessionId], a recording the recorder has deleted because
     * nothing was said in it (DiscardSilentRecordingUseCase), at Stop or as it closed: no place
     * would be stored, and the satellites and the recording service would go on for it until the
     * position comes. Does nothing when its place isn't being read.
     */
    fun onRecordingDiscarded(sessionId: String) {
        val read = synchronized(inFlight) { inFlight[sessionId] } ?: return
        read.cancel()
        info("Recording $sessionId was discarded, its place is not read", null)
    }

    /** One line for the field log: how it went and how long it took, never where. */
    private fun log(sessionId: String, outcome: Outcome, startedAt: Long) {
        val afterMs = (locationSource.elapsedRealtimeNanos() - startedAt) / 1_000_000
        when (outcome) {
            Outcome.Gone -> info("Recording $sessionId is gone, no place stored", null)
            Outcome.Kept -> info("Recording $sessionId keeps the place it has", null)
            Outcome.NoFix -> info("No position for recording $sessionId, gave up after $afterMs ms", null)
            is Outcome.Saved -> info(
                "Place of recording $sessionId saved after $afterMs ms from ${outcome.step}, " +
                    "accuracy ${outcome.accuracyMeters?.roundToInt()?.let { "$it m" } ?: "unknown"}, named=${outcome.named}",
                outcome.listFailure
            )
            is Outcome.NotSaved -> warn("Place of recording $sessionId not saved", outcome.cause)
        }
    }

    private companion object {
        const val TAG = "SatoriPlace"
    }
}
