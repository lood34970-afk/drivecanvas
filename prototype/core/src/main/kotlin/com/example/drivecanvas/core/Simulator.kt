package com.example.drivecanvas.core

import kotlin.math.abs
import kotlin.math.min

/** A detour: once [atRouteFraction] of the plan is driven, the car leaves the plan at the next plan turn and drives [lengthM] metres straight-ish. */
data class DeviationScenario(val atRouteFraction: Double = 0.55, val lengthM: Double = 700.0)

/** Simulated drive: [seed] drives the GPS and heading noise; [turnSlowKmh] applies within [turnSlowWindowM] of a turn of at least 45 degrees. */
data class SimConfig(
    val seed: Long = 42L,
    val noiseM: Double = 3.0,
    val headingNoiseDeg: Double = 2.0,
    val stepMs: Long = 1_000L,
    val turnSlowKmh: Double = 20.0,
    val turnSlowWindowM: Double = 25.0,
    val deviation: DeviationScenario? = null,
)

/**
 * Simulated navigation engine and vehicle. Answers [GuidanceCommand.RequestRoute] by routing from the
 * current edge with the TIME model, and moves the vehicle along the resulting edges.
 */
class SimNavi(
    private val graph: RoadGraph,
    private val plan: GuidancePlan,
    private val cfg: SimConfig,
) {
    private val rnd = java.util.Random(cfg.seed)
    private val navi = NaviEmulator(graph)
    private val planLengthM = plan.routeEdges.sumOf { graph.edges[it].lengthM }

    /** Current edge and metres driven along it. */
    private var ce: Int = plan.routeEdges[0]
    private var offset = 0.0
    /** Edges still to drive after [ce]. */
    private val upcoming = ArrayDeque<Int>()
    /** Leg target edges not yet passed, with their waypoint index. */
    private val pending = ArrayDeque<Pair<Int, Int>>()

    private var moving = false
    private var arrivalPending = false
    private var broken = false
    private var driven = 0.0
    private var deviationArmed = false
    private var deviationDone = false
    private var deviating = false
    private var devDriven = 0.0

    /** True position of the vehicle (no GPS noise). */
    fun truePosition(): LatLon = plan.projection.toLatLon(pointAlong(graph.edges[ce].pts, offset))

    /** Applies a route request. Returns [GuidanceEvent.RouteStarted], or [GuidanceEvent.NaviError] when no route exists. */
    fun onCommand(cmd: GuidanceCommand.RequestRoute, nowMs: Long): List<GuidanceEvent> {
        val leg = cmd.leg
        val path: List<Int>?
        pending.clear()
        if (leg == null) {
            // Home request: leave the plan for good.
            deviationDone = true
            deviating = false
            path = navi.routeToNode(ce, plan.startNode, NaviModel.TIME)
        } else {
            path = navi.routeThrough(ce, leg.targetEdges, NaviModel.TIME)
            leg.targetEdges.forEachIndexed { i, e -> pending.addLast(i to e) }
        }
        if (path == null) return listOf(GuidanceEvent.NaviError(nowMs, "no route from current edge"))
        upcoming.clear()
        upcoming.addAll(path.drop(1))
        moving = true
        arrivalPending = false
        broken = false
        return listOf(GuidanceEvent.RouteStarted(nowMs))
    }

    /** Advances the vehicle by one step of [SimConfig.stepMs] and returns its events (location first). */
    fun tick(nowMs: Long): List<GuidanceEvent> {
        val out = ArrayList<GuidanceEvent>()
        val dev = cfg.deviation
        if (moving) {
            val edge = graph.edges[ce]
            var speedMps = edge.cls.speedKmh / 3.6
            val nextEdge = upcoming.firstOrNull()
            if (nextEdge != null &&
                edge.lengthM - offset <= cfg.turnSlowWindowM &&
                abs(turnDeg(edge.headingOut, graph.edges[nextEdge].headingIn)) >= TURN_DEG
            ) {
                speedMps = min(speedMps, cfg.turnSlowKmh / 3.6)
            }
            val d = speedMps * cfg.stepMs / 1000.0
            driven += d
            if (deviating) devDriven += d
            if (dev != null && !deviationArmed && !deviationDone && driven >= dev.atRouteFraction * planLengthM) {
                deviationArmed = true
            }
            advance(d, nowMs, out)
        }

        val edge = graph.edges[ce]
        val pos = pointAlong(edge.pts, offset)
        val heading = headingAlong(edge.pts, offset)
        val gx = rnd.nextGaussian() * cfg.noiseM
        val gy = rnd.nextGaussian() * cfg.noiseM
        val gh = rnd.nextGaussian() * cfg.headingNoiseDeg
        val compass = normalizeCompass(90.0 - Math.toDegrees(heading) + gh)
        out.add(
            GuidanceEvent.Location(
                nowMs,
                plan.projection.toLatLon(XY(pos.x + gx, pos.y + gy)),
                compass,
            ),
        )
        if (arrivalPending) {
            arrivalPending = false
            out.add(GuidanceEvent.DestinationArrived(nowMs))
        }
        return out
    }

    private fun advance(distance: Double, nowMs: Long, out: MutableList<GuidanceEvent>) {
        var remaining = distance
        while (true) {
            val len = graph.edges[ce].lengthM
            val room = len - offset
            if (remaining < room) {
                offset += remaining
                checkWaypoints(nowMs, out)
                return
            }
            remaining -= room
            offset = len
            checkWaypoints(nowMs, out)
            val next = nextEdge(ce, nowMs, out)
            if (next == null) {
                moving = false
                arrivalPending = !broken
                return
            }
            ce = next
            offset = 0.0
        }
    }

    private fun checkWaypoints(nowMs: Long, out: MutableList<GuidanceEvent>) {
        val half = graph.edges[ce].lengthM / 2
        while (pending.isNotEmpty() && pending.first().second == ce && offset >= half) {
            out.add(GuidanceEvent.WaypointArrived(nowMs, pending.removeFirst().first))
        }
    }

    /** Picks the edge after [prev] at the junction it ends at, or null when the path is finished. */
    private fun nextEdge(prev: Int, nowMs: Long, out: MutableList<GuidanceEvent>): Int? {
        if (deviating) {
            val limit = cfg.deviation?.lengthM ?: 0.0
            if (devDriven < limit) return straightestOut(prev, null)
            deviating = false
            out.add(GuidanceEvent.ReRouteEnded(nowMs))
            val rerouted = navi.routeThrough(prev, pending.map { it.second }, NaviModel.TIME)
            if (rerouted == null) {
                broken = true
                out.add(GuidanceEvent.NaviError(nowMs, "reroute failed"))
                return null
            }
            upcoming.clear()
            upcoming.addAll(rerouted.drop(1))
        } else if (!deviationDone && deviationArmed && upcoming.isNotEmpty() &&
            abs(turnDeg(graph.edges[prev].headingOut, graph.edges[upcoming.first()].headingIn)) >= TURN_DEG
        ) {
            deviating = true
            deviationDone = true
            devDriven = 0.0
            return straightestOut(prev, upcoming.first())
        }
        return if (upcoming.isNotEmpty()) upcoming.removeFirst() else null
    }

    /** Allowed out-edge of [prev] with the smallest turn, skipping [exclude]; a U-turn only as last resort. */
    private fun straightestOut(prev: Int, exclude: Int?): Int? {
        val pe = graph.edges[prev]
        var best = -1
        var bestAbs = Double.POSITIVE_INFINITY
        var twinOk = false
        var anyOk = -1
        for (e2 in graph.outEdges[pe.head]) {
            if (e2 == exclude || graph.isBanned(prev, e2)) continue
            anyOk = e2
            if (e2 == pe.twin) {
                twinOk = true
                continue
            }
            val th = abs(turnDeg(pe.headingOut, graph.edges[e2].headingIn))
            if (th > UTURN_LIMIT_DEG) continue
            if (th < bestAbs) {
                bestAbs = th
                best = e2
            }
        }
        return when {
            best >= 0 -> best
            twinOk -> pe.twin
            anyOk >= 0 -> anyOk
            else -> null
        }
    }

    private fun normalizeCompass(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    companion object {
        private const val TURN_DEG = 45.0
        private const val UTURN_LIMIT_DEG = 165.0
    }
}

/**
 * Runs a [GuidanceEngine] against a [SimNavi] one simulated second at a time. Each [step] delivers the
 * vehicle's events and a Tick to the engine and dispatches its route requests back to the simulator.
 */
class SimulationRunner(
    private val graph: RoadGraph,
    private val plan: GuidancePlan,
    private val simCfg: SimConfig = SimConfig(),
    private val guidCfg: GuidanceConfig = GuidanceConfig(),
) {
    val engine = GuidanceEngine(plan, guidCfg)
    private val navi = SimNavi(graph, plan, simCfg)
    private var nowMs = 0L
    private var started = false

    /** True once the engine has emitted [GuidanceCommand.Finish]. */
    var finished: Boolean = false
        private set

    /** Summary delivered with [GuidanceCommand.Finish]; null until the drive ends. */
    var summary: DriveSummary? = null
        private set

    /** Current noise-free vehicle position. */
    val position: LatLon get() = navi.truePosition()

    /** Simulated clock in ms (0 at [start]). */
    val simTimeMs: Long get() = nowMs

    /** Starts the guidance (Start event at t = 0). */
    fun start(): List<GuidanceCommand> {
        check(!started) { "already started" }
        started = true
        return feed(listOf(GuidanceEvent.Start(nowMs)))
    }

    /** Advances one simulated second (sim events, then Tick) and returns the commands produced. */
    fun step(): List<GuidanceCommand> {
        require(started) { "call start() first" }
        if (finished) return emptyList()
        nowMs += simCfg.stepMs
        val events = ArrayList<GuidanceEvent>(navi.tick(nowMs))
        events.add(GuidanceEvent.Tick(nowMs))
        return feed(events)
    }

    /** Simulated "그리기 끝내기" press at the current sim time; the engine ignores it outside the drawing states. */
    fun userEndEarly(): List<GuidanceCommand> {
        require(started) { "call start() first" }
        if (finished) return emptyList()
        return feed(listOf(GuidanceEvent.UserEndEarly(nowMs)))
    }

    /** Steps until the engine finishes or [maxSimMs] is reached. Returns the summary, or null if unfinished. */
    fun runToEnd(maxSimMs: Long = 4L * 60L * 60L * 1000L): DriveSummary? {
        if (!started) start()
        while (!finished && nowMs < maxSimMs) step()
        return summary
    }

    private fun feed(initial: List<GuidanceEvent>): List<GuidanceCommand> {
        val commands = ArrayList<GuidanceCommand>()
        val queue = ArrayDeque(initial)
        while (queue.isNotEmpty()) {
            val ev = queue.removeFirst()
            for (c in engine.handle(ev)) {
                commands.add(c)
                when (c) {
                    is GuidanceCommand.RequestRoute -> queue.addAll(navi.onCommand(c, nowMs))
                    is GuidanceCommand.Finish -> {
                        finished = true
                        summary = c.summary
                    }
                    is GuidanceCommand.Announce -> Unit
                }
            }
        }
        return commands
    }
}
