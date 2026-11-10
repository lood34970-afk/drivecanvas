package com.example.drivecanvas.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun distanceBetweenNearbyPointsIsAboutNineHundredMeters() {
        val d = haversineMeters(LatLon(36.5, 127.25), LatLon(36.5, 127.26))
        assertTrue("distance was $d m", d in 890.0..900.0)
    }
}
