package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPlannerTest {
    private val graph = GridFixtures.gridGraph()
    private val planner = CanvasPlanner(graph, QualityThresholds(etaMinMin = 0.0, etaMinMax = 1000.0))

    private fun record(kind: CandidateKind, x: Double, y: Double, widthM: Double, rotDeg: Double): PlacementRecord =
        PlacementRecord(kind, graph.projection.toLatLon(XY(x, y)), widthM, rotDeg, 0.0, 0.0, 0.0)

    @Test
    fun endToEndOkWithLegs() {
        val result = planner.planFromPlacements(listOf(record(CandidateKind.SHAPE, 0.0, 0.0, 3000.0, 0.0)))
        assertTrue("expected Ok, got $result", result is PlanResult.Ok)
        val ok = result as PlanResult.Ok
        assertEquals("모양 우선", ok.shapeFirst.labelKo)
        assertEquals(CandidateKind.SHAPE, ok.shapeFirst.kind)
        assertTrue(ok.shapeFirst.plan.legs.isNotEmpty())
        assertTrue(ok.shapeFirst.plan.legs.all { it.waypoints.size <= 2 })
        assertTrue(ok.shapeFirst.plan.legs.last().isFinal)
    }

    @Test
    fun unroutablePlacementGivesNoRouteWithKoreanReason() {
        val result = planner.planFromPlacements(listOf(record(CandidateKind.SHAPE, 5000.0, 5000.0, 3000.0, 0.0)))
        assertEquals(PlanResult.NoRoute(listOf("출발점 근처에 차량 도로가 없어요")), result)
    }
}
