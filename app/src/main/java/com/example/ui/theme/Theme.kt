package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryDark,
    onPrimary = OnCobaltPrimaryDark,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = OnCobaltContainerDark,
    secondary = CyanSecondaryDark,
    onSecondary = OnCyanSecondaryDark,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = OnCyanContainerDark,
    tertiary = TealTertiaryDark,
    onTertiary = OnTealTertiaryDark,
    tertiaryContainer = TealContainerDark,
    onTertiaryContainer = OnTealContainerDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = OnCobaltPrimaryLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = OnCobaltContainerLight,
    secondary = CyanSecondaryLight,
    onSecondary = OnCyanSecondaryLight,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = OnCyanContainerLight,
    tertiary = TealTertiaryLight,
    onTertiary = OnTealTertiaryLight,
    tertiaryContainer = TealContainerLight,
    onTertiaryContainer = OnTealContainerLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight
)

@Composable
fun UdaDownloaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
