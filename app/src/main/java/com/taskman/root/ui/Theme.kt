package com.taskman.root.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0067C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FA),
    onPrimaryContainer = Color(0xFF001E36),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE9E9E9),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFB5B5B5),
    error = Color(0xFFC42B1C),
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF5C0F08),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE3E3E3)
)

/** Dark gray, close to the dark mode of Windows. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF60CDFF),
    onPrimary = Color(0xFF00263F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD0E8FF),
    background = Color(0xFF202020),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF2B2B2B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFC8C8C8),
    outline = Color(0xFF6A6A6A),
    error = Color(0xFFFF99A4),
    errorContainer = Color(0xFF5C1A1F),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF252525),
    surfaceContainer = Color(0xFF2B2B2B),
    surfaceContainerHigh = Color(0xFF333333),
    surfaceContainerHighest = Color(0xFF3A3A3A)
)

/** True black backgrounds; panels are separated by thin outlines instead of lighter fills. */
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF60CDFF),
    onPrimary = Color(0xFF00263F),
    primaryContainer = Color(0xFF00334F),
    onPrimaryContainer = Color(0xFFD0E8FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFC8C8C8),
    outline = Color(0xFF5A5A5A),
    error = Color(0xFFFF99A4),
    errorContainer = Color(0xFF4A1218),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0x00000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF171717),
    surfaceContainerHighest = Color(0xFF1F1F1F)
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
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Graph colors, close to the ones the Windows Task Manager uses per resource. */
object Palette {
    val cpu = Color(0xFF117DBB)
    val memory = Color(0xFF8B12AE)
    val mobile = Color(0xFF3E8E41)
    val wifi = Color(0xFFD07F1D)
    val gpu = Color(0xFF0A8F94)
    val storage = Color(0xFF4DA64D)
    val heat = Color(0xFFFFB900)
}
