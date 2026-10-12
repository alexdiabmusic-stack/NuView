package app.tvessentials.installer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun TvText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Palette.TextPrimary,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(text = text, modifier = modifier, style = style.copy(color = color, textAlign = textAlign), maxLines = maxLines, overflow = overflow)
}

/** Smoothly animated focus state: scale, glow and border brightness. Instant when reduced motion is on. */
class FocusVisual(val scale: Float, val glow: Float, val border: Float)

@Composable
fun rememberFocusVisual(focused: Boolean, focusScale: Float = 1.05f): FocusVisual {
    val motion = LocalMotion.current
    val spec = tween<Float>(durationMillis = motion.duration(200), easing = FastOutSlowInEasing)
    val scale by animateFloatAsState(if (focused && !motion.reduced) focusScale else 1f, spec, label = "focusScale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, spec, label = "focusGlow")
    return FocusVisual(scale, glow, glow)
}

/**
 * The one focus primitive: tracks focus, is activated by Select/Enter, and offers the Menu key as a secondary trigger.
 * Everything focusable in the product is built on this, so focus behaviour is identical everywhere.
 */
@Composable
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    description: String? = null,
    role: Role = Role.Button,
    onFocusChange: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    var hasFocus by remember { mutableStateOf(false) }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { hasFocus = it.isFocused; onFocusChange(it.isFocused) }
        .onPreviewKeyEvent { e ->
            if (onMenu != null && e.key == Key.Menu) {
                if (e.type == KeyEventType.KeyUp) onMenu()
                true
            } else false
        }
        // The focus state is stated on the merged node itself so assistive tech and UI tests see it on cards and links too.
        .semantics(mergeDescendants = true) {
            if (description != null) contentDescription = description
            focused = hasFocus
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

enum class ButtonKind { Primary, Secondary, Quiet }

/** A large, high-contrast button. Focus turns it solid white with dark text: unmistakable from across the room. */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    accent: Color = Palette.Info,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    description: String? = null,
) {
    val d = LocalDimens.current
    var focused by remember { mutableStateOf(false) }
    val fv = rememberFocusVisual(focused, 1.04f)
    val shape = RoundedCornerShape(d.dp(14))
    val container = when {
        !enabled -> Color.White.copy(alpha = 0.05f)
        focused -> Palette.TextPrimary
        kind == ButtonKind.Primary -> accent.copy(alpha = 0.30f)
        kind == ButtonKind.Secondary -> Color.White.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> Palette.TextTertiary
        focused -> Palette.Background
        kind == ButtonKind.Primary -> Color.White
        else -> Palette.TextSecondary
    }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = fv.scale; scaleY = fv.scale }
            .height(d.dp(52))
            .clip(shape)
            .background(container, shape)
            .border(d.dp(if (focused) 2.5f else 1f), if (focused) Palette.BorderFocused else if (kind == ButtonKind.Quiet) Color.Transparent else Palette.Border, shape)
            .tvFocusable(enabled, focusRequester, description ?: label, onFocusChange = { focused = it; onFocusChange(it) }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = d.dp(20)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (glyph != null) {
                GlyphIcon(glyph, content, d.dp(22))
                Spacer(Modifier.width(d.dp(10)))
            }
            TvText(label, LocalType.current.button, color = content, maxLines = 1)
        }
    }
}

/** Round icon button for the header (refresh, settings). */
@Composable
fun TvIconButton(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val d = LocalDimens.current
    val motion = LocalMotion.current
    var focused by remember { mutableStateOf(false) }
    val fv = rememberFocusVisual(focused, 1.12f)
    val spin = if (spinning && !motion.reduced) {
        val t = rememberInfiniteTransition(label = "spin")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        a
    } else 0f
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = fv.scale; scaleY = fv.scale }
            .size(d.dp(48))
            .clip(CircleShape)
            .background(if (focused) Palette.TextPrimary else Color.White.copy(alpha = 0.09f), CircleShape)
            .border(d.dp(if (focused) 2.5f else 1f), if (focused) Palette.BorderFocused else Palette.Border, CircleShape)
            .tvFocusable(true, focusRequester, description, onFocusChange = { focused = it; onFocusChange(it) }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (focused) Palette.Background else Palette.TextPrimary, d.dp(26), Modifier.rotate(spin))
        if (spinning && motion.reduced) {
            // Reduced motion: no spinning, just a calm dot that says "working".
            Box(Modifier.align(Alignment.TopEnd).padding(d.dp(9)).size(d.dp(8)).background(Palette.Info, CircleShape))
        }
    }
}

/** Understated text link used for secondary actions such as "Details". Underlines on focus. */
@Composable
fun TvLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val d = LocalDimens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.dp(10))
    Row(
        modifier
            .height(d.dp(36))
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent, shape)
            .border(d.dp(if (focused) 2f else 0f), if (focused) Palette.BorderFocused else Color.Transparent, shape)
            .tvFocusable(true, focusRequester, label, onFocusChange = { focused = it; onFocusChange(it) }, onClick = onClick)
            .padding(horizontal = d.dp(14)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TvText(label, LocalType.current.captionStrong, color = if (focused) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1)
        Spacer(Modifier.width(d.dp(6)))
        GlyphIcon(Glyph.Arrow, if (focused) Palette.TextPrimary else Palette.TextTertiary, d.dp(16))
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val d = LocalDimens.current
    val motion = LocalMotion.current
    val alpha = if (pulse && !motion.reduced) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulseAlpha")
        a
    } else 1f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(d.dp(10)).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
        Spacer(Modifier.width(d.dp(8)))
        TvText(text, LocalType.current.captionStrong, color = color, maxLines = 1)
    }
}

/** Determinate when [fraction] is known; a calm sweeping segment when it is not. */
@Composable
fun TvProgressBar(fraction: Float?, accent: Color, modifier: Modifier = Modifier) {
    val d = LocalDimens.current
    val motion = LocalMotion.current
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(d.dp(10)).clip(shape).background(Color.White.copy(alpha = 0.13f), shape)) {
        if (fraction != null) {
            val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(motion.duration(260), easing = LinearEasing), label = "progress")
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.75f), accent)), shape))
        } else if (motion.reduced) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.35f).background(accent.copy(alpha = 0.8f), shape))
        } else {
            val t = rememberInfiniteTransition(label = "indeterminate")
            val x by t.animateFloat(-0.4f, 1.0f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "sweep")
            Box(Modifier.fillMaxHeight().fillMaxWidth().drawBehind {
                val w = size.width * 0.4f
                val start = x * size.width
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent), startX = start, endX = start + w), topLeft = Offset(start, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
            })
        }
    }
}

/** The app's tile: its own launcher icon once known (from the user's device), otherwise a neutral vector glyph. */
@Composable
fun AppIconTile(ui: AppCardUi, size: Dp, modifier: Modifier = Modifier) {
    val radius = size * 0.24f
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(ui.accent.copy(alpha = 0.95f), ui.accent.copy(alpha = 0.55f).compositeOver(Palette.Background))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val icon = ui.officialIcon
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop)
        } else {
            GlyphIcon(ui.glyph, Color.White, size * 0.56f)
        }
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** Brand tile for "TV Essentials" itself. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.26f)
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF5B7CFF), Color(0xFF2DD4BF))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.Logo, Color.White, size * 0.62f) }
}
