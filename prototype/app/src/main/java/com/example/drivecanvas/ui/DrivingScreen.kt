package com.example.drivecanvas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.drivecanvas.DriveCanvasViewModel
import com.example.drivecanvas.core.GuidanceState
import kotlin.math.roundToInt

/** Minimal while-driving view: progress, leg, state, the latest announcement and the end-early button. */
@Composable
fun DrivingScreen(vm: DriveCanvasViewModel) {
    val d by vm.driving.collectAsState()
    val canEndEarly = d.guidance in setOf(
        GuidanceState.LEG_REQUESTED,
        GuidanceState.GUIDING,
        GuidanceState.OFF_ROUTE,
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (d.sim) "시뮬레이션 주행" else "차량 내비 안내",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextMuted,
            )
            Box(modifier = Modifier.padding(16.dp).size(220.dp), contentAlignment = Alignment.Center) {
                ProgressRing(d.progress.toFloat())
                Text(
                    "${(d.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Palette.TextPrimary,
                )
            }
            Text(
                legText(d.legIndex, d.legCount),
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.TextPrimary,
            )
            Text(
                stateText(d.guidance),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Route,
            )
        }

        Column(
            modifier = Modifier.weight(0.6f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Palette.SurfaceRaised,
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        d.banner.ifBlank { "안내를 기다리는 중이에요" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = { vm.endEarly() },
                enabled = canEndEarly,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.SurfaceRaised,
                    contentColor = Palette.TextPrimary,
                    disabledContainerColor = Palette.Surface,
                    disabledContentColor = Palette.TextMuted,
                ),
            ) {
                Text("그리기 끝내기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 16.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = Palette.SurfaceRaised,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = Palette.Route,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun legText(legIndex: Int, legCount: Int): String = when {
    legCount == 0 -> ""
    legIndex < 0 -> "출발지 복귀 구간"
    else -> "구간 ${legIndex + 1}/$legCount"
}

private fun stateText(state: GuidanceState): String = when (state) {
    GuidanceState.GUIDING -> "안내 중"
    GuidanceState.OFF_ROUTE -> "경로 재탐색 중"
    GuidanceState.ENDING_EARLY -> "출발지로 복귀 중"
    GuidanceState.LEG_REQUESTED -> "다음 구간 요청 중"
    GuidanceState.IDLE, GuidanceState.FINISHED -> "준비 중"
}
