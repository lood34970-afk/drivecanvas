package com.example.drivecanvas.core.tools

import com.example.drivecanvas.core.CandidateKind
import com.example.drivecanvas.core.EvaluatedRoute
import com.example.drivecanvas.core.RoadGraph
import com.example.drivecanvas.core.XY
import java.util.Locale

/**
 * Plain SVG preview of a routed heart: light grey road edges, dashed template outline, solid coloured
 * route, start marker, caption with S / ETA / length and the OpenStreetMap credit. Units are metres
 * (viewBox), with y flipped so north is up.
 */
object SvgExport {
    private const val MARGIN_M = 150.0
    private const val WIDTH_PX = 1000.0
    private const val CREDIT = "© OpenStreetMap contributors"

    fun render(graph: RoadGraph, route: EvaluatedRoute, kind: CandidateKind): String {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in route.template + route.polyline) {
            minX = minOf(minX, p.x)
            minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x)
            maxY = maxOf(maxY, p.y)
        }
        minX -= MARGIN_M
        minY -= MARGIN_M
        maxX += MARGIN_M
        maxY += MARGIN_M
        val vw = maxX - minX
        val vh = maxY - minY

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${f0(WIDTH_PX)}\" height=\"${f0(WIDTH_PX * vh / vw)}\" ")
            .append("viewBox=\"0 0 ${f1(vw)} ${f1(vh)}\">\n")
        sb.append("<rect x=\"0\" y=\"0\" width=\"${f1(vw)}\" height=\"${f1(vh)}\" fill=\"#ffffff\"/>\n")

        // Road edges inside the view (one path for all of them).
        sb.append("<path fill=\"none\" stroke=\"#c4c4c4\" stroke-width=\"8\" d=\"")
        for (e in graph.edges) {
            var ex0 = Double.POSITIVE_INFINITY
            var ey0 = Double.POSITIVE_INFINITY
            var ex1 = Double.NEGATIVE_INFINITY
            var ey1 = Double.NEGATIVE_INFINITY
            for (p in e.pts) {
                ex0 = minOf(ex0, p.x)
                ey0 = minOf(ey0, p.y)
                ex1 = maxOf(ex1, p.x)
                ey1 = maxOf(ey1, p.y)
            }
            if (ex1 < minX || ex0 > maxX || ey1 < minY || ey0 > maxY) continue
            appendPolyline(sb, e.pts, minX, maxY, open = true)
        }
        sb.append("\"/>\n")

        // Template: dashed closed outline.
        sb.append("<path fill=\"none\" stroke=\"#333333\" stroke-width=\"14\" stroke-dasharray=\"60 40\" d=\"")
        appendPolyline(sb, route.template, minX, maxY, open = false)
        sb.append("\"/>\n")

        // Route: solid, coloured by kind.
        val colour = when (kind) {
            CandidateKind.SHAPE -> "#d62728"
            CandidateKind.TIME -> "#1f77b4"
            CandidateKind.ALT -> "#2ca02c"
        }
        sb.append("<path fill=\"none\" stroke=\"$colour\" stroke-width=\"22\" stroke-linejoin=\"round\" stroke-linecap=\"round\" d=\"")
        appendPolyline(sb, route.polyline, minX, maxY, open = true)
        sb.append("\"/>\n")

        // Start marker at the tail of the first edge.
        val start = graph.nodes[graph.edges[route.edges.first()].tail]
        sb.append("<circle cx=\"${f1(start.x - minX)}\" cy=\"${f1(maxY - start.y)}\" r=\"60\" fill=\"#000000\"/>\n")

        val s = route.shape.score
        val eta = route.stats.etaMin
        val km = route.stats.lengthM / 1000.0
        val caption = String.format(Locale.ROOT, "%s  S %.1f  ETA %.1f min  %.1f km", kind.name, s, eta, km)
        sb.append("<text x=\"60\" y=\"150\" font-size=\"130\" font-family=\"sans-serif\" fill=\"#222222\">")
            .append(caption).append("</text>\n")
        sb.append("<text x=\"60\" y=\"").append(f1(vh - 50.0))
            .append("\" font-size=\"90\" font-family=\"sans-serif\" fill=\"#555555\">").append(CREDIT).append("</text>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun appendPolyline(sb: StringBuilder, pts: List<XY>, minX: Double, maxY: Double, open: Boolean) {
        for ((i, p) in pts.withIndex()) {
            sb.append(if (i == 0) 'M' else 'L')
            sb.append(f1(p.x - minX)).append(' ').append(f1(maxY - p.y)).append(' ')
        }
        if (!open) sb.append('Z')
    }

    private fun f1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    private fun f0(v: Double): String = String.format(Locale.ROOT, "%.0f", v)
}
