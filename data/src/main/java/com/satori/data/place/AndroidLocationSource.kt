package com.satori.data.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.LocationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The phone's positions through Android's LocationManager. Only while the app may read the
 * location ([isAllowed]): it asks for no background access, so a request made while the screen
 * is off is served only while the recording service holds the location type (RecordingService).
 */
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    override fun isAllowed(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun lastFix(provider: LocationProvider): LocationFix? {
        val manager = manager ?: return null
        if (!isAllowed()) return null
        return try {
            manager.getLastKnownLocation(nameOf(provider))?.toFix()
        } catch (e: SecurityException) {
            // Taken away between the check and the call.
            null
        } catch (e: IllegalArgumentException) {
            // A phone without this provider.
            null
        }
    }

    override suspend fun currentFix(provider: LocationProvider): LocationFix? {
        val manager = manager ?: return null
        if (!isAllowed()) return null
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                // Android answers once: with a position, or with null when the provider is off or gives up.
                manager.getCurrentLocation(nameOf(provider), signal, Runnable::run) { location ->
                    continuation.resume(location?.toFix())
                }
            } catch (e: SecurityException) {
                continuation.resume(null)
            } catch (e: IllegalArgumentException) {
                continuation.resume(null)
            }
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun nameOf(provider: LocationProvider): String = when (provider) {
        LocationProvider.GPS -> LocationManager.GPS_PROVIDER
        LocationProvider.NETWORK -> LocationManager.NETWORK_PROVIDER
        LocationProvider.PASSIVE -> LocationManager.PASSIVE_PROVIDER
    }

    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        elapsedRealtimeNanos = elapsedRealtimeNanos
    )
}
