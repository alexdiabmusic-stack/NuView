package app.tvessentials.installer.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Requests focus once the target is actually on screen; retries for a few frames because layout may not have run yet. */
suspend fun FocusRequester.requestWhenReady() {
    repeat(8) {
        if (runCatching { requestFocus() }.isSuccess) return
        withFrameNanos { }
    }
}

@Composable
fun HomeScreen(
    ui: HomeUi,
    focus: HomeFocus,
    focusEpoch: Int,
    inputEnabled: Boolean,
    onFocus: (HomeFocus) -> Unit,
    onCardClick: (Int) -> Unit,
    onCardMenu: (Int) -> Unit,
    onDetails: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val d = LocalDimens.current
    val type = LocalType.current
    val n = ui.cards.size
    val cardReq = remember(n) { List(n) { FocusRequester() } }
    val detailsReq = remember(n) { List(n) { FocusRequester() } }
    val refreshReq = remember { FocusRequester() }
    val settingsReq = remember { FocusRequester() }

    // Land focus where the user left it (first launch, return from details/settings/dialogs/system screens).
    LaunchedEffect(focusEpoch, n) {
        val target = when (focus) {
            is HomeFocus.Card -> cardReq.getOrNull(focus.index)
            is HomeFocus.Details -> detailsReq.getOrNull(focus.index)
            HomeFocus.Refresh -> refreshReq
            HomeFocus.Settings -> settingsReq
        } ?: cardReq.firstOrNull()
        target?.requestWhenReady()
    }

    Column(
        Modifier.fillMaxSize()
            .focusProperties { canFocus = inputEnabled }
            .padding(horizontal = d.safeHorizontal, vertical = d.safeVertical),
    ) {
        // ---- header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(d.dp(46))
            Spacer(Modifier.width(d.dp(14)))
            Column {
                TvText(UiText.APP_NAME, type.cardTitle.copy(fontSize = d.sp(22f)), maxLines = 1)
                TvText(UiText.TAGLINE, type.caption, color = Palette.TextTertiary, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            ConnectionPill(ui.online)
            Spacer(Modifier.width(d.dp(14)))
            TvIconButton(
                Glyph.Refresh, "Refresh", onRefresh, spinning = ui.checking, focusRequester = refreshReq,
                modifier = Modifier.focusProperties { down = cardReq.getOrNull((focus as? HomeFocus.Card)?.index ?: 1) ?: FocusRequester.Default; right = settingsReq },
                onFocusChange = { if (it) onFocus(HomeFocus.Refresh) },
            )
            Spacer(Modifier.width(d.dp(10)))
            TvIconButton(
                Glyph.Sliders, "Settings", onSettings, focusRequester = settingsReq,
                modifier = Modifier.focusProperties { down = cardReq.getOrNull((focus as? HomeFocus.Card)?.index ?: 1) ?: FocusRequester.Default; left = refreshReq },
                onFocusChange = { if (it) onFocus(HomeFocus.Settings) },
            )
        }

        Spacer(Modifier.height(d.dp(22)))

        // ---- hero
        TvText(UiText.HERO_TITLE, type.display, maxLines = 1)
        Spacer(Modifier.height(d.dp(6)))
        TvText(UiText.HERO_SUBTITLE, type.subtitle, color = Palette.TextSecondary, maxLines = 1)
        ui.banner?.let {
            Spacer(Modifier.height(d.dp(6)))
            TvText(it, type.caption, color = Palette.Warning, maxLines = 1)
        }

        Spacer(Modifier.height(d.dp(24)))

        // ---- cards
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(28))) {
            ui.cards.forEachIndexed { i, card ->
                Column(Modifier.weight(1f).fillMaxSize()) {
                    AppCard(
                        ui = card,
                        onClick = { onCardClick(i) },
                        onMenu = { onCardMenu(i) },
                        focusRequester = cardReq[i],
                        modifier = Modifier.weight(1f).fillMaxWidth().focusProperties {
                            up = if (i >= n - 1) settingsReq else refreshReq
                            down = detailsReq[i]
                        },
                        onFocusChange = { if (it) onFocus(HomeFocus.Card(i)) },
                    )
                    Spacer(Modifier.height(d.dp(8)))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TvLink(
                            "Details", { onDetails(i) }, focusRequester = detailsReq[i],
                            modifier = Modifier.focusProperties { up = cardReq[i] },
                            onFocusChange = { if (it) onFocus(HomeFocus.Details(i)) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(d.dp(10)))

        // ---- footer
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvText(UiText.FOOTER, type.caption, color = Palette.TextTertiary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            TvText("v${ui.installerVersion}", type.caption, color = Palette.TextTertiary, maxLines = 1)
        }
    }
}

@Composable
fun ConnectionPill(online: Boolean) {
    val d = LocalDimens.current
    val color = if (online) Palette.Success else Palette.Warning
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(Color.White.copy(alpha = 0.06f), shape).border(1.dp, Palette.Border, shape)
            .padding(horizontal = d.dp(14), vertical = d.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(if (online) Glyph.Online else Glyph.Offline, color, d.dp(22))
        Spacer(Modifier.width(d.dp(8)))
        TvText(if (online) "Online" else "Offline", LocalType.current.captionStrong, color = color, maxLines = 1)
    }
}
