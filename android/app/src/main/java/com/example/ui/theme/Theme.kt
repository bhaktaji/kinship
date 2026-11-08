package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Kinship enforces a warm editorial light-only theme matching the web PWA:
 * Paper (#F7F4EE), Surface (#FFFDF9), Ink (#1C1B18), Forest (#2D4839),
 * Terracotta (#B64C2B), Sage (#687E60), Gold (#BC8B2C).
 */
private val KinshipLightColorScheme = lightColorScheme(
    primary = ForestPrimary,
    onPrimary = ElevatedWhite,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = TerracottaBrand,
    secondary = TerracottaBrand,
    onSecondary = ElevatedWhite,
    secondaryContainer = TerracottaSoft,
    onSecondaryContainer = TerracottaBrand,
    tertiary = SageAccent,
    onTertiary = ElevatedWhite,
    tertiaryContainer = PaperCanvas,
    onTertiaryContainer = InkDark,
    background = PaperCanvas,
    onBackground = InkDark,
    surface = SurfaceCream,
    onSurface = InkDark,
    surfaceVariant = PaperCanvas,
    onSurfaceVariant = MutedText,
    outline = LineBorder,
    outlineVariant = FaintText,
    error = DangerRed,
    onError = ElevatedWhite,
    errorContainer = TerracottaSoft,
    onErrorContainer = DangerRed
)

@Composable
fun KinshipTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KinshipLightColorScheme,
        typography = Typography,
        content = content
    )
}
