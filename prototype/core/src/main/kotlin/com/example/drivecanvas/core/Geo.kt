package com.example.drivecanvas.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

fun haversineMeters(a: LatLon, b: LatLon): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusMeters * asin(sqrt(h))
}

/** Local planar coordinates in metres: x east, y north. */
data class XY(val x: Double, val y: Double) {
    fun distanceTo(other: XY): Double = hypot(x - other.x, y - other.y)
}

/** Metres per degree of latitude (spherical Earth, same radius as [haversineMeters]). */
const val METERS_PER_DEG = 111194.92664455873

/** Equirectangular projection around (originLat, originLon); accurate for region-sized areas. */
class LocalProjection(val originLat: Double, val originLon: Double) {
    /** Metres per degree of longitude at the origin latitude. */
    val mPerDegLon: Double = METERS_PER_DEG * cos(Math.toRadians(originLat))

    fun toXY(p: LatLon): XY =
        XY((p.lon - originLon) * mPerDegLon, (p.lat - originLat) * METERS_PER_DEG)

    fun toLatLon(p: XY): LatLon =
        LatLon(originLat + p.y / METERS_PER_DEG, originLon + p.x / mPerDegLon)
}
