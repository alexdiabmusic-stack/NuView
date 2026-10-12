package app.tvessentials.installer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Every glyph in the product, drawn as vectors. Nothing here imitates a third-party brand mark. */
enum class Glyph { Library, Antenna, Play, Refresh, Sliders, Online, Offline, Check, Warning, Download, Open, Info, Shield, Stop, Arrow, Logo }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val s = size.minDimension
    val w = s * 0.085f // stroke weight scales with the icon
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    when (glyph) {
        Glyph.Library -> {
            // Three overlapping posters: a media library at a glance.
            drawRoundRect(tint.copy(alpha = 0.35f), p(0.12f, 0.30f), Size(0.46f * s, 0.58f * s), CornerRadius(0.06f * s), style = Fill)
            drawRoundRect(tint.copy(alpha = 0.6f), p(0.27f, 0.20f), Size(0.46f * s, 0.58f * s), CornerRadius(0.06f * s), style = Fill)
            drawRoundRect(tint, p(0.42f, 0.10f), Size(0.46f * s, 0.58f * s), CornerRadius(0.06f * s), style = Fill)
            drawPath(Path().apply { moveTo(0.58f * s, 0.27f * s); lineTo(0.58f * s, 0.51f * s); lineTo(0.78f * s, 0.39f * s); close() }, Color(0x66000000))
        }
        Glyph.Antenna -> {
            // Live broadcast: a source with signal rings.
            drawCircle(tint, 0.085f * s, p(0.5f, 0.5f))
            for ((i, r) in listOf(0.22f, 0.36f).withIndex()) {
                val a = tint.copy(alpha = 1f - i * 0.35f)
                drawArc(a, -50f, 100f, false, p(0.5f - r, 0.5f - r), Size(2 * r * s, 2 * r * s), style = stroke)
                drawArc(a, 130f, 100f, false, p(0.5f - r, 0.5f - r), Size(2 * r * s, 2 * r * s), style = stroke)
            }
            drawLine(tint, p(0.5f, 0.58f), p(0.5f, 0.88f), w, StrokeCap.Round)
        }
        Glyph.Play -> {
            drawRoundRect(tint, p(0.08f, 0.2f), Size(0.84f * s, 0.6f * s), CornerRadius(0.16f * s), style = Fill)
            drawPath(Path().apply { moveTo(0.42f * s, 0.36f * s); lineTo(0.42f * s, 0.64f * s); lineTo(0.66f * s, 0.5f * s); close() }, Color(0x99000000))
        }
        Glyph.Refresh -> {
            drawArc(tint, 40f, 280f, false, p(0.2f, 0.2f), Size(0.6f * s, 0.6f * s), style = stroke)
            val tip = p(0.5f + 0.3f * 0.766f, 0.5f - 0.3f * 0.643f)
            drawPath(Path().apply { moveTo(tip.x - 0.02f * s, tip.y - 0.16f * s); lineTo(tip.x + 0.02f * s, tip.y + 0.02f * s); lineTo(tip.x - 0.18f * s, tip.y + 0.0f * s) }, tint, style = stroke)
        }
        Glyph.Sliders -> {
            for ((y, knob) in listOf(0.30f to 0.65f, 0.50f to 0.30f, 0.70f to 0.55f)) {
                drawLine(tint.copy(alpha = 0.55f), p(0.14f, y), p(0.86f, y), w, StrokeCap.Round)
                drawCircle(tint, 0.085f * s, p(knob, y))
            }
        }
        Glyph.Online -> {
            drawCircle(tint, 0.07f * s, p(0.5f, 0.78f))
            for (r in listOf(0.2f, 0.36f)) drawArc(tint, -135f, 90f, false, p(0.5f - r, 0.78f - r), Size(2 * r * s, 2 * r * s), style = stroke)
        }
        Glyph.Offline -> {
            drawCircle(tint, 0.07f * s, p(0.5f, 0.78f))
            for (r in listOf(0.2f, 0.36f)) drawArc(tint.copy(alpha = 0.5f), -135f, 90f, false, p(0.5f - r, 0.78f - r), Size(2 * r * s, 2 * r * s), style = stroke)
            drawLine(tint, p(0.18f, 0.18f), p(0.82f, 0.82f), w, StrokeCap.Round)
        }
        Glyph.Check -> drawPath(Path().apply { moveTo(0.2f * s, 0.54f * s); lineTo(0.43f * s, 0.76f * s); lineTo(0.82f * s, 0.28f * s) }, tint, style = Stroke(w * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.Warning -> {
            drawPath(Path().apply { moveTo(0.5f * s, 0.12f * s); lineTo(0.9f * s, 0.84f * s); lineTo(0.1f * s, 0.84f * s); close() }, tint, style = stroke)
            drawLine(tint, p(0.5f, 0.38f), p(0.5f, 0.60f), w, StrokeCap.Round)
            drawCircle(tint, 0.05f * s, p(0.5f, 0.72f))
        }
        Glyph.Download -> {
            drawLine(tint, p(0.5f, 0.14f), p(0.5f, 0.62f), w, StrokeCap.Round)
            drawPath(Path().apply { moveTo(0.3f * s, 0.44f * s); lineTo(0.5f * s, 0.64f * s); lineTo(0.7f * s, 0.44f * s) }, tint, style = stroke)
            drawPath(Path().apply { moveTo(0.16f * s, 0.7f * s); lineTo(0.16f * s, 0.84f * s); lineTo(0.84f * s, 0.84f * s); lineTo(0.84f * s, 0.7f * s) }, tint, style = stroke)
        }
        Glyph.Open -> {
            drawPath(Path().apply { moveTo(0.4f * s, 0.2f * s); lineTo(0.8f * s, 0.2f * s); lineTo(0.8f * s, 0.6f * s) }, tint, style = stroke)
            drawLine(tint, p(0.8f, 0.2f), p(0.22f, 0.78f), w, StrokeCap.Round)
        }
        Glyph.Info -> {
            drawCircle(tint, 0.38f * s, p(0.5f, 0.5f), style = stroke)
            drawLine(tint, p(0.5f, 0.46f), p(0.5f, 0.68f), w, StrokeCap.Round)
            drawCircle(tint, 0.05f * s, p(0.5f, 0.32f))
        }
        Glyph.Shield -> {
            drawPath(Path().apply {
                moveTo(0.5f * s, 0.1f * s); lineTo(0.84f * s, 0.24f * s); lineTo(0.84f * s, 0.5f * s)
                cubicTo(0.84f * s, 0.7f * s, 0.68f * s, 0.84f * s, 0.5f * s, 0.92f * s)
                cubicTo(0.32f * s, 0.84f * s, 0.16f * s, 0.7f * s, 0.16f * s, 0.5f * s); lineTo(0.16f * s, 0.24f * s); close()
            }, tint, style = stroke)
            drawPath(Path().apply { moveTo(0.34f * s, 0.5f * s); lineTo(0.46f * s, 0.62f * s); lineTo(0.68f * s, 0.38f * s) }, tint, style = stroke)
        }
        Glyph.Stop -> drawRoundRect(tint, p(0.24f, 0.24f), Size(0.52f * s, 0.52f * s), CornerRadius(0.08f * s), style = Fill)
        Glyph.Arrow -> {
            drawLine(tint, p(0.18f, 0.5f), p(0.82f, 0.5f), w, StrokeCap.Round)
            drawPath(Path().apply { moveTo(0.58f * s, 0.26f * s); lineTo(0.82f * s, 0.5f * s); lineTo(0.58f * s, 0.74f * s) }, tint, style = stroke)
        }
        Glyph.Logo -> {
            // An arrow landing in a tray: "install", in the brand gradient colours supplied by the caller.
            drawLine(tint, p(0.5f, 0.16f), p(0.5f, 0.58f), w * 1.25f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(0.3f * s, 0.42f * s); lineTo(0.5f * s, 0.62f * s); lineTo(0.7f * s, 0.42f * s) }, tint, style = Stroke(w * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(Path().apply { moveTo(0.2f * s, 0.7f * s); lineTo(0.2f * s, 0.8f * s); lineTo(0.8f * s, 0.8f * s); lineTo(0.8f * s, 0.7f * s) }, tint, style = Stroke(w * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
