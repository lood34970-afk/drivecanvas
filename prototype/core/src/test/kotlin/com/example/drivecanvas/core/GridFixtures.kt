package com.example.drivecanvas.core

import java.io.ByteArrayInputStream
import java.io.StringWriter

/** Synthetic street grids for tests. Helper object, deliberately not named *Test. */
object GridFixtures {
    val ORIGIN = LatLon(37.5, 127.0)

    /**
     * n x n grid, node (i, j) at (i*s - off, j*s - off) with off = (n-1)*s/2 (i.e. -2000..2000 for the
     * defaults). Node index = i*n + j. Horizontal streets are two-way TERTIARY. Vertical streets: with
     * [onewayColumns], even i is northbound only and odd i southbound only; otherwise two-way.
     * Segment order: for j, for i: horizontal (i,j)->(i+1,j) if i+1<n, then vertical (i,j)->(i,j+1) if j+1<n.
     */
    fun rawGrid(n: Int = 21, spacingM: Double = 200.0, onewayColumns: Boolean = true): RawNetwork {
        val proj = LocalProjection(ORIGIN.lat, ORIGIN.lon)
        val off = (n - 1) * spacingM / 2
        val nodes = ArrayList<LatLon>(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            nodes.add(proj.toLatLon(XY(i * spacingM - off, j * spacingM - off)))
        }
        fun idx(i: Int, j: Int): Int = i * n + j
        val tertiary = RoadClass.fromOsm("tertiary")!!.code
        val segments = ArrayList<RawSegment>()
        for (j in 0 until n) for (i in 0 until n) {
            if (i + 1 < n) {
                segments.add(RawSegment(idx(i, j), idx(i + 1, j), tertiary, oneway = false, shape = emptyList()))
            }
            if (j + 1 < n) {
                if (onewayColumns && i % 2 == 1) {
                    segments.add(RawSegment(idx(i, j + 1), idx(i, j), tertiary, oneway = true, shape = emptyList()))
                } else {
                    segments.add(
                        RawSegment(
                            idx(i, j),
                            idx(i, j + 1),
                            tertiary,
                            oneway = onewayColumns,
                            shape = emptyList(),
                        ),
                    )
                }
            }
        }
        return RawNetwork(
            region = "grid",
            origin = ORIGIN,
            bboxMin = proj.toLatLon(XY(-off, -off)),
            bboxMax = proj.toLatLon(XY(off, off)),
            nodes = nodes,
            segments = segments,
            bans = emptyList(),
        )
    }

    /** Grid built through a DCG write/read round trip. */
    fun gridGraph(n: Int = 21, spacingM: Double = 200.0, onewayColumns: Boolean = true): RoadGraph {
        val sw = StringWriter()
        GraphIo.write(rawGrid(n, spacingM, onewayColumns), sw)
        return GraphIo.read(ByteArrayInputStream(sw.toString().toByteArray(Charsets.UTF_8)))
    }
}
