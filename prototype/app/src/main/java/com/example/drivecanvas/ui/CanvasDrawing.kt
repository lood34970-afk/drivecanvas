package com.example.drivecanvas.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.drivecanvas.core.RoadGraph
import com.example.drivecanvas.core.XY
import com.example.drivecanvas.core.cumulativeLengths
import kotlin.math.max
import kotlin.math.min

/**
 * Maps local metres (x east, y north) onto canvas pixels with a uniform scale, north up and the points
 * centred inside the padded canvas.
 */
class MapFit private constructor(
    private val minX: Double,
    private val maxY: Double,
    private val scale: Float,
    private val offsetX: Float,
    private val offsetY: Float,
) {
    fun map(p: XY): Offset = Offset(
        offsetX + ((p.x - minX) * scale).toFloat(),
        offsetY + ((maxY - p.y) * scale).toFloat(),
    )

    companion object {
        fun of(points: List<XY>, size: Size, paddingPx: Float): MapFit {
            if (points.isEmpty()) return MapFit(0.0, 0.0, 1f, 0f, 0f)
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val spanX = max(maxX - minX, 1.0)
            val spanY = max(maxY - minY, 1.0)
            val availW = max(size.width - 2 * paddingPx, 1f)
            val availH = max(size.height - 2 * paddingPx, 1f)
            val scale = min(availW / spanX, availH / spanY).toFloat()
            val offX = paddingPx + (availW - spanX.toFloat() * scale) / 2f
            val offY = paddingPx + (availH - spanY.toFloat() * scale) / 2f
            return MapFit(minX, maxY, scale, offX, offY)
        }
    }
}

/** Builds an open (or closed) path through [points] in screen space. */
fun pathOf(points: List<XY>, fit: MapFit, closed: Boolean = false): Path = Path().apply {
    points.forEachIndexed { i, p ->
        val o = fit.map(p)
        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    if (closed && points.size > 2) close()
}

/** Draws every road edge of the graph as a thin grey network. */
fun DrawScope.drawRoadNetwork(graph: RoadGraph, fit: MapFit, color: Color = Palette.Road) {
    val path = Path()
    for (edge in graph.edges) {
        edge.pts.forEachIndexed { i, p ->
            val o = fit.map(p)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
    }
    drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
}

/** Draws a polyline in local metres, optionally dashed (used for the target template). */
fun DrawScope.drawXyLine(
    points: List<XY>,
    fit: MapFit,
    color: Color,
    width: Dp,
    dashed: Boolean = false,
    closed: Boolean = false,
    alpha: Float = 1f,
) {
    if (points.size < 2) return
    val effect: PathEffect? = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()))
    } else {
        null
    }
    drawPath(
        path = pathOf(points, fit, closed),
        color = color,
        alpha = alpha,
        style = Stroke(
            width = width.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = effect,
        ),
    )
}

/** Start marker: filled dot with a dark ring so it stands out on the road grey. */
fun DrawScope.drawStartMarker(at: Offset, color: Color = Palette.TextPrimary) {
    drawCircle(color = Palette.Background, radius = 14.dp.toPx(), center = at)
    drawCircle(color = color, radius = 10.dp.toPx(), center = at)
}

/**
 * Removes the points within [meters] of either end of the track (arc length along the points). Used for
 * the share card so the start and finish places are not shown.
 */
fun hideEnds(points: List<XY>, meters: Double): List<XY> {
    if (points.size < 2) return emptyList()
    val cumulative = cumulativeLengths(points)
    val total = cumulative.last()
    return points.filterIndexed { i, _ -> cumulative[i] >= meters && total - cumulative[i] >= meters }
}
