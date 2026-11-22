package com.taskman.root.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SYSTEM follows the phone's dark mode (light or the dark-gray theme); LIGHT / DARK force one of them;
 * AMOLED is a true-black variant of the dark theme, which switches the pixels off on OLED screens.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

/** Color of the window behind Compose, so that a cold start does not flash another theme. */
fun windowBackgroundArgb(mode: ThemeMode, systemDark: Boolean): Int = when {
    mode == ThemeMode.AMOLED -> 0xFF000000.toInt()
    mode.isDark(systemDark) -> 0xFF202020.toInt()
    else -> 0xFFF3F3F3.toInt()
}

// ───────────────────────── colors ─────────────────────────
//
// The roles follow the Windows 11 layering:
//   background               the "Mica" base the title bar and navigation sit on
//   surface                  the content layer (rounded, hairline border) the pages are drawn on
//   surfaceContainerLowest   a raised fill on top of the content layer: tiles, inputs, standard buttons
//   outlineVariant           hairlines: dividers, card borders
//   primary                  the accent color (selection pills, links, heat map, graphs' focus)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0067C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBF8),
    onPrimaryContainer = Color(0xFF00325A),
    secondary = Color(0xFF5B6770),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF0F7B0F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDFF6DD),
    onTertiaryContainer = Color(0xFF0B3D0B),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFBFBFB),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEBEBEB),
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFF60CDFF),
    error = Color(0xFFC42B1C),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E9),
    onErrorContainer = Color(0xFF5C0F08),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFE3E3E3),
    scrim = Color.Black,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFF7F7F7),
    surfaceContainerHighest = Color(0xFFEDEDED)
)

/** Dark gray, close to the dark mode of Windows 11. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF60CDFF),
    onPrimary = Color(0xFF00263F),
    primaryContainer = Color(0xFF24394B),
    onPrimaryContainer = Color(0xFFD6ECFF),
    secondary = Color(0xFFB0B8C0),
    onSecondary = Color(0xFF1B1B1B),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFF6CCB5F),
    onTertiary = Color(0xFF0B2A0B),
    tertiaryContainer = Color(0xFF223D22),
    onTertiaryContainer = Color(0xFFC9F2C4),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF272727),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF343434),
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1B1B1B),
    inversePrimary = Color(0xFF0067C0),
    error = Color(0xFFFF99A4),
    onError = Color(0xFF3B0B10),
    errorContainer = Color(0xFF442726),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFF3C3C3C),
    scrim = Color.Black,
    surfaceContainerLowest = Color(0xFF2E2E2E),
    surfaceContainerLow = Color(0xFF2B2B2B),
    surfaceContainer = Color(0xFF2C2C2C),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF3A3A3A)
)

/** True black backgrounds; panels are separated by thin outlines instead of lighter fills. */
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF60CDFF),
    onPrimary = Color(0xFF00263F),
    primaryContainer = Color(0xFF0F2A40),
    onPrimaryContainer = Color(0xFFD6ECFF),
    secondary = Color(0xFFB0B8C0),
    onSecondary = Color(0xFF1B1B1B),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFF6CCB5F),
    onTertiary = Color(0xFF0B2A0B),
    tertiaryContainer = Color(0xFF16301A),
    onTertiaryContainer = Color(0xFFC9F2C4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1B1B1B),
    inversePrimary = Color(0xFF0067C0),
    error = Color(0xFFFF99A4),
    onError = Color(0xFF3B0B10),
    errorContainer = Color(0xFF3A1217),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF6A6A6A),
    outlineVariant = Color(0xFF2E2E2E),
    scrim = Color.Black,
    surfaceContainerLowest = Color(0xFF141414),
    surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF191919),
    surfaceContainerHighest = Color(0xFF222222)
)

// ───────────────────────── type and shape ─────────────────────────
//
// Sizes follow the Windows 11 type ramp (Caption 12, Body 14, Subtitle 20, ...). Windows adds no tracking,
// Material's defaults add some, so every style sets letterSpacing explicitly.

private val TaskManTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
)

/** Windows 11 corner radii: 4 dp for controls, 8 dp for cards, flyouts, dialogs and sheets. */
private val TaskManShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

/** True when the app is currently drawn with a dark scheme (not necessarily the phone's setting). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun TaskManTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val scheme = when {
        mode == ThemeMode.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TaskManTypography,
            shapes = TaskManShapes,
            content = content
        )
    }
}

/** Graph colors, close to the ones the Windows Task Manager uses per resource, plus two status colors. */
object Palette {
    val cpu = Color(0xFF117DBB)
    val memory = Color(0xFF8B12AE)
    val mobile = Color(0xFF3E8E41)
    val wifi = Color(0xFFD07F1D)
    val gpu = Color(0xFF0A8F94)
    val storage = Color(0xFF4DA64D)

    /** running / healthy */
    val good = Color(0xFF2E9E4F)

    /** restarting / waiting */
    val warn = Color(0xFFE08A00)
}

/**
 * The Windows 11 "heat map": the busier a resource is, the stronger the accent tint behind its value.
 * [fraction] is 0 (idle, no tint) .. 1 (the strongest tint).
 */
@Composable
fun heatBackground(fraction: Float): Color {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    if (f < 0.02f) return Color.Transparent
    // Tuned for contrast: text stays above 4.5:1 on the strongest tint, labels above 4.5:1 once they switch to onSurface.
    val peak = if (LocalDarkTheme.current) 0.40f else 0.36f
    return MaterialTheme.colorScheme.primary.copy(alpha = f * peak)
}
