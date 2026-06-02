package com.arcisai.nvr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Brand palette — mirrors the production ArcisAI Android app so the NVR app
// feels like part of the same product family.
// ---------------------------------------------------------------------------

val AccentPurple = Color(0xFF7D6BE8)
val AccentPeri   = Color(0xFF9EABDE)
val ArcisGreen   = Color(0xFF14AD52)
val ArcisRed     = Color(0xFFD92626)
val ArcisOrange  = Color(0xFFE07009)
val ArcisGray    = Color(0xFF808090)

// Surface tokens — iOS systemGroupedBackground parity, same as production.
private val DarkBg         = Color(0xFF000000)
private val DarkSurface    = Color(0xFF1C1C1E)
private val DarkSurfaceVar = Color(0xFF2C2C2E)
private val DarkOnSurface  = Color(0xFFF0F0FF)
private val DarkOnSurfVar  = Color(0xFF9999BB)

private val LightBg         = Color(0xFFF2F2F7)
private val LightSurface    = Color(0xFFFFFFFF)
private val LightSurfaceVar = Color(0xFFE5E5EA)
private val LightOnSurface  = Color(0xFF1A1A2E)
private val LightOnSurfVar  = Color(0xFF6E6E82)

// ---------------------------------------------------------------------------
// Login + splash atmospheres — deep brand-purple gradients.
// ---------------------------------------------------------------------------

val SplashBg            = Color(0xFF0D0B1A)
val SplashGradientInner = Color(0xFF1E1548)

val LoginGradientDark = Brush.verticalGradient(
    listOf(Color(0xFF1E1548), Color(0xFF130F30), Color(0xFF0A0818))
)
val LoginGradientLight = Brush.verticalGradient(
    listOf(Color(0xFF1A1832), Color(0xFF151228), Color(0xFF0F0D1E))
)

@Composable
fun loginGradient(): Brush =
    if (isSystemInDarkTheme()) LoginGradientDark else LoginGradientLight

// ---------------------------------------------------------------------------
// Card/surface extras Material3 doesn't expose directly.
// ---------------------------------------------------------------------------

data class ArcisExtras(
    val cardBackground: Color,
    val cardShadowAlpha: Float,
    val isDark: Boolean,
)

private val DarkExtras  = ArcisExtras(cardBackground = DarkSurface,  cardShadowAlpha = 0.25f, isDark = true)
private val LightExtras = ArcisExtras(cardBackground = LightSurface, cardShadowAlpha = 0.05f, isDark = false)

val LocalArcisExtras = staticCompositionLocalOf { DarkExtras }

// ---------------------------------------------------------------------------
// Typography — same scale as the production app.
// ---------------------------------------------------------------------------

private val ArcisTypography = Typography(
    displayLarge   = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 32.sp),
    displayMedium  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp),
    displaySmall   = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 24.sp),
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 22.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 12.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 12.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 12.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 10.sp),
)

// ---------------------------------------------------------------------------
// Material 3 ColorSchemes.
// ---------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary             = AccentPurple,
    onPrimary           = Color.White,
    primaryContainer    = AccentPeri,
    onPrimaryContainer  = Color.White,
    secondary           = ArcisGreen,
    onSecondary         = Color.White,
    tertiary            = ArcisOrange,
    onTertiary          = Color.White,
    error               = ArcisRed,
    onError             = Color.White,
    errorContainer      = Color(0xFFFFDADA),
    onErrorContainer    = ArcisRed,
    background          = LightBg,
    onBackground        = LightOnSurface,
    surface             = LightSurface,
    onSurface           = LightOnSurface,
    surfaceVariant      = LightSurfaceVar,
    onSurfaceVariant    = LightOnSurfVar,
    outline             = Color(0xFFC0C0CC),
    outlineVariant      = Color(0xFFDDDDE8),
)

private val DarkColors = darkColorScheme(
    primary             = AccentPurple,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF5A4BA8),
    onPrimaryContainer  = Color.White,
    secondary           = ArcisGreen,
    onSecondary         = Color.White,
    tertiary            = ArcisOrange,
    onTertiary          = Color.White,
    error               = ArcisRed,
    onError             = Color.White,
    errorContainer      = Color(0xFF8B1A1A),
    onErrorContainer    = Color(0xFFFFDADA),
    background          = DarkBg,
    onBackground        = DarkOnSurface,
    surface             = DarkSurface,
    onSurface           = DarkOnSurface,
    surfaceVariant      = DarkSurfaceVar,
    onSurfaceVariant    = DarkOnSurfVar,
    outline             = Color(0xFF444466),
    outlineVariant      = Color(0xFF333350),
)

@Composable
fun ArcisNvrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    val extras = if (darkTheme) DarkExtras else LightExtras
    CompositionLocalProvider(LocalArcisExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography  = ArcisTypography,
            content     = content,
        )
    }
}
