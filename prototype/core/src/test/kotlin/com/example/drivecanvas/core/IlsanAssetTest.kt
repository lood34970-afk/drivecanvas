package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** End-to-end check on the bundled Ilsan assets (prototype/app/src/main/assets/canvas). */
class IlsanAssetTest {
    /** Finds [name] in the canvas asset folder by searching upward from the working directory. */
    private fun assetFile(name: String): File {
        val rel = "ideas/02-drive-canvas/prototype/app/src/main/assets/canvas/$name"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("asset not found: $rel (searched upward from ${System.getProperty("user.dir")})")
    }

    private fun describe(label: String, c: PlannedCandidate) {
        val plan = c.plan
        println(
            "Ilsan $label: S=${"%.2f".format(c.route.shape.score)} eta=${"%.1f".format(c.route.stats.etaMin)} min " +
                "len=${"%.2f".format(c.route.stats.lengthM / 1000)} km vias=${plan.viaRouteIdx.size} legs=${plan.legs.size} " +
                "waypoints=${plan.legs.map { it.waypoints.size }} legsVerified=${plan.legsVerified}",
        )
    }

    @Test
    fun ilsanPlansShapeFirstAndDrivesToCompletion() {
        val graph = assetFile("ilsan.dcg").inputStream().use { CanvasPlanner.loadGraph(it) }
        val placements = assetFile("ilsan.placements").inputStream().use { CanvasPlanner.loadPlacements(it) }

        val start = System.nanoTime()
        val result = CanvasPlanner(graph).planFromPlacements(placements.records)
        val planMs = (System.nanoTime() - start) / 1_000_000
        println("Ilsan planning time: $planMs ms (${placements.records.size} records)")

        assertTrue("expected Ok, got $result", result is PlanResult.Ok)
        val ok = result as PlanResult.Ok
        val shape = ok.shapeFirst
        describe("SHAPE", shape)
        ok.timeFirst?.let { describe("TIME", it) } ?: println("Ilsan TIME: none")

        assertTrue("shape S=${shape.route.shape.score}", shape.route.shape.score >= 70.0)
        for (leg in shape.plan.legs) {
            assertTrue("leg ${leg.index} has ${leg.waypoints.size} waypoints", leg.waypoints.size <= 2)
        }
        assertTrue("legs=${shape.plan.legs.size}", shape.plan.legs.size in 3..6)

        val simStart = System.nanoTime()
        val summary = SimulationRunner(graph, shape.plan).runToEnd()
        println("Ilsan simulation time: ${(System.nanoTime() - simStart) / 1_000_000} ms")
        assertNotNull("drive did not finish", summary)
        println("Ilsan drive: end=${summary!!.endReason} completion=${"%.3f".format(summary.completion)} " +
            "S=${"%.2f".format(summary.shapeScore)} duration=${summary.durationMs / 1000} s")
        assertEquals(EndReason.COMPLETED, summary.endReason)
        assertTrue("completion=${summary.completion}", summary.completion >= 0.95)
    }
}
