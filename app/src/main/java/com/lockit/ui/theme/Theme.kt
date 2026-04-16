package com.lockit.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LockitColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = SurfaceHighest,
    onPrimaryContainer = Black,
    secondary = IndustrialOrange,
    onSecondary = White,
    secondaryContainer = SurfaceHighest,
    tertiary = TacticalRed,
    onTertiary = White,
    error = TacticalRed,
    onError = White,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = SurfaceHighest,
    onSurfaceVariant = Grey600,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceHighest,
    surfaceContainerHighest = SurfaceHighest,
    outline = Grey400,
    outlineVariant = Grey500,
)

@Composable
fun LockitTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = White.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = LockitColorScheme,
        typography = LockitTypography,
        shapes = LockitShapes,
        content = content,
    )
}
