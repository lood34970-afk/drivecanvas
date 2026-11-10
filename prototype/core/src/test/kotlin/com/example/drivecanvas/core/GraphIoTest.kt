package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringWriter

class GraphIoTest {
    private fun roundTrip(raw: RawNetwork): RawNetwork {
        val sw = StringWriter()
        GraphIo.write(raw, sw)
        return GraphIo.readRaw(ByteArrayInputStream(sw.toString().toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun roundTripKeepsCounts() {
        val raw = GridFixtures.rawGrid(21, 200.0, onewayColumns = true)
        val back = roundTrip(raw)
        assertEquals(441, back.nodes.size)
        assertEquals(840, back.segments.size)
        assertEquals(0, back.bans.size)
        assertEquals(raw.segments, back.segments)

        val g = GridFixtures.gridGraph(21, 200.0, onewayColumns = true)
        assertEquals(441, g.nodes.size)
        // 420 two-way horizontal streets (2 edges each) + 420 one-way vertical streets
        assertEquals(1260, g.edges.size)
        assertEquals(441, g.outEdges.size)
        assertEquals(441, g.inEdges.size)
    }

    @Test
    fun onewaySegmentIsOneDirectedEdgeAndTwoWayIsTwinned() {
        val raw = GridFixtures.rawGrid(5, 200.0, onewayColumns = true)
        val g = GraphIo.read(ByteArrayInputStream(writeToBytes(raw)))
        for ((s, seg) in raw.segments.withIndex()) {
            val edgesOfSeg = g.edges.filter { it.seg == s }
            if (seg.oneway) {
                assertEquals(1, edgesOfSeg.size)
                assertEquals(-1, edgesOfSeg[0].twin)
                assertEquals(-1, g.segDirToEdge[2 * s + 1])
            } else {
                assertEquals(2, edgesOfSeg.size)
                val (f, b) = edgesOfSeg
                assertEquals(b.id, f.twin)
                assertEquals(f.id, b.twin)
                assertEquals(f.tail, b.head)
                assertEquals(f.head, b.tail)
            }
        }
    }

    @Test
    fun bansAreMappedToDirectedEdges() {
        val base = GridFixtures.rawGrid(5, 200.0, onewayColumns = true)
        // seg 0 = (0,0)->(1,0), seg 2 = (1,0)->(2,0): forbid going straight along the bottom row
        val raw = base.copy(bans = listOf(RawBan(fromSeg = 0, fromDir = 0, toSeg = 2, toDir = 0)))
        val g = GraphIo.read(ByteArrayInputStream(writeToBytes(raw)))
        assertEquals(1, g.banned.size)
        val inE = g.segDirToEdge[0]
        val outE = g.segDirToEdge[4]
        assertNotNull(g.edges.getOrNull(inE))
        assertTrue(g.isBanned(inE, outE))
        assertFalse(g.isBanned(outE, inE))
    }

    @Test
    fun unsupportedMagicThrows() {
        val text = "DCG 2\nregion x\norigin 0.0 0.0\nbbox 0.0 0.0 1.0 1.0\nnodes 0\nsegments 0\nbans 0\nend\n"
        try {
            GraphIo.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unsupported graph format", e.message)
        }
    }

    private fun writeToBytes(raw: RawNetwork): ByteArray {
        val sw = StringWriter()
        GraphIo.write(raw, sw)
        return sw.toString().toByteArray(Charsets.UTF_8)
    }
}
