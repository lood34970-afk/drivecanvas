package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviEmulatorTest {
    private val n = 5

    private fun idx(i: Int, j: Int): Int = i * n + j

    private fun dirEdge(g: RoadGraph, tail: Int, head: Int): Int =
        g.edges.single { it.tail == tail && it.head == head }.id

    /** Segment index and direction (0 = a->b) of the raw segment joining two nodes. */
    private fun segDir(raw: RawNetwork, tail: Int, head: Int): Pair<Int, Int> {
        val s = raw.segments.indexOfFirst { (it.a == tail && it.b == head) || (it.a == head && it.b == tail) }
        require(s >= 0) { "no segment $tail-$head" }
        return s to (if (raw.segments[s].a == tail) 0 else 1)
    }

    /** Two-way 5x5 grid, 100 m, with the left turn from (1,1)->(2,1) onto (2,1)->(2,2) banned. */
    private val graph: RoadGraph by lazy {
        val base = GridFixtures.rawGrid(n = n, spacingM = 100.0, onewayColumns = false)
        val (fromSeg, fromDir) = segDir(base, idx(1, 1), idx(2, 1))
        val (toSeg, toDir) = segDir(base, idx(2, 1), idx(2, 2))
        RoadGraph.build(base.copy(bans = listOf(RawBan(fromSeg, fromDir, toSeg, toDir))))
    }

    @Test
    fun routeAroundBannedLeftTurnNeverContainsBannedPair() {
        val navi = NaviEmulator(graph)
        val start = dirEdge(graph, idx(1, 1), idx(2, 1))
        val target = dirEdge(graph, idx(2, 1), idx(2, 2))
        val path = navi.routeThrough(start, listOf(target), NaviModel.TIME)
        assertNotNull("path must exist around the ban", path)
        val p = path!!
        assertEquals(start, p.first())
        assertEquals(target, p.last())
        for (k in 0 until p.size - 1) {
            assertFalse("banned pair at $k", graph.isBanned(p[k], p[k + 1]))
        }
    }

    @Test
    fun treeLabelsAccumulateAlongStraightRoad() {
        val navi = NaviEmulator(graph)
        val start = dirEdge(graph, idx(0, 0), idx(1, 0))
        val far = dirEdge(graph, idx(3, 0), idx(4, 0))
        val tree = navi.tree(start, NaviModel.DIST)
        assertEquals(0.0, tree.label[start], 0.0)
        // Label is the cost to the END of an edge: the three edges after the start weigh 10 each (length/10), no turns.
        assertEquals(30.0, tree.label[far], 1e-6)
        val expected = listOf(
            start,
            dirEdge(graph, idx(1, 0), idx(2, 0)),
            dirEdge(graph, idx(2, 0), idx(3, 0)),
            far,
        )
        assertEquals(expected, tree.pathTo(far))
    }

    @Test
    fun routeToNodeEndsWithInEdgeOfNode() {
        val navi = NaviEmulator(graph)
        val start = dirEdge(graph, idx(0, 0), idx(1, 0))
        val node = idx(3, 3)
        val path = navi.routeToNode(start, node, NaviModel.TIME)
        assertNotNull(path)
        val p = path!!
        assertEquals(start, p.first())
        assertEquals(node, graph.edges[p.last()].head)
        assertTrue(p.size >= 2)
    }
}
