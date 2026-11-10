package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShapeRouterTest {
    private val graph = GridFixtures.gridGraph()

    private fun foundRoute(): EvaluatedRoute {
        val out = ShapeRouter(graph).routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertTrue("expected Found, got $out", out is RouteOutcome.Found)
        return (out as RouteOutcome.Found).route
    }

    private val route: EvaluatedRoute by lazy { foundRoute() }

    @Test
    fun onewayGridCentreRoutesFound() {
        assertTrue(route.edges.isNotEmpty())
    }

    @Test
    fun shapeScoreAtLeast65() {
        assertTrue("S=${route.shape.score}", route.shape.score >= 65.0)
    }

    @Test
    fun shapeMetricsWithinGates() {
        assertTrue("nmsd=${route.shape.nmsd}", route.shape.nmsd <= 0.035)
        assertTrue("tip=${route.shape.tipD} cusp=${route.shape.cuspD}", route.shape.tipD <= 0.065 && route.shape.cuspD <= 0.065)
    }

    @Test
    fun overlapAndReuseWithinGates() {
        assertTrue("overlap=${route.stats.overlapRatio}", route.stats.overlapRatio <= 0.05)
        assertTrue("reuse=${route.stats.reuseRatio}", route.stats.reuseRatio <= 0.05)
    }

    @Test
    fun noSelfIntersectionsAndTurnsWithinGate() {
        assertEquals(0, route.stats.selfIntersections)
        assertTrue("turnsPerKm=${route.stats.turnsPerKm}", route.stats.turnsPerKm <= 3.0)
    }

    @Test
    fun loopIsContiguousAndLegal() {
        val edges = route.edges
        val start = graph.edges[edges.first()].tail
        assertEquals(start, graph.edges[edges.last()].head)
        for (i in 0 until edges.size - 1) {
            val a = graph.edges[edges[i]]
            val b = graph.edges[edges[i + 1]]
            assertEquals("edges not contiguous at $i", a.head, b.tail)
            assertFalse("twin follows own edge at $i", b.id == a.twin)
            val th = turnDeg(a.headingOut, b.headingIn)
            assertTrue("U-turn $th at $i", abs(th) <= 165.0)
        }
    }

    @Test
    fun verticalEdgesObeyColumnDirection() {
        val n = 21
        val off = (n - 1) * 200.0 / 2
        for (id in route.edges) {
            val e = graph.edges[id]
            val t = graph.nodes[e.tail]
            val h = graph.nodes[e.head]
            if (abs(t.x - h.x) > 1e-6) continue
            val col = Math.round((t.x + off) / 200.0).toInt()
            if (col % 2 == 0) {
                assertTrue("even column $col must go north", h.y > t.y)
            } else {
                assertTrue("odd column $col must go south", h.y < t.y)
            }
        }
    }

    @Test
    fun threeByThreeGridIsStartTooFar() {
        val small = GridFixtures.gridGraph(n = 3)
        val out = ShapeRouter(small).routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertEquals(RouteOutcome.NoRoute(NoRouteReason.START_TOO_FAR), out)
    }

    @Test
    fun turnsGateFailureReportedWhenThresholdTight() {
        val strict = ShapeRouter(graph, quality = QualityThresholds(turnsPerKmMax = 1.0))
        val out = strict.routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertTrue("expected Found, got $out", out is RouteOutcome.Found)
        val r = (out as RouteOutcome.Found).route
        assertTrue("gates=${r.gateFailures}", r.gateFailures.contains("turns"))
        assertFalse(r.passes)
    }
}
