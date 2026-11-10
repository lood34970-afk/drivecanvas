package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementSearchTest {
    private val graph = GridFixtures.gridGraph()

    private val config = SearchConfig(
        widthsM = listOf(2600.0, 3000.0),
        rotationsDeg = listOf(0.0),
        centreStepM = 400.0,
        topPerWidth = 3,
        quality = QualityThresholds(etaMinMin = 0.0, etaMinMax = 1000.0),
    )

    @Test
    fun gridSearchFindsPassingAndShape() {
        val results = PlacementSearch(graph, config).search()
        val passing = results.filter { it.passes }
        assertTrue("passing=${passing.size} of ${results.size}", passing.isNotEmpty())
        val picks = CandidatePicker.pick(passing)
        assertNotNull(picks.firstOrNull { it.kind == CandidateKind.SHAPE })
    }

    @Test
    fun outOfBoundsPlacementPrescoreIsMinusOne() {
        val search = PlacementSearch(graph, config)
        assertEquals(-1.0, search.prescore(Placement(XY(5000.0, 0.0), 3000.0, 0.0)), 0.0)
    }
}
