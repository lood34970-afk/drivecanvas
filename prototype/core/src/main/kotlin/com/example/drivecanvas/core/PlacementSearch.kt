package com.example.drivecanvas.core

import java.io.InputStream
import java.io.Writer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Offline placement search parameters (SPEC 3.3). Defaults are the Ilsan/Sejong/Bundang run. */
data class SearchConfig(
    val widthsM: List<Double> = (0..6).map { 2500.0 + 250.0 * it },
    val rotationsDeg: List<Double> = (-3..3).map { 10.0 * it },
    val centreStepM: Double = 200.0,
    val topPerWidth: Int = 10,
    val marginM: Double = 150.0,
    /** Prescore tolerance tau as a fraction of W. */
    val tauFrac: Double = 0.04,
    val quality: QualityThresholds = QualityThresholds(),
)

enum class CandidateKind { SHAPE, TIME, ALT }

/** A picked placement and its role. */
data class Candidate(val kind: CandidateKind, val route: EvaluatedRoute)

/** Placement search over a road graph: prescore on a grid, then route the best candidates per width. */
class PlacementSearch(
    val graph: RoadGraph,
    val config: SearchConfig = SearchConfig(),
    val router: ShapeRouter = ShapeRouter(graph, quality = config.quality),
) {
    /** PointGrid over every edge sample (40 m apart), built on first use. */
    private val roadGrid: PointGrid by lazy {
        val pts = ArrayList<XY>()
        for (e in graph.edges) pts.addAll(e.samples)
        PointGrid(pts, 50.0)
    }

    /** Prescore of [placement]: -1 when outside the bbox shrunk by the margin, else coverage + 0.1 * features. */
    fun prescore(placement: Placement): Double = prescoreOf(placement.place(), placement.widthM)

    private fun prescoreOf(tpl: List<XY>, widthM: Double): Double {
        val lo = graph.bboxMinXY
        val hi = graph.bboxMaxXY
        val mg = config.marginM
        for (i in 0 until tpl.size step 5) {
            val p = tpl[i]
            if (p.x < lo.x + mg || p.x > hi.x - mg || p.y < lo.y + mg || p.y > hi.y - mg) return -1.0
        }
        val tau = config.tauFrac * widthM
        var hit = 0
        var n = 0
        for (i in 0 until tpl.size step 4) {
            n++
            if (roadGrid.nearestWithin(tpl[i], tau) != null) hit++
        }
        var features = 0
        if (roadGrid.nearestWithin(tpl[0], tau) != null) features++
        if (roadGrid.nearestWithin(tpl[tpl.size / 2], tau) != null) features++
        return hit.toDouble() / n + 0.1 * features
    }

    /**
     * Searches all widths and rotations on the centre grid. Per width the top [SearchConfig.topPerWidth]
     * prescored placements (sorted by -prescore, rotation, cx, cy; prescore > 0) are routed with
     * routeAndRepair. Returns every routed (Found) placement, passing or not.
     */
    fun search(): List<EvaluatedRoute> {
        val results = ArrayList<EvaluatedRoute>()
        val lo = graph.bboxMinXY
        val hi = graph.bboxMaxXY
        val step = config.centreStepM
        for (w in config.widthsM) {
            val cands = ArrayList<Cand>()
            for (rot in config.rotationsDeg) {
                var cx = lo.x
                while (cx <= hi.x) {
                    var cy = lo.y
                    while (cy <= hi.y) {
                        val score = prescoreOf(Placement(XY(cx, cy), w, rot).place(), w)
                        if (score > 0.0) cands.add(Cand(score, cx, cy, rot))
                        cy += step
                    }
                    cx += step
                }
            }
            cands.sortWith(compareBy<Cand>({ -it.score }, { it.rot }, { it.cx }, { it.cy }))
            for (c in cands.take(config.topPerWidth)) {
                val outcome = router.routeAndRepair(Placement(XY(c.cx, c.cy), w, c.rot))
                if (outcome is RouteOutcome.Found) results.add(outcome.route)
            }
        }
        return results
    }

    private class Cand(val score: Double, val cx: Double, val cy: Double, val rot: Double)
}

/**
 * Candidate selection (SPEC 3.3). SHAPE = argmax S - 0.5*max(0, eta-(T+10)) over passing routes.
 * TIME = argmax S among distinct passing routes with eta <= T, else the smallest eta among distinct
 * passing routes. ALT = up to 6 more passing routes, by S descending, each distinct from every
 * candidate already picked.
 */
object CandidatePicker {
    const val ALT_MAX = 6

    /** Two placements are distinct when centres are >300 m apart, or |dW| >= 250, or |drho| >= 10. */
    fun isDistinct(a: EvaluatedRoute, b: EvaluatedRoute): Boolean {
        val pa = a.placement
        val pb = b.placement
        return pa.center.distanceTo(pb.center) > 300.0 ||
            abs(pa.widthM - pb.widthM) >= 250.0 ||
            abs(pa.rotationDeg - pb.rotationDeg) >= 10.0
    }

    fun pick(passing: List<EvaluatedRoute>, targetMin: Double = 35.0): List<Candidate> {
        val ok = passing.filter { it.passes }
        // SHAPE must fit the product's 30–45 min drive band (proposal §10); fall back to the
        // spec 3.3 formula only when no passing route lands inside the band.
        val inBand = ok.filter { it.stats.etaMin in (targetMin - 5.0)..(targetMin + 10.0) }
        val shape = inBand.maxByOrNull { it.shape.score }
            ?: ok.maxByOrNull { it.shape.score - 0.5 * max(0.0, it.stats.etaMin - (targetMin + 10.0)) }
            ?: return emptyList()
        val out = ArrayList<Candidate>()
        val chosen = ArrayList<EvaluatedRoute>()
        out.add(Candidate(CandidateKind.SHAPE, shape))
        chosen.add(shape)

        val distinct = ok.filter { isDistinct(it, shape) }
        val time = distinct.filter { it.stats.etaMin <= targetMin }.maxByOrNull { it.shape.score }
            ?: distinct.minByOrNull { it.stats.etaMin }
        if (time != null) {
            out.add(Candidate(CandidateKind.TIME, time))
            chosen.add(time)
        }

        var alts = 0
        for (r in ok.sortedByDescending { it.shape.score }) {
            if (alts == ALT_MAX) break
            if (chosen.all { isDistinct(r, it) }) {
                out.add(Candidate(CandidateKind.ALT, r))
                chosen.add(r)
                alts++
            }
        }
        return out
    }
}

/** One picked placement in the DCP asset (centre in lat/lon). */
data class PlacementRecord(
    val kind: CandidateKind,
    val centerLatLon: LatLon,
    val widthM: Double,
    val rotDeg: Double,
    val expScore: Double,
    val expEtaMin: Double,
    val expLenKm: Double,
) {
    companion object {
        fun of(c: Candidate, projection: LocalProjection): PlacementRecord {
            val p = c.route.placement
            return PlacementRecord(
                kind = c.kind,
                centerLatLon = projection.toLatLon(p.center),
                widthM = p.widthM,
                rotDeg = p.rotationDeg,
                expScore = c.route.shape.score,
                expEtaMin = c.route.stats.etaMin,
                expLenKm = c.route.stats.lengthM / 1000.0,
            )
        }
    }
}

/** Parsed "DCP 1" asset. */
data class PlacementFile(val region: String, val records: List<PlacementRecord>)

/**
 * Text asset "DCP 1":
 * ```
 * DCP 1
 * region <name>
 * candidates <K>
 * <SHAPE|TIME|ALT> <centerLat> <centerLon> <widthM> <rotDeg> <expScore> <expEtaMin> <expLenKm>  x K
 * end
 * ```
 */
object PlacementsIo {
    private const val MAGIC = "DCP 1"

    fun write(region: String, records: List<PlacementRecord>, out: Writer) {
        val sb = StringBuilder()
        sb.append(MAGIC).append('\n')
        sb.append("region ").append(region).append('\n')
        sb.append("candidates ").append(records.size).append('\n')
        for (r in records) {
            sb.append(r.kind.name).append(' ')
                .append(fmt(r.centerLatLon.lat, 7)).append(' ')
                .append(fmt(r.centerLatLon.lon, 7)).append(' ')
                .append(fmt(r.widthM, 4)).append(' ')
                .append(fmt(r.rotDeg, 4)).append(' ')
                .append(fmt(r.expScore, 4)).append(' ')
                .append(fmt(r.expEtaMin, 4)).append(' ')
                .append(fmt(r.expLenKm, 4)).append('\n')
        }
        sb.append("end\n")
        out.write(sb.toString())
        out.flush()
    }

    fun read(input: InputStream): PlacementFile {
        val lines = input.bufferedReader(Charsets.UTF_8).readLines()
        if (lines.isEmpty() || lines[0].trim() != MAGIC) {
            throw IllegalArgumentException("Unsupported placements format")
        }
        var pos = 1
        fun next(): String {
            if (pos >= lines.size) throw IllegalArgumentException("Unexpected end of placements file")
            return lines[pos++].trim()
        }
        fun keyed(key: String): String {
            val line = next()
            val prefix = "$key "
            if (!line.startsWith(prefix)) throw IllegalArgumentException("Expected '$key' line, got: $line")
            return line.substring(prefix.length)
        }

        val region = keyed("region")
        val count = keyed("candidates").toInt()
        val records = ArrayList<PlacementRecord>(count)
        repeat(count) {
            val t = next().split(' ')
            require(t.size == 8) { "bad candidate line" }
            records.add(
                PlacementRecord(
                    kind = CandidateKind.valueOf(t[0]),
                    centerLatLon = LatLon(t[1].toDouble(), t[2].toDouble()),
                    widthM = t[3].toDouble(),
                    rotDeg = t[4].toDouble(),
                    expScore = t[5].toDouble(),
                    expEtaMin = t[6].toDouble(),
                    expLenKm = t[7].toDouble(),
                ),
            )
        }
        require(next() == "end") { "missing end marker" }
        return PlacementFile(region, records)
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)
}
