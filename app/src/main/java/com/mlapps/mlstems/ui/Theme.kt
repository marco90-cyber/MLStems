package com.mlapps.mlstems.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB9A7FF), onPrimary = Color(0xFF2A1A6B),
    primaryContainer = Color(0xFF41307F), onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFF7FD6CB), tertiary = Color(0xFFFFB68A),
    background = Color(0xFF13121C), surface = Color(0xFF13121C),
    surfaceContainer = Color(0xFF1F1D2B), surfaceContainerHigh = Color(0xFF2A2837),
)
private val LightFallback = lightColorScheme(
    primary = Color(0xFF5A46B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF), onPrimaryContainer = Color(0xFF1B0B5C),
    secondary = Color(0xFF006A62), tertiary = Color(0xFF8F4D1F),
    background = Color(0xFFFCF8FF), surface = Color(0xFFFCF8FF),
    surfaceContainer = Color(0xFFF0ECF8), surfaceContainerHigh = Color(0xFFEAE5F3),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
)

/** Material You (colori dinamici) con tavolozza viola/azzurra di riserva e forme più morbide. */
@Composable
fun MLStemsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
