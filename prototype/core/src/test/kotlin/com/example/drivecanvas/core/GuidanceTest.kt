package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class GuidanceTest {
    private val graph = GridFixtures.gridGraph()

    private fun idx(i: Int, j: Int): Int = i * 21 + j

    private fun dirEdge(tail: Int, head: Int): Int = graph.edges.single { it.tail == tail && it.head == head }.id

    /** Oneway-grid loop from the router (multi-leg). */
    private val loopPlan: GuidancePlan by lazy {
        val out = ShapeRouter(graph).routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertTrue("expected Found, got $out", out is RouteOutcome.Found)
        LegPlanner(graph).plan((out as RouteOutcome.Found).route)
    }

    /** Straight 2 km eastbound street (one leg), used for off-route scripts. */
    private val linePlan: GuidancePlan by lazy {
        val edges = (0 until 10).map { dirEdge(idx(it, 10), idx(it + 1, 10)) }
        LegPlanner(graph).plan(edges, listOf(XY(0.0, 0.0), XY(1.0, 0.0)), 3000.0)
    }

    /** Point at arc length [s] along the plan, shifted [offsetN] metres north. */
    private fun at(plan: GuidancePlan, s: Double, offsetN: Double = 0.0): LatLon {
        val p = pointAlong(plan.routeXY, s)
        return plan.projection.toLatLon(XY(p.x, p.y + offsetN))
    }

    /** Feeds events and answers every route request with RouteStarted, as the navi would. */
    private class Driver(val engine: GuidanceEngine) {
        val commands = ArrayList<GuidanceCommand>()

        fun send(ev: GuidanceEvent) {
            val out = engine.handle(ev)
            commands.addAll(out)
            for (c in out) {
                if (c is GuidanceCommand.RequestRoute) send(GuidanceEvent.RouteStarted(ev.timeMs))
            }
        }

        fun summary(): DriveSummary? = commands.filterIsInstance<GuidanceCommand.Finish>().lastOrNull()?.summary

        fun hasAnnounce(text: String): Boolean =
            commands.any { it is GuidanceCommand.Announce && it.textKo == text }
    }

    @Test
    fun happyPathCompletesWithFullCoverage() {
        val plan = loopPlan
        val d = Driver(GuidanceEngine(plan))
        var t = 0L
        var s = 0.0
        d.send(GuidanceEvent.Start(t))
        for (leg in plan.legs) {
            val endS = if (leg.isFinal) polylineLength(plan.routeXY) else leg.targetRouteS.last()
            while (s < endS) {
                s = min(endS, s + 10.0)
                t += 1000
                d.send(GuidanceEvent.Location(t, at(plan, s), 0.0))
            }
            // The navi reports its arrival only while it is still driving this leg. If the engine already
            // moved on (fallback arrival fires up to 30 m before the destination), the old leg is gone.
            t += 1000
            if (d.engine.currentLegIndex == leg.index) d.send(GuidanceEvent.DestinationArrived(t))
        }
        val summary = d.summary()
        assertNotNull("no Finish command", summary)
        assertEquals(EndReason.COMPLETED, summary!!.endReason)
        assertTrue("completion=${summary.completion}", summary.completion >= 0.98)
        assertEquals(GuidanceState.FINISHED, d.engine.state)
        assertTrue(d.hasAnnounce(GuidanceEngine.TEXT_COMPLETED))
    }

    @Test
    fun tenFixesFarOffRouteThenBackRejoins() {
        val plan = linePlan
        val engine = GuidanceEngine(plan)
        val d = Driver(engine)
        var t = 0L
        d.send(GuidanceEvent.Start(t))
        for (s in 10..1500 step 10) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, s.toDouble()), 90.0))
        }
        assertEquals(GuidanceState.GUIDING, engine.state)

        // 10 fixes 150 m north of the street: held for at least 8 s -> OFF_ROUTE.
        repeat(10) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, 1500.0, 150.0), 0.0))
        }
        assertEquals(GuidanceState.OFF_ROUTE, engine.state)
        assertTrue(d.hasAnnounce(GuidanceEngine.TEXT_OFF_ROUTE))

        // Back on the street for 6 s (held at least 5 s) -> GUIDING.
        for (k in 1..7) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, 1500.0 + 10.0 * k), 90.0))
        }
        assertEquals(GuidanceState.GUIDING, engine.state)
        assertEquals(0, engine.currentLegIndex)
    }

    @Test
    fun giveUpSendsHomeRequestThenEndsEarlyOffRoute() {
        val plan = linePlan
        val d = Driver(GuidanceEngine(plan, GuidanceConfig.demo()))
        var t = 0L
        d.send(GuidanceEvent.Start(t))
        for (s in 10..1500 step 10) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, s.toDouble()), 90.0))
        }
        repeat(40) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, 1500.0, 150.0), 0.0))
        }
        val home = d.commands.filterIsInstance<GuidanceCommand.RequestRoute>().last { it.legIndex == -1 }
        assertTrue(home.waypoints.isEmpty())
        assertEquals(plan.start, home.destination)
        assertTrue(d.hasAnnounce(GuidanceEngine.TEXT_GIVE_UP))
        assertEquals(GuidanceState.ENDING_EARLY, d.engine.state)

        d.send(GuidanceEvent.DestinationArrived(t + 1000))
        val summary = d.summary()
        assertNotNull(summary)
        assertEquals(EndReason.ENDED_EARLY_OFF_ROUTE, summary!!.endReason)
        assertEquals(GuidanceState.FINISHED, d.engine.state)
    }

    @Test
    fun legRequestTimeoutRetriesOnceThenErrors() {
        val engine = GuidanceEngine(linePlan)
        val first = engine.handle(GuidanceEvent.Start(0))
        assertEquals(1, first.count { it is GuidanceCommand.RequestRoute })

        val retry = engine.handle(GuidanceEvent.Tick(20_000))
        assertEquals(1, retry.count { it is GuidanceCommand.RequestRoute })
        assertEquals(GuidanceState.LEG_REQUESTED, engine.state)

        val end = engine.handle(GuidanceEvent.Tick(40_000))
        assertTrue(end.any { it is GuidanceCommand.Announce && it.textKo == GuidanceEngine.TEXT_NO_RESPONSE })
        val finish = end.filterIsInstance<GuidanceCommand.Finish>().single()
        assertEquals(EndReason.ERROR, finish.summary.endReason)
        assertEquals(GuidanceState.FINISHED, engine.state)
    }

    @Test
    fun userEndEarlyHeadsHomeThenEndsWithUserReason() {
        val plan = linePlan
        val d = Driver(GuidanceEngine(plan))
        d.send(GuidanceEvent.Start(0))
        d.send(GuidanceEvent.Location(1000, at(plan, 10.0), 90.0))
        d.send(GuidanceEvent.UserEndEarly(2000))
        assertEquals(GuidanceState.ENDING_EARLY, d.engine.state)
        assertTrue(d.commands.any { it is GuidanceCommand.RequestRoute && it.legIndex == -1 })
        assertTrue(d.hasAnnounce(GuidanceEngine.TEXT_USER_END))

        d.send(GuidanceEvent.DestinationArrived(60_000))
        assertEquals(EndReason.ENDED_EARLY_USER, d.summary()!!.endReason)
    }

    @Test
    fun routeCancelledFinishesAsCancelled() {
        val engine = GuidanceEngine(linePlan)
        engine.handle(GuidanceEvent.Start(0))
        val out = engine.handle(GuidanceEvent.RouteCancelled(1000))
        val finish = out.filterIsInstance<GuidanceCommand.Finish>().single()
        assertEquals(EndReason.CANCELLED, finish.summary.endReason)
        assertEquals(GuidanceState.FINISHED, engine.state)
        assertTrue(engine.handle(GuidanceEvent.Tick(2000)).isEmpty())
    }

    @Test
    fun progressNeverDecreases() {
        val plan = linePlan
        val engine = GuidanceEngine(plan)
        val d = Driver(engine)
        var t = 0L
        d.send(GuidanceEvent.Start(t))
        val positions = (10..500 step 10).map { it.toDouble() } +
            listOf(490.0, 480.0, 470.0) +
            (480..700 step 10).map { it.toDouble() }
        var last = -1.0
        for (s in positions) {
            t += 1000
            d.send(GuidanceEvent.Location(t, at(plan, s), 90.0))
            val p = engine.progressFraction
            assertTrue("progress fell to $p at s=$s (was $last)", p >= last)
            last = p
        }
        assertEquals(GuidanceState.GUIDING, engine.state)
    }
}
