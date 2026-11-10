package com.example.drivecanvas.core

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Undirected road segment between graph nodes [a] and [b] (indices into RawNetwork.nodes).
 * Direction 0 is a->b. A [oneway] segment has only the a->b direction. [shape] holds the
 * intermediate shape points only, in order from a to b.
 */
data class RawSegment(
    val a: Int,
    val b: Int,
    val classCode: Int,
    val oneway: Boolean,
    val shape: List<LatLon>,
)

/** Turn ban: from directed edge (fromSeg, fromDir) to directed edge (toSeg, toDir). Dir 0 = a->b, 1 = b->a. */
data class RawBan(val fromSeg: Int, val fromDir: Int, val toSeg: Int, val toDir: Int)

/** Offline road network in plain lat/lon, as stored in the DCG asset. */
data class RawNetwork(
    val region: String,
    val origin: LatLon,
    val bboxMin: LatLon,
    val bboxMax: LatLon,
    val nodes: List<LatLon>,
    val segments: List<RawSegment>,
    val bans: List<RawBan>,
)

/**
 * Directed edge of [RoadGraph]. [seg] is the raw segment index; [twin] is the opposite directed
 * edge of the same segment, or -1 for one-way segments. [pts] runs tail to head in local metres.
 * [headingIn]/[headingOut] are radians. [samples] are points about 40 m apart along [pts].
 */
class Edge(
    val id: Int,
    val seg: Int,
    val tail: Int,
    val head: Int,
    val cls: RoadClass,
    val pts: List<XY>,
    val lengthM: Double,
    val timeS: Double,
    val headingIn: Double,
    val headingOut: Double,
    val twin: Int,
    val samples: List<XY>,
)

/** Projected road graph with directed edges, ready for routing. */
class RoadGraph(
    val region: String,
    val projection: LocalProjection,
    val bboxMinXY: XY,
    val bboxMaxXY: XY,
    val nodes: List<XY>,
    val edges: List<Edge>,
    /** Outgoing edge ids per node, ascending. */
    val outEdges: List<IntArray>,
    /** Incoming edge ids per node, ascending. */
    val inEdges: List<IntArray>,
    /** Banned (in, out) edge pairs encoded with [banKey]. */
    val banned: Set<Long>,
    /** Edge id for directed segment (2*seg + dir), or -1 when that direction does not exist. */
    val segDirToEdge: IntArray,
) {
    fun isBanned(inEdge: Int, outEdge: Int): Boolean = banned.contains(banKey(inEdge, outEdge))

    fun nodeLatLon(node: Int): LatLon = projection.toLatLon(nodes[node])

    companion object {
        fun banKey(inEdge: Int, outEdge: Int): Long = (inEdge.toLong() shl 32) or outEdge.toLong()

        private const val HEADING_MIN_M = 10.0
        private const val SAMPLE_STEP_M = 40.0

        /** Builds the routing graph from a raw network (also used after reading a DCG asset). */
        fun build(raw: RawNetwork): RoadGraph {
            val proj = LocalProjection(raw.origin.lat, raw.origin.lon)
            val nodeXY = raw.nodes.map { proj.toXY(it) }
            val edges = ArrayList<Edge>(raw.segments.size * 2)
            val segDirToEdge = IntArray(raw.segments.size * 2) { -1 }

            for ((s, seg) in raw.segments.withIndex()) {
                val pts = ArrayList<XY>(seg.shape.size + 2)
                pts.add(nodeXY[seg.a])
                for (p in seg.shape) pts.add(proj.toXY(p))
                pts.add(nodeXY[seg.b])
                val cls = RoadClass.fromCode(seg.classCode)
                val fwd = edges.size
                val twoWay = !seg.oneway
                edges.add(makeEdge(fwd, s, seg.a, seg.b, cls, pts, if (twoWay) fwd + 1 else -1))
                segDirToEdge[2 * s] = fwd
                if (twoWay) {
                    edges.add(makeEdge(fwd + 1, s, seg.b, seg.a, cls, pts.asReversed().toList(), fwd))
                    segDirToEdge[2 * s + 1] = fwd + 1
                }
            }

            val n = nodeXY.size
            val outL = Array(n) { ArrayList<Int>() }
            val inL = Array(n) { ArrayList<Int>() }
            for (e in edges) {
                outL[e.tail].add(e.id)
                inL[e.head].add(e.id)
            }

            val banned = HashSet<Long>()
            for (b in raw.bans) {
                val i = segDirToEdge[2 * b.fromSeg + b.fromDir]
                val o = segDirToEdge[2 * b.toSeg + b.toDir]
                if (i < 0 || o < 0) continue
                banned.add(banKey(i, o))
            }

            return RoadGraph(
                region = raw.region,
                projection = proj,
                bboxMinXY = proj.toXY(raw.bboxMin),
                bboxMaxXY = proj.toXY(raw.bboxMax),
                nodes = nodeXY,
                edges = edges,
                outEdges = outL.map { it.toIntArray() },
                inEdges = inL.map { it.toIntArray() },
                banned = banned,
                segDirToEdge = segDirToEdge,
            )
        }

        private fun makeEdge(
            id: Int,
            seg: Int,
            tail: Int,
            head: Int,
            cls: RoadClass,
            pts: List<XY>,
            twin: Int,
        ): Edge {
            var len = 0.0
            for (i in 0 until pts.size - 1) len += pts[i].distanceTo(pts[i + 1])
            val timeS = len / (cls.speedKmh / 3.6)
            return Edge(
                id = id,
                seg = seg,
                tail = tail,
                head = head,
                cls = cls,
                pts = pts,
                lengthM = len,
                timeS = timeS,
                headingIn = headingIn(pts),
                headingOut = headingOut(pts),
                twin = twin,
                samples = resample(pts, SAMPLE_STEP_M),
            )
        }

        /** Heading from the first point to the first point at least 10 m away (else the last point). */
        private fun headingIn(pts: List<XY>): Double {
            val p0 = pts[0]
            val q = pts.firstOrNull { p0.distanceTo(it) >= HEADING_MIN_M } ?: pts.last()
            return atan2(q.y - p0.y, q.x - p0.x)
        }

        /** Heading from the last point at least 10 m before the end to the end (else the first point). */
        private fun headingOut(pts: List<XY>): Double {
            val pe = pts.last()
            val p = pts.asReversed().firstOrNull { it.distanceTo(pe) >= HEADING_MIN_M } ?: pts.first()
            return atan2(pe.y - p.y, pe.x - p.x)
        }
    }
}

/** Nearest edge found by [EdgeSpatialIndex.nearestEdge]. */
data class EdgeHit(val edge: Int, val distM: Double)

/** Grid index over edge polylines for nearest-edge queries. */
class EdgeSpatialIndex(private val graph: RoadGraph, private val cellM: Double = 100.0) {
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        require(cellM > 0.0) { "cellM must be positive" }
        for (e in graph.edges) {
            for (i in 0 until e.pts.size - 1) {
                val a = e.pts[i]
                val b = e.pts[i + 1]
                val x0 = floor(min(a.x, b.x) / cellM).toInt()
                val x1 = floor(max(a.x, b.x) / cellM).toInt()
                val y0 = floor(min(a.y, b.y) / cellM).toInt()
                val y1 = floor(max(a.y, b.y) / cellM).toInt()
                for (cx in x0..x1) for (cy in y0..y1) {
                    val list = cells.getOrPut(cellKey(cx, cy)) { ArrayList() }
                    if (list.isEmpty() || list.last() != e.id) list.add(e.id)
                }
            }
        }
    }

    /**
     * Nearest edge to [p] (distance to the edge polyline) within [maxR], skipping ids in [exclude].
     * Returns null when no such edge lies within [maxR].
     */
    fun nearestEdge(p: XY, maxR: Double, exclude: Set<Int> = emptySet()): EdgeHit? {
        val cx = floor(p.x / cellM).toInt()
        val cy = floor(p.y / cellM).toInt()
        val seen = HashSet<Int>()
        var bestEdge = -1
        var bestDist = Double.POSITIVE_INFINITY
        var r = 0
        while (true) {
            for (dy in -r..r) {
                val fullRow = kotlin.math.abs(dy) == r
                val step = if (fullRow || r == 0) 1 else 2 * r
                var dx = -r
                while (dx <= r) {
                    val list = cells[cellKey(cx + dx, cy + dy)]
                    if (list != null) {
                        for (id in list) {
                            if (id in exclude || !seen.add(id)) continue
                            val d = distPointPolyline(p, graph.edges[id].pts)
                            if (d < bestDist) {
                                bestDist = d
                                bestEdge = id
                            }
                        }
                    }
                    dx += step
                }
            }
            val lower = r * cellM
            if (bestDist <= lower || lower >= maxR) break
            r++
        }
        return if (bestEdge >= 0 && bestDist <= maxR) EdgeHit(bestEdge, bestDist) else null
    }
}

/** Strongly connected components. */
object Scc {
    /**
     * Returns a flag per node (size [n]) that is true for nodes in the largest strongly connected
     * component of the directed graph with edges tails[k] -> heads[k]. Ties go to the component
     * whose lowest-numbered node is smallest. Uses iterative Kosaraju.
     */
    fun largestComponent(n: Int, tails: IntArray, heads: IntArray): BooleanArray {
        require(tails.size == heads.size) { "tails and heads must have the same size" }
        if (n == 0) return BooleanArray(0)
        val outStart = IntArray(n + 1)
        val inStart = IntArray(n + 1)
        for (k in tails.indices) {
            outStart[tails[k] + 1]++
            inStart[heads[k] + 1]++
        }
        for (v in 0 until n) {
            outStart[v + 1] += outStart[v]
            inStart[v + 1] += inStart[v]
        }
        val outList = IntArray(tails.size)
        val inList = IntArray(tails.size)
        val outFill = outStart.copyOf()
        val inFill = inStart.copyOf()
        for (k in tails.indices) {
            outList[outFill[tails[k]]++] = heads[k]
            inList[inFill[heads[k]]++] = tails[k]
        }

        // Pass 1: iterative DFS over out-edges, record finish order.
        val seen = BooleanArray(n)
        val order = IntArray(n)
        var orderSize = 0
        val stackNode = IntArray(n)
        val stackPos = IntArray(n)
        for (s in 0 until n) {
            if (seen[s]) continue
            var top = 0
            seen[s] = true
            stackNode[0] = s
            stackPos[0] = outStart[s]
            while (top >= 0) {
                val v = stackNode[top]
                if (stackPos[top] < outStart[v + 1]) {
                    val w = outList[stackPos[top]]
                    stackPos[top]++
                    if (!seen[w]) {
                        seen[w] = true
                        top++
                        stackNode[top] = w
                        stackPos[top] = outStart[w]
                    }
                } else {
                    order[orderSize++] = v
                    top--
                }
            }
        }

        // Pass 2: flood fill over in-edges in reverse finish order.
        val comp = IntArray(n) { -1 }
        var compCount = 0
        val work = IntArray(n)
        for (idx in n - 1 downTo 0) {
            val s = order[idx]
            if (comp[s] >= 0) continue
            var wsz = 0
            comp[s] = compCount
            work[wsz++] = s
            while (wsz > 0) {
                val v = work[--wsz]
                for (q in inStart[v] until inStart[v + 1]) {
                    val w = inList[q]
                    if (comp[w] < 0) {
                        comp[w] = compCount
                        work[wsz++] = w
                    }
                }
            }
            compCount++
        }

        val counts = IntArray(compCount)
        for (v in 0 until n) counts[comp[v]]++
        var best = -1
        for (v in 0 until n) {
            val c = comp[v]
            if (best < 0 || counts[c] > counts[best]) best = c
        }
        return BooleanArray(n) { comp[it] == best }
    }
}
