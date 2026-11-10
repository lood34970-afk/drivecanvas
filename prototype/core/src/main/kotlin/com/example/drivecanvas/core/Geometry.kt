package com.example.drivecanvas.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Squared distance from [p] to the segment [a]-[b]. */
fun distSqPointSegment(p: XY, a: XY, b: XY): Double {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val wx = p.x - a.x
    val wy = p.y - a.y
    val l2 = vx * vx + vy * vy
    val t = if (l2 == 0.0) 0.0 else ((wx * vx + wy * vy) / l2).coerceIn(0.0, 1.0)
    val dx = wx - t * vx
    val dy = wy - t * vy
    return dx * dx + dy * dy
}

/** Distance from [p] to the polyline [pl] (a single point counts as a degenerate polyline). */
fun distPointPolyline(p: XY, pl: List<XY>): Double {
    if (pl.isEmpty()) return Double.POSITIVE_INFINITY
    if (pl.size == 1) return p.distanceTo(pl[0])
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until pl.size - 1) {
        best = minOf(best, distSqPointSegment(p, pl[i], pl[i + 1]))
    }
    return sqrt(best)
}

fun polylineLength(pl: List<XY>): Double {
    var sum = 0.0
    for (i in 0 until pl.size - 1) sum += pl[i].distanceTo(pl[i + 1])
    return sum
}

/** Arc length from the first point to each vertex; element 0 is 0.0. */
fun cumulativeLengths(pl: List<XY>): DoubleArray {
    val cum = DoubleArray(pl.size)
    for (i in 1 until pl.size) cum[i] = cum[i - 1] + pl[i - 1].distanceTo(pl[i])
    return cum
}

/**
 * Resamples a polyline so consecutive points are about [stepM] apart. The first point is kept;
 * each segment is split into ceil(L/stepM) equal parts (zero-length segments are skipped).
 * When [closed] is true the first point is appended again at the end, so the result ends where
 * it starts.
 */
fun resample(pl: List<XY>, stepM: Double, closed: Boolean = false): List<XY> {
    require(pl.isNotEmpty()) { "resample needs at least one point" }
    require(stepM > 0.0) { "stepM must be positive" }
    val pts = if (closed) pl + pl[0] else pl
    val out = ArrayList<XY>()
    out.add(pts[0])
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val len = a.distanceTo(b)
        if (len == 0.0) continue
        val n = max(1, ceil(len / stepM).toInt())
        for (k in 1..n) {
            if (k == n) {
                out.add(b)
            } else {
                out.add(XY(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n))
            }
        }
    }
    return out
}

/** Heading of the vector a->b in radians (atan2(dy, dx), CCW positive). */
fun headingRad(a: XY, b: XY): Double = atan2(b.y - a.y, b.x - a.x)

/** Normalises an angle in degrees to (-180, 180]. */
fun normDeg(deg: Double): Double {
    var d = deg % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}

/** Signed turn in degrees from an outgoing heading to an incoming heading (both radians), in (-180, 180]. Left is positive. */
fun turnDeg(hOut: Double, hIn: Double): Double = normDeg(Math.toDegrees(hIn - hOut))

/** True when segments p1-p2 and p3-p4 cross at a point interior to both. */
fun properlyIntersect(p1: XY, p2: XY, p3: XY, p4: XY): Boolean {
    val d1 = cross(p4.x - p3.x, p4.y - p3.y, p1.x - p3.x, p1.y - p3.y)
    val d2 = cross(p4.x - p3.x, p4.y - p3.y, p2.x - p3.x, p2.y - p3.y)
    val d3 = cross(p2.x - p1.x, p2.y - p1.y, p3.x - p1.x, p3.y - p1.y)
    val d4 = cross(p2.x - p1.x, p2.y - p1.y, p4.x - p1.x, p4.y - p1.y)
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
        ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

private fun cross(ux: Double, uy: Double, vx: Double, vy: Double): Double = ux * vy - uy * vx

/**
 * Number of distinct pairs of properly crossing non-adjacent segments of [pts]. Candidate pairs
 * come from a grid of [cellM] buckets. When [closed] is true the polyline is treated as a loop, so
 * its first and last segments count as adjacent (a loop route starts and ends at the same node).
 */
fun countSelfIntersections(pts: List<XY>, closed: Boolean = false, cellM: Double = 100.0): Int {
    val nSeg = pts.size - 1
    if (nSeg < 2) return 0
    val buckets = HashMap<Long, MutableList<Int>>()
    for (i in 0 until nSeg) {
        val a = pts[i]
        val b = pts[i + 1]
        if (a == b) continue
        val x0 = floor(minOf(a.x, b.x) / cellM).toInt()
        val x1 = floor(maxOf(a.x, b.x) / cellM).toInt()
        val y0 = floor(minOf(a.y, b.y) / cellM).toInt()
        val y1 = floor(maxOf(a.y, b.y) / cellM).toInt()
        for (cx in x0..x1) for (cy in y0..y1) {
            buckets.getOrPut(cellKey(cx, cy)) { ArrayList() }.add(i)
        }
    }
    val tested = HashSet<Long>()
    var count = 0
    for (list in buckets.values) {
        for (x in list.indices) {
            for (y in x + 1 until list.size) {
                val i = list[x]
                val j = list[y]
                if (abs(i - j) <= 1 || (closed && i == 0 && j == nSeg - 1)) continue
                if (!tested.add((i.toLong() shl 32) or j.toLong())) continue
                if (properlyIntersect(pts[i], pts[i + 1], pts[j], pts[j + 1])) count++
            }
        }
    }
    return count
}

internal fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

/** Uniform grid over points for nearest-neighbour queries within a radius. */
class PointGrid(points: List<XY>, private val cellM: Double = 50.0) {
    private val cells = HashMap<Long, MutableList<XY>>()
    private var empty = true

    init {
        require(cellM > 0.0) { "cellM must be positive" }
        for (p in points) {
            empty = false
            cells.getOrPut(cellKey(cellIndex(p.x), cellIndex(p.y))) { ArrayList() }.add(p)
        }
    }

    private fun cellIndex(v: Double): Int = floor(v / cellM).toInt()

    /**
     * Distance from [p] to the nearest stored point if it is at most [maxR]; null otherwise.
     * Searches square rings of cells outward and stops once no outer ring can be closer.
     */
    fun nearestWithin(p: XY, maxR: Double): Double? {
        if (empty) return null
        val cx = cellIndex(p.x)
        val cy = cellIndex(p.y)
        var best = Double.POSITIVE_INFINITY
        var r = 0
        while (true) {
            for (dy in -r..r) {
                val fullRow = abs(dy) == r
                val step = if (fullRow || r == 0) 1 else 2 * r
                var dx = -r
                while (dx <= r) {
                    val list = cells[cellKey(cx + dx, cy + dy)]
                    if (list != null) {
                        for (q in list) {
                            val ddx = q.x - p.x
                            val ddy = q.y - p.y
                            val d2 = ddx * ddx + ddy * ddy
                            if (d2 < best) best = d2
                        }
                    }
                    dx += step
                }
            }
            val lower = r * cellM
            if (best <= lower * lower || lower >= maxR) break
            r++
        }
        val d = sqrt(best)
        return if (d <= maxR) d else null
    }

    /** Nearest stored point distance, or [maxR] when no point lies within [maxR]. */
    fun nearestDist(p: XY, maxR: Double): Double = nearestWithin(p, maxR) ?: maxR
}
