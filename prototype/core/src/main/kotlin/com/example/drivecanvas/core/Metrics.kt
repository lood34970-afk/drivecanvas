package com.example.drivecanvas.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** Statistics of a routed loop (SPEC 3.5). Edge ids refer to the RoadGraph the route came from. */
data class RouteStats(
    val lengthM: Double,
    /** Sum of edge free-flow times in seconds. */
    val freeFlowS: Double,
    /** ETA in minutes: (1.25*freeFlowS + 20*lefts + 8*rights) / 60. */
    val etaMin: Double,
    /** Consecutive edge pairs with |turn| >= 45 degrees. */
    val turns: Int,
    val lefts: Int,
    val rights: Int,
    val turnsPerKm: Double,
    /** Share of route length on segments that the route traverses at least twice. */
    val reuseRatio: Double,
    /** Share of 10 m resampled points that lie within 30 m of a point more than 400 m away along the route. */
    val overlapRatio: Double,
    /** Edges on reused segments plus edges containing a flagged overlap point. */
    val overlapEdges: Set<Int>,
    /** Proper crossings of non-adjacent route segments. */
    val selfIntersections: Int,
)

/** Shape comparison of a route polyline against the placed template (SPEC 3.5). */
data class ShapeMetrics(
    /** Mean distance route -> template in metres. */
    val dRT: Double,
    /** Mean distance template -> route in metres. */
    val dTR: Double,
    /** (dRT + dTR) / 2 / W. */
    val nmsd: Double,
    /** 90th percentile of all sampled distances / W. */
    val p90: Double,
    /** Distance of the template tip to the route / W. */
    val tipD: Double,
    /** Distance of the template cusp to the route / W. */
    val cuspD: Double,
    /** Shape score 0..100 (100 = identical). */
    val score: Double,
)

/** Pass/fail limits for a routed loop. Gate names are returned by [gateFailures]. */
data class QualityThresholds(
    val nmsdMax: Double = 0.035,
    val p90Max: Double = 0.08,
    val featureMax: Double = 0.065,
    val overlapMax: Double = 0.05,
    val reuseMax: Double = 0.05,
    val selfIntersectionsMax: Int = 0,
    val turnsPerKmMax: Double = 3.0,
    val etaMinMin: Double = 25.0,
    val etaMinMax: Double = 50.0,
) {
    /** Names of failed gates, in order: nmsd, p90, feature, overlap, reuse, selfIntersections, turns, eta. */
    fun gateFailures(stats: RouteStats, shape: ShapeMetrics): List<String> {
        val failed = ArrayList<String>()
        if (shape.nmsd > nmsdMax) failed.add("nmsd")
        if (shape.p90 > p90Max) failed.add("p90")
        if (shape.tipD > featureMax || shape.cuspD > featureMax) failed.add("feature")
        if (stats.overlapRatio > overlapMax) failed.add("overlap")
        if (stats.reuseRatio > reuseMax) failed.add("reuse")
        if (stats.selfIntersections > selfIntersectionsMax) failed.add("selfIntersections")
        if (stats.turnsPerKm > turnsPerKmMax) failed.add("turns")
        if (stats.etaMin < etaMinMin || stats.etaMin > etaMinMax) failed.add("eta")
        return failed
    }
}

object Metrics {
    private const val TURN_THRESHOLD_DEG = 45.0
    private const val OVERLAP_TOL_M = 30.0
    private const val OVERLAP_GAP_M = 400.0
    private const val RESAMPLE_M = 10.0

    /** Route polyline: concatenated edge points, shared joints not duplicated. */
    fun routePolyline(graph: RoadGraph, edges: List<Int>): List<XY> {
        val out = ArrayList<XY>()
        for (e in edges) {
            val pts = graph.edges[e].pts
            if (out.isEmpty()) {
                out.addAll(pts)
            } else {
                for (i in 1 until pts.size) out.add(pts[i])
            }
        }
        return out
    }

    fun routeStats(graph: RoadGraph, edges: List<Int>): RouteStats {
        require(edges.isNotEmpty()) { "route has no edges" }
        val route = edges.map { graph.edges[it] }
        val pts = routePolyline(graph, edges)

        var length = 0.0
        var freeFlow = 0.0
        for (e in route) {
            length += e.lengthM
            freeFlow += e.timeS
        }

        var turns = 0
        var lefts = 0
        var rights = 0
        for (i in 0 until route.size - 1) {
            val th = turnDeg(route[i].headingOut, route[i + 1].headingIn)
            if (abs(th) >= TURN_THRESHOLD_DEG) {
                turns++
                if (th > 0) lefts++ else rights++
            }
        }
        val eta = (1.25 * freeFlow + 20.0 * lefts + 8.0 * rights) / 60.0
        val km = length / 1000.0
        val turnsPerKm = turns / km

        val segCount = HashMap<Int, Int>()
        for (e in route) segCount[e.seg] = (segCount[e.seg] ?: 0) + 1
        var reusedLength = 0.0
        val overlapSet = LinkedHashSet<Int>()
        for (e in edges) {
            if ((segCount[graph.edges[e].seg] ?: 0) >= 2) {
                reusedLength += graph.edges[e].lengthM
                overlapSet.add(e)
            }
        }
        val reuse = reusedLength / length

        // Overlap: flagged 10 m resampled points, mapped back to the edges they lie on.
        val rr = resample(pts, RESAMPLE_M)
        val cum = cumulativeLengths(rr)
        val total = cum[cum.size - 1]
        val flagged = flaggedOverlapPoints(rr, cum, total)
        val edgeStart = DoubleArray(edges.size)
        for (k in 1 until edges.size) edgeStart[k] = edgeStart[k - 1] + route[k - 1].lengthM
        for (i in flagged) {
            val k = edgeIndexAt(edgeStart, cum[i])
            overlapSet.add(edges[k])
        }
        val overlapRatio = if (rr.isEmpty()) 0.0 else flagged.size.toDouble() / rr.size

        val closed = pts.first() == pts.last()
        val selfX = countSelfIntersections(pts, closed)

        return RouteStats(
            lengthM = length,
            freeFlowS = freeFlow,
            etaMin = eta,
            turns = turns,
            lefts = lefts,
            rights = rights,
            turnsPerKm = turnsPerKm,
            reuseRatio = reuse,
            overlapRatio = overlapRatio,
            overlapEdges = overlapSet,
            selfIntersections = selfX,
        )
    }

    /** Indices of resampled points that have another point within 30 m more than 400 m away along the route (cyclic). */
    private fun flaggedOverlapPoints(rr: List<XY>, cum: DoubleArray, total: Double): List<Int> {
        if (rr.isEmpty()) return emptyList()
        val cell = OVERLAP_TOL_M
        val buckets = HashMap<Long, MutableList<Int>>()
        for (i in rr.indices) {
            buckets.getOrPut(cellKey(floor(rr[i].x / cell).toInt(), floor(rr[i].y / cell).toInt())) { ArrayList() }.add(i)
        }
        val flagged = ArrayList<Int>()
        for (i in rr.indices) {
            val p = rr[i]
            val cx = floor(p.x / cell).toInt()
            val cy = floor(p.y / cell).toInt()
            var hit = false
            loop@ for (dx in -1..1) for (dy in -1..1) {
                val list = buckets[cellKey(cx + dx, cy + dy)] ?: continue
                for (j in list) {
                    var sep = abs(cum[j] - cum[i])
                    sep = min(sep, total - sep)
                    if (sep > OVERLAP_GAP_M && p.distanceTo(rr[j]) <= OVERLAP_TOL_M) {
                        hit = true
                        break@loop
                    }
                }
            }
            if (hit) flagged.add(i)
        }
        return flagged
    }

    /** Index k of the edge whose arc-length span [edgeStart[k], edgeStart[k+1]) contains s. */
    private fun edgeIndexAt(edgeStart: DoubleArray, s: Double): Int {
        var lo = 0
        var hi = edgeStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (edgeStart[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Shape metrics of [route] (open polyline) against [template] (placed template, closed
     * implicitly) for a heart of width [widthM]. Samples are 10 m apart; averages use every 2nd.
     */
    fun shapeMetrics(route: List<XY>, template: List<XY>, widthM: Double): ShapeMetrics {
        require(widthM > 0.0) { "widthM must be positive" }
        val rr = resample(route, RESAMPLE_M)
        val tt = resample(template, RESAMPLE_M, closed = true)
        val gr = PointGrid(rr, 50.0)
        val gt = PointGrid(tt, 50.0)
        val maxR = 0.3 * widthM

        val dRT = rr.filterIndexed { i, _ -> i % 2 == 0 }.map { gt.nearestDist(it, maxR) }
        val dTR = tt.filterIndexed { i, _ -> i % 2 == 0 }.map { gr.nearestDist(it, maxR) }
        val meanRT = dRT.average()
        val meanTR = dTR.average()
        val nmsd = (meanRT + meanTR) / 2.0 / widthM

        val all = (dRT + dTR).sorted()
        val p90 = all[min(all.size - 1, floor(0.9 * all.size).toInt())] / widthM

        val tipD = gr.nearestDist(template[0], maxR) / widthM
        val cuspD = gr.nearestDist(template[template.size / 2], maxR) / widthM

        val penalty = 0.5 * min(1.0, nmsd / 0.10) +
            0.3 * min(1.0, p90 / 0.20) +
            0.2 * min(1.0, maxOf(tipD, cuspD) / 0.12)
        val score = 100.0 * (1.0 - min(1.0, penalty))

        return ShapeMetrics(
            dRT = meanRT,
            dTR = meanTR,
            nmsd = nmsd,
            p90 = p90,
            tipD = tipD,
            cuspD = cuspD,
            score = score,
        )
    }

    /**
     * Share of [plan] samples (10 m) that lie within 40 m of [track] samples (5 m).
     * Pass an open polyline; close it explicitly if the plan is a loop.
     */
    fun completion(plan: List<XY>, track: List<XY>): Double {
        val planRs = resample(plan, RESAMPLE_M)
        val trackRs = resample(track, 5.0)
        val grid = PointGrid(trackRs, 50.0)
        val covered = planRs.count { grid.nearestWithin(it, 40.0) != null }
        return covered.toDouble() / planRs.size
    }

    /**
     * Drops fixes that imply more than [maxSpeedMps] from the last accepted fix. The first fix is
     * always kept. Fixes with the same time as the last accepted fix are kept only if they coincide.
     */
    fun <T> cleanTrack(
        points: List<T>,
        timeMs: (T) -> Long,
        xy: (T) -> XY,
        maxSpeedMps: Double = 55.0,
    ): List<T> {
        val out = ArrayList<T>()
        var last: T? = null
        for (p in points) {
            val prev = last
            if (prev == null) {
                out.add(p)
                last = p
                continue
            }
            val dt = (timeMs(p) - timeMs(prev)) / 1000.0
            val d = xy(p).distanceTo(xy(prev))
            val speed = if (dt > 0.0) d / dt else if (d > 0.0) Double.POSITIVE_INFINITY else 0.0
            if (speed <= maxSpeedMps) {
                out.add(p)
                last = p
            }
        }
        return out
    }

    /** UI label for a shape score S (Korean). */
    fun shapeLabel(score: Double): String = when {
        score >= 65.0 -> "하트로 잘 보여요"
        score >= 50.0 -> "하트 모양이 조금 흐려요"
        else -> "그림이 많이 달라요"
    }
}
