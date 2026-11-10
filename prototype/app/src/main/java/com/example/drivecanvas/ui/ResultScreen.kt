package com.example.drivecanvas.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.drivecanvas.DriveCanvasViewModel
import com.example.drivecanvas.DriveResult
import com.example.drivecanvas.core.EndReason
import com.example.drivecanvas.core.Metrics
import com.example.drivecanvas.core.XY
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Parked-only result screen: timelapse of the drawn track, stats, and the privacy-trimmed share card. */
@Composable
fun ResultScreen(vm: DriveCanvasViewModel) {
    val result by vm.result.collectAsState()
    val r = result ?: return
    ResultContent(r, onBack = { vm.backToCourse() })
}

/** Share card hides the first and last metres of the drawn track. */
private const val HIDE_ENDS_M = 300.0

@Composable
private fun ResultContent(r: DriveResult, onBack: () -> Unit) {
    val plan = r.plan
    val summary = r.summary
    val trackXY = remember(r) { summary.drawingTrack.map { plan.projection.toXY(it.latLon) } }
    val allXY = remember(r) { plan.routeXY + plan.templateXY + trackXY }
    val shareXY = remember(r) { hideEnds(trackXY, HIDE_ENDS_M) }

    val timelapse = remember(r) { Animatable(0f) }
    LaunchedEffect(r) {
        timelapse.snapTo(0f)
        timelapse.animateTo(1f, tween(durationMillis = 3000, easing = LinearEasing))
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                endTitle(summary.endReason),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (summary.endReason == EndReason.COMPLETED) Palette.Route else Palette.TextPrimary,
            )
            Text(
                "${r.label} 코스 · ${if (r.sim) "시뮬레이션 결과" else "차량 내비 결과"}",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextMuted,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Surface),
            ) {
                TimelapseMap(plan.routeXY, plan.templateXY, trackXY, allXY, timelapse.value)
            }
        }

        Column(
            modifier = Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatLine("완주율", "${(summary.completion * 100).roundToInt()}%")
            StatLine(
                "그림 유사도",
                "S ${summary.shapeScore.roundToInt()}점 · ${Metrics.shapeLabel(summary.shapeScore)}",
            )
            StatLine("주행 거리", String.format(Locale.ROOT, "%.1f km", summary.distanceM / 1000.0))
            StatLine("주행 시간", durationText(summary.durationMs))
            StatLine("종료", endReasonKo(summary.endReason))

            ShareCard(
                points = shareXY,
                distanceKm = summary.distanceM / 1000.0,
                score = summary.shapeScore,
            )
            Text(
                "출발·도착 지점은 가려서 공유돼요",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
            )
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceRaised, contentColor = Palette.TextPrimary),
            ) {
                Text("다른 코스 보기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TimelapseMap(
    routeXY: List<XY>,
    templateXY: List<XY>,
    trackXY: List<XY>,
    allXY: List<XY>,
    progress: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val fit = MapFit.of(allXY, size, 12.dp.toPx())
        drawXyLine(templateXY, fit, Palette.Template, 3.dp, dashed = true, closed = true)
        drawXyLine(routeXY, fit, Palette.Route, 4.dp, alpha = 0.35f)
        val visible = (trackXY.size * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, trackXY.size)
        if (visible >= 2) drawXyLine(trackXY.subList(0, visible), fit, Palette.Track, 10.dp)
    }
}

@Composable
private fun ShareCard(points: List<XY>, distanceKm: Double, score: Double) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.SurfaceRaised)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "길 위에 그린 하트",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Palette.TextPrimary,
        )
        Text(date, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        Canvas(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (points.size >= 2) {
                val fit = MapFit.of(points, size, 12.dp.toPx())
                drawXyLine(points, fit, Palette.Track, 8.dp)
            }
        }
        Text(
            String.format(Locale.ROOT, "%.1f km · S %d", distanceKm, score.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextPrimary,
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
    }
}

private fun durationText(durationMs: Long): String {
    val totalSec = durationMs / 1000
    return "${totalSec / 60}분 ${String.format(Locale.ROOT, "%02d", totalSec % 60)}초"
}

private fun endTitle(reason: EndReason): String = when (reason) {
    EndReason.COMPLETED -> "완성!"
    EndReason.ENDED_EARLY_OFF_ROUTE -> "안전하게 마무리했어요"
    EndReason.ENDED_EARLY_USER -> "여기까지 그렸어요"
    EndReason.CANCELLED -> "안내가 취소됐어요"
    EndReason.ERROR -> "안내가 중단됐어요"
}

private fun endReasonKo(reason: EndReason): String = when (reason) {
    EndReason.COMPLETED -> "완주"
    EndReason.ENDED_EARLY_OFF_ROUTE -> "안전 우선 조기 종료"
    EndReason.ENDED_EARLY_USER -> "사용자 종료"
    EndReason.CANCELLED -> "안내 취소"
    EndReason.ERROR -> "오류로 종료"
}
