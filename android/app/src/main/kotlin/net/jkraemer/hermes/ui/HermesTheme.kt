package net.jkraemer.hermes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The backgrounds are repeated in res/values*/colors.xml as the window background shown before
// Compose draws; HermesThemeTest keeps the two in step.
internal val HermesLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A62C9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E4FA),
    onPrimaryContainer = Color(0xFF0B2A5E),
    secondaryContainer = Color(0xFFEAE6DB),
    onSecondaryContainer = Color(0xFF2B2A26),
    tertiaryContainer = Color(0xFFFBE3C7),
    onTertiaryContainer = Color(0xFF3F2300),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFEFEDE6),
    onSurfaceVariant = Color(0xFF5E5D59),
    surfaceContainer = Color(0xFFF2F0E9),
    surfaceContainerHigh = Color(0xFFEAE8E0),
    outline = Color(0xFF8A8880),
)

internal val HermesDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF5B9CF5),
    onPrimary = Color(0xFF00255A),
    primaryContainer = Color(0xFF1E3F73),
    onPrimaryContainer = Color(0xFFD9E4FA),
    secondaryContainer = Color(0xFF3A3834),
    onSecondaryContainer = Color(0xFFEAE6DB),
    tertiaryContainer = Color(0xFF5A3A12),
    onTertiaryContainer = Color(0xFFFBE3C7),
    background = Color(0xFF1F1E1D),
    onBackground = Color(0xFFF1EFE8),
    surface = Color(0xFF1F1E1D),
    onSurface = Color(0xFFF1EFE8),
    surfaceVariant = Color(0xFF302F2C),
    onSurfaceVariant = Color(0xFFB9B6AD),
    surfaceContainer = Color(0xFF282725),
    surfaceContainerHigh = Color(0xFF353432),
    outline = Color(0xFF8A8880),
)

/** The light or the dark scheme as the system says; no wallpaper-derived colors. */
@Composable
fun HermesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) HermesDarkColors else HermesLightColors, content = content)
}
