package com.lockit.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================
// LIGHT THEME COLORS (Version 1 - Brutalist)
// ============================================

// Brutalism Color Palette - Light Mode
// Based on lockit/ui/version_1/DESIGN.md

val Primary = Color(0xFF000000)           // Black - primary buttons, borders
val IndustrialOrange = Color(0xFFB34700)  // Industrial Orange - status, warnings
val TacticalRed = Color(0xFFA30000)       // Tactical Red - errors, danger
val DarkCrimson = Color(0xFF8B0000)       // Dark Crimson - critical alerts

val SurfaceLow = Color(0xFFF3F3F5)        // Low container surface (lightest grey)
val SurfaceContainer = Color(0xFFEEEEEE)  // Medium container surface
val SurfaceHighest = Color(0xFFE2E2E2)    // Highest container surface (headers)

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)
val Grey400 = Color(0xFF777777)           // outline
val Grey500 = Color(0xFF999999)
val Grey600 = Color(0xFF666666)

// ============================================
// DARK THEME COLORS (Version 4 - Digital Monolith)
// ============================================

// Digital Monolith - Dark Mode
// Based on lockit/ui/version_4/monolith/DESIGN.md
// "No-Line" rule: boundaries by background color shifts, not borders

// Foundation
val DarkSurface = Color(0xFF131313)       // Deep neutral void - standard page background
val DarkPrimary = Color(0xFFFFFFFF)       // Pure white - maximum legibility, high-contrast
val DarkOnPrimary = Color(0xFF410000)     // Dark red - text on white buttons

// Surface Hierarchy (stacking levels of darkness)
val DarkSurfaceLowest = Color(0xFF0E0E0E) // Deepest background - sidebars, recessed areas
val DarkSurfaceContainer = Color(0xFF1F1F1F) // Cards, active panels
val DarkSurfaceContainerHigh = Color(0xFF353535) // Elevated modals, popovers

// For compatibility with surfaceHighest naming
val DarkSurfaceHighest = Color(0xFF353535)

// Accent Colors
val DarkIndustrialOrange = Color(0xFFB34700) // Same orange accent
val DarkTacticalRed = Color(0xFF8B0000)     // Darker red for dark mode (more visible on dark)

// Outlines (used sparingly, 20% opacity equivalent)
val DarkOutline = Color(0xFF474747)        // Ghost border fallback
val DarkOutlineVariant = Color(0xFF474747)
val DarkSurfaceVariant = Color(0xFF474747) // For surfaceVariant

// ============================================
// COMPATIBILITY ALIASES
// ============================================

// These provide semantic access for components
val Surface = White                        // Light mode default surface

// Dark mode aliases (computed in Theme.kt via colorScheme)