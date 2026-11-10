package com.example.drivecanvas.core

import kotlin.math.max

enum class GuidanceState { IDLE, LEG_REQUESTED, GUIDING, OFF_ROUTE, ENDING_EARLY, FINISHED }

enum class EndReason { COMPLETED, ENDED_EARLY_OFF_ROUTE, ENDED_EARLY_USER, CANCELLED, ERROR }

/** Input to [GuidanceEngine.handle]. Every event carries the time it happened (ms). */
sealed class GuidanceEvent {
    abstract val timeMs: Long

    data class Start(override val timeMs: Long) : GuidanceEvent()
    data class RouteStarted(override val timeMs: Long) : GuidanceEvent()
    data class WaypointArrived(override val timeMs: Long, val index: Int) : GuidanceEvent()
    data class DestinationArrived(override val timeMs: Long) : GuidanceEvent()
    data class ReRouteEnded(override val timeMs: Long) : GuidanceEvent()
    data class RouteCancelled(override val timeMs: Long) : GuidanceEvent()
    data class NaviError(override val timeMs: Long, val message: String) : GuidanceEvent()
    data class Location(override val timeMs: Long, val latLon: LatLon, val headingDeg: Double) : GuidanceEvent()
    data class UserEndEarly(override val timeMs: Long) : GuidanceEvent()
    data class Tick(override val timeMs: Long) : GuidanceEvent()
}

/** Output of [GuidanceEngine.handle]. [RequestRoute.legIndex] is -1 for a home request (leg is null). */
sealed class GuidanceCommand {
    data class RequestRoute(
        val legIndex: Int,
        val waypoints: List<LatLon>,
        val destination: LatLon,
        val leg: Leg? = null,
    ) : GuidanceCommand()

    data class Announce(val textKo: String) : GuidanceCommand()

    data class Finish(val summary: DriveSummary) : GuidanceCommand()
}

/** Thresholds and windows of the guidance state machine (SPEC 3.7). [demo] shortens the give-up limits. */
data class GuidanceConfig(
    val offRouteDistM: Double = 50.0,
    val offRouteHoldMs: Long = 8_000L,
    val rejoinDistM: Double = 30.0,
    val rejoinHoldMs: Long = 5_000L,
    val giveUpOffRouteMs: Long = 180_000L,
    val giveUpDistM: Double = 1_000.0,
    val minProjectedCompletion: Double = 0.6,
    val legRequestTimeoutMs: Long = 20_000L,
    val maxLegRetries: Int = 1,
    val arrivalFallbackDistM: Double = 30.0,
    val arrivalFallbackHoldMs: Long = 8_000L,
    /** Progress search window: behind and ahead of the current progress s (metres). */
    val progressBehindM: Double = 50.0,
    val progressAheadM: Double = 600.0,
    /** Rejoin search window ahead of s while off route (metres). */
    val rejoinAheadM: Double = 3_000.0,
    /** Plan samples within this distance of a drawing fix count as covered (metres). */
    val coverageTolM: Double = 40.0,
) {
    companion object {
        /** Shorter give-up limits for demos and the deviation simulation. */
        fun demo(): GuidanceConfig = GuidanceConfig(giveUpOffRouteMs = 20_000L, giveUpDistM = 400.0)
    }
}

/** A recorded fix. [drawing] is true only while LEG_REQUESTED, GUIDING or OFF_ROUTE. */
data class TrackPoint(val timeMs: Long, val latLon: LatLon, val drawing: Boolean)

/**
 * Result of a drive. [completion] is the share of route samples within 40 m of the cleaned drawing
 * points; [shapeScore] is S computed on those points; [track] is the cleaned full track and
 * [drawingTrack] its drawing subset.
 */
data class DriveSummary(
    val endReason: EndReason,
    val completion: Double,
    val shapeScore: Double,
    val durationMs: Long,
    val distanceM: Double,
    val track: List<TrackPoint>,
    val drawingTrack: List<TrackPoint>,
)

/**
 * Pure guidance state machine over a [GuidancePlan] (SPEC 3.7). Feed events with [handle]; it
 * returns the commands to execute. Not thread-safe.
 */
class GuidanceEngine(val plan: GuidancePlan, val config: GuidanceConfig = GuidanceConfig()) {
    private val samples: List<XY> = plan.samples
    private val sampleS: DoubleArray = DoubleArray(plan.sampleS.size) { plan.sampleS[it] }
    private val covered = BooleanArray(samples.size)
    private var coveredCount = 0
    private val totalLenM: Double = polylineLength(plan.routeXY)
    private val trackList = ArrayList<TrackPoint>()

    private var stateValue = GuidanceState.IDLE
    private var legIndex = -1
    /** Progress: arc length (m) along the route, never decreasing. */
    private var s = 0.0
    private var startMs = 0L
    private var lastNaviMs = 0L
    private var requestSinceMs = 0L
    private var retries = 0
    private var offCandidateSince: Long? = null
    private var offEnteredMs = 0L
    private var offDistAccum = 0.0
    private var rejoinSince: Long? = null
    private var rejoinIdx = -1
    private var endReason: EndReason? = null
    private var lastXY: XY? = null

    val state: GuidanceState get() = stateValue

    /** Index of the leg being requested or guided; -1 before the first request and during a home request. */
    val currentLegIndex: Int get() = legIndex

    val legCount: Int get() = plan.legs.size

    /** Progress along the route, 0..1. */
    val progressFraction: Double
        get() = if (totalLenM <= 0.0) 0.0 else (s / totalLenM).coerceIn(0.0, 1.0)

    /** Every recorded fix so far (raw; the summary holds the cleaned track). */
    val track: List<TrackPoint> get() = trackList

    fun handle(event: GuidanceEvent): List<GuidanceCommand> {
        if (stateValue == GuidanceState.FINISHED) return emptyList()
        val out = ArrayList<GuidanceCommand>()
        val t = event.timeMs
        when (event) {
            is GuidanceEvent.Start -> if (stateValue == GuidanceState.IDLE) {
                startMs = t
                lastNaviMs = t
                requestLeg(0, t, out)
            }
            is GuidanceEvent.RouteStarted -> {
                lastNaviMs = t
                if (stateValue == GuidanceState.LEG_REQUESTED) stateValue = GuidanceState.GUIDING
            }
            is GuidanceEvent.WaypointArrived -> lastNaviMs = t
            is GuidanceEvent.ReRouteEnded -> lastNaviMs = t
            is GuidanceEvent.DestinationArrived -> {
                lastNaviMs = t
                onArrival(t, out)
            }
            is GuidanceEvent.RouteCancelled -> finish(EndReason.CANCELLED, t, out)
            is GuidanceEvent.NaviError -> finish(EndReason.ERROR, t, out)
            is GuidanceEvent.UserEndEarly -> if (stateValue in LEG_STATES) {
                endEarly(EndReason.ENDED_EARLY_USER, t, out)
            }
            is GuidanceEvent.Location -> onLocation(event, out)
            is GuidanceEvent.Tick -> onTick(t, out)
        }
        return out
    }

    private fun onLocation(ev: GuidanceEvent.Location, out: MutableList<GuidanceCommand>) {
        val t = ev.timeMs
        val drawing = stateValue in DRAWING_STATES
        trackList.add(TrackPoint(t, ev.latLon, drawing))
        val xy = plan.projection.toXY(ev.latLon)
        if (drawing) markCovered(xy)
        val prev = lastXY
        lastXY = xy

        when (stateValue) {
            GuidanceState.GUIDING -> {
                val hit = nearest(xy, s - config.progressBehindM, s + config.progressAheadM)
                if (hit.index >= 0 && hit.dist <= config.offRouteDistM) {
                    s = max(s, sampleS[hit.index])
                    offCandidateSince = null
                } else if (offCandidateSince == null) {
                    offCandidateSince = t
                }
                checkGuidingTimers(t, xy, out)
            }
            GuidanceState.OFF_ROUTE -> {
                if (prev != null) offDistAccum += prev.distanceTo(xy)
                val hit = nearest(xy, s - config.progressBehindM, s + config.rejoinAheadM)
                if (hit.index >= 0 && hit.dist <= config.rejoinDistM) {
                    if (rejoinSince == null) rejoinSince = t
                    rejoinIdx = hit.index
                } else {
                    rejoinSince = null
                    rejoinIdx = -1
                }
                checkOffRouteTimers(t, out)
            }
            else -> Unit
        }
    }

    private fun onTick(t: Long, out: MutableList<GuidanceCommand>) {
        when (stateValue) {
            GuidanceState.LEG_REQUESTED -> if (t - requestSinceMs >= config.legRequestTimeoutMs) {
                if (retries < config.maxLegRetries) {
                    retries++
                    requestSinceMs = t
                    out.add(legRequest(plan.legs[legIndex]))
                } else {
                    out.add(GuidanceCommand.Announce(TEXT_NO_RESPONSE))
                    finish(EndReason.ERROR, t, out)
                }
            }
            GuidanceState.GUIDING -> lastXY?.let { checkGuidingTimers(t, it, out) }
            GuidanceState.OFF_ROUTE -> checkOffRouteTimers(t, out)
            else -> Unit
        }
    }

    private fun checkGuidingTimers(t: Long, xy: XY, out: MutableList<GuidanceCommand>) {
        val since = offCandidateSince
        if (since != null && t - since >= config.offRouteHoldMs) {
            enterOffRoute(t, out)
            return
        }
        checkFallbackArrival(xy, t, out)
    }

    /** Arrival without a navi event: within range of the leg destination, progress reached, and navi silent. */
    private fun checkFallbackArrival(xy: XY, t: Long, out: MutableList<GuidanceCommand>) {
        if (legIndex < 0) return
        val leg = plan.legs[legIndex]
        val destXY = if (leg.isFinal) plan.routeXY.last() else plan.projection.toXY(leg.destination)
        val destS = if (leg.isFinal) totalLenM else leg.targetRouteS.last()
        if (xy.distanceTo(destXY) <= config.arrivalFallbackDistM &&
            s >= destS - config.arrivalFallbackDistM &&
            t - lastNaviMs >= config.arrivalFallbackHoldMs
        ) {
            onArrival(t, out)
        }
    }

    private fun checkOffRouteTimers(t: Long, out: MutableList<GuidanceCommand>) {
        val since = rejoinSince
        if (since != null && t - since >= config.rejoinHoldMs && rejoinIdx >= 0) {
            stateValue = GuidanceState.GUIDING
            s = max(s, sampleS[rejoinIdx])
            rejoinSince = null
            rejoinIdx = -1
            offCandidateSince = null
            return
        }
        val elapsed = t - offEnteredMs
        if (elapsed >= config.giveUpOffRouteMs ||
            offDistAccum >= config.giveUpDistM ||
            projectedCompletion() < config.minProjectedCompletion
        ) {
            endEarly(EndReason.ENDED_EARLY_OFF_ROUTE, t, out)
        }
    }

    private fun enterOffRoute(t: Long, out: MutableList<GuidanceCommand>) {
        stateValue = GuidanceState.OFF_ROUTE
        offEnteredMs = t
        offDistAccum = 0.0
        offCandidateSince = null
        rejoinSince = null
        rejoinIdx = -1
        out.add(GuidanceCommand.Announce(TEXT_OFF_ROUTE))
    }

    /** Arrival at the current leg destination (navi event or fallback). */
    private fun onArrival(t: Long, out: MutableList<GuidanceCommand>) {
        when (stateValue) {
            GuidanceState.GUIDING, GuidanceState.OFF_ROUTE -> {
                if (plan.legs[legIndex].isFinal) {
                    out.add(GuidanceCommand.Announce(TEXT_COMPLETED))
                    finish(EndReason.COMPLETED, t, out)
                } else {
                    requestLeg(legIndex + 1, t, out)
                    out.add(GuidanceCommand.Announce("다음 구간 안내를 시작합니다 (${legIndex + 1}/${legCount})"))
                }
            }
            GuidanceState.ENDING_EARLY -> finish(endReason ?: EndReason.ENDED_EARLY_USER, t, out)
            else -> Unit
        }
    }

    private fun requestLeg(k: Int, t: Long, out: MutableList<GuidanceCommand>) {
        legIndex = k
        stateValue = GuidanceState.LEG_REQUESTED
        requestSinceMs = t
        retries = 0
        offCandidateSince = null
        rejoinSince = null
        rejoinIdx = -1
        out.add(legRequest(plan.legs[k]))
    }

    private fun legRequest(leg: Leg): GuidanceCommand.RequestRoute =
        GuidanceCommand.RequestRoute(leg.index, leg.waypoints, leg.destination, leg)

    private fun endEarly(reason: EndReason, t: Long, out: MutableList<GuidanceCommand>) {
        stateValue = GuidanceState.ENDING_EARLY
        endReason = reason
        legIndex = -1
        offCandidateSince = null
        rejoinSince = null
        rejoinIdx = -1
        out.add(GuidanceCommand.RequestRoute(-1, emptyList(), plan.start, null))
        out.add(
            GuidanceCommand.Announce(
                if (reason == EndReason.ENDED_EARLY_OFF_ROUTE) TEXT_GIVE_UP else TEXT_USER_END,
            ),
        )
    }

    private fun finish(reason: EndReason, t: Long, out: MutableList<GuidanceCommand>) {
        stateValue = GuidanceState.FINISHED
        out.add(GuidanceCommand.Finish(buildSummary(reason, t)))
    }

    /** Share of samples covered, plus uncovered samples beyond the next target of the current leg. */
    private fun projectedCompletion(): Double {
        if (samples.isEmpty()) return 0.0
        val leg = plan.legs[legIndex]
        val nextS = if (leg.isFinal) {
            totalLenM
        } else {
            leg.targetRouteS.firstOrNull { it > s } ?: leg.targetRouteS.last()
        }
        var beyond = 0
        for (i in samples.indices) {
            if (!covered[i] && sampleS[i] > nextS) beyond++
        }
        return (coveredCount + beyond).toDouble() / samples.size
    }

    private fun markCovered(xy: XY) {
        for (i in samples.indices) {
            if (!covered[i] && samples[i].distanceTo(xy) <= config.coverageTolM) {
                covered[i] = true
                coveredCount++
            }
        }
    }

    private class NearHit(val index: Int, val dist: Double)

    /** Nearest plan sample with arc length in [lo, hi]; index -1 when none. */
    private fun nearest(xy: XY, lo: Double, hi: Double): NearHit {
        var best = -1
        var bestD = Double.POSITIVE_INFINITY
        for (i in samples.indices) {
            val si = sampleS[i]
            if (si < lo || si > hi) continue
            val d = samples[i].distanceTo(xy)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return NearHit(best, bestD)
    }

    private fun buildSummary(reason: EndReason, t: Long): DriveSummary {
        val proj = plan.projection
        val cleaned = Metrics.cleanTrack(trackList, { it.timeMs }, { proj.toXY(it.latLon) })
        val drawing = cleaned.filter { it.drawing }
        val drawXY = drawing.map { proj.toXY(it.latLon) }
        val completion = if (drawXY.isEmpty()) 0.0 else Metrics.completion(plan.routeXY, drawXY)
        val shape = if (drawXY.isEmpty() || plan.templateXY.isEmpty() || plan.widthM <= 0.0) {
            0.0
        } else {
            Metrics.shapeMetrics(drawXY, plan.templateXY, plan.widthM).score
        }
        var distance = 0.0
        for (i in 1 until cleaned.size) {
            distance += proj.toXY(cleaned[i - 1].latLon).distanceTo(proj.toXY(cleaned[i].latLon))
        }
        return DriveSummary(
            endReason = reason,
            completion = completion,
            shapeScore = shape,
            durationMs = t - startMs,
            distanceM = distance,
            track = cleaned,
            drawingTrack = drawing,
        )
    }

    companion object {
        private val LEG_STATES = setOf(
            GuidanceState.LEG_REQUESTED,
            GuidanceState.GUIDING,
            GuidanceState.OFF_ROUTE,
        )
        private val DRAWING_STATES = LEG_STATES

        const val TEXT_OFF_ROUTE = "경로를 벗어났어요. 내비게이션이 안전한 길로 다시 안내합니다."
        const val TEXT_COMPLETED = "하트 완성! 안전한 곳에 정차한 뒤 결과를 확인하세요."
        const val TEXT_NO_RESPONSE = "내비게이션 응답이 없어 안내를 종료합니다"
        const val TEXT_GIVE_UP = "그림 완성도가 낮아져요. 무리하게 돌아가지 않고 출발지로 안전하게 안내할게요."
        const val TEXT_USER_END = "그리기를 마치고 출발지로 안내할게요."
    }
}
