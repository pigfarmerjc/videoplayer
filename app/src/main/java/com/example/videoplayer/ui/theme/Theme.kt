package com.example.videoplayer.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryNeonPurple,
    secondary = SecondaryNeonCyan,
    tertiary = AccentPink,
    background = CarbonBg,
    surface = CarbonCard,
    onPrimary = TextPrimary,
    onSecondary = CarbonBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun VideoPlayerTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use solid Carbon background for regular screens, but can be customized for glass overlays
            window.statusBarColor = CarbonBg.toArgb()
            window.navigationBarColor = CarbonBg.toArgb()
            
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = false
            windowInsetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
