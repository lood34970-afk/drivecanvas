package com.example.drivecanvas.core.tools

import com.example.drivecanvas.core.CandidateKind
import com.example.drivecanvas.core.CandidatePicker
import com.example.drivecanvas.core.EvaluatedRoute
import com.example.drivecanvas.core.GraphIo
import com.example.drivecanvas.core.LatLon
import com.example.drivecanvas.core.OsmImport
import com.example.drivecanvas.core.PlacementRecord
import com.example.drivecanvas.core.PlacementSearch
import com.example.drivecanvas.core.PlacementsIo
import com.example.drivecanvas.core.RoadGraph
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

/** Region box for OSM filtering and the projection origin (SPEC 3.9). */
data class RegionBox(val name: String, val min: LatLon, val max: LatLon)

private val REGIONS = listOf(
    RegionBox("sejong", LatLon(36.478, 127.235), LatLon(36.523, 127.290)),
    RegionBox("ilsan", LatLon(37.640, 126.745), LatLon(37.685, 126.800)),
    RegionBox("bundang", LatLon(37.345, 127.095), LatLon(37.390, 127.150)),
)

private class RegionRow(
    val name: String,
    val nodes: Int,
    val segments: Int,
    val edges: Int,
    val evaluated: Int,
    val passing: Int,
    val shape: PlacementRecord?,
    val time: PlacementRecord?,
    val regionScore: Double?,
    val seconds: Double,
    val routeMs: Double,
)

/**
 * Offline asset builder. Usage: `--osm <dir> --out <dir> [--regions a,b]`, where <dir> for OSM holds
 * one sub-folder per region with `tile_*.osm` files.
 */
fun main(args: Array<String>) {
    var osmDir: File? = null
    var outDir: File? = null
    var only: Set<String>? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--osm" -> osmDir = File(valueAt(args, ++i))
            "--out" -> outDir = File(valueAt(args, ++i))
            "--regions" -> only = valueAt(args, ++i).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else -> usage("unknown argument ${args[i]}")
        }
        i++
    }
    val osm = osmDir ?: usage("--osm is required")
    val out = outDir ?: usage("--out is required")
    out.mkdirs()

    val wanted = only
    val regions = REGIONS.filter { wanted == null || it.name in wanted }
    if (regions.isEmpty()) usage("no known region in --regions")

    val rows = regions.map { processRegion(it, osm, out) }
    printSummary(rows)
}

private fun valueAt(args: Array<String>, idx: Int): String {
    if (idx >= args.size) usage("missing value after ${args[idx - 1]}")
    return args[idx]
}

private fun usage(msg: String): Nothing {
    System.err.println("error: $msg")
    System.err.println("usage: PreprocessKt --osm <dir> --out <dir> [--regions sejong,ilsan,bundang]")
    exitProcess(2)
}

private fun processRegion(region: RegionBox, osmDir: File, outDir: File): RegionRow {
    val t0 = System.nanoTime()
    val dir = File(osmDir, region.name)
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".osm") }?.sortedBy { it.name }
        ?: emptyList()
    require(files.isNotEmpty()) { "no .osm files in $dir" }

    val data = OsmImport.parseTiles(files)
    val raw = OsmImport.buildNetwork(data, region.name, region.min, region.max)
    File(outDir, "${region.name}.dcg").writer(Charsets.UTF_8).use { GraphIo.write(raw, it) }
    val graph = RoadGraph.build(raw)

    val search = PlacementSearch(graph)
    val results = search.search()
    val passing = results.filter { it.passes }
    val picks = CandidatePicker.pick(passing)
    val records = picks.map { PlacementRecord.of(it, graph.projection) }
    File(outDir, "${region.name}.placements").writer(Charsets.UTF_8).use {
        PlacementsIo.write(region.name, records, it)
    }
    for (c in picks.filter { it.kind != CandidateKind.ALT }) {
        val svg = SvgExport.render(graph, c.route, c.kind)
        File(outDir, "${region.name}_${c.kind.name.lowercase(Locale.ROOT)}.svg")
            .writeText(svg, Charsets.UTF_8)
    }

    val shapeRec = records.firstOrNull { it.kind == CandidateKind.SHAPE }
    val timeRec = records.firstOrNull { it.kind == CandidateKind.TIME }
    val shapeRoute: EvaluatedRoute? = picks.firstOrNull { it.kind == CandidateKind.SHAPE }?.route

    // Single-route timing: best of 3 routeAndRepair calls on the SHAPE placement (or the first routed one).
    val probe = shapeRoute?.placement ?: results.firstOrNull()?.placement
    var routeMs = -1.0
    if (probe != null) {
        var best = Double.POSITIVE_INFINITY
        repeat(3) {
            val s = System.nanoTime()
            search.router.routeAndRepair(probe)
            best = minOf(best, (System.nanoTime() - s) / 1e6)
        }
        routeMs = best
    }

    val regionScore = if (shapeRec != null && timeRec != null) {
        (shapeRec.expScore + timeRec.expScore) / 2.0 -
            0.5 * abs(shapeRec.expEtaMin - 37.5) +
            0.2 * minOf(passing.size, 25)
    } else {
        null
    }
    return RegionRow(
        name = region.name,
        nodes = graph.nodes.size,
        segments = raw.segments.size,
        edges = graph.edges.size,
        evaluated = results.size,
        passing = passing.size,
        shape = shapeRec,
        time = timeRec,
        regionScore = regionScore,
        seconds = (System.nanoTime() - t0) / 1e9,
        routeMs = routeMs,
    )
}

private fun fmt(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

private fun summaryCell(r: PlacementRecord?): String =
    if (r == null) "none" else "${fmt(r.expScore, 1)}/${fmt(r.expEtaMin, 1)}/${fmt(r.expLenKm, 1)}"

private fun printSummary(rows: List<RegionRow>) {
    val header = String.format(
        Locale.ROOT,
        "%-9s %6s %6s %6s %9s  %-20s %-20s %11s %8s %9s",
        "region", "nodes", "segs", "edges", "pass/eval", "SHAPE S/eta/km", "TIME S/eta/km",
        "RegionScore", "time(s)", "route(ms)",
    )
    println(header)
    println("-".repeat(header.length))
    for (r in rows) {
        println(
            String.format(
                Locale.ROOT,
                "%-9s %6d %6d %6d %9s  %-20s %-20s %11s %8s %9s",
                r.name, r.nodes, r.segments, r.edges, "${r.passing}/${r.evaluated}",
                summaryCell(r.shape), summaryCell(r.time),
                r.regionScore?.let { fmt(it, 1) } ?: "none",
                fmt(r.seconds, 1), fmt(r.routeMs, 0),
            ),
        )
    }
    println()
    for (r in rows) {
        for ((label, rec) in listOf("SHAPE" to r.shape, "TIME" to r.time)) {
            if (rec == null) continue
            println(
                String.format(
                    Locale.ROOT,
                    "%-9s %-5s centre %s,%s  W %s  rho %s",
                    r.name, label,
                    fmt(rec.centerLatLon.lat, 5), fmt(rec.centerLatLon.lon, 5),
                    fmt(rec.widthM, 0), fmt(rec.rotDeg, 0),
                ),
            )
        }
    }
    println()
    val best = rows.filter { it.regionScore != null }.maxByOrNull { it.regionScore!! }
    if (best == null) {
        println("Recommended region: none")
    } else {
        println("Recommended region: ${best.name} (RegionScore ${fmt(best.regionScore!!, 1)})")
    }
}
