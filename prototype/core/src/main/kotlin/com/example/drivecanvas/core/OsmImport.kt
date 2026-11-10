package com.example.drivecanvas.core

import java.io.File

/** One member of an OSM relation. */
data class OsmMember(val type: String, val ref: Long, val role: String)

/** OSM way kept for routing (highway-tagged). */
data class OsmWay(val id: Long, val tags: Map<String, String>, val refs: List<Long>)

/** OSM relation kept for routing (type=restriction). */
data class OsmRelation(val id: Long, val tags: Map<String, String>, val members: List<OsmMember>)

/** Parsed OSM content merged across tiles; first occurrence of an id wins. Maps keep file order. */
class OsmData(
    val nodes: Map<Long, LatLon>,
    val highways: Map<Long, OsmWay>,
    val restrictions: Map<Long, OsmRelation>,
)

/**
 * Dependency-free OSM XML import. OSM XML is one element per line, so a line parser is enough.
 * [parseTiles] and [parseText] feed the same parser; [buildNetwork] applies the filter,
 * simplification, SCC pruning and restriction handling of SPEC 3.1.
 */
object OsmImport {
    private val ATTR = Regex("""([A-Za-z_:]+)="([^"]*)"""")

    private val CAR_ACCESS_KEYS = listOf("motorcar", "motor_vehicle", "vehicle", "access")
    private val BAD_ACCESS = setOf(
        "no", "private", "permit", "delivery", "destination", "agricultural", "forestry",
        "customers", "emergency", "military", "bus", "psv", "discouraged",
    )

    fun parseTiles(files: List<File>): OsmData {
        val acc = Accumulator()
        for (f in files) {
            f.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { acc.feed(it) } }
        }
        return acc.build()
    }

    fun parseText(texts: List<String>): OsmData {
        val acc = Accumulator()
        for (t in texts) {
            for (line in t.lineSequence()) acc.feed(line)
        }
        return acc.build()
    }

    /**
     * Builds the raw routing network for [region]. The projection origin is the bbox centre.
     * Output node and segment indices are renumbered after SCC pruning (increasing old order).
     */
    fun buildNetwork(data: OsmData, region: String, bboxMin: LatLon, bboxMax: LatLon): RawNetwork {
        val origin = LatLon((bboxMin.lat + bboxMax.lat) / 2, (bboxMin.lon + bboxMax.lon) / 2)
        val proj = LocalProjection(origin.lat, origin.lon)

        // 1. Way filter and direction.
        class KeptWay(val id: Long, val cls: RoadClass, val refs: List<Long>, val oneway: Boolean)

        val kept = ArrayList<KeptWay>()
        for (w in data.highways.values) {
            val cls = RoadClass.fromOsm(w.tags["highway"] ?: continue) ?: continue
            val access = CAR_ACCESS_KEYS.firstNotNullOfOrNull { k -> w.tags[k]?.takeIf { it.isNotEmpty() } } ?: "yes"
            if (access in BAD_ACCESS) continue
            if (w.tags["area"] == "yes") continue
            val ow = w.tags["oneway"]
            if (ow == "reversible" || ow == "alternating") continue
            val dir = when {
                ow == "yes" || ow == "true" || ow == "1" -> 1
                ow == "-1" || ow == "reverse" -> -1
                ow == "no" || ow == "false" || ow == "0" -> 0
                w.tags["junction"] == "roundabout" || w.tags["junction"] == "circular" -> 1
                else -> 0
            }
            var refs = w.refs.filter { data.nodes.containsKey(it) }
            if (refs.size < 2) continue
            if (dir == -1) refs = refs.asReversed()
            kept.add(KeptWay(w.id, cls, refs, dir != 0))
        }

        // 2. Graph nodes: used at least twice (way endpoints count twice).
        val use = HashMap<Long, Int>()
        for (k in kept) {
            for (n in k.refs) use[n] = (use[n] ?: 0) + 1
            val first = k.refs.first()
            val last = k.refs.last()
            use[first] = (use[first] ?: 0) + 1
            use[last] = (use[last] ?: 0) + 1
        }
        val graphNodeIds: List<Long> = use.filter { it.value >= 2 }.keys.sorted()
        val nodeIdx = HashMap<Long, Int>(graphNodeIds.size * 2)
        graphNodeIds.forEachIndexed { i, id -> nodeIdx[id] = i }

        // 3. Split ways at graph nodes into segments.
        class PreSeg(val wayId: Long, val a: Int, val b: Int, val cls: RoadClass, val oneway: Boolean, val shape: List<LatLon>)

        val segs = ArrayList<PreSeg>()
        for (k in kept) {
            var cur = ArrayList<Long>()
            cur.add(k.refs[0])
            for (idx in 1 until k.refs.size) {
                val n = k.refs[idx]
                cur.add(n)
                val b = nodeIdx[n] ?: continue
                val a = nodeIdx.getValue(cur[0])
                val xy = cur.map { proj.toXY(data.nodes.getValue(it)) }
                if (a != b && polylineLength(xy) > 0.5) {
                    val shape = cur.subList(1, cur.size - 1).map { data.nodes.getValue(it) }
                    segs.add(PreSeg(k.id, a, b, k.cls, k.oneway, shape))
                }
                cur = arrayListOf(n)
            }
        }

        // 4. Directed edges before pruning: (seg, dir) -> tail, head.
        val deSeg = ArrayList<Int>()
        val deDir = ArrayList<Int>()
        val deTail = ArrayList<Int>()
        val deHead = ArrayList<Int>()
        for ((s, seg) in segs.withIndex()) {
            deSeg.add(s); deDir.add(0); deTail.add(seg.a); deHead.add(seg.b)
            if (!seg.oneway) {
                deSeg.add(s); deDir.add(1); deTail.add(seg.b); deHead.add(seg.a)
            }
        }

        // 5. SCC: keep the largest component; keep segments with both endpoints inside it.
        val n = graphNodeIds.size
        val big = Scc.largestComponent(n, deTail.toIntArray(), deHead.toIntArray())
        val newNode = IntArray(n) { -1 }
        val keptNodeOld = ArrayList<Int>()
        for (v in 0 until n) if (big[v]) {
            newNode[v] = keptNodeOld.size
            keptNodeOld.add(v)
        }
        val newSeg = IntArray(segs.size) { -1 }
        val keptSegOld = ArrayList<Int>()
        for ((s, seg) in segs.withIndex()) {
            if (big[seg.a] && big[seg.b]) {
                newSeg[s] = keptSegOld.size
                keptSegOld.add(s)
            }
        }

        // Kept directed edges (post-prune), indexed by position in this list.
        val kdSeg = ArrayList<Int>()
        val kdDir = ArrayList<Int>()
        val kdTail = ArrayList<Int>()
        val kdHead = ArrayList<Int>()
        val kdWay = ArrayList<Long>()
        for (i in deSeg.indices) {
            if (newSeg[deSeg[i]] < 0) continue
            kdSeg.add(deSeg[i]); kdDir.add(deDir[i]); kdTail.add(deTail[i]); kdHead.add(deHead[i])
            kdWay.add(segs[deSeg[i]].wayId)
        }
        val wIn = HashMap<Pair<Long, Int>, MutableList<Int>>()
        val wOut = HashMap<Pair<Long, Int>, MutableList<Int>>()
        val outsAt = HashMap<Int, MutableList<Int>>()
        for (i in kdSeg.indices) {
            wIn.getOrPut(kdWay[i] to kdHead[i]) { ArrayList() }.add(i)
            wOut.getOrPut(kdWay[i] to kdTail[i]) { ArrayList() }.add(i)
            outsAt.getOrPut(kdTail[i]) { ArrayList() }.add(i)
        }

        // 6. Restrictions -> bans (pairs of kept directed-edge indices).
        val banPairs = LinkedHashSet<Pair<Int, Int>>()
        for (rel in data.restrictions.values) {
            val tags = rel.tags
            val except = tags["except"]
            if (except != null && except.split(';').any { it.trim() == "motorcar" }) continue
            val r = tags["restriction:motorcar"] ?: tags["restriction"] ?: continue
            val fromWays = rel.members.filter { it.type == "way" && it.role == "from" }
            val toWays = rel.members.filter { it.type == "way" && it.role == "to" }
            val viaNodes = rel.members.filter { it.type == "node" && it.role == "via" }
            val viaWays = rel.members.filter { it.type == "way" && it.role == "via" }
            if (fromWays.size != 1 || toWays.size != 1 || viaNodes.size != 1 || viaWays.isNotEmpty()) continue
            val fromWay = fromWays[0].ref
            val toWay = toWays[0].ref
            if (fromWay == toWay) continue
            val v = nodeIdx[viaNodes[0].ref] ?: continue
            val ins = wIn[fromWay to v] ?: emptyList()
            val outs = wOut[toWay to v] ?: emptyList()
            if (r.startsWith("no_")) {
                for (a in ins) for (b in outs) banPairs.add(a to b)
            } else if (r.startsWith("only_") && outs.isNotEmpty()) {
                val outSet = outs.toHashSet()
                for (a in ins) for (b in outsAt[v] ?: emptyList()) if (b !in outSet) banPairs.add(a to b)
            }
        }

        // 7. Renumbered output.
        val nodes = keptNodeOld.map { data.nodes.getValue(graphNodeIds[it]) }
        val segments = keptSegOld.map { s ->
            val seg = segs[s]
            RawSegment(newNode[seg.a], newNode[seg.b], seg.cls.code, seg.oneway, seg.shape)
        }
        val bans = banPairs.map { (a, b) ->
            RawBan(
                fromSeg = newSeg[kdSeg[a]],
                fromDir = kdDir[a],
                toSeg = newSeg[kdSeg[b]],
                toDir = kdDir[b],
            )
        }
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

    private fun unescape(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun attrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (match in ATTR.findAll(line)) m[match.groupValues[1]] = unescape(match.groupValues[2])
        return m
    }

    private class Accumulator {
        private val nodes = LinkedHashMap<Long, LatLon>()
        private val highways = LinkedHashMap<Long, OsmWay>()
        private val restrictions = LinkedHashMap<Long, OsmRelation>()

        private enum class State { NONE, WAY, RELATION }

        private var state = State.NONE
        private var curId = 0L
        private var curTags = LinkedHashMap<String, String>()
        private var curRefs = ArrayList<Long>()
        private var curMembers = ArrayList<OsmMember>()

        fun feed(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            when (state) {
                State.NONE -> when {
                    line.startsWith("<node ") -> onNode(line)
                    line.startsWith("<way ") -> {
                        val a = attrs(line)
                        curId = a["id"]?.toLong() ?: return
                        curTags = LinkedHashMap()
                        curRefs = ArrayList()
                        if (line.endsWith("/>")) finishWay() else state = State.WAY
                    }
                    line.startsWith("<relation ") -> {
                        val a = attrs(line)
                        curId = a["id"]?.toLong() ?: return
                        curTags = LinkedHashMap()
                        curMembers = ArrayList()
                        if (line.endsWith("/>")) finishRelation() else state = State.RELATION
                    }
                    else -> Unit
                }
                State.WAY -> when {
                    line.startsWith("<nd ") -> attrs(line)["ref"]?.toLong()?.let { curRefs.add(it) }
                    line.startsWith("<tag ") -> putTag(line)
                    line.startsWith("</way>") -> finishWay()
                    else -> Unit
                }
                State.RELATION -> when {
                    line.startsWith("<member ") -> {
                        val a = attrs(line)
                        val ref = a["ref"]?.toLong()
                        if (ref != null) curMembers.add(OsmMember(a["type"] ?: "", ref, a["role"] ?: ""))
                    }
                    line.startsWith("<tag ") -> putTag(line)
                    line.startsWith("</relation>") -> finishRelation()
                    else -> Unit
                }
            }
        }

        private fun onNode(line: String) {
            val a = attrs(line)
            val id = a["id"]?.toLong() ?: return
            val lat = a["lat"]?.toDouble() ?: return
            val lon = a["lon"]?.toDouble() ?: return
            if (!nodes.containsKey(id)) nodes[id] = LatLon(lat, lon)
        }

        private fun putTag(line: String) {
            val a = attrs(line)
            val k = a["k"] ?: return
            curTags[k] = a["v"] ?: ""
        }

        private fun finishWay() {
            state = State.NONE
            if (curTags.containsKey("highway") && !highways.containsKey(curId)) {
                highways[curId] = OsmWay(curId, curTags, curRefs)
            }
        }

        private fun finishRelation() {
            state = State.NONE
            if (curTags["type"] == "restriction" && !restrictions.containsKey(curId)) {
                restrictions[curId] = OsmRelation(curId, curTags, curMembers)
            }
        }

        fun build(): OsmData = OsmData(nodes, highways, restrictions)
    }
}
