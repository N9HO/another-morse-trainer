package app.anothermorsetrainer

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * The AMT brand palette, matched to the iOS app (MorseTrainerApp/Theme.swift):
 * a deep-navy field with a teal accent. The app is dark-only on iOS, so we
 * mirror that — one fixed scheme, no light variant.
 */
object Brand {
    val navy = Color(0xFF0B1A2D)          // primary background / deepest field
    val navyElevated = Color(0xFF14263C)  // card / tile surface
    val navyRaised = Color(0xFF1C324C)    // surface resting on a card (icon chips)
    val teal = Color(0xFF2CC0D1)          // primary accent
    val tealBright = Color(0xFF46D6E3)    // highlights / selected
    val textPrimary = Color(0xFFF2F6FA)
    val textSecondary = Color(0xFF9DB2C6)
    val warning = Color(0xFFF59E0B)       // amber — "this works against you" notes
    val hairline = Color(0x14FFFFFF)      // white @ ~8% — borders / dividers
    val gradientTop = Color(0xFF05121C)   // top of the background gradient

    val cornerRadius = 16.dp
}

private val AmtColors = darkColorScheme(
    primary = Brand.teal,
    onPrimary = Brand.navy,
    primaryContainer = Brand.navyRaised,
    onPrimaryContainer = Brand.tealBright,
    secondary = Brand.tealBright,
    onSecondary = Brand.navy,
    background = Brand.navy,
    onBackground = Brand.textPrimary,
    surface = Brand.navyElevated,
    onSurface = Brand.textPrimary,
    surfaceVariant = Brand.navyRaised,
    onSurfaceVariant = Brand.textSecondary,
    outline = Brand.hairline,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
)

/** The app's Material 3 theme — fixed dark navy/teal, matching iOS. */
@Composable
fun AmtTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = AmtColors, content = content)
}

/**
 * Full-bleed brand background: a vertical navy gradient with a soft teal radial
 * glow at the top — the same lit-ring effect the iOS app uses behind every
 * screen.
 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    // The gradient is full-bleed (edge-to-edge, behind the system bars on
    // targetSdk 35); the content is inset by the system bars so nothing hides
    // under the status/navigation bars.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.gradientTop, Brand.navy)))
            .background(
                Brush.radialGradient(
                    colors = listOf(Brand.teal.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(540f, 0f),
                    radius = 1100f
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // Material3's default LocalContentColor is black, which only gets
            // overridden inside a Surface. AMT paints its own navy background
            // instead of a Surface, so any bare Text/Icon would inherit black
            // (invisible on navy). Provide the light brand text color as the
            // default so every screen reads correctly without setting it by hand.
            CompositionLocalProvider(LocalContentColor provides Brand.textPrimary) {
                content()
            }
        }
    }
}

/** iOS "brandCard": a navy-elevated rounded surface with a hairline border. */
fun Modifier.brandCard(cornerRadius: androidx.compose.ui.unit.Dp = Brand.cornerRadius): Modifier =
    clip(RoundedCornerShape(cornerRadius))
        .background(Brand.navyElevated)
        .border(width = 1.dp, color = Brand.hairline, shape = RoundedCornerShape(cornerRadius))
