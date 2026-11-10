package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class MetricsTest {
    private val w = 3500.0
    private val template = Placement(XY(0.0, 0.0), w, 0.0).place()
    private val closedRoute = template + template[0]

    private fun score(route: List<XY>): Double = Metrics.shapeMetrics(route, template, w).score

    @Test
    fun identicalShapeScoresHundred() {
        assertEquals(100.0, score(closedRoute), 0.01)
    }

    @Test
    fun shiftedByFivePercentOfWidthScoresBetween60And78() {
        val shifted = closedRoute.map { XY(it.x + 0.05 * w, it.y) }
        val s = score(shifted)
        assertTrue("S=$s", s in 60.0..78.0)
    }

    @Test
    fun boundingEllipseScoresAtMost35() {
        val ellipse = (0 until 480).map { i ->
            val t = 2 * Math.PI * i / 480
            XY(0.5 * w * cos(t), 0.4342 * w * sin(t))
        }
        val s = score(ellipse + ellipse[0])
        assertTrue("S=$s", s <= 35.0)
    }

    @Test
    fun circleOfRadius045WidthScoresAtMost35() {
        val circle = (0 until 480).map { i ->
            val t = 2 * Math.PI * i / 480
            XY(0.45 * w * cos(t), 0.45 * w * sin(t))
        }
        val s = score(circle + circle[0])
        assertTrue("S=$s", s <= 35.0)
    }

    @Test
    fun firstHalfScoresLowAndCompletionIsHalf() {
        val half = template.subList(0, 240)
        val s = score(half)
        assertTrue("S=$s", s <= 55.0)
        val completion = Metrics.completion(closedRoute, half)
        assertEquals(0.5, completion, 0.05)
    }

    @Test
    fun tenMetreNoiseScoresAtLeast95() {
        val rnd = Random(1)
        val noisy = closedRoute.map { XY(it.x + rnd.nextGaussian() * 10.0, it.y + rnd.nextGaussian() * 10.0) }
        val s = score(noisy)
        assertTrue("S=$s", s >= 95.0)
    }

    @Test
    fun routeStatsOnStraightGridRoute() {
        val n = 21
        val g = GridFixtures.gridGraph(n, 200.0, onewayColumns = true)
        val row = 10
        // eastbound along row 10: nodes (i,row) -> (i+1,row), node index = i*n + j
        val route = (0 until 20).map { i ->
            val tail = i * n + row
            val head = (i + 1) * n + row
            g.edges.single { it.tail == tail && it.head == head }.id
        }
        val stats = Metrics.routeStats(g, route)
        // coordinates went through the 7-decimal DCG asset, so allow centimetre-level error
        assertEquals(4000.0, stats.lengthM, 0.5)
        assertEquals(0, stats.turns)
        assertEquals(0.0, stats.reuseRatio, 1e-12)
        assertEquals(0.0, stats.overlapRatio, 1e-12)
        assertEquals(0, stats.selfIntersections)
        assertEquals(1.25 * stats.freeFlowS / 60.0, stats.etaMin, 1e-9)
    }

    @Test
    fun cleanTrackDropsTeleportingFixes() {
        data class Fix(val t: Long, val x: Double)
        val fixes = listOf(Fix(0, 0.0), Fix(1000, 10.0), Fix(2000, 500.0), Fix(3000, 20.0))
        val kept = Metrics.cleanTrack(fixes, { it.t }, { XY(it.x, 0.0) })
        assertEquals(listOf(0L, 1000L, 3000L), kept.map { it.t })
    }
}
