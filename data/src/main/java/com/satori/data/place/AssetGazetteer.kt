package com.satori.data.place

import android.content.Context
import com.satori.domain.place.Gazetteer
import com.satori.domain.place.GazetteerPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline list of places bundled with the app ([PlaceFile.ASSET]). It is read once, the first
 * time a place is named or shown, and then kept: about 2 MB of memory, against a read of the
 * whole asset for every recording.
 */
@Singleton
class AssetGazetteer @Inject constructor(
    @ApplicationContext private val context: Context
) : Gazetteer {

    private val loading = Mutex()

    @Volatile
    private var index: PlaceIndex? = null

    /** Looks up on a background thread, also when called from the main thread. */
    override suspend fun placesNear(latitude: Double, longitude: Double, radiusMeters: Double): List<GazetteerPlace> {
        val places = index ?: load()
        return withContext(Dispatchers.Default) { places.placesNear(latitude, longitude, radiusMeters) }
    }

    /** Reads the asset, once even when two lookups come at the same time. A failed read is tried again next time. */
    private suspend fun load(): PlaceIndex = loading.withLock {
        index ?: withContext(Dispatchers.IO) {
            context.assets.open(PlaceFile.ASSET).use { PlaceFile.read(it.readBytes()) }
        }.also { index = it }
    }
}
