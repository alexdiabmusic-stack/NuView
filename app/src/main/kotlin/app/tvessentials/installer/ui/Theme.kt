package app.tvessentials.installer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colour system. Near-black base, restrained blue depth, one accent per app. */
object Palette {
    val Background = Color(0xFF0B0F17)
    val BackgroundDeep = Color(0xFF070A11)
    val GlowBlue = Color(0xFF1A2A52)
    val GlowTeal = Color(0xFF123A44)

    val TextPrimary = Color(0xFFF3F6FB)
    val TextSecondary = Color(0xFFB6C0D2)
    val TextTertiary = Color(0xFF7D899E)

    val GlassTop = Color.White.copy(alpha = 0.085f)
    val GlassBottom = Color.White.copy(alpha = 0.035f)
    val GlassFocusTop = Color.White.copy(alpha = 0.17f)
    val GlassFocusBottom = Color.White.copy(alpha = 0.07f)
    val Border = Color.White.copy(alpha = 0.10f)
    val BorderFocused = Color.White.copy(alpha = 0.95f)
    val Scrim = Color(0xE6070A11)

    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val Info = Color(0xFF7AA2FF)

    fun accent(hex: String): Color = runCatching {
        Color(("FF" + hex.removePrefix("#")).toLong(16))
    }.getOrDefault(Info)
}

/**
 * Scales layout and type with the screen so the same design reads on 720p, 1080p and 4K panels
 * (Android reports very different dp sizes for these). 1.0 is a 1280x720dp canvas.
 */
@Immutable
class Dimens(val scale: Float, val widthDp: Float, val heightDp: Float) {
    fun dp(v: Float): Dp = (v * scale).dp
    fun dp(v: Int): Dp = (v * scale).dp
    fun sp(v: Float): TextUnit = (v * scale).sp

    /** TV "title-safe" margins (overscan): 5% horizontally, 5% vertically, never less than the reference values. */
    val safeHorizontal: Dp get() = maxOf((widthDp * 0.05f).dp, dp(48))
    val safeVertical: Dp get() = maxOf((heightDp * 0.05f).dp, dp(28))
}

/** Motion preference: when [reduced], movement and scaling are replaced by instant, non-animated changes. */
@Immutable
class MotionPrefs(val reduced: Boolean) {
    fun duration(ms: Int): Int = if (reduced) 0 else ms
}

val LocalDimens = staticCompositionLocalOf { Dimens(1f, 1280f, 720f) }
val LocalMotion = staticCompositionLocalOf { MotionPrefs(false) }

@Immutable
class Type(d: Dimens) {
    private val family = FontFamily.SansSerif
    val display = TextStyle(fontFamily = family, fontSize = d.sp(42f), fontWeight = FontWeight.Bold, letterSpacing = (-0.6f).sp, lineHeight = d.sp(48f))
    val title = TextStyle(fontFamily = family, fontSize = d.sp(30f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3f).sp, lineHeight = d.sp(36f))
    val cardTitle = TextStyle(fontFamily = family, fontSize = d.sp(26f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2f).sp, lineHeight = d.sp(31f))
    val subtitle = TextStyle(fontFamily = family, fontSize = d.sp(20f), fontWeight = FontWeight.Normal, lineHeight = d.sp(27f))
    val body = TextStyle(fontFamily = family, fontSize = d.sp(18f), fontWeight = FontWeight.Normal, lineHeight = d.sp(25f))
    val bodyStrong = TextStyle(fontFamily = family, fontSize = d.sp(18f), fontWeight = FontWeight.SemiBold, lineHeight = d.sp(25f))
    val button = TextStyle(fontFamily = family, fontSize = d.sp(19f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.1f.sp)
    val caption = TextStyle(fontFamily = family, fontSize = d.sp(15f), fontWeight = FontWeight.Normal, lineHeight = d.sp(20f))
    val captionStrong = TextStyle(fontFamily = family, fontSize = d.sp(15f), fontWeight = FontWeight.SemiBold, lineHeight = d.sp(20f))
    val overline = TextStyle(fontFamily = family, fontSize = d.sp(14f), fontWeight = FontWeight.SemiBold, letterSpacing = 1.2f.sp)
}

val LocalType = staticCompositionLocalOf<Type> { error("TvTheme missing") }

const val MAX_FONT_SCALE = 1.3f

/** Wraps the whole UI: backdrop, scale, typography, motion. */
@Composable
fun TvTheme(motion: MotionPrefs = MotionPrefs(false), content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background).drawBehind {
        // Subtle depth: a cool glow top-left and a faint teal one bottom-right. No hard shapes, nothing distracting.
        drawRect(Brush.radialGradient(listOf(Palette.GlowBlue.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width * 0.12f, -size.height * 0.05f), radius = size.width * 0.75f))
        drawRect(Brush.radialGradient(listOf(Palette.GlowTeal.copy(alpha = 0.32f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 1.05f), radius = size.width * 0.6f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Palette.BackgroundDeep.copy(alpha = 0.55f))))
    }) {
        val w = maxWidth.value
        val h = maxHeight.value
        val dims = remember(w, h) { Dimens((minOf(w / 1280f, h / 720f)).coerceIn(0.7f, 1.7f), w, h) }
        val type = remember(dims) { Type(dims) }
        // The layout is a fixed 10-foot composition. Android TV's own text-size setting tops out at 1.3x, so that is
        // honoured in full; anything beyond it (phone-style 2.0x) would push the primary action off the card.
        val density = LocalDensity.current
        val tvDensity = remember(density) { Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE)) }
        CompositionLocalProvider(LocalDensity provides tvDensity, LocalDimens provides dims, LocalType provides type, LocalMotion provides motion) {
            content()
        }
    }
}
