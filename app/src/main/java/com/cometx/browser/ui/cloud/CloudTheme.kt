package com.cometx.browser.ui.cloud

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Comet Cloud theme (v2.2.0) — Material 3 Expressive baseline for the Cloud AI
 * center, the app's first Jetpack Compose surface.
 *
 * Customizability contract:
 *  - Material You dynamic color by default on Android 12+ (follows the user's
 *    wallpaper, same `material_you` setting as the rest of the app)
 *  - brand fallback palettes mirror values(-night)/colors.xml: comet violet
 *    (#6D28D9 light / #A78BFA dark) on the deep-space surfaces (#FCFBFF / #0E1116)
 */
private val LightComet = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF2A0B66),
    secondary = Color(0xFF625F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF00696B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F2),
    onTertiaryContainer = Color(0xFF002020),
    background = Color(0xFFFCFBFF),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFF7F4FB),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE7E0F0),
    onSurfaceVariant = Color(0xFF494657),
    outline = Color(0xFF7A7589),
    outlineVariant = Color(0xFFC9C3D8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkComet = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF80D4D5),
    onTertiary = Color(0xFF003737),
    tertiaryContainer = Color(0xFF004F50),
    onTertiaryContainer = Color(0xFF9CF1F2),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF2A2633),
    onSurfaceVariant = Color(0xFFC9C3D8),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

@Composable
fun CometCloudTheme(materialYou: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkComet
        else -> LightComet
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
