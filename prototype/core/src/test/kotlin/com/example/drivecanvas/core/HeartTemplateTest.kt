package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeartTemplateTest {
    private val p = HeartTemplate.unit

    @Test
    fun hasFourHundredEightyPoints() {
        assertEquals(480, p.size)
        assertEquals(480, HeartTemplate.POINT_COUNT)
    }

    @Test
    fun tipAndCuspPositions() {
        assertEquals(0.0, p[0].x, 1e-3)
        assertEquals(-0.434211, p[0].y, 1e-3)
        assertEquals(0.0, p[240].x, 1e-3)
        assertEquals(0.285760, p[240].y, 1e-3)
    }

    @Test
    fun widthIsOneAndCentred() {
        for (q in p) assertTrue("x=${q.x}", abs(q.x) <= 0.5 + 2e-3)
        val minX = p.minOf { it.x }
        val maxX = p.maxOf { it.x }
        assertEquals(-0.5, minX, 2e-3)
        assertEquals(0.5, maxX, 2e-3)
    }

    @Test
    fun perimeterIsAboutThreePointZeroTwoZeroFour() {
        val closed = p + p[0]
        assertEquals(3.0204, polylineLength(closed), 2e-3)
    }

    @Test
    fun outlineIsClockwise() {
        var area2 = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            area2 += a.x * b.y - b.x * a.y
        }
        assertTrue("shoelace area*2 = $area2", area2 < 0)
    }

    @Test
    fun outlineIsMirrorSymmetric() {
        for (i in 1 until 240) {
            assertEquals(p[i].x, -p[480 - i].x, 1e-3)
            assertEquals(p[i].y, p[480 - i].y, 1e-3)
        }
    }

    @Test
    fun placementScalesAndRotates() {
        val unrotated = Placement(XY(100.0, 200.0), 3500.0, 0.0).place()
        assertEquals(480, unrotated.size)
        assertEquals(100.0 + 3500.0 * p[0].x, unrotated[0].x, 1e-6)
        assertEquals(200.0 + 3500.0 * p[0].y, unrotated[0].y, 1e-6)

        // 90 degrees CCW maps (x, y) -> (-y, x)
        val rotated = Placement(XY(0.0, 0.0), 1000.0, 90.0).place()
        assertEquals(-1000.0 * p[100].y, rotated[100].x, 1e-6)
        assertEquals(1000.0 * p[100].x, rotated[100].y, 1e-6)
    }
}
