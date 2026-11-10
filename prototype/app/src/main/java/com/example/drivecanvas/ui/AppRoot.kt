package com.example.drivecanvas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.drivecanvas.DriveCanvasViewModel
import com.example.drivecanvas.Screen

/** Speed above which the parked-only screens are locked (km/h). */
private const val MOVING_KMH = 1f

@Composable
fun DriveCanvasApp(vm: DriveCanvasViewModel) {
    val screen by vm.screen.collectAsState()
    val speed by vm.speedKmh.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.COURSE -> CourseScreen(vm)
            Screen.DRIVING -> DrivingScreen(vm)
            Screen.RESULT -> ResultScreen(vm)
        }
        if (screen != Screen.DRIVING && (speed ?: 0f) > MOVING_KMH) {
            DrivingLockOverlay()
        }
    }
}

/** Covers the parked-only screens while the car moves and swallows every touch. */
@Composable
private fun DrivingLockOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "주행 중에는 사용할 수 없어요 · 안전하게 정차한 뒤 확인하세요",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
