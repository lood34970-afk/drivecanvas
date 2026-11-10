package com.example.drivecanvas.core

import java.io.InputStream

/**
 * A candidate offered to the UI: the routed loop, its navi legs, and the Korean label. Named
 * PlannedCandidate because [Candidate] (PlacementSearch.kt) is the picker's own type.
 */
data class PlannedCandidate(
    val kind: CandidateKind,
    val route: EvaluatedRoute,
    val plan: GuidancePlan,
) {
    val labelKo: String
        get() = when (kind) {
            CandidateKind.SHAPE -> "모양 우선"
            CandidateKind.TIME -> "시간 우선"
            CandidateKind.ALT -> "다른 경로"
        }
}

/** Outcome of planning on device: at least the shape-first candidate, or the Korean reasons why none passed. */
sealed class PlanResult {
    data class Ok(val shapeFirst: PlannedCandidate, val timeFirst: PlannedCandidate?) : PlanResult()

    data class NoRoute(val reasonsKo: List<String>) : PlanResult()
}

/**
 * On-device planner (SPEC 3.9): re-routes the bundled placements, keeps the passing ones, picks
 * SHAPE and TIME candidates and splits each into navi legs. [quality] defaults to the spec gates.
 */
class CanvasPlanner(
    private val graph: RoadGraph,
    quality: QualityThresholds = QualityThresholds(),
) {
    private val router = ShapeRouter(graph, quality = quality)
    private val legPlanner = LegPlanner(graph)

    fun planFromPlacements(records: List<PlacementRecord>, targetMin: Double = 35.0): PlanResult {
        val passing = ArrayList<EvaluatedRoute>()
        val reasons = LinkedHashSet<String>()
        for (rec in records) {
            val placement = Placement(graph.projection.toXY(rec.centerLatLon), rec.widthM, rec.rotDeg)
            when (val outcome = router.routeAndRepair(placement)) {
                is RouteOutcome.Found -> {
                    if (outcome.route.passes) passing.add(outcome.route) else reasons.add(QUALITY_KO)
                }
                is RouteOutcome.NoRoute -> reasons.add(reasonKo(outcome.reason))
            }
        }

        val picks = CandidatePicker.pick(passing, targetMin)
        val shape = picks.firstOrNull { it.kind == CandidateKind.SHAPE }
            ?: return PlanResult.NoRoute(reasons.toList().ifEmpty { listOf(QUALITY_KO) })
        val time = picks.firstOrNull { it.kind == CandidateKind.TIME }
        return PlanResult.Ok(
            shapeFirst = PlannedCandidate(CandidateKind.SHAPE, shape.route, legPlanner.plan(shape.route)),
            timeFirst = time?.let { PlannedCandidate(CandidateKind.TIME, it.route, legPlanner.plan(it.route)) },
        )
    }

    private fun reasonKo(reason: NoRouteReason): String = when (reason) {
        NoRouteReason.START_TOO_FAR -> "출발점 근처에 차량 도로가 없어요"
        NoRouteReason.NO_ROAD_NEAR_ANCHOR -> "그림 일부 구간 근처에 도로가 없어요"
        NoRouteReason.NO_PATH, NoRouteReason.SEARCH_LIMIT -> "규칙을 지키는 연결 경로가 없어요"
        NoRouteReason.QUALITY -> QUALITY_KO
    }

    companion object {
        private const val QUALITY_KO = "안전·모양 기준을 통과한 경로가 없어요"

        /** Reads a "DCG 1" road graph asset. */
        fun loadGraph(dcg: InputStream): RoadGraph = GraphIo.read(dcg)

        /** Reads a "DCP 1" placements asset. */
        fun loadPlacements(input: InputStream): PlacementFile = PlacementsIo.read(input)
    }
}
