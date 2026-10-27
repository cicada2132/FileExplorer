package com.offlinefiles.explorer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.offlinefiles.explorer.core.FileKind
import com.offlinefiles.explorer.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF1F5FD1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF006A60),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E4),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB1C5FF),
    surfaceTint = Color(0xFF1F5FD1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB1C5FF),
    onPrimary = Color(0xFF002C70),
    primaryContainer = Color(0xFF1446A0),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF82D5C8),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF9EF2E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF1F5FD1),
    surfaceTint = Color(0xFFB1C5FF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Accent colors per file type, tuned separately for light and dark backgrounds. */
data class KindColors(val dark: Boolean) {
    fun of(kind: FileKind): Color = if (dark) darkMap.getValue(kind) else lightMap.getValue(kind)

    private companion object {
        val lightMap = mapOf(
            FileKind.FOLDER to Color(0xFF2F6FE0),
            FileKind.IMAGE to Color(0xFF1E8E4E),
            FileKind.VIDEO to Color(0xFFD9443A),
            FileKind.AUDIO to Color(0xFFE07800),
            FileKind.PDF to Color(0xFFC62828),
            FileKind.DOCUMENT to Color(0xFF1A73E8),
            FileKind.TEXT to Color(0xFF546E7A),
            FileKind.ARCHIVE to Color(0xFF8E5CD9),
            FileKind.APK to Color(0xFF2E9D5B),
            FileKind.CODE to Color(0xFF00897B),
            FileKind.OTHER to Color(0xFF6E7681),
        )
        val darkMap = mapOf(
            FileKind.FOLDER to Color(0xFF8FB2FF),
            FileKind.IMAGE to Color(0xFF7DD99F),
            FileKind.VIDEO to Color(0xFFFF8A80),
            FileKind.AUDIO to Color(0xFFFFB74D),
            FileKind.PDF to Color(0xFFFF8A80),
            FileKind.DOCUMENT to Color(0xFF8AB4F8),
            FileKind.TEXT to Color(0xFFB0BEC5),
            FileKind.ARCHIVE to Color(0xFFC6A6FF),
            FileKind.APK to Color(0xFF81D4A0),
            FileKind.CODE to Color(0xFF80CBC4),
            FileKind.OTHER to Color(0xFFAAB2BD),
        )
    }
}

val LocalKindColors = staticCompositionLocalOf { KindColors(false) }

@Composable
fun FileExplorerTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) Dark else Light
    CompositionLocalProvider(LocalKindColors provides KindColors(dark)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
