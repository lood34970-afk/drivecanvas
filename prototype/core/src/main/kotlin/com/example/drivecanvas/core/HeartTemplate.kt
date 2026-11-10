package com.example.drivecanvas.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Road-friendly heart outline (SPEC 3.2): two unit circles joined by tangent lines, a 90 degree tip
 * at the bottom and a cusp at the top. Points run clockwise from the tip.
 */
object HeartTemplate {
    const val POINT_COUNT = 480
    const val CUSP_INDEX = 240
    const val ANCHOR_STEP = 20
    const val ANCHOR_COUNT = 24

    private const val R = 1.0
    private const val C = 0.9
    private const val H = 2.3
    private const val LINE_PTS = 400
    private const val ARC_PTS = 800

    /** Raw outline: circles of radius 1, tip at (0, -2.3). Closed implicitly. */
    val raw: List<XY> = buildRaw()

    /** Normalised outline: width 1, height 0.8684, centred on the origin. Closed implicitly. */
    val unit: List<XY> = raw.map { XY(it.x / 3.8, (it.y + 0.65) / 3.8) }

    private fun buildRaw(): List<XY> {
        val yc = sqrt(R * R - C * C)
        val tip = XY(0.0, -H)
        val dx = C - tip.x
        val dy = 0.0 - tip.y
        val d = hypot(dx, dy)
        val len = sqrt(d * d - R * R)
        val a = atan2(dy, dx) - asin(R / d)
        val qr = XY(tip.x + len * cos(a), tip.y + len * sin(a))
        val ql = XY(-qr.x, qr.y)

        val fine = ArrayList<XY>()

        fun line(p: XY, q: XY, n: Int) {
            for (i in 0 until n) {
                fine.add(XY(p.x + (q.x - p.x) * i / n, p.y + (q.y - p.y) * i / n))
            }
        }

        fun arc(c: XY, a0: Double, a1: Double) {
            for (i in 0 until ARC_PTS) {
                val t = a0 + (a1 - a0) * i / ARC_PTS
                fine.add(XY(c.x + R * cos(t), c.y + R * sin(t)))
            }
        }

        line(tip, ql, LINE_PTS)

        var aL0 = atan2(ql.y, ql.x + C)
        val aL1 = atan2(yc, C)
        if (aL0 <= aL1) aL0 += 2 * PI
        arc(XY(-C, 0.0), aL0, aL1)

        val aR0 = atan2(yc, -C)
        var aR1 = atan2(qr.y, qr.x - C)
        if (aR1 > aR0) aR1 -= 2 * PI
        arc(XY(C, 0.0), aR0, aR1)

        line(qr, tip, LINE_PTS)
        fine.add(tip)

        // Uniform resampling by arc length (closed loop).
        val cum = cumulativeLengths(fine)
        val total = cum[cum.size - 1]
        val out = ArrayList<XY>(POINT_COUNT)
        var j = 0
        for (i in 0 until POINT_COUNT) {
            val s = total * i / POINT_COUNT
            while (cum[j + 1] < s) j++
            val u = (s - cum[j]) / (cum[j + 1] - cum[j])
            val p = fine[j]
            val q = fine[j + 1]
            out.add(XY(p.x + (q.x - p.x) * u, p.y + (q.y - p.y) * u))
        }
        return out
    }
}

/** Heart placed in local metres: centre, width in metres, rotation in degrees (CCW positive). */
data class Placement(val center: XY, val widthM: Double, val rotationDeg: Double) {
    /** Template outline (POINT_COUNT points, closed implicitly) in local metres. */
    fun place(): List<XY> {
        val rad = Math.toRadians(rotationDeg)
        val c = cos(rad)
        val s = sin(rad)
        return HeartTemplate.unit.map { n ->
            val nx = n.x * widthM
            val ny = n.y * widthM
            XY(center.x + c * nx - s * ny, center.y + s * nx + c * ny)
        }
    }
}
