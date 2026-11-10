package com.example.drivecanvas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.drivecanvas.CourseUiState
import com.example.drivecanvas.DriveCanvasViewModel
import com.example.drivecanvas.DriveMode
import com.example.drivecanvas.core.CandidateKind
import com.example.drivecanvas.core.GuidancePlan
import com.example.drivecanvas.core.Metrics
import com.example.drivecanvas.core.PlannedCandidate
import com.example.drivecanvas.core.RoadGraph
import java.util.Locale
import kotlin.math.roundToInt

/** Parked-only course screen: region, template, two candidates on a road map, and the start controls. */
@Composable
fun CourseScreen(vm: DriveCanvasViewModel) {
    val s by vm.course.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            MapPanel(
                graph = s.graph,
                plan = s.selected?.plan,
                loading = s.loading,
                loadError = s.loadError,
                noRouteReasons = s.noRouteReasons,
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
            )
            ControlPanel(vm, s, Modifier.weight(0.42f).fillMaxHeight())
        }
        Footer()
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Drive Canvas · 길 위에 그리는 드라이브",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Palette.TextPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("지역", "고양 일산")
            Chip("그림", "하트")
        }
    }
}

@Composable
private fun Chip(label: String, value: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.SurfaceRaised) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
        }
    }
}

@Composable
private fun MapPanel(
    graph: RoadGraph?,
    plan: GuidancePlan?,
    loading: Boolean,
    loadError: String?,
    noRouteReasons: List<String>,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadError != null -> Text(
                "코스 데이터를 읽지 못했어요: $loadError",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Warning,
                modifier = Modifier.padding(24.dp),
            )
            loading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Palette.Route, modifier = Modifier.size(56.dp))
                Text("하트 코스를 계산하는 중…", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
            }
            plan == null || graph == null -> Column(
                modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("이 지역에서 하트 코스를 찾지 못했어요", style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
                noRouteReasons.forEach { reason ->
                    Text("· $reason", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
                }
            }
            else -> CourseMap(graph, plan, Modifier.fillMaxSize().padding(20.dp))
        }
    }
}

@Composable
private fun CourseMap(graph: RoadGraph, plan: GuidancePlan, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val fit = MapFit.of(plan.routeXY + plan.templateXY, size, 16.dp.toPx())
        drawRoadNetwork(graph, fit)
        drawXyLine(plan.templateXY, fit, Palette.Template, 3.dp, dashed = true, closed = true)
        drawXyLine(plan.routeXY, fit, Palette.Route, 8.dp)
        drawStartMarker(fit.map(plan.projection.toXY(plan.start)))
    }
}

@Composable
private fun ControlPanel(vm: DriveCanvasViewModel, s: CourseUiState, modifier: Modifier) {
    val shape = s.candidates.firstOrNull { it.kind == CandidateKind.SHAPE }
    val time = s.candidates.firstOrNull { it.kind == CandidateKind.TIME }
    val selectedKind = s.selected?.kind

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("코스 고르기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
        CandidateCard(
            title = "모양 우선",
            candidate = shape,
            selected = selectedKind == CandidateKind.SHAPE,
            onClick = { vm.selectCandidate(CandidateKind.SHAPE) },
        )
        CandidateCard(
            title = "시간 우선",
            candidate = time,
            selected = selectedKind == CandidateKind.TIME,
            onClick = { vm.selectCandidate(CandidateKind.TIME) },
        )

        Text("주행 방식", style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeButton("차량 내비 안내", s.mode == DriveMode.REAL_NAVI, Modifier.weight(1f)) {
                vm.setMode(DriveMode.REAL_NAVI)
            }
            ModeButton("시뮬레이션 주행", s.mode == DriveMode.SIMULATION, Modifier.weight(1f)) {
                vm.setMode(DriveMode.SIMULATION)
            }
        }
        if (s.mode == DriveMode.SIMULATION) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .clickable { vm.setIncludeDeviation(!s.includeDeviation) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "경로 이탈 시나리오 포함",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = s.includeDeviation, onCheckedChange = null)
            }
        }

        s.startBlockReason?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = Palette.Warning)
        }
        Button(
            onClick = { vm.startDrive() },
            enabled = s.startBlockReason == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Route,
                contentColor = Color.Black,
                disabledContainerColor = Palette.SurfaceRaised,
                disabledContentColor = Palette.TextMuted,
            ),
        ) {
            Text("드라이브 시작", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CandidateCard(
    title: String,
    candidate: PlannedCandidate?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Palette.Route else Palette.SurfaceRaised,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(enabled = candidate != null, onClick = onClick),
        color = Palette.Surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate == null) Palette.TextMuted else Palette.TextPrimary,
                )
                if (selected) Text("선택됨", style = MaterialTheme.typography.titleMedium, color = Palette.Route)
            }
            if (candidate == null) {
                Text("조건에 맞는 후보가 없어요", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
            } else {
                val stats = candidate.route.stats
                val score = candidate.route.shape.score
                Text(
                    "거리 ${String.format(Locale.ROOT, "%.1f", stats.lengthM / 1000.0)} km · 예상 시간 ${stats.etaMin.roundToInt()}분",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextPrimary,
                )
                Text(
                    "그림 유사도 S ${score.roundToInt()}점 · ${Metrics.shapeLabel(score)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextPrimary,
                )
                Text(
                    "안내 구간 ${candidate.plan.legs.size}개",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Route, contentColor = Color.Black),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 76.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.TextPrimary),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Footer() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "지도 데이터 © OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
        Text(
            "운전 중에는 코스 선택과 결과 화면이 잠깁니다. 교통법규와 운전자 판단이 그림보다 우선합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextMuted,
        )
    }
}
