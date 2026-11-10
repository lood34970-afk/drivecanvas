package com.example.drivecanvas.core

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Cost model of the emulated navigation engine (SPEC 3.6). Edge weight is free-flow time for the
 * TIME models and length/10 for DIST; turn penalties (seconds, or units for DIST) are per turn class.
 */
enum class NaviModel(val leftS: Double, val rightS: Double, val sharpS: Double, private val byTime: Boolean) {
    TIME(10.0, 3.0, 30.0, true),
    DIST(0.0, 0.0, 0.0, false),
    TIME_LEFT_AVERSE(30.0, 5.0, 60.0, true);

    fun edgeWeight(e: Edge): Double = if (byTime) e.timeS else e.lengthM / 10.0
}

/**
 * Single-source label tree from [startEdge]. [label][e] is the model cost to the END of edge e
 * (label[startEdge] = 0, +Infinity when unreached); [pred][e] is the predecessor edge (-1 for the start).
 */
class NaviTree internal constructor(
    val startEdge: Int,
    val model: NaviModel,
    val label: DoubleArray,
    val pred: IntArray,
) {
    /** Edges from [startEdge] (inclusive) to [edge] (inclusive), or null when [edge] is unreached. */
    fun pathTo(edge: Int): List<Int>? {
        if (!label[edge].isFinite()) return null
        val out = ArrayList<Int>()
        var e = edge
        while (e >= 0) {
            out.add(e)
            e = pred[e]
        }
        out.reverse()
        return out
    }
}

/**
 * Emulates the navigation engine that routes legs (SPEC 3.6). Legality matches [ShapeRouter]: no
 * twin (U-turn over the same segment), no OSM-banned turn, |turn| <= 165 degrees, and no 2-step U-turn
 * through a short edge.
 */
class NaviEmulator(private val graph: RoadGraph, private val rules: RouterConfig = RouterConfig()) {

    /** Label tree from [startEdge]; expansion stops once a label exceeds [bound] (model units). */
    fun tree(startEdge: Int, model: NaviModel, bound: Double = DEFAULT_BOUND): NaviTree {
        val nE = graph.edges.size
        val label = DoubleArray(nE) { Double.POSITIVE_INFINITY }
        val pred = IntArray(nE) { -1 }
        val queue = PriorityQueue<QueueItem>(compareBy<QueueItem>({ it.cost }, { it.edge }))
        label[startEdge] = 0.0
        queue.add(QueueItem(0.0, startEdge))
        while (queue.isNotEmpty()) {
            val item = queue.poll()
            val e = item.edge
            if (item.cost > label[e]) continue
            if (item.cost > bound) break
            val ed = graph.edges[e]
            for (e2 in graph.outEdges[ed.head]) {
                if (e2 == ed.twin || graph.isBanned(e, e2)) continue
                val next = graph.edges[e2]
                val th = turnDeg(ed.headingOut, next.headingIn)
                if (abs(th) > rules.uTurnMinDeg) continue
                val pe = pred[e]
                if (pe >= 0 && ed.lengthM < rules.twoStepShortEdgeM &&
                    abs(turnDeg(graph.edges[pe].headingOut, next.headingIn)) > rules.twoStepUTurnDeg
                ) continue
                val nc = item.cost + model.edgeWeight(next) + turnCost(model, th)
                if (nc < label[e2]) {
                    label[e2] = nc
                    pred[e2] = e
                    queue.add(QueueItem(nc, e2))
                }
            }
        }
        return NaviTree(startEdge, model, label, pred)
    }

    /**
     * Route from [startEdge] visiting [targetEdges] in order, each leg being the model-optimal path.
     * Result starts with [startEdge] and ends with the last target. Null when a target is unreachable.
     */
    fun routeThrough(startEdge: Int, targetEdges: List<Int>, model: NaviModel): List<Int>? {
        val out = arrayListOf(startEdge)
        var cur = startEdge
        for (t in targetEdges) {
            if (t == cur) continue
            val path = tree(cur, model).pathTo(t) ?: return null
            for (k in 1 until path.size) out.add(path[k])
            cur = t
        }
        return out
    }

    /**
     * Route from [startEdge] to the cheapest arrival at [node] (ends with an in-edge of [node]).
     * Result starts with [startEdge]. Null when the node is unreachable.
     */
    fun routeToNode(startEdge: Int, node: Int, model: NaviModel): List<Int>? {
        val tr = tree(startEdge, model)
        var best = -1
        var bestLabel = Double.POSITIVE_INFINITY
        for (e in graph.inEdges[node]) {
            val l = tr.label[e]
            if (l < bestLabel) {
                bestLabel = l
                best = e
            }
        }
        if (best < 0) return null
        return tr.pathTo(best)
    }

    private fun turnCost(model: NaviModel, th: Double): Double = when {
        abs(th) <= rules.straightMaxDeg -> 0.0
        abs(th) > rules.sharpMinDeg -> model.sharpS
        th > 0.0 -> model.leftS
        else -> model.rightS
    }

    private class QueueItem(val cost: Double, val edge: Int)

    companion object {
        /** Default expansion bound in model units (seconds for TIME models). */
        const val DEFAULT_BOUND = 1500.0
    }
}
