package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class LegPlannerTest {
    private val graph = GridFixtures.gridGraph()
    private val planner = LegPlanner(graph)

    private fun idx(i: Int, j: Int): Int = i * 21 + j

    private fun dirEdge(tail: Int, head: Int): Int = graph.edges.single { it.tail == tail && it.head == head }.id

    private val loopPlan: GuidancePlan by lazy {
        val out = ShapeRouter(graph).routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertTrue("expected Found, got $out", out is RouteOutcome.Found)
        planner.plan((out as RouteOutcome.Found).route)
    }

    @Test
    fun legsHaveAtMostTwoWaypointsAndCountIsCeilOfViasOverThree() {
        val plan = loopPlan
        for (leg in plan.legs) {
            assertTrue("leg ${leg.index} has ${leg.waypoints.size} waypoints", leg.waypoints.size <= 2)
        }
        assertEquals(ceil(plan.viaRouteIdx.size / 3.0).toInt(), plan.legs.size)
        assertTrue(plan.legs.last().isFinal)
    }

    @Test
    fun finalDestinationIsStartNodeWithinOneMetre() {
        val plan = loopPlan
        val d = haversineMeters(plan.legs.last().destination, plan.start)
        assertTrue("final destination is $d m from start", d <= 1.0)
    }

    @Test
    fun legsVerifiedOnOnewayGrid() {
        assertTrue("legsVerified=${loopPlan.legsVerified}", loopPlan.legsVerified)
    }

    @Test
    fun everyViaEdgeExceptLastIsAtLeast60Metres() {
        val plan = loopPlan
        for (pos in plan.viaRouteIdx.dropLast(1)) {
            val len = graph.edges[plan.routeEdges[pos]].lengthM
            assertTrue("via at $pos is $len m", len >= 60.0)
        }
    }

    @Test
    fun straightEastboundRouteIsOneLegWithoutWaypoints() {
        val edges = (8 until 12).map { dirEdge(idx(it, 10), idx(it + 1, 10)) }
        val plan = planner.plan(edges, listOf(XY(0.0, 0.0), XY(1.0, 0.0)), 3000.0)
        assertEquals(1, plan.legs.size)
        assertTrue(plan.legs[0].waypoints.isEmpty())
        assertTrue(plan.legs[0].isFinal)
        assertTrue(plan.legsVerified)
    }
}
