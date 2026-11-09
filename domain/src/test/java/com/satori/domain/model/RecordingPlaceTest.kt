package com.satori.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaceTest {

    private fun place(latitude: Double, longitude: Double) = RecordingPlace(latitude, longitude, accuracyMeters = null, name = null)

    @Test
    fun `coordinates in range are on the earth, the poles and the date line included`() {
        listOf(0.0 to 0.0, 45.5 to 7.25, 90.0 to 0.0, -90.0 to 0.0, 10.0 to 180.0, -10.0 to -180.0).forEach { (latitude, longitude) ->
            assertTrue("$latitude, $longitude", place(latitude, longitude).isOnEarth)
        }
    }

    @Test
    fun `coordinates out of range, infinite or NaN are not`() {
        listOf(
            90.01 to 0.0, -90.01 to 0.0, 0.0 to 180.01, 0.0 to -180.01,
            Double.NaN to 0.0, 0.0 to Double.NaN, Double.POSITIVE_INFINITY to 0.0, 0.0 to Double.NEGATIVE_INFINITY
        ).forEach { (latitude, longitude) ->
            assertFalse("$latitude, $longitude", place(latitude, longitude).isOnEarth)
        }
    }
}
