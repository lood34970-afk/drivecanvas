package com.example.drivecanvas.core

/**
 * One navigation leg: the navi is asked to drive through [targetEdges] (in order) and stop at
 * [destination]. [waypoints] are the intermediate targets (at most two). [targetRouteIdx] are the
 * positions of the targets in [GuidancePlan.routeEdges]; [targetRouteS] their arc length along the
 * route at the target edge midpoints (metres).
 */
data class Leg(
    val index: Int,
    val waypoints: List<LatLon>,
    val destination: LatLon,
    val targetRouteIdx: List<Int>,
    val targetEdges: List<Int>,
    val targetRouteS: List<Double>,
    val isFinal: Boolean,
)

/**
 * Everything the guidance engine and the simulator need about one planned loop. [routeEdges] are
 * the directed edge ids in driving order; [routeXY] is their polyline; [samples] are 10 m plan
 * samples along it with arc length [sampleS]; [viaRouteIdx] are the via positions in [routeEdges];
 * [legsVerified] is true when every leg re-routes (TIME model) to exactly the planned edges.
 */
class GuidancePlan(
    val startNode: Int,
    val start: LatLon,
    val routeEdges: List<Int>,
    val routeXY: List<XY>,
    val routeLatLon: List<LatLon>,
    val samples: List<XY>,
    val sampleS: List<Double>,
    val legs: List<Leg>,
    val templateXY: List<XY>,
    val widthM: Double,
    val viaRouteIdx: List<Int>,
    val legsVerified: Boolean,
    val projection: LocalProjection,
)

/** Splits a route into navi legs (SPEC 3.6). */
class LegPlanner(private val graph: RoadGraph) {
    private val navi = NaviEmulator(graph)
    private val spatial = EdgeSpatialIndex(graph, 100.0)

    fun plan(route: EvaluatedRoute): GuidancePlan = plan(route.edges, route.template, route.placement.widthM)

    /** Plans legs for a raw loop of [edges] (at least two, contiguous). [template] is kept for progress/score use. */
    fun plan(edges: List<Int>, template: List<XY>, widthM: Double): GuidancePlan {
        require(edges.size >= 2) { "route needs at least two edges" }
        val seq = edges.toList()
        val n = seq.size

        val edgeStartS = DoubleArray(n)
        for (k in 1 until n) edgeStartS[k] = edgeStartS[k - 1] + graph.edges[seq[k - 1]].lengthM

        val split = splitVias(seq)
        val vias = split.vias
        val legs = ArrayList<Leg>()
        var allVerified = true
        var legStart = 0
        var i = 0
        while (i < vias.size) {
            val chunk = vias.subList(i, minOf(i + 3, vias.size)).toList()
            val last = chunk.last()
            val isFinal = i + chunk.size == vias.size
            val targetEdges = chunk.map { seq[it] }

            val planned = seq.subList(legStart, last + 1).toList()
            val routed = navi.routeThrough(seq[legStart], targetEdges, NaviModel.TIME)
            if (routed != planned) allVerified = false

            val destination = if (isFinal) {
                graph.nodeLatLon(graph.edges[seq[n - 1]].head)
            } else {
                midpointLatLon(seq[last])
            }
            legs.add(
                Leg(
                    index = legs.size,
                    waypoints = chunk.dropLast(1).map { midpointLatLon(seq[it]) },
                    destination = destination,
                    targetRouteIdx = chunk,
                    targetEdges = targetEdges,
                    targetRouteS = chunk.map { edgeStartS[it] + graph.edges[seq[it]].lengthM / 2 },
                    isFinal = isFinal,
                ),
            )
            legStart = last
            i += chunk.size
        }

        val routeXY = Metrics.routePolyline(graph, seq)
        val samples = resample(routeXY, SAMPLE_M)
        return GuidancePlan(
            startNode = graph.edges[seq[0]].tail,
            start = graph.nodeLatLon(graph.edges[seq[0]].tail),
            routeEdges = seq,
            routeXY = routeXY,
            routeLatLon = routeXY.map { graph.projection.toLatLon(it) },
            samples = samples,
            sampleS = cumulativeLengths(samples).toList(),
            legs = legs,
            templateXY = template,
            widthM = widthM,
            viaRouteIdx = vias,
            legsVerified = !split.forced && allVerified,
            projection = graph.projection,
        )
    }

    private class Split(val vias: List<Int>, val forced: Boolean)

    /** Greedy via selection over the three navi models; [Split.forced] is set when no model advanced. */
    private fun splitVias(seq: List<Int>): Split {
        val n = seq.size
        val vias = ArrayList<Int>()
        var forced = false
        var cur = 0
        while (cur < n - 1) {
            var r = MODELS.minOf { reach(seq, cur, it) }
            if (r <= cur) {
                r = cur + 1
                forced = true
            }
            vias.add(r)
            cur = r
        }
        return Split(vias, forced)
    }

    /** Furthest index j reachable from seq[cur] along the model tree, following the chain of predecessors. */
    private fun reach(seq: List<Int>, cur: Int, model: NaviModel): Int {
        val n = seq.size
        val t = navi.tree(seq[cur], model)
        var best = cur
        var j = cur + 1
        while (j < n) {
            if (t.pred[seq[j]] != seq[j - 1]) break
            if (j == n - 1 || valid(seq[j], t, model)) best = j
            j++
        }
        return best
    }

    /**
     * A via edge is valid when it is at least 60 m long, wins the midpoint-cost race against its twin,
     * and no other edge lies within 12 m of its midpoint.
     */
    private fun valid(e: Int, t: NaviTree, model: NaviModel): Boolean {
        val ed = graph.edges[e]
        if (ed.lengthM < MIN_VIA_M) return false
        val tw = ed.twin
        if (tw >= 0 && t.label[tw].isFinite()) {
            val lhs = t.label[e] - model.edgeWeight(ed) / 2
            val rhs = t.label[tw] - model.edgeWeight(graph.edges[tw]) / 2
            if (lhs >= rhs) return false
        }
        val mid = pointAlong(ed.pts, ed.lengthM / 2)
        val exclude = if (tw >= 0) setOf(e, tw) else setOf(e)
        return spatial.nearestEdge(mid, AMBIGUITY_M, exclude) == null
    }

    private fun midpointLatLon(e: Int): LatLon {
        val ed = graph.edges[e]
        return graph.projection.toLatLon(pointAlong(ed.pts, ed.lengthM / 2))
    }

    companion object {
        private const val SAMPLE_M = 10.0
        private const val MIN_VIA_M = 60.0
        private const val AMBIGUITY_M = 12.0
        private val MODELS = listOf(NaviModel.TIME, NaviModel.DIST, NaviModel.TIME_LEFT_AVERSE)
    }
}

/** Point at arc length [s] along the polyline [pts] (clamped to its ends). */
internal fun pointAlong(pts: List<XY>, s: Double): XY {
    if (pts.size == 1 || s <= 0.0) return pts[0]
    var acc = 0.0
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val len = a.distanceTo(b)
        if (len > 0.0 && acc + len >= s) {
            val u = (s - acc) / len
            return XY(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
        }
        acc += len
    }
    return pts.last()
}

/** Heading (radians, atan2 convention) of the segment that contains arc length [s]; the last segment past the end. */
internal fun headingAlong(pts: List<XY>, s: Double): Double {
    var acc = 0.0
    var lastHeading = 0.0
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val len = a.distanceTo(b)
        if (len <= 0.0) continue
        lastHeading = headingRad(a, b)
        if (acc + len >= s) return lastHeading
        acc += len
    }
    return lastHeading
}
