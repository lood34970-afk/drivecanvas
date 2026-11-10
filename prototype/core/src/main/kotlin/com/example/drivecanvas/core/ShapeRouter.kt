package com.example.drivecanvas.core

import java.util.Arrays
import kotlin.math.abs

/** Router parameters (SPEC 3.4). Fractions are of the placed width W; lengths in metres; angles in degrees. */
data class RouterConfig(
    val anchors: Int = 24,
    val alpha: Double = 4.0,
    val sigmaFrac: Double = 0.05,
    val bandFrac: Double = 0.20,
    val diskFrac: Double = 0.06,
    val diskMaxFrac: Double = 0.10,
    val startMaxFrac: Double = 0.09,
    val turnRightS: Double = 5.0,
    val turnLeftS: Double = 12.0,
    val turnSharpS: Double = 40.0,
    val straightMaxDeg: Double = 30.0,
    val sharpMinDeg: Double = 150.0,
    val uTurnMinDeg: Double = 165.0,
    val twoStepShortEdgeM: Double = 40.0,
    val twoStepUTurnDeg: Double = 160.0,
    val repairIterations: Int = 2,
    val repairFactor: Double = 3.0,
    val maxPops: Int = 2_000_000,
)

enum class NoRouteReason { START_TOO_FAR, NO_ROAD_NEAR_ANCHOR, NO_PATH, SEARCH_LIMIT, QUALITY }

/** Result of routing one placement. */
sealed class RouteOutcome {
    /** A closed loop found; [route] holds its statistics and gate results (see [EvaluatedRoute.passes]). */
    data class Found(val route: EvaluatedRoute) : RouteOutcome()

    /** No loop. [anchor] is the anchor index for NO_ROAD_NEAR_ANCHOR, otherwise -1. */
    data class NoRoute(val reason: NoRouteReason, val anchor: Int = -1) : RouteOutcome()
}

/**
 * A routed loop with its metrics. [template] is the placed 480-point outline; [edges] are the
 * directed edge ids in driving order (first tail == last head == start node).
 */
class EvaluatedRoute(
    val placement: Placement,
    val template: List<XY>,
    val edges: List<Int>,
    val polyline: List<XY>,
    val stats: RouteStats,
    val shape: ShapeMetrics,
    /** Failed gate names (see [QualityThresholds.gateFailures]); empty when the route passes. */
    val gateFailures: List<String>,
) {
    val passes: Boolean get() = gateFailures.isEmpty()
}

/**
 * Phase-expanded Dijkstra over (directed edge, anchor phase) states (SPEC 3.4). One instance per
 * graph; per-placement caches are built inside each call.
 */
class ShapeRouter(
    val graph: RoadGraph,
    val config: RouterConfig = RouterConfig(),
    val quality: QualityThresholds = QualityThresholds(),
) {
    private val nE = graph.edges.size
    private val nV = graph.nodes.size
    private val m = config.anchors

    // Flattened edge data (primitive arrays keep the hot loop free of object access and boxing).
    private val eTwin = IntArray(nE)
    private val eHead = IntArray(nE)
    private val eHin = DoubleArray(nE)
    private val eHout = DoubleArray(nE)
    private val eLen = DoubleArray(nE)
    private val eBase = DoubleArray(nE)
    private val eSamStart = IntArray(nE + 1)
    private val eSamX: DoubleArray
    private val eSamY: DoubleArray
    private val ePtStart = IntArray(nE + 1)
    private val ePtX: DoubleArray
    private val ePtY: DoubleArray

    /** Banned (in, out) pairs as sorted keys for allocation-free binary search. */
    private val bans: LongArray
    private val index = EdgeSpatialIndex(graph, 100.0)

    init {
        require(config.anchors > 1 && HeartTemplate.POINT_COUNT % config.anchors == 0) {
            "anchors must divide ${HeartTemplate.POINT_COUNT}"
        }
        var nSam = 0
        var nPt = 0
        for (e in graph.edges) {
            nSam += e.samples.size
            nPt += e.pts.size
        }
        eSamX = DoubleArray(nSam)
        eSamY = DoubleArray(nSam)
        ePtX = DoubleArray(nPt)
        ePtY = DoubleArray(nPt)
        var si = 0
        var pi = 0
        for (e in graph.edges) {
            val i = e.id
            eTwin[i] = e.twin
            eHead[i] = e.head
            eHin[i] = e.headingIn
            eHout[i] = e.headingOut
            eLen[i] = e.lengthM
            eBase[i] = e.timeS * e.cls.planFactor
            eSamStart[i] = si
            for (p in e.samples) {
                eSamX[si] = p.x
                eSamY[si] = p.y
                si++
            }
            ePtStart[i] = pi
            for (p in e.pts) {
                ePtX[pi] = p.x
                ePtY[pi] = p.y
                pi++
            }
        }
        eSamStart[nE] = si
        ePtStart[nE] = pi
        bans = graph.banned.toLongArray()
        bans.sort()
    }

    /** Plain route: one Dijkstra pass with no penalties (or [penalty] multipliers per edge), evaluated. */
    fun route(placement: Placement, penalty: DoubleArray? = null): RouteOutcome {
        val ctx = Context(placement)
        ctx.precheck()?.let { return it }
        return when (val raw = ctx.search(penalty)) {
            is Raw.Fail -> RouteOutcome.NoRoute(raw.reason)
            is Raw.Path -> RouteOutcome.Found(ctx.evaluate(raw.edges))
        }
    }

    /**
     * Routes [placement], analyses the loop, and while it still shares road (overlap or reuse above
     * 0.01) re-routes up to [RouterConfig.repairIterations] times with the overlapping edges (and their
     * twins) penalised. Keeps the best candidate by (gate-failure count, then higher S).
     */
    fun routeAndRepair(placement: Placement): RouteOutcome {
        val ctx = Context(placement)
        ctx.precheck()?.let { return it }
        val first = when (val raw = ctx.search(null)) {
            is Raw.Fail -> return RouteOutcome.NoRoute(raw.reason)
            is Raw.Path -> ctx.evaluate(raw.edges)
        }
        var best = first
        var cur = first
        var pen: DoubleArray? = null
        for (iter in 0 until config.repairIterations) {
            if (cur.stats.overlapRatio <= REPAIR_TOL && cur.stats.reuseRatio <= REPAIR_TOL) break
            val penArr = pen ?: DoubleArray(nE) { 1.0 }.also { pen = it }
            val affected = HashSet<Int>()
            for (e in cur.stats.overlapEdges) {
                affected.add(e)
                if (eTwin[e] >= 0) affected.add(eTwin[e])
            }
            for (e in affected) penArr[e] *= config.repairFactor
            val raw = ctx.search(penArr)
            if (raw !is Raw.Path) break
            cur = ctx.evaluate(raw.edges)
            if (better(cur, best)) best = cur
        }
        return RouteOutcome.Found(best)
    }

    private fun better(a: EvaluatedRoute, b: EvaluatedRoute): Boolean {
        if (a.gateFailures.size != b.gateFailures.size) return a.gateFailures.size < b.gateFailures.size
        return a.shape.score > b.shape.score
    }

    private fun isBanned(e: Int, e2: Int): Boolean =
        bans.isNotEmpty() && Arrays.binarySearch(bans, (e.toLong() shl 32) or e2.toLong()) >= 0

    private sealed class Raw {
        class Path(val edges: List<Int>) : Raw()
        class Fail(val reason: NoRouteReason) : Raw()
    }

    /** Per-placement state: anchors, disk radii, piece geometry and lazily filled caches. */
    private inner class Context(val placement: Placement) {
        val widthM = placement.widthM
        val tpl: List<XY> = placement.place()
        val nper = HeartTemplate.POINT_COUNT / m
        val ax = DoubleArray(m) { tpl[it * nper].x }
        val ay = DoubleArray(m) { tpl[it * nper].y }
        val radius = DoubleArray(m) { config.diskFrac * widthM }
        val pieceX = DoubleArray(m * (nper + 1))
        val pieceY = DoubleArray(m * (nper + 1))
        val sigma2 = (config.sigmaFrac * widthM) * (config.sigmaFrac * widthM)
        val band2 = (config.bandFrac * widthM) * (config.bandFrac * widthM)
        /** Mean squared distance of each edge's samples to each piece; NaN = not computed. */
        val d2Cache = DoubleArray(nE * m) { Double.NaN }
        /** 0 = not computed, 1 = no touch, 2 = touches anchor. */
        val touchCache = ByteArray(nE * m)
        var v0 = -1

        init {
            for (k in 0 until m) for (i in 0..nper) {
                val p = tpl[(k * nper + i) % HeartTemplate.POINT_COUNT]
                pieceX[k * (nper + 1) + i] = p.x
                pieceY[k * (nper + 1) + i] = p.y
            }
        }

        /** Start-node and anchor-disk checks. Sets [v0] and widens [radius] where needed. */
        fun precheck(): RouteOutcome.NoRoute? {
            var best = -1
            var bestD = Double.POSITIVE_INFINITY
            val p0 = tpl[0]
            for (v in 0 until nV) {
                if (graph.inEdges[v].isEmpty() || graph.outEdges[v].isEmpty()) continue
                val d = graph.nodes[v].distanceTo(p0)
                if (d < bestD) {
                    bestD = d
                    best = v
                }
            }
            if (best < 0 || bestD > config.startMaxFrac * widthM) {
                return RouteOutcome.NoRoute(NoRouteReason.START_TOO_FAR)
            }
            v0 = best
            val maxR = config.diskMaxFrac * widthM
            for (k in 1 until m) {
                val hit = index.nearestEdge(XY(ax[k], ay[k]), maxR)
                    ?: return RouteOutcome.NoRoute(NoRouteReason.NO_ROAD_NEAR_ANCHOR, k)
                if (hit.distM > radius[k]) {
                    radius[k] = hit.distM + 0.01 * widthM
                    if (radius[k] > maxR) return RouteOutcome.NoRoute(NoRouteReason.NO_ROAD_NEAR_ANCHOR, k)
                }
            }
            return null
        }

        fun d2(e: Int, k: Int): Double {
            val idx = e * m + k
            val cached = d2Cache[idx]
            if (!cached.isNaN()) return cached
            val s0 = eSamStart[e]
            val s1 = eSamStart[e + 1]
            val base = k * (nper + 1)
            var sum = 0.0
            for (q in s0 until s1) {
                val px = eSamX[q]
                val py = eSamY[q]
                var best = Double.POSITIVE_INFINITY
                for (i in 0 until nper) {
                    val d = segDistSq(px, py, pieceX[base + i], pieceY[base + i], pieceX[base + i + 1], pieceY[base + i + 1])
                    if (d < best) best = d
                }
                sum += best
            }
            val v = sum / (s1 - s0)
            d2Cache[idx] = v
            return v
        }

        fun touches(e: Int, j: Int): Boolean {
            val idx = e * m + j
            val c = touchCache[idx].toInt()
            if (c != 0) return c == 2
            val ox = ax[j]
            val oy = ay[j]
            val r2 = radius[j] * radius[j]
            var hit = false
            val p0 = ePtStart[e]
            val p1 = ePtStart[e + 1]
            for (q in p0 until p1 - 1) {
                if (segDistSq(ox, oy, ePtX[q], ePtY[q], ePtX[q + 1], ePtY[q + 1]) <= r2) {
                    hit = true
                    break
                }
            }
            touchCache[idx] = if (hit) 2 else 1
            return hit
        }

        fun adv(e: Int, k: Int): Int {
            var kk = k
            while (kk + 1 < m && touches(e, kk + 1)) kk++
            return kk
        }

        /** Edge cost for phases k..k2, or -1.0 when outside the band. */
        fun cost(e: Int, k: Int, k2: Int, pen: DoubleArray?): Double {
            val a = d2(e, k)
            val b = d2(e, k2)
            val dd = if (a < b) a else b
            if (dd > band2) return -1.0
            var c = eBase[e] * (1.0 + config.alpha * dd / sigma2)
            if (pen != null) c *= pen[e]
            return c
        }

        fun search(pen: DoubleArray?): Raw {
            val states = nE * m
            val dist = DoubleArray(states)
            Arrays.fill(dist, Double.POSITIVE_INFINITY)
            val pred = IntArray(states)
            Arrays.fill(pred, -1)
            val heap = MinHeap(1024)

            for (e in graph.outEdges[v0]) {
                val k2 = adv(e, 0)
                val c = cost(e, 0, k2, pen)
                if (c < 0.0) continue
                val s = e * m + k2
                if (c < dist[s]) {
                    dist[s] = c
                    heap.push(c, s)
                }
            }

            var pops = 0
            while (heap.size > 0) {
                heap.pop()
                val c = heap.topKey
                val s = heap.topVal
                if (c > dist[s]) continue
                pops++
                if (pops > config.maxPops) return Raw.Fail(NoRouteReason.SEARCH_LIMIT)
                val e = s / m
                val k = s % m
                val v = eHead[e]
                if (k == m - 1 && v == v0) return Raw.Path(reconstruct(s, pred))

                val pe = if (pred[s] >= 0) pred[s] / m else -1
                for (e2 in graph.outEdges[v]) {
                    if (e2 == eTwin[e] || isBanned(e, e2)) continue
                    val th = turnDeg(eHout[e], eHin[e2])
                    val ath = abs(th)
                    if (ath > config.uTurnMinDeg) continue
                    if (pe >= 0 && eLen[e] < config.twoStepShortEdgeM &&
                        abs(turnDeg(eHout[pe], eHin[e2])) > config.twoStepUTurnDeg
                    ) continue
                    val tc = when {
                        ath <= config.straightMaxDeg -> 0.0
                        ath > config.sharpMinDeg -> config.turnSharpS
                        th > 0.0 -> config.turnLeftS
                        else -> config.turnRightS
                    }
                    val k2 = adv(e2, k)
                    val c2 = cost(e2, k, k2, pen)
                    if (c2 < 0.0) continue
                    val nc = c + c2 + tc
                    val s2 = e2 * m + k2
                    if (nc < dist[s2]) {
                        dist[s2] = nc
                        pred[s2] = s
                        heap.push(nc, s2)
                    }
                }
            }
            return Raw.Fail(NoRouteReason.NO_PATH)
        }

        private fun reconstruct(goal: Int, pred: IntArray): List<Int> {
            val out = ArrayList<Int>()
            var s = goal
            while (s >= 0) {
                out.add(s / m)
                s = pred[s]
            }
            out.reverse()
            return out
        }

        fun evaluate(edges: List<Int>): EvaluatedRoute {
            val poly = Metrics.routePolyline(graph, edges)
            val stats = Metrics.routeStats(graph, edges)
            val shape = Metrics.shapeMetrics(poly, tpl, widthM)
            return EvaluatedRoute(
                placement = placement,
                template = tpl,
                edges = edges,
                polyline = poly,
                stats = stats,
                shape = shape,
                gateFailures = quality.gateFailures(stats, shape),
            )
        }
    }

    companion object {
        private const val REPAIR_TOL = 0.01
    }
}

/** Squared distance from point (px,py) to segment (ax,ay)-(bx,by). */
private fun segDistSq(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val vx = bx - ax
    val vy = by - ay
    val wx = px - ax
    val wy = py - ay
    val l2 = vx * vx + vy * vy
    var t = if (l2 == 0.0) 0.0 else (wx * vx + wy * vy) / l2
    if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
    val dx = wx - t * vx
    val dy = wy - t * vy
    return dx * dx + dy * dy
}

/** Binary min-heap over (key, value) pairs ordered by key, then value. No boxing. */
private class MinHeap(initialCapacity: Int) {
    private var keys = DoubleArray(initialCapacity)
    private var vals = IntArray(initialCapacity)
    var size = 0
        private set
    var topKey = 0.0
        private set
    var topVal = 0
        private set

    private fun less(k1: Double, v1: Int, k2: Double, v2: Int): Boolean =
        k1 < k2 || (k1 == k2 && v1 < v2)

    fun push(k: Double, v: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            vals = vals.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) shr 1
            if (!less(k, v, keys[p], vals[p])) break
            keys[i] = keys[p]
            vals[i] = vals[p]
            i = p
        }
        keys[i] = k
        vals[i] = v
    }

    /** Removes the minimum; its key and value are then available as [topKey] and [topVal]. */
    fun pop() {
        topKey = keys[0]
        topVal = vals[0]
        size--
        if (size == 0) return
        val k = keys[size]
        val v = vals[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && less(keys[c + 1], vals[c + 1], keys[c], vals[c])) c++
            if (!less(keys[c], vals[c], k, v)) break
            keys[i] = keys[c]
            vals[i] = vals[c]
            i = c
        }
        keys[i] = k
        vals[i] = v
    }
}
