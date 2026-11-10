package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GeometryTest {
    @Test
    fun resampleSplitsSegmentsIntoEqualPartsAndKeepsEndpoints() {
        val out = resample(listOf(XY(0.0, 0.0), XY(100.0, 0.0)), 30.0)
        // ceil(100/30) = 4 parts -> 5 points
        assertEquals(5, out.size)
        assertEquals(XY(0.0, 0.0), out.first())
        assertEquals(100.0, out.last().x, 1e-9)
        assertEquals(25.0, out[1].x, 1e-9)
    }

    @Test
    fun resampleClosedEndsWhereItStarts() {
        val square = listOf(XY(0.0, 0.0), XY(100.0, 0.0), XY(100.0, 100.0), XY(0.0, 100.0))
        val out = resample(square, 10.0, closed = true)
        assertEquals(41, out.size) // 4 sides of 10 parts each + closing point
        assertEquals(out.first(), out.last())
    }

    @Test
    fun normDegWrapsIntoMinus180ToPlus180() {
        assertEquals(-170.0, normDeg(190.0), 1e-12)
        assertEquals(170.0, normDeg(-190.0), 1e-12)
        assertEquals(180.0, normDeg(-180.0), 1e-12)
        assertEquals(10.0, normDeg(370.0), 1e-12)
    }

    @Test
    fun turnDegIsPositiveForLeftTurns() {
        // heading east (0) then north (90 deg) is a left turn of +90
        assertEquals(90.0, turnDeg(0.0, Math.PI / 2), 1e-9)
        assertEquals(-90.0, turnDeg(0.0, -Math.PI / 2), 1e-9)
    }

    @Test
    fun pointGridNearestMatchesBruteForce() {
        val rnd = Random(7)
        val pts = (0 until 500).map { XY(rnd.nextDouble() * 2000 - 1000, rnd.nextDouble() * 2000 - 1000) }
        val grid = PointGrid(pts, 50.0)
        repeat(300) {
            val q = XY(rnd.nextDouble() * 2400 - 1200, rnd.nextDouble() * 2400 - 1200)
            val brute = pts.minOf { it.distanceTo(q) }
            val got = grid.nearestDist(q, 1e6)
            assertEquals(brute, got, 1e-9)
        }
    }

    @Test
    fun pointGridReturnsMaxRWhenNothingInRange() {
        val grid = PointGrid(listOf(XY(0.0, 0.0)), 50.0)
        assertEquals(100.0, grid.nearestDist(XY(1000.0, 0.0), 100.0), 1e-12)
        assertTrue(grid.nearestWithin(XY(1000.0, 0.0), 100.0) == null)
    }

    @Test
    fun countSelfIntersectionsFindsOneCrossing() {
        // bow-tie: segments 0-1 and 2-3 cross once
        val bowTie = listOf(XY(0.0, 0.0), XY(10.0, 10.0), XY(10.0, 0.0), XY(0.0, 10.0))
        assertEquals(1, countSelfIntersections(bowTie))
        val square = listOf(XY(0.0, 0.0), XY(10.0, 0.0), XY(10.0, 10.0), XY(0.0, 10.0), XY(0.0, 0.0))
        assertEquals(0, countSelfIntersections(square, closed = true))
    }
}
