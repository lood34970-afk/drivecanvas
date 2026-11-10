package com.example.drivecanvas.core

import java.io.InputStream
import java.io.Writer
import java.util.Locale

/**
 * Text asset "DCG 1" (UTF-8, `\n` line endings, single spaces, coordinates with 7 decimals).
 *
 * ```
 * DCG 1
 * region <name>
 * origin <lat> <lon>
 * bbox <minLat> <minLon> <maxLat> <maxLon>
 * nodes <N>
 * <lat> <lon>                                  x N
 * segments <S>
 * <a> <b> <classCode> <oneway 0|1> <k> [<lat> <lon>] x k     x S
 * bans <B>
 * <fromSeg> <fromDir> <toSeg> <toDir>          x B
 * end
 * ```
 */
object GraphIo {
    private const val MAGIC = "DCG 1"

    fun write(raw: RawNetwork, out: Writer) {
        val sb = StringBuilder()
        sb.append(MAGIC).append('\n')
        sb.append("region ").append(raw.region).append('\n')
        sb.append("origin ").append(coord(raw.origin)).append('\n')
        sb.append("bbox ").append(coord(raw.bboxMin)).append(' ').append(coord(raw.bboxMax)).append('\n')
        sb.append("nodes ").append(raw.nodes.size).append('\n')
        for (p in raw.nodes) sb.append(coord(p)).append('\n')
        sb.append("segments ").append(raw.segments.size).append('\n')
        for (s in raw.segments) {
            sb.append(s.a).append(' ').append(s.b).append(' ').append(s.classCode).append(' ')
                .append(if (s.oneway) 1 else 0).append(' ').append(s.shape.size)
            for (p in s.shape) sb.append(' ').append(coord(p))
            sb.append('\n')
        }
        sb.append("bans ").append(raw.bans.size).append('\n')
        for (b in raw.bans) {
            sb.append(b.fromSeg).append(' ').append(b.fromDir).append(' ')
                .append(b.toSeg).append(' ').append(b.toDir).append('\n')
        }
        sb.append("end\n")
        out.write(sb.toString())
        out.flush()
    }

    fun readRaw(input: InputStream): RawNetwork {
        val lines = input.bufferedReader(Charsets.UTF_8).readLines()
        if (lines.isEmpty() || lines[0].trim() != MAGIC) {
            throw IllegalArgumentException("Unsupported graph format")
        }
        val cursor = Cursor(lines)
        cursor.next() // magic

        val region = cursor.keyed("region").let { it.trim() }
        val origin = latLon(cursor.keyed("origin"))
        val bb = cursor.keyed("bbox").split(' ')
        require(bb.size == 4) { "bad bbox line" }
        val bboxMin = LatLon(bb[0].toDouble(), bb[1].toDouble())
        val bboxMax = LatLon(bb[2].toDouble(), bb[3].toDouble())

        val nodeCount = cursor.keyed("nodes").trim().toInt()
        val nodes = ArrayList<LatLon>(nodeCount)
        repeat(nodeCount) { nodes.add(latLon(cursor.next())) }

        val segCount = cursor.keyed("segments").trim().toInt()
        val segments = ArrayList<RawSegment>(segCount)
        repeat(segCount) {
            val t = cursor.next().split(' ')
            require(t.size >= 5) { "bad segment line" }
            val k = t[4].toInt()
            require(t.size == 5 + 2 * k) { "bad segment shape count" }
            val shape = ArrayList<LatLon>(k)
            for (i in 0 until k) {
                shape.add(LatLon(t[5 + 2 * i].toDouble(), t[6 + 2 * i].toDouble()))
            }
            segments.add(
                RawSegment(
                    a = t[0].toInt(),
                    b = t[1].toInt(),
                    classCode = t[2].toInt(),
                    oneway = t[3] == "1",
                    shape = shape,
                ),
            )
        }

        val banCount = cursor.keyed("bans").trim().toInt()
        val bans = ArrayList<RawBan>(banCount)
        repeat(banCount) {
            val t = cursor.next().split(' ')
            require(t.size == 4) { "bad ban line" }
            bans.add(RawBan(t[0].toInt(), t[1].toInt(), t[2].toInt(), t[3].toInt()))
        }

        require(cursor.next() == "end") { "missing end marker" }

        return RawNetwork(
            region = region,
            origin = origin,
            bboxMin = bboxMin,
            bboxMax = bboxMax,
            nodes = nodes,
            segments = segments,
            bans = bans,
        )
    }

    fun read(input: InputStream): RoadGraph = RoadGraph.build(readRaw(input))

    private fun coord(p: LatLon): String = "${fmt(p.lat)} ${fmt(p.lon)}"

    private fun latLon(line: String): LatLon {
        val t = line.trim().split(' ')
        require(t.size == 2) { "bad coordinate line: $line" }
        return LatLon(t[0].toDouble(), t[1].toDouble())
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

    private class Cursor(private val lines: List<String>) {
        private var pos = 0

        fun next(): String {
            if (pos >= lines.size) throw IllegalArgumentException("Unexpected end of graph file")
            return lines[pos++].trim()
        }

        /** Reads a line of the form "<key> <value>" and returns the value. */
        fun keyed(key: String): String {
            val line = next()
            val prefix = "$key "
            if (!line.startsWith(prefix)) throw IllegalArgumentException("Expected '$key' line, got: $line")
            return line.substring(prefix.length)
        }
    }
}
