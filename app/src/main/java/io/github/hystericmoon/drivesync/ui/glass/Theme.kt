package io.github.hystericmoon.drivesync.ui.glass

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

/** Brand palette: a cool cobalt with a violet accent, tuned so text on glass keeps ≥ 4.5:1 contrast. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5BD8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF00195A),
    secondary = Color(0xFF555D7E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1FF), onSecondaryContainer = Color(0xFF121A37),
    tertiary = Color(0xFF7448C8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDDCFF), onTertiaryContainer = Color(0xFF29005F),
    background = Color(0xFFF5F6FB), onBackground = Color(0xFF181B23),
    surface = Color(0xFFF5F6FB), onSurface = Color(0xFF181B23),
    surfaceVariant = Color(0xFFE1E2EE), onSurfaceVariant = Color(0xFF41444F),
    surfaceContainerHigh = Color(0xFFE9EAF3), surfaceContainer = Color(0xFFEEEFF7),
    outline = Color(0xFF727584), outlineVariant = Color(0xFFC3C5D3),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3C5FF), onPrimary = Color(0xFF002B79),
    primaryContainer = Color(0xFF1F45B8), onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC0C5EB), onSecondary = Color(0xFF282F4D),
    secondaryContainer = Color(0xFF3E4565), onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFFD6BBFF), onTertiary = Color(0xFF41128F),
    tertiaryContainer = Color(0xFF5A30AA), onTertiaryContainer = Color(0xFFEDDCFF),
    background = Color(0xFF0C0E14), onBackground = Color(0xFFE3E4EE),
    surface = Color(0xFF0C0E14), onSurface = Color(0xFFE3E4EE),
    surfaceVariant = Color(0xFF41444F), onSurfaceVariant = Color(0xFFC6C7D5),
    surfaceContainerHigh = Color(0xFF22252F), surfaceContainer = Color(0xFF1A1D26),
    outline = Color(0xFF8F919F), outlineVariant = Color(0xFF41444F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** Tokens for the frosted-glass surfaces; differ per mode so edges read on both light and dark fields. */
@Immutable
data class GlassTokens(
    val dark: Boolean,
    val tint: Color,
    val tintStrong: Color,
    val edgeHighlight: Color,
    val edgeShade: Color,
    val sheen: Color,
)

private val LightGlass = GlassTokens(
    dark = false,
    tint = Color.White.copy(alpha = 0.58f),
    tintStrong = Color(0xFFF7F8FD).copy(alpha = 0.94f),
    edgeHighlight = Color.White.copy(alpha = 0.95f),
    edgeShade = Color(0xFF8A90B0).copy(alpha = 0.22f),
    sheen = Color.White.copy(alpha = 0.55f),
)

private val DarkGlass = GlassTokens(
    dark = true,
    tint = Color.White.copy(alpha = 0.075f),
    tintStrong = Color(0xFF1A1D29).copy(alpha = 0.92f),
    edgeHighlight = Color.White.copy(alpha = 0.24f),
    edgeShade = Color.White.copy(alpha = 0.04f),
    sheen = Color.White.copy(alpha = 0.07f),
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

/** True when the user turned animations off (Developer options / Accessibility → Remove animations). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun DriveSyncTheme(dark: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val base = Typography()
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(
        LocalGlass provides if (dark) DarkGlass else LightGlass,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
