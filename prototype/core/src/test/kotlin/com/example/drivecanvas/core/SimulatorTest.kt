package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorTest {
    private val graph = GridFixtures.gridGraph()

    /** Oneway-grid loop from the router, planned into legs. */
    private val plan: GuidancePlan by lazy {
        val out = ShapeRouter(graph).routeAndRepair(Placement(XY(0.0, 0.0), 3000.0, 0.0))
        assertTrue("expected Found, got $out", out is RouteOutcome.Found)
        LegPlanner(graph).plan((out as RouteOutcome.Found).route)
    }

    @Test
    fun sameSeedGivesIdenticalTrack() {
        val a = SimulationRunner(graph, plan, SimConfig(seed = 7L))
        a.start()
        val sa = a.runToEnd()
        val b = SimulationRunner(graph, plan, SimConfig(seed = 7L))
        b.start()
        val sb = b.runToEnd()
        assertNotNull(sa)
        assertEquals(a.engine.track, b.engine.track)
        assertEquals(sa, sb)
    }

    @Test
    fun noDeviationCompletesOnTheRoute() {
        val runner = SimulationRunner(graph, plan)
        runner.start()
        val summary = runner.runToEnd()
        assertNotNull("drive did not finish", summary)
        assertEquals(EndReason.COMPLETED, summary!!.endReason)
        assertTrue("completion=${summary.completion}", summary.completion >= 0.95)
        assertTrue("S=${summary.shapeScore}", summary.shapeScore >= 60.0)
    }

    @Test
    fun deviationWithDemoConfigPassesOffRouteAndEndsEarly() {
        val runner = SimulationRunner(
            graph,
            plan,
            SimConfig(deviation = DeviationScenario()),
            GuidanceConfig.demo(),
        )
        runner.start()
        val seen = HashSet<GuidanceState>()
        while (!runner.finished && runner.simTimeMs < 4L * 60L * 60L * 1000L) {
            runner.step()
            seen.add(runner.engine.state)
        }
        assertTrue("states seen=$seen", seen.contains(GuidanceState.OFF_ROUTE))
        assertTrue("states seen=$seen", seen.contains(GuidanceState.ENDING_EARLY))
        val summary = runner.summary
        assertNotNull("drive did not finish", summary)
        assertEquals(EndReason.ENDED_EARLY_OFF_ROUTE, summary!!.endReason)
        assertTrue("completion=${summary.completion}", summary.completion in 0.3..0.9)
    }

    @Test
    fun userEndEarlyAtFortyPercentDrivesHomeAndEndsEarlyUser() {
        val runner = SimulationRunner(graph, plan)
        runner.start()
        while (!runner.finished && runner.engine.progressFraction < 0.4) runner.step()
        assertTrue("drive finished before 40%", !runner.finished)
        val commands = runner.userEndEarly()
        assertTrue(
            "no home request in $commands",
            commands.any { it is GuidanceCommand.RequestRoute && it.leg == null },
        )
        val summary = runner.runToEnd()
        assertNotNull("drive did not finish", summary)
        assertEquals(EndReason.ENDED_EARLY_USER, summary!!.endReason)
        assertTrue("completion=${summary.completion}", summary.completion in 0.2..0.7)
    }
}
