package app.tvessentials.installer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.CardKind
import app.tvessentials.core.state.CardModel

/**
 * One app on the home screen. The whole card is the focus target and Select runs its primary action; the
 * button inside is a visual that becomes solid white when the card is focused so the action is always obvious.
 */
@Composable
fun AppCard(
    ui: AppCardUi,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val d = LocalDimens.current
    val type = LocalType.current
    var focused by remember { mutableStateOf(false) }
    val fv = rememberFocusVisual(focused, 1.05f)
    val shape = RoundedCornerShape(d.dp(26))
    val c = ui.card
    val g = fv.glow

    Box(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = fv.scale; scaleY = fv.scale
                shadowElevation = 36f * g
                this.shape = shape
                clip = false
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .drawBehind {
                // A soft accent bloom behind the focused card; invisible when unfocused.
                if (g > 0.01f) drawRect(
                    Brush.radialGradient(listOf(ui.accent.copy(alpha = 0.30f * g), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.85f),
                    topLeft = Offset(-size.width * 0.25f, -size.height * 0.2f), size = androidx.compose.ui.geometry.Size(size.width * 1.5f, size.height * 1.4f),
                )
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(Palette.GlassTop, Palette.GlassFocusTop, g), lerp(Palette.GlassBottom, Palette.GlassFocusBottom, g))), shape)
            .background(Brush.radialGradient(listOf(ui.accent.copy(alpha = 0.10f + 0.12f * g), Color.Transparent), center = Offset(0f, 0f), radius = 900f), shape)
            .border(d.dp(1f + 2.2f * g), lerp(Palette.Border, Palette.BorderFocused, g), shape)
            .tvFocusable(
                enabled = true, focusRequester = focusRequester, description = UiText.a11y(ui),
                onFocusChange = { focused = it; onFocusChange(it) }, onMenu = onMenu, onClick = onClick,
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = d.dp(24), vertical = d.dp(22))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                AppIconTile(ui, d.dp(76))
                Spacer(Modifier.weight(1f))
                if (ui.channelLabel.isNotEmpty()) ChannelBadge(ui.channelLabel)
            }
            Spacer(Modifier.height(d.dp(14)))
            TvText(ui.name, type.cardTitle, maxLines = 1)
            Spacer(Modifier.height(d.dp(4)))
            TvText(ui.tagline, type.body, color = Palette.TextSecondary, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(d.dp(10)))
            CardBody(ui, c)
            Spacer(Modifier.height(d.dp(14)))
            ActionVisual(c, ui, focused)
        }
    }
}

@Composable
private fun ChannelBadge(label: String) {
    val d = LocalDimens.current
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(Color.White.copy(alpha = 0.10f), shape).border(1.dp, Palette.Border, shape).padding(horizontal = d.dp(10), vertical = d.dp(4))) {
        TvText(label.uppercase(), LocalType.current.overline, color = Palette.TextSecondary, maxLines = 1)
    }
}

/** The state-specific middle of the card: progress while working, version facts while resting. */
@Composable
private fun CardBody(ui: AppCardUi, c: CardModel) {
    val d = LocalDimens.current
    val type = LocalType.current
    when (c.kind) {
        CardKind.DOWNLOADING, CardKind.DOWNLOAD_PAUSED -> {
            val p = c.progress
            TvText(if (c.kind == CardKind.DOWNLOADING) "Downloading ${ui.name}" else "Download paused", type.bodyStrong, maxLines = 1)
            Spacer(Modifier.height(d.dp(10)))
            TvProgressBar(p?.fraction, ui.accent)
            Spacer(Modifier.height(d.dp(8)))
            Row(Modifier.fillMaxWidth()) {
                TvText(p?.fraction?.let { Format.percent(it) } ?: "Working…", type.captionStrong, color = Palette.TextPrimary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                TvText(Format.progressPair(p?.downloadedBytes ?: 0, p?.totalBytes), type.caption, color = Palette.TextSecondary, maxLines = 1)
            }
        }
        CardKind.VERIFYING, CardKind.INSTALLING -> {
            TvText(if (c.kind == CardKind.VERIFYING) "Verifying download" else "Installing ${ui.name}", type.bodyStrong, maxLines = 1)
            Spacer(Modifier.height(d.dp(10)))
            TvProgressBar(null, ui.accent)
            Spacer(Modifier.height(d.dp(8)))
            TvText(if (c.kind == CardKind.VERIFYING) "Checking the publisher's signature" else "Confirm in the Android window", type.caption, color = Palette.TextSecondary, maxLines = 1)
        }
        CardKind.READY_TO_INSTALL -> {
            StatusPill("Download complete", Palette.Success)
            Spacer(Modifier.height(d.dp(6)))
            TvText("Ready to install", type.bodyStrong, maxLines = 1)
            TvText("Press Select to continue", type.caption, color = Palette.TextSecondary, maxLines = 1)
        }
        CardKind.DOWNLOAD_FAILED, CardKind.INSTALL_FAILED, CardKind.SOURCE_UNAVAILABLE, CardKind.SIGNER_MISMATCH,
        CardKind.UNSUPPORTED_DEVICE, CardKind.NEEDS_PERMISSION, CardKind.GUIDED_ONLY -> {
            StatusPill(UiText.statusLabel(c), UiText.statusColor(c.kind))
            c.error?.let {
                Spacer(Modifier.height(d.dp(6)))
                TvText(UiText.errorBody(it, ui.name), type.caption, color = Palette.TextSecondary, maxLines = 3)
            }
            if (c.kind == CardKind.DOWNLOAD_FAILED) {
                c.progress?.let { Spacer(Modifier.height(d.dp(6))); TvProgressBar(it.fraction, Palette.Danger) }
            }
        }
        else -> {
            StatusPill(UiText.statusLabel(c), UiText.statusColor(c.kind), pulse = c.kind == CardKind.CHECKING)
            Spacer(Modifier.height(d.dp(8)))
            UiText.installedLine(c)?.let { TvText(it, type.caption, color = Palette.TextSecondary, maxLines = 1) }
            TvText(UiText.availableLine(c), type.caption, color = if (c.versionNote == app.tvessentials.core.state.VersionNote.UNVERIFIED) Palette.Warning else Palette.TextSecondary, maxLines = 2)
        }
    }
}

private fun glyphFor(a: CardAction): Glyph? = when (a) {
    CardAction.INSTALL, CardAction.UPDATE, CardAction.INSTALL_NOW, CardAction.RESUME -> Glyph.Download
    CardAction.OPEN -> Glyph.Open
    CardAction.CANCEL -> Glyph.Stop
    CardAction.RETRY -> Glyph.Refresh
    CardAction.SHOW_GUIDE, CardAction.OPEN_APP_INFO -> Glyph.Info
    CardAction.OPEN_INSTALL_SETTINGS -> Glyph.Sliders
    else -> null
}

/** Not focusable itself: it mirrors the card's focus so the primary action is unmistakable. */
@Composable
private fun ActionVisual(c: CardModel, ui: AppCardUi, cardFocused: Boolean) {
    val d = LocalDimens.current
    val enabled = c.primaryEnabled
    val shape = RoundedCornerShape(d.dp(14))
    val bg = when {
        !enabled -> Color.White.copy(alpha = 0.05f)
        cardFocused -> Palette.TextPrimary
        else -> ui.accent.copy(alpha = 0.28f)
    }
    val fg = when {
        !enabled -> Palette.TextTertiary
        cardFocused -> Palette.Background
        else -> Color.White
    }
    Box(
        Modifier.fillMaxWidth().height(d.dp(50)).clip(shape).background(bg, shape)
            .border(d.dp(if (cardFocused && enabled) 0f else 1f), if (enabled) ui.accent.copy(alpha = 0.55f) else Palette.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            glyphFor(c.primary)?.takeIf { enabled }?.let { GlyphIcon(it, fg, d.dp(22)); Spacer(Modifier.width(d.dp(10))) }
            TvText(UiText.primaryLabel(c), LocalType.current.button, color = fg, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}
