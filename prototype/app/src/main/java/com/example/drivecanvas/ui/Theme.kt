package com.example.drivecanvas.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark automotive palette shared by all screens and the map drawing. */
object Palette {
    val Background = Color(0xFF0B0E13)
    val Surface = Color(0xFF161B22)
    val SurfaceRaised = Color(0xFF232B36)
    val Road = Color(0xFF2B333E)
    val Template = Color(0xFF9AA7B8)
    val Route = Color(0xFF3FD0B5)
    val Track = Color(0xFFFFB84D)
    val TextPrimary = Color(0xFFEEF2F7)
    val TextMuted = Color(0xFF9AA7B8)
    val Warning = Color(0xFFFF8A65)
    val Danger = Color(0xFFE5534B)
}

private val DarkScheme = darkColorScheme(
    primary = Palette.Route,
    onPrimary = Color(0xFF00201A),
    secondary = Palette.Track,
    onSecondary = Color(0xFF2B1A00),
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextMuted,
    outline = Palette.Template,
    error = Palette.Danger,
)

@Composable
fun DriveCanvasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
