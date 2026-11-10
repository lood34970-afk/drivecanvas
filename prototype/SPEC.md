# Drive Canvas core — implementation spec

Source: deep-reasoner design, 2026-11-10. Prototyped in Python on real OSM tiles
(`reference/python/*.py` — dcgraph, dcroute, dcana, dcsearch, dcfull, dclegs, dcgrid). When this
spec is ambiguous, the Python reference is the tiebreaker.

**Decision.** Phase-expanded, edge-based Dijkstra ("ShapeRouter") over a road-friendly heart
(two unit circles + tangent lines: 90° tip, 128° cusp), driven clockwise from the bottom tip. The
template is placed in local meters, cut into 24 ordered phases, and one Dijkstra over (directed edge,
phase) finds the cheapest legal loop touching all 24 anchor disks in order and returning to the start
node. Cost = time·classFactor·(1+α·d²/σ²) + turn cost; U-turns and OSM-banned turns forbidden.
Placement search runs OFFLINE with the same Kotlin code and bundles per region a text graph and the
top placements; on device: re-route ≤8 bundled placements, gate, offer "모양 우선"/"시간 우선",
split into NaviHelper legs (≤2 waypoints + 1 destination; NaviHelper `WaypointIndex` has only
FIRST/SECOND), guide via a pure-Kotlin state machine fed by NaviHelper or a deterministic simulator.

Prototype results: **Ilsan** — 29/38 routed placements pass all gates; SHAPE 16.9 km ≈39 min S=77
(14 waypoints = 5 legs), TIME 12.9 km ≈27 min S=76 (4 legs). Sejong only up to W≈2.75 km. Bundang no
passing heart. Max ≈40 min inside these bboxes.

Data facts: after filter+SCC, nodes/segments/directed edges/bans = Sejong 1007/1446/2197/17,
Ilsan 1399/2034/3476/13, Bundang 1113/1586/2794/36. Sejong has 101 roundabout ways
(`junction=roundabout` ⇒ oneway). Bundang has 150 `no_u_turn` relations with from==to — mapping them
naively bans straight-through moves; they MUST be skipped. Use class speeds (maxspeed sparse). OSM
XML is strictly one element per line → dependency-free line parser (javax.xml.stream is not on
Android).

## 3.0 Ground rules
- Main: `prototype/core/src/main/kotlin/com/example/drivecanvas/core/`; tests:
  `prototype/core/src/test/kotlin/com/example/drivecanvas/core/`; package `com.example.drivecanvas.core`,
  tools in `com.example.drivecanvas.core.tools`.
- Gradle test worker cannot start on this machine (non-ASCII user home). Tests are compiled by Gradle
  and run via JUnitCore on every `*Test.class` (`scripts/test-core.ps1`). Test helper files must NOT
  end in "Test".
- Local meters `XY` (x east, y north) projected around the region bbox centre. Math angles
  atan2(dy,dx), CCW positive ⇒ positive turn = left.
- Determinism: PQ ordered by (cost, stateId); adjacency in edge-id order; `Locale.ROOT` formatting;
  `java.util.Random(seed)`.
- Only stdlib + JDK `java.io`/`java.util`.
- OSM tiles: `prototype/data/osm/<region>/tile_*.osm` (sejong, ilsan, bundang).

## 3.1 Graph preprocessing (offline)
Line parser: trim; prefix `<node ` → id, lat, lon; `<way ` → open, collect `<nd ref>` and
`<tag k v>` until `</way>`; `<relation ` → open, collect `<member type ref role>` + tags until
`</relation>`; line ending `/>` has no children. Attr regex `([A-Za-z_:]+)="([^"]*)"`; unescape
`&quot; &apos; &lt; &gt;` then `&amp;` last.

Merge: union by OSM id, first occurrence wins (nodes, highway ways, `type=restriction` relations).

Way filter — keep `highway` → RoadClass(code, speed km/h, planFactor):
trunk 0/60/1.2, trunk_link 1/40/1.0, primary 2/50/1.0, primary_link 3/35/1.0, secondary 4/45/1.0,
secondary_link 5/30/1.0, tertiary 6/40/1.0, tertiary_link 7/30/1.0, unclassified 8/30/1.2,
residential 9/25/1.3. Drop everything else (motorway, motorway_link, service, living_street, track,
path, footway, cycleway, steps, pedestrian, busway, construction, road, …).
carAccess = first present of motorcar, motor_vehicle, vehicle, access (default yes); drop if in
{no, private, permit, delivery, destination, agricultural, forestry, customers, emergency, military,
bus, psv, discouraged}. Drop area=yes and oneway ∈ {reversible, alternating}.
Direction: oneway ∈ {yes,true,1} → forward; {-1,reverse} → reverse refs then forward; {no,false,0}
→ both; else junction ∈ {roundabout, circular} → forward; else both. Drop refs with missing node;
drop ways left with <2 refs.

Simplification: use[n] = +1 per occurrence in a kept way, +1 more if way endpoint; graph node = use≥2.
Split ways at graph nodes into segments (a, b, intermediate shape points; keep all shape points).
Drop a==b or length <0.5 m. Record segWay[s].

Restrictions → bans: read `restriction` or `restriction:motorcar`; skip if `except` contains
motorcar. Require exactly one from-way, one via-node, one to-way (skip via-way). Skip from==to. Skip
if via is not a graph node. ins = directed edges of from-way with head=via; outs = directed edges of
to-way with tail=via. `no_*` → ban every (in,out). `only_*` → for each in, ban every out-edge of via
not in outs (only if outs non-empty). Store ban as (fromSeg, fromDir, toSeg, toDir), dir 0=a→b,
1=b→a.

SCC: iterative Kosaraju over directed edges (pass 1 DFS with explicit stack of (node,nextEdgeIdx),
append on finish; pass 2 reverse order flood-fill over in-edges). Keep largest component; keep a
segment only if both endpoints in it. Renumber nodes & segments in increasing old order; drop bans
referencing removed segments.

Asset format "DCG 1" (UTF-8, `\n`, single spaces, coords `%.7f` Locale.ROOT):
```
DCG 1
region <name>
origin <lat> <lon>                 (bbox centre; projection origin)
bbox <minLat> <minLon> <maxLat> <maxLon>
nodes <N>
<lat> <lon>                        × N
segments <S>
<a> <b> <classCode> <oneway 0|1> <k> [<lat> <lon>]×k     × S   (k = intermediate shape points)
bans <B>
<fromSeg> <fromDir> <toSeg> <toDir>                    × B
end
```
First line ≠ `DCG 1` → `IllegalArgumentException("Unsupported graph format")`. Expect 120–180 KB.

Loader `RoadGraph.build`: for each segment in file order: forward edge id = edges.size; if two-way
also backward edge with reversed pts; set `twin` on both. Keep `segDirToEdge[2*seg+dir]` (−1 absent).
Per edge: lengthM; timeS = lengthM/(speedKmh/3.6); headingIn = heading pts[0] → first point ≥10 m
away (else last point); headingOut = heading from last point ≥10 m before end → end (else first
point); samples = resample(pts, 40 m). outEdges/inEdges per node in ascending id. banned:
Set<Long> key `(in.toLong() shl 32) or out`.

## 3.2 Heart template (`HeartTemplate.kt`)
R=1, C=0.9, H=2.3. yc=√(R²−C²)=0.435890 (cusp (0,yc)). Tip T=(0,−H).
Right tangent point: dx=C, dy=H, d=hypot, L=√(d²−R²), a=atan2(dy,dx)−asin(R/d),
Qr=(L·cos a, −H+L·sin a) ≈ (1.6040,−0.7102); Ql=(−Qr.x, Qr.y).
Fine path clockwise from tip: (1) line T→Ql 400 pts; (2) left arc centre (−C,0) 800 pts, angle from
aL0=atan2(Ql.y, Ql.x+C) (add 2π if ≤ aL1) down to aL1=atan2(yc, C); (3) right arc centre (C,0) 800 pts
from aR0=atan2(yc,−C) down to aR1=atan2(Qr.y, Qr.x−C) (subtract 2π if > aR0); (4) line Qr→T.
Resample uniformly by arc length to N=480: index 0 tip, 240 cusp, anchors at 20k, k=0..23.
Normalize n=(x/3.8, (y+0.65)/3.8): width 1, height 0.8684, centred on bbox. Perimeter 3.0204×width.
`Placement(center: XY, widthM: Double, rotationDeg: Double)`; p = center + W·Rot(ρ)·n (ρ CCW +).

## 3.3 Placement search — hybrid
Offline (`tools/Preprocess.kt`): W ∈ {2500,2750,…,4000}; ρ ∈ {−30,−20,…,30}; centres on 200 m grid
over local bbox. In-bounds: every 5th template point inside bbox shrunk by 150 m, else prescore −1.
Prescore = coverage + 0.1·features; coverage = fraction of every-4th template points with an edge
sample within τ=0.04W (PointGrid of all edge samples); features = count of {tip, cusp} with edge
sample within τ. Per W sort by (−prescore, rot, cx, cy), top 10 → routeAndRepair; keep all evaluated.

Candidate selection (device too), target T=35 min:
SHAPE = argmax over passing of S − 0.5·max(0, eta−(T+10)).
TIME = argmax S over passing with eta ≤ T and distinct from SHAPE; if none, smallest eta among
distinct passing. Distinct = centre dist >300 m or |ΔW|≥250 or |Δρ|≥10.
ALT = up to 6 more distinct passing by S desc.

Placements asset "DCP 1":
```
DCP 1
region <name>
candidates <K>
<SHAPE|TIME|ALT> <centerLat> <centerLon> <widthM> <rotDeg> <expScore> <expEtaMin> <expLenKm>    × K
end
```
RegionScore = (SHAPE.S+TIME.S)/2 − 0.5·|SHAPE.eta−37.5| + 0.2·min(passing,25); both must exist.
Prototype: Ilsan ≈80.5, Sejong ≈78.5, Bundang none.
Expected Ilsan (±10%): SHAPE centre (37.65979,126.77227) W 4000 ρ −20; TIME centre
(37.66878,126.77454) W 3750 ρ 0.
On device: load DCG+DCP assets, re-route each record, gate, re-pick, leg planner; run off main thread.

## 3.4 Routing (`ShapeRouter.kt`)
`RouterConfig` defaults: anchors M=24, alpha 4.0, sigmaFrac 0.05, bandFrac 0.20, diskFrac 0.06,
diskMaxFrac 0.10, startMaxFrac 0.09, turnRightS 5, turnLeftS 12, turnSharpS 40, straightMaxDeg 30,
sharpMinDeg 150, uTurnMinDeg 165, twoStepShortEdgeM 40, twoStepUTurnDeg 160, repairIterations 2,
repairFactor 3.0, maxPops 2_000_000.
```
anchor[k]=T[20k]; piece(k)=T[20k..20k+20] (index 480 -> T[0]); σ=0.05W; band²=(0.2W)²
R[k]=0.06W; for k=1..23: dn=nearestEdgeDist(anchor[k]); if dn>R[k] {R[k]=dn+0.01W; if R[k]>0.10W -> NoRoute(NO_ROAD_NEAR_ANCHOR,k)}
v0 = node with in>=1 && out>=1 nearest to anchor[0]; if dist>0.09W -> NoRoute(START_TOO_FAR)
d2(e,k)   = mean over e.samples of distSq(sample, piece(k))            (cache DoubleArray(E*M), NaN=unset)
touch(e,j)= min over e.pts segments distSq(anchor[j],seg) <= R[j]²     (cache ByteArray(E*M))
adv(e,k)  = while k+1<M && touch(e,k+1): k++
cost(e,k,k2)= dd=min(d2(e,k),d2(e,k2)); if dd>band² null else e.timeS*e.cls.planFactor*(1+α*dd/σ²)*(pen[e]?:1)
state=e*M+k; dist=DoubleArray(E*M){∞}; pred=IntArray(E*M){-1}
init: for e in out(v0): k2=adv(e,0); c=cost(e,0,k2)?:skip; relax(state(e,k2),c,-1)
pop (cost,state) min (tie: state id); skip stale; pops>maxPops -> NoRoute(SEARCH_LIMIT)
  e=state/M, k=state%M, v=head(e); if k==M-1 && v==v0 -> reconstruct via pred -> Found(edges,v0,pops)
  pe = pred[state]>=0 ? pred[state]/M : -1
  for e2 in out(v): skip if e2==twin(e) || banned(e,e2)
     θ=turnDeg(e.headingOut,e2.headingIn); skip if |θ|>165
     skip if pe>=0 && e.lengthM<40 && |turnDeg(edge(pe).headingOut,e2.headingIn)|>160   (2-step U-turn)
     tc = |θ|<=30?0 : |θ|>150?40 : θ>0?12 : 5
     k2=adv(e2,k); c2=cost(e2,k,k2)?:skip; relax(state(e2,k2), cost+c2+tc, state)
heap empty -> NoRoute(NO_PATH)
```
`routeAndRepair(placement)`: route without penalties, analyse (3.5); up to 2× while overlap>0.01 or
reuse>0.01: penalty×3 for every edge in `Metrics.overlapEdges` and its twin, re-route, analyse; keep
better by (gate-failure count, then −S). Returns `EvaluatedRoute` or `NoRoute` (START_TOO_FAR,
NO_ROAD_NEAR_ANCHOR, NO_PATH, SEARCH_LIMIT, QUALITY).

## 3.5 Metrics & gates (`Metrics.kt`)
Route stats (polyline = concatenated edge pts without duplicate joints): lengthM; freeFlowS=ΣtimeS;
turns: per consecutive edge pair θ, |θ|≥45 is a turn (left θ>0 else right).
ETA(min) = (1.25·freeFlowS + 20·lefts + 8·rights)/60. turnsPerKm. reuseRatio = length of edges whose
segment appears ≥2× ÷ length. overlapRatio = share of 10 m-resampled points with another point within
30 m whose cyclic along-route separation >400 m. overlapEdges = reused edges + edges containing a
flagged point. selfIntersections = proper crossings of non-adjacent segments (100 m grid buckets).

Shape metrics (Q vs template T, width W): resample Q (open) and T (closed) at 10 m; every 2nd point
for averages. PointGrid (50 m cells) ring search r=0,1,… stop when best ≤ r·cell or r reaches
maxR=0.3W. dRT = mean dist Q→T; dTR = mean T→Q; nmsd=(dRT+dTR)/2/W; p90 = 90th pct of all those
distances /W; tipD = dist(T[0],Q)/W; cuspD = dist(T[240],Q)/W.
**S = 100·(1 − min(1, 0.5·min(1,nmsd/0.10) + 0.3·min(1,p90/0.20) + 0.2·min(1,max(tipD,cuspD)/0.12)))**
Calibration (W=3500): identical 100; 10 m noise ≈98; shift 0.05W ≈69; road hearts 72–78; first 50%
≈39; circle ≈20; ellipse ≈18; rectangle ≈4.

`QualityThresholds` (data class): nmsd ≤0.035, p90 ≤0.08, feature (tip & cusp) ≤0.065, overlap ≤0.05,
reuse ≤0.05, selfIntersections =0, turnsPerKm ≤3.0, 25 ≤ etaMin ≤ 50. `gateFailures` returns failed
gate names (use names: "nmsd","p90","feature","overlap","reuse","selfIntersections","turns","eta").

Completion: plan resampled 10 m, track 5 m; completion = share of plan samples within 40 m of track.
Track cleaning: drop fixes implying >55 m/s from last accepted fix.
UI labels: S≥65 "하트로 잘 보여요"; 50–65 "하트 모양이 조금 흐려요"; <50 "그림이 많이 달라요".

## 3.6 Leg splitting (`NaviEmulator.kt`, `LegPlanner.kt`)
Navi cost models (edge weight / left / right / sharp): TIME timeS/10/3/30; DIST lengthM/10 / 0/0/0;
TIME_LEFT_AVERSE timeS/30/5/60. Same legality as router (twin, banned, |θ|>165, 2-step U-turn).
`tree(startEdge, model, bound=1500)` → label[e] = cost to END of e (label[startEdge]=0), pred[e].
`routeThrough(startEdge, targetEdges, model)`, `routeToNode(startEdge, node, model)` built on tree.
```
vias=[]; cur=0
while cur<n-1: r=min_m reach(cur,m); if r<=cur {r=cur+1; forced=true}; vias+=r; cur=r
reach(cur,m): t=tree(seq[cur],m); best=cur
  for j in cur+1..n-1: if t.pred[seq[j]]!=seq[j-1] break; if j==n-1 || valid(j,t,m) best=j
valid: lengthM>=60 && (twin<0 || label[twin]=∞ || label[e]-w(e)/2 < label[twin]-w(twin)/2)
       && no edge other than e/twin within 12 m of e's midpoint (EdgeSpatialIndex, 100 m cells)
```
Legs: chunk vias by 3; waypoints = all but last via of chunk as edge-midpoint LatLon; destination =
last via's midpoint, or start node LatLon for the final via (index n−1). Verify: per leg
`routeThrough(seq[legStart], targets, TIME)` equals planned sub-sequence; `legsVerified = !forced &&
all equal`. Expected: real routes 10–14 vias (4–5 legs); oneway grid fixture 12 vias (4 legs).

## 3.7 Guidance state machine (`Guidance.kt`, pure; `handle(event): List<Command>`)
States IDLE, LEG_REQUESTED, GUIDING, OFF_ROUTE, ENDING_EARLY, FINISHED.
Events (each has timeMs): Start, RouteStarted, WaypointArrived(index), DestinationArrived,
ReRouteEnded, RouteCancelled, NaviError(msg), Location(LatLon, headingDeg), UserEndEarly, Tick.
Commands: RequestRoute(legIndex, waypoints, destination, leg?) (legIndex −1 = home), Announce(textKo),
Finish(DriveSummary).
`GuidanceConfig` (default [demo()]): offRouteDistM 50, offRouteHoldMs 8000, rejoinDistM 30,
rejoinHoldMs 5000, giveUpOffRouteMs 180000 [20000], giveUpDistM 1000 [400],
minProjectedCompletion 0.6, legRequestTimeoutMs 20000, maxLegRetries 1, arrivalFallbackDistM 30,
arrivalFallbackHoldMs 8000; progress window −50 m/+600 m; rejoin window +3000 m; coverage tol 40 m.
Progress s = arc length of nearest 10 m plan sample inside window, never decreases; off-route distance
measured against same window; covered[] marks plan samples within 40 m of any drawing fix.
Transitions:
- IDLE+Start → LEG_REQUESTED, request leg 0.
- LEG_REQUESTED: RouteStarted → GUIDING; Tick after timeout → re-request once; second timeout →
  FINISHED(ERROR), Announce "내비게이션 응답이 없어 안내를 종료합니다".
- GUIDING: off-route >50 m held ≥8 s → OFF_ROUTE, Announce "경로를 벗어났어요. 내비게이션이 안전한
  길로 다시 안내합니다."; DestinationArrived non-final → LEG_REQUESTED next leg, Announce "다음 구간
  안내를 시작합니다 (n/N)"; final → FINISHED(COMPLETED), Announce "하트 완성! 안전한 곳에 정차한 뒤
  결과를 확인하세요."; fallback: within 30 m of leg destination with s ≥ destS−30 and no event for 8 s
  → treat as DestinationArrived.
- OFF_ROUTE: within 30 m (rejoin window) held 5 s → GUIDING (s jumps to rejoin point);
  DestinationArrived as GUIDING; give up (off-route time ≥ giveUp OR distance ≥ giveUpDistM OR
  projected < 0.6, projected = (covered + samples beyond current leg's next target)/total) →
  ENDING_EARLY + RequestRoute(−1, [], start), Announce "그림 완성도가 낮아져요. 무리하게 돌아가지
  않고 출발지로 안전하게 안내할게요."
- LEG_REQUESTED/GUIDING/OFF_ROUTE + UserEndEarly → ENDING_EARLY + home request, Announce "그리기를
  마치고 출발지로 안내할게요."
- ENDING_EARLY + DestinationArrived → FINISHED(ENDED_EARLY_OFF_ROUTE or ENDED_EARLY_USER).
- Any non-final: RouteCancelled → FINISHED(CANCELLED); NaviError → FINISHED(ERROR).
TrackPoint(timeMs, LatLon, drawing) — drawing=true only in LEG_REQUESTED/GUIDING/OFF_ROUTE.
DriveSummary: endReason, completion and S on cleaned drawing points, duration, distance, full track,
drawing track. App precondition: Start enabled only within 300 m of start point.

## 3.8 Simulation (`Simulator.kt`)
`SimConfig(seed=42, noiseM=3.0, headingNoiseDeg=2.0, stepMs=1000, turnSlowKmh=20, turnSlowWindowM=25,
deviation: DeviationScenario?=null)`; `DeviationScenario(atRouteFraction=0.55, lengthM=700)`.
SimNavi: vehicle starts at tail of route[0], offset 0. `onCommand(RequestRoute)` builds path =
remainder of current edge + `routeThrough(current, leg.targetEdges, TIME)` or `routeToNode(current,
v0)` for home; returns [RouteStarted]. `tick(now)` advances 1 s: speed = class speed, capped 20 km/h
within 25 m of a junction with |θ|≥45; emits Location (XY + N(0,3 m) per axis from Random(seed) →
LatLon; heading = compass bearing (90−mathDeg) + N(0,2°)); WaypointArrived when passing a target
edge's midpoint; DestinationArrived at path end.
Deviation: arm when distance driven ≥ fraction×plan length; at next junction where plan turns
(|θ|≥45) take allowed out-edge with smallest |θ|, keep choosing straightest (U-turn only as last
resort) until lengthM driven; then emit ReRouteEnded and continue with routeThrough to remaining
targets. A home request replaces path immediately.
`SimulationRunner(graph, plan, simCfg, guidCfg)`: start(), step() (one sim second: sim events + Tick
→ engine → dispatch RequestRoute to sim and feed RouteStarted back), runToEnd(maxSimMs=4h). App calls
step() every 50 ms (20×).

## 3.9 Files, APIs, tests
- `Geo.kt` (extend, keep existing): `data class XY(x,y)`, `const val METERS_PER_DEG =
  111194.92664455873`, `class LocalProjection(originLat, originLon){toXY(LatLon); toLatLon(XY)}`,
  mPerDegLon = METERS_PER_DEG·cos(lat0).
- `Geometry.kt`: distSqPointSegment, distPointPolyline, polylineLength, cumulativeLengths,
  resample(pl, stepM, closed=false) (keep first point; split each segment into ceil(L/step) equal
  parts), headingRad, normDeg (→(−180,180]), turnDeg(hOut,hIn), properlyIntersect,
  countSelfIntersections, `class PointGrid(points, cellM=50){nearestDist(p, maxR)}`.
- `RoadClass.kt` (table 3.1, fromOsm, fromCode).
- `RoadGraph.kt`: RawSegment, RawBan, RawNetwork, `class Edge(id, seg, tail, head, cls, pts, lengthM,
  timeS, headingIn, headingOut, twin, samples)`, `class RoadGraph(region, projection, bboxMinXY,
  bboxMaxXY, nodes, edges, outEdges, inEdges, banned){isBanned; nodeLatLon; companion build(raw)}`,
  `class EdgeSpatialIndex(graph, 100.0){nearestEdge(p, maxR, exclude)}`,
  `object Scc{largestComponent(n, tails, heads): BooleanArray}`.
- `GraphIo.kt`: write(RawNetwork, Writer), readRaw(InputStream), read(InputStream): RoadGraph.
- `OsmImport.kt`: parseTiles(files), parseText(texts) → OsmData; buildNetwork(data, region, bboxMin,
  bboxMax): RawNetwork.
- `HeartTemplate.kt` (+ Placement, Placement.place()).
- `Metrics.kt`: RouteStats, ShapeMetrics, QualityThresholds, object Metrics.
- `ShapeRouter.kt`: RouterConfig, RouteOutcome.Found/NoRoute, NoRouteReason, EvaluatedRoute(placement,
  template, edges, polyline, stats, shape, gateFailures), routeAndRepair.
- `PlacementSearch.kt`: SearchConfig, PlacementSearch(graph).prescore/search(),
  CandidateKind{SHAPE,TIME,ALT}, CandidatePicker.pick(passing, targetMin=35), PlacementRecord,
  PlacementsIo.
- `NaviEmulator.kt`, `LegPlanner.kt`: Leg(index, waypoints, destination, targetRouteIdx, targetEdges,
  targetRouteS, isFinal), GuidancePlan(startNode, start, routeEdges, routeXY, routeLatLon, samples,
  sampleS, legs, templateXY, widthM, viaRouteIdx, legsVerified, projection).
- `Guidance.kt`, `Simulator.kt`.
- `CanvasPlanner.kt`: Candidate(kind, route, plan) label "모양 우선"/"시간 우선";
  PlanResult.Ok(shapeFirst, timeFirst?) / PlanResult.NoRoute(reasonsKo); reasons START_TOO_FAR
  "출발점 근처에 차량 도로가 없어요", NO_ROAD_NEAR_ANCHOR "그림 일부 구간 근처에 도로가 없어요",
  NO_PATH "규칙을 지키는 연결 경로가 없어요", QUALITY "안전·모양 기준을 통과한 경로가 없어요";
  planFromPlacements(records, targetMin).
- `tools/Preprocess.kt`: main(--osm, --out, --regions); region bboxes constant: sejong
  (36.478,127.235)-(36.523,127.290), ilsan (37.640,126.745)-(37.685,126.800), bundang
  (37.345,127.095)-(37.390,127.150). Writes `<region>.dcg`, `<region>.placements`,
  `<region>_<kind>.svg` (dashed template + solid route); prints summary table + recommended region.
- `tools/SvgExport.kt`.
- Script `scripts/build-canvas-assets.ps1` (clone of test-core.ps1): `param([string]$OsmDir)`;
  `gradlew.bat -p <prototype> :core:classes`; classpath core main classes + kotlin-stdlib-2.0.0.jar;
  `java -Dfile.encoding=UTF-8 -cp … com.example.drivecanvas.core.tools.PreprocessKt --osm $OsmDir
  --out <prototype>\app\src\main\assets\canvas`.

Tests:
- `GridFixtures.kt`: gridGraph(n=21, spacingM=200, onewayColumns=true), origin LatLon(37.5,127.0);
  node (i,j) at (i·s−2000, j·s−2000); horizontal streets two-way TERTIARY; vertical: even column i
  northbound only, odd southbound only (all two-way if onewayColumns=false); built through a GraphIo
  round trip.
- GeometryTest: resample counts/endpoints; normDeg(190)==−170; PointGrid nearest == brute force.
- OsmImportTest (inline XML, 2 "tiles" with the same way twice): residential A-B-C → one segment
  with shape point B; service & access=private dropped; roundabout & oneway=yes oneway; oneway=−1
  reversed; duplicates merged; no_left_turn → exactly 1 ban; from==to no_u_turn → 0; dangling oneway
  spur removed by SCC.
- GraphIoTest: round trip counts; oneway → 1 directed edge, two-way → 2 twinned; bans mapped;
  `DCG 2` throws.
- HeartTemplateTest: size 480; p[0] ≈ (0,−0.434211), p[240] ≈ (0,0.285760) ±1e-3; x ∈ [−0.5,0.5]
  ±2e-3; perimeter 3.0204±2e-3; shoelace area <0 (clockwise); p[i].x ≈ −p[480−i].x.
- MetricsTest (W=3500): identical S=100±0.01; shift +0.05W x → S∈[60,78]; bbox ellipse ≤35; circle
  r=0.45W ≤35; first half (0..239) S≤55 and completion vs full =0.5±0.05; σ=10 m noise Random(1) S≥95.
- ShapeRouterTest: oneway grid W=3000 centre (0,0) → Found; S≥65 (proto 72.2); nmsd≤0.035;
  tip/cusp≤0.065; overlap/reuse≤0.05; 0 self-intersections; turnsPerKm≤3.0; first tail==last
  head==start node; edges contiguous; no twin follows own edge, all |θ|≤165; vertical edges obey
  column direction. 3×3 grid → NoRoute(START_TOO_FAR). QualityThresholds(turnsPerKmMax=1.0) → "turns"
  gate failure.
- NaviEmulatorTest: 5×5 grid 100 m, one banned left turn → path exists, never contains banned pair.
- LegPlannerTest: oneway grid route: each leg ≤2 waypoints; legs = ceil(vias/3); final destination =
  start node within 1 m; legsVerified; every via edge except last ≥60 m. Straight eastbound route → 1
  leg, 0 waypoints.
- GuidanceTest: happy path COMPLETED completion ≥0.98; 10 fixes 150 m off → OFF_ROUTE then 6 s back →
  GUIDING; give-up → home request (empty waypoints) then FINISHED(ENDED_EARLY_OFF_ROUTE); timeout →
  retry then ERROR; UserEndEarly; RouteCancelled; progress never decreases.
- SimulatorTest: same seed → identical track; no deviation → COMPLETED completion ≥0.95, S≥60;
  deviation with demo() → passes OFF_ROUTE and ENDING_EARLY, ends ENDED_EARLY_OFF_ROUTE, completion
  ∈[0.3,0.9].
- PlacementSearchTest: grid widths [2600,3000], rotation [0], step 400, top-3, ETA gate [0,1000] → ≥1
  passing and SHAPE non-null; out-of-bbox placement prescore −1.
- CanvasPlannerTest: end-to-end Ok with legs.

## 3.10 Work packages
A Geo/Geometry, RoadClass, RoadGraph, GraphIo, OsmImport, GridFixtures + tests · B HeartTemplate,
Metrics + tests · C ShapeRouter, PlacementSearch, Preprocess, SvgExport, build script · D NaviEmulator,
LegPlanner · E Guidance, Simulator, CanvasPlanner.
Acceptance: `scripts/test-core.ps1` passes; `build-canvas-assets.ps1` prints Ilsan with ≥15 passing
placements, SHAPE S≥70 & ETA 30–45, TIME present; assets <300 KB each; SVGs read as hearts.

## 4. Risks
OSM legality gaps (check vias on road view); real navi routing may diverge (compare per leg on real
device); waypoints may snap to wrong carriageway (≥60 m mid-edge + 12 m ambiguity rules mitigate);
ETA model ±30%; 45 min unreachable in these bboxes; Kotlin speed/determinism estimated (ALT
candidates absorb JVM-vs-ART differences); intermediate "destination arrived" announcements 3–4× per
drive; readability is a human test; show "© OpenStreetMap contributors". Fallback if nothing passes:
curated course ("DCC 1" via list) snapped/chained/gated by the same code; else "경로 없음" with reason.
