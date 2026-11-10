package com.example.drivecanvas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmImportTest {
    private val bboxMin = LatLon(37.0, 126.0)
    private val bboxMax = LatLon(38.0, 127.0)

    private fun node(id: Long, lat: Double, lon: Double) =
        """<node id="$id" visible="true" lat="$lat" lon="$lon"/>"""

    private fun way(id: Long, refs: List<Long>, vararg tags: Pair<String, String>): String {
        val sb = StringBuilder("""<way id="$id" visible="true">""").append('\n')
        for (r in refs) sb.append("""  <nd ref="$r"/>""").append('\n')
        for ((k, v) in tags) sb.append("""  <tag k="$k" v="$v"/>""").append('\n')
        sb.append("</way>")
        return sb.toString()
    }

    private fun relation(id: Long, tags: List<Pair<String, String>>, members: List<Triple<String, Long, String>>): String {
        val sb = StringBuilder("""<relation id="$id" visible="true">""").append('\n')
        for ((type, ref, role) in members) sb.append("""  <member type="$type" ref="$ref" role="$role"/>""").append('\n')
        for ((k, v) in tags) sb.append("""  <tag k="$k" v="$v"/>""").append('\n')
        sb.append("</relation>")
        return sb.toString()
    }

    private fun osm(vararg parts: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\">\n" + parts.joinToString("\n") + "\n</osm>\n"

    private fun build(vararg tiles: String): RawNetwork =
        OsmImport.buildNetwork(OsmImport.parseText(tiles.toList()), "test", bboxMin, bboxMax)

    private val nodes = arrayOf(
        node(1, 37.500, 127.000),
        node(2, 37.500, 127.002),
        node(3, 37.500, 127.004),
        node(4, 37.502, 127.002),
        node(5, 37.504, 127.002),
        node(6, 37.498, 127.000),
        node(7, 37.498, 127.004),
        node(8, 37.496, 127.002),
        node(9, 37.494, 127.002),
    )

    private val residentialLine = way(10, listOf(1, 2, 3), "highway" to "residential")

    @Test
    fun residentialLineBecomesOneSegmentWithShapePoint() {
        val net = build(osm(*nodes, residentialLine))
        assertEquals(2, net.nodes.size) // node 2 is only used once, so it is shape, not a graph node
        assertEquals(1, net.segments.size)
        val seg = net.segments[0]
        assertEquals(1, seg.shape.size)
        assertEquals(37.500, seg.shape[0].lat, 1e-9)
        assertEquals(127.002, seg.shape[0].lon, 1e-9)
        assertEquals(false, seg.oneway)
        assertTrue(net.bans.isEmpty())
    }

    @Test
    fun serviceAndPrivateAccessWaysAreDropped() {
        val service = way(11, listOf(4, 5), "highway" to "service")
        val privateRes = way(12, listOf(6, 7), "highway" to "residential", "access" to "private")
        val net = build(osm(*nodes, residentialLine, service, privateRes))
        assertEquals(1, net.segments.size)
        assertEquals(2, net.nodes.size)
    }

    @Test
    fun roundaboutAndOnewayYesAreOneway() {
        val ring = listOf(
            way(20, listOf(1, 2), "highway" to "residential", "junction" to "roundabout"),
            way(21, listOf(2, 3), "highway" to "residential", "oneway" to "yes"),
            way(22, listOf(3, 1), "highway" to "residential", "junction" to "roundabout"),
        )
        val net = build(osm(*nodes, *ring.toTypedArray()))
        assertEquals(3, net.segments.size)
        assertTrue(net.segments.all { it.oneway })
        // nodes 1,2,3 -> indices 0,1,2; directions follow the way order 1->2, 2->3, 3->1
        assertEquals(setOf(0 to 1, 1 to 2, 2 to 0), net.segments.map { it.a to it.b }.toSet())
    }

    @Test
    fun onewayMinusOneIsReversed() {
        val ways = listOf(
            way(30, listOf(1, 2), "highway" to "residential", "oneway" to "-1"),
            way(31, listOf(1, 2), "highway" to "residential", "oneway" to "yes"),
        )
        val net = build(osm(*nodes, *ways.toTypedArray()))
        assertEquals(2, net.segments.size)
        assertTrue(net.segments.all { it.oneway })
        // way 30 carries traffic 2->1 (indices 1->0); way 31 carries 1->2 (0->1)
        assertEquals(setOf(1 to 0, 0 to 1), net.segments.map { it.a to it.b }.toSet())
    }

    @Test
    fun duplicateWaysAcrossTilesAreMerged() {
        val tile = osm(*nodes, residentialLine)
        val net = build(tile, tile)
        assertEquals(1, net.segments.size)
        assertEquals(2, net.nodes.size)
    }

    @Test
    fun noLeftTurnRestrictionCreatesOneBan() {
        val from = way(40, listOf(1, 2), "highway" to "residential")
        val to = way(41, listOf(2, 4), "highway" to "residential")
        val rel = relation(
            50,
            listOf("type" to "restriction", "restriction" to "no_left_turn"),
            listOf(Triple("way", 40L, "from"), Triple("node", 2L, "via"), Triple("way", 41L, "to")),
        )
        val net = build(osm(*nodes, from, to, rel))
        // segment 0 = way 40 (node1 -> node2), segment 1 = way 41 (node2 -> node4); both two-way
        assertEquals(listOf(RawBan(0, 0, 1, 0)), net.bans)
    }

    @Test
    fun noUTurnFromSameWayCreatesNoBan() {
        val from = way(40, listOf(1, 2), "highway" to "residential")
        val to = way(41, listOf(2, 4), "highway" to "residential")
        val rel = relation(
            50,
            listOf("type" to "restriction", "restriction" to "no_u_turn"),
            listOf(Triple("way", 40L, "from"), Triple("node", 2L, "via"), Triple("way", 40L, "to")),
        )
        val net = build(osm(*nodes, from, to, rel))
        assertTrue(net.bans.isEmpty())
    }

    @Test
    fun danglingOnewaySpurIsRemovedBySccPruning() {
        val spur = way(50, listOf(2, 9), "highway" to "residential", "oneway" to "yes")
        val net = build(osm(*nodes, residentialLine, spur))
        // node 2 becomes a graph node (shared with the spur), so the two-way line splits into 1-2 and 2-3;
        // the one-way spur 2->9 leaves node 9 outside the largest SCC and is removed
        assertEquals(2, net.segments.size)
        assertTrue(net.segments.none { it.oneway })
        assertEquals(3, net.nodes.size)
    }
}
