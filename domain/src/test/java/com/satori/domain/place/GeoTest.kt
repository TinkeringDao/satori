package com.satori.domain.place

import org.junit.Assert.assertEquals
import org.junit.Test

/** Great-circle distances, also across the date line, at the poles and between opposite points. */
class GeoTest {

    @Test
    fun `a degree of latitude is about 111 km everywhere`() {
        assertEquals(Geo.METERS_PER_DEGREE, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 0.001)
        assertEquals(Geo.METERS_PER_DEGREE, Geo.distanceMeters(60.0, 13.0, 61.0, 13.0), 0.001)
        assertEquals(111_195.0, Geo.METERS_PER_DEGREE, 1.0)
    }

    @Test
    fun `Cologne to Bonn`() {
        assertEquals(24_300.0, Geo.distanceMeters(50.9375, 6.9603, 50.7374, 7.0982), 200.0)
    }

    @Test
    fun `across the date line the distance is the short way round`() {
        assertEquals(0.2 * Geo.METERS_PER_DEGREE, Geo.distanceMeters(0.0, 179.9, 0.0, -179.9), 1.0)
    }

    @Test
    fun `at a pole every longitude is the same point`() {
        assertEquals(0.0, Geo.distanceMeters(90.0, 0.0, 90.0, 123.0), 0.001)
        assertEquals(0.0, Geo.distanceMeters(-90.0, -45.0, -90.0, 170.0), 0.001)
    }

    @Test
    fun `opposite points are half the circumference apart`() {
        assertEquals(Math.PI * Geo.EARTH_RADIUS_METERS, Geo.distanceMeters(0.0, 0.0, 0.0, 180.0), 1.0)
        assertEquals(Math.PI * Geo.EARTH_RADIUS_METERS, Geo.distanceMeters(90.0, 0.0, -90.0, 0.0), 1.0)
    }
}
