package com.lockit.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Theme mode preference for dark/light mode switching.
 */
enum class ThemeMode {
    SYSTEM,  // Follow Android system setting
    LIGHT,   // Force light mode
    DARK     // Force dark mode
}

/**
 * Helper to get/saved theme preference.
 */
object ThemePreference {
    private const val PREFS_NAME = "lockit_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}

// Light Color Scheme (Version 1 - current brutalist)
private val LightColorScheme = lightColorScheme(
    primary = Primary,                    // Black #000000
    onPrimary = White,                    // White
    primaryContainer = SurfaceHighest,    // #E2E2E2
    onPrimaryContainer = Primary,         // Black
    secondary = IndustrialOrange,         // #B34700
    onSecondary = White,
    secondaryContainer = SurfaceHighest,
    onSecondaryContainer = Primary,
    tertiary = TacticalRed,               // #A30000
    onTertiary = White,
    error = TacticalRed,
    onError = White,
    background = White,                   // #FFFFFF
    onBackground = Primary,
    surface = White,
    onSurface = Primary,
    surfaceVariant = Grey400,
    onSurfaceVariant = Grey500,
    surfaceContainerLowest = SurfaceLow,      // #F3F3F5
    surfaceContainerLow = SurfaceLow,         // #F3F3F5
    surfaceContainer = SurfaceContainer,      // #EEEEEE
    surfaceContainerHigh = SurfaceContainer,  // #EEEEEE
    surfaceContainerHighest = SurfaceHighest, // #E2E2E2
    outline = Grey400,                        // #777777
    outlineVariant = Grey500,                 // #999999
)

// Dark Color Scheme (Version 4 - Digital Monolith)
// "No-Line" rule: boundaries defined by background color shifts, not borders
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,                    // White #FFFFFF (inverse of light)
    onPrimary = DarkOnPrimary,                // #410000 (dark red for text on white)
    primaryContainer = DarkSurfaceContainerHigh, // #353535
    onPrimaryContainer = DarkPrimary,         // White
    secondary = DarkIndustrialOrange,         // #B34700 (same accent)
    onSecondary = DarkPrimary,                // White
    secondaryContainer = DarkSurfaceContainerHigh,
    onSecondaryContainer = DarkPrimary,
    tertiary = DarkTacticalRed,               // #8B0000 (deeper red)
    onTertiary = DarkPrimary,                 // White
    error = DarkTacticalRed,                  // #8B0000
    onError = DarkPrimary,                    // White
    background = DarkSurface,                 // #131313 - deep neutral void
    onBackground = DarkPrimary,               // White
    surface = DarkSurface,                    // #131313
    onSurface = DarkPrimary,                  // White
    surfaceVariant = DarkSurfaceVariant,      // #474747
    onSurfaceVariant = DarkPrimary.copy(alpha = 0.7f),
    surfaceContainerLowest = DarkSurfaceLowest,   // #0E0E0E - deepest recessed
    surfaceContainerLow = DarkSurfaceLowest,     // #0E0E0E
    surfaceContainer = DarkSurfaceContainer,    // #1F1F1F - cards/panels
    surfaceContainerHigh = DarkSurfaceContainerHigh, // #353535 - elevated modals
    surfaceContainerHighest = DarkSurfaceHighest,    // #353535
    outline = DarkOutline,                       // #474747 at 20% opacity equivalent
    outlineVariant = DarkOutlineVariant,          // #474747
)

@Composable
fun LockitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                val window = activity.window
                // Set status bar content color: dark icons for light theme, light for dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
                // Navigation bar follows same logic
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LockitTypography,
        shapes = LockitShapes,
        content = content,
    )
}