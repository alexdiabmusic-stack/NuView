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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A frosted-glass container used by details, settings and dialogs. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val d = LocalDimens.current
    val shape = RoundedCornerShape(d.dp(24))
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.095f), Color.White.copy(alpha = 0.04f))), shape)
            .border(1.dp, Palette.Border, shape),
    ) { content() }
}

// ============================================================ details

@Composable
fun DetailsScreen(
    ui: DetailsUi,
    focusEpoch: Int,
    inputEnabled: Boolean,
    onAction: (app.tvessentials.core.state.CardAction) -> Unit,
    onBack: () -> Unit,
) {
    val d = LocalDimens.current
    val type = LocalType.current
    val c = ui.card.card
    val actions = buildList {
        add(c.primary)
        addAll(c.secondary)
        if (c.primary != app.tvessentials.core.state.CardAction.CHECK_UPDATES && app.tvessentials.core.state.CardAction.CHECK_UPDATES !in c.secondary && c.installed) add(app.tvessentials.core.state.CardAction.CHECK_UPDATES)
    }.filter { it != app.tvessentials.core.state.CardAction.NONE }.distinct()
    val primaryReq = remember { FocusRequester() }
    val backReq = remember { FocusRequester() }
    LaunchedEffect(focusEpoch) { (if (actions.isEmpty()) backReq else primaryReq).requestWhenReady() }

    Column(Modifier.fillMaxSize().focusProperties { canFocus = inputEnabled }.padding(horizontal = d.safeHorizontal, vertical = d.safeVertical)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton("Back", onBack, kind = ButtonKind.Quiet, glyph = null, focusRequester = backReq, modifier = Modifier.width(d.dp(120)))
            Spacer(Modifier.width(d.dp(16)))
            TvText("Details", type.overline, color = Palette.TextTertiary)
        }
        Spacer(Modifier.height(d.dp(18)))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(40))) {
            // left: identity
            Column(Modifier.weight(1.15f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconTile(ui.card, d.dp(112))
                    Spacer(Modifier.width(d.dp(24)))
                    Column {
                        TvText(ui.card.name, type.display, maxLines = 1)
                        Spacer(Modifier.height(d.dp(4)))
                        TvText("by ${ui.publisher} · ${ui.channel}", type.subtitle, color = Palette.TextSecondary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(d.dp(22)))
                TvText(ui.description, type.subtitle, color = Palette.TextPrimary.copy(alpha = 0.92f), maxLines = 4)
                ui.disclosure?.let {
                    Spacer(Modifier.height(d.dp(18)))
                    GlassPanel(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(d.dp(18)), verticalAlignment = Alignment.Top) {
                            GlyphIcon(Glyph.Info, Palette.Info, d.dp(26))
                            Spacer(Modifier.width(d.dp(14)))
                            TvText(it, type.body, color = Palette.TextSecondary, maxLines = 5)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TvText("Official website", type.overline, color = Palette.TextTertiary)
                Spacer(Modifier.height(d.dp(2)))
                TvText(ui.website, type.body, color = Palette.TextSecondary, maxLines = 1)
            }
            // right: facts + actions
            Column(Modifier.weight(1f)) {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = d.dp(26), vertical = d.dp(20))) {
                        StatusPill(UiText.statusLabel(c), UiText.statusColor(c.kind))
                        Spacer(Modifier.height(d.dp(14)))
                        Fact("Installed version", ui.installedVersion)
                        Fact("Latest verified version", ui.latestVersion)
                        Fact("Download size", ui.size)
                        Fact("Channel", ui.channel)
                        Fact("Last checked", ui.lastChecked)
                        Fact("Package", ui.packageId, last = true)
                    }
                }
                Spacer(Modifier.height(d.dp(20)))
                actions.forEachIndexed { i, a ->
                    TvButton(
                        UiText.actionLabel(a, c), { onAction(a) }, modifier = Modifier.fillMaxWidth().padding(bottom = d.dp(12)),
                        kind = if (i == 0) ButtonKind.Primary else ButtonKind.Secondary, accent = ui.card.accent,
                        enabled = if (i == 0) c.primaryEnabled else true,
                        focusRequester = if (i == 0) primaryReq else null,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, last: Boolean = false) {
    val d = LocalDimens.current
    val type = LocalType.current
    Row(Modifier.fillMaxWidth().padding(vertical = d.dp(7)), verticalAlignment = Alignment.CenterVertically) {
        TvText(label, type.body, color = Palette.TextTertiary, maxLines = 1, modifier = Modifier.weight(1f))
        TvText(value, type.bodyStrong, color = Palette.TextPrimary, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = d.dp(250)))
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
}

// ============================================================ settings

@Composable
fun SettingsScreen(
    ui: SettingsUi,
    focusEpoch: Int,
    inputEnabled: Boolean,
    onCheckUpdates: () -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onPreferStable: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    val d = LocalDimens.current
    val type = LocalType.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusEpoch) { first.requestWhenReady() }

    Column(Modifier.fillMaxSize().focusProperties { canFocus = inputEnabled }.padding(horizontal = d.safeHorizontal, vertical = d.safeVertical)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton("Back", onBack, kind = ButtonKind.Quiet, modifier = Modifier.width(d.dp(120)))
            Spacer(Modifier.width(d.dp(16)))
            TvText("Settings", type.title)
        }
        Spacer(Modifier.height(d.dp(18)))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(40))) {
            Column(Modifier.weight(1f)) {
                SectionLabel("General")
                SettingRow("Check for updates", "Last checked ${ui.lastChecked}", onClick = onCheckUpdates, focusRequester = first, glyph = Glyph.Refresh)
                SettingRow("Application version", ui.installerVersion, onClick = null)
                Spacer(Modifier.height(d.dp(18)))
                SectionLabel("Downloads")
                SettingRow("Automatic update checks", "Look for new versions when the app opens", toggle = ui.autoCheck, onClick = { onAutoCheck(!ui.autoCheck) })
                SettingRow("Prefer stable releases", "Turn off to follow beta builds where an app offers them", toggle = ui.preferStable, onClick = { onPreferStable(!ui.preferStable) })
                SettingRow("Clear downloaded APK cache", "Frees ${ui.cacheSize} of storage", onClick = onClearCache, glyph = Glyph.Stop)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("About")
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.dp(24))) {
                        TvText("${UiText.APP_NAME} ${ui.installerVersion}", type.cardTitle, maxLines = 1)
                        Spacer(Modifier.height(d.dp(10)))
                        TvText(UiText.INDEPENDENCE_NOTE, type.body, color = Palette.TextSecondary, maxLines = 4)
                        Spacer(Modifier.height(d.dp(16)))
                        Row(verticalAlignment = Alignment.Top) {
                            GlyphIcon(Glyph.Shield, Palette.Success, d.dp(26))
                            Spacer(Modifier.width(d.dp(12)))
                            TvText(UiText.SECURITY_NOTE, type.caption, color = Palette.TextSecondary, maxLines = 7)
                        }
                        Spacer(Modifier.height(d.dp(16)))
                        TvText("Third-party applications", type.overline, color = Palette.TextTertiary)
                        Spacer(Modifier.height(d.dp(6)))
                        ui.attributions.forEach { (n, who) -> TvText("$n  ·  $who", type.caption, color = Palette.TextSecondary, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val d = LocalDimens.current
    TvText(text.uppercase(), LocalType.current.overline, color = Palette.TextTertiary, modifier = Modifier.padding(bottom = d.dp(8), start = d.dp(4)))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    toggle: Boolean? = null,
    glyph: Glyph? = null,
    focusRequester: FocusRequester? = null,
) {
    val d = LocalDimens.current
    val type = LocalType.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(d.dp(16))
    val rowMod = Modifier.fillMaxWidth().padding(bottom = d.dp(8)).clip(shape)
        .background(if (focused) Palette.TextPrimary else Color.White.copy(alpha = 0.06f), shape)
        .border(d.dp(if (focused) 2.5f else 1f), if (focused) Palette.BorderFocused else Palette.Border, shape)
    val fg = if (focused) Palette.Background else Palette.TextPrimary
    val sub = if (focused) Palette.Background.copy(alpha = 0.7f) else Palette.TextTertiary
    val interactive = if (onClick != null) rowMod.tvFocusable(true, focusRequester, "$title. $subtitle" + (toggle?.let { if (it) ". On" else ". Off" } ?: ""), onFocusChange = { focused = it }, onClick = onClick) else rowMod
    Row(interactive.padding(horizontal = d.dp(20), vertical = d.dp(14)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TvText(title, type.bodyStrong, color = fg, maxLines = 1)
            TvText(subtitle, type.caption, color = sub, maxLines = 1)
        }
        if (toggle != null) {
            val on = toggle
            val pill = RoundedCornerShape(50)
            Box(
                Modifier.clip(pill).background(if (on) Palette.Success.copy(alpha = if (focused) 1f else 0.28f) else Color.White.copy(alpha = if (focused) 0.18f else 0.10f), pill)
                    .padding(horizontal = d.dp(16), vertical = d.dp(6)),
            ) { TvText(if (on) "On" else "Off", type.captionStrong, color = if (on && focused) Palette.Background else if (on) Palette.Success else fg, maxLines = 1) }
        } else if (glyph != null) {
            GlyphIcon(glyph, fg, d.dp(24))
        }
    }
}

// ============================================================ modals

/** Dim backdrop plus a centered panel. Focus starts on the first button; Back dismisses (handled by the host). */
@Composable
fun ModalFrame(title: String, glyph: Glyph, tint: Color, content: @Composable () -> Unit) {
    val d = LocalDimens.current
    Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.widthIn(max = d.dp(760)).fillMaxWidth(0.62f).background(Palette.Background.copy(alpha = 0.55f), RoundedCornerShape(d.dp(24)))) {
            Column(Modifier.padding(d.dp(34))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(glyph, tint, d.dp(34))
                    Spacer(Modifier.width(d.dp(16)))
                    TvText(title, LocalType.current.title, maxLines = 2)
                }
                Spacer(Modifier.height(d.dp(16)))
                content()
            }
        }
    }
}

@Composable
fun DialogButtons(vararg buttons: Triple<String, ButtonKind, () -> Unit>) {
    val d = LocalDimens.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Row(Modifier.fillMaxWidth().padding(top = d.dp(26)), horizontalArrangement = Arrangement.spacedBy(d.dp(14))) {
        buttons.forEachIndexed { i, (label, kind, action) ->
            TvButton(label, action, modifier = Modifier.weight(1f), kind = kind, focusRequester = if (i == 0) first else null)
        }
    }
}

@Composable
fun PermissionDialog(appName: String, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val type = LocalType.current
    ModalFrame("Allow installs from ${UiText.APP_NAME}", Glyph.Shield, Palette.Warning) {
        TvText("Android needs your permission before ${UiText.APP_NAME} can install $appName.", type.subtitle, color = Palette.TextPrimary, maxLines = 3)
        Spacer(Modifier.height(LocalDimens.current.dp(10)))
        TvText("Choose Open Settings, switch on \"Allow from this source\", then press Back. We'll pick up right where you left off; you won't need to start over.", type.body, color = Palette.TextSecondary, maxLines = 4)
        DialogButtons(Triple("Open Settings", ButtonKind.Primary, onOpenSettings), Triple("Not now", ButtonKind.Secondary, onDismiss))
    }
}

@Composable
fun GuideDialog(appName: String, steps: List<String>, code: String?, officialUrl: String, onOpenPage: () -> Unit, onDismiss: () -> Unit) {
    val d = LocalDimens.current
    val type = LocalType.current
    ModalFrame("Install $appName with Downloader", Glyph.Info, Palette.Info) {
        TvText("This app's official download can't be fetched automatically, so here's the quickest manual route.", type.body, color = Palette.TextSecondary, maxLines = 3)
        Spacer(Modifier.height(d.dp(12)))
        steps.forEachIndexed { i, s -> TvText("${i + 1}.  $s", type.body, color = Palette.TextPrimary, maxLines = 3, modifier = Modifier.padding(bottom = d.dp(4))) }
        if (code != null) {
            Spacer(Modifier.height(d.dp(12)))
            val shape = RoundedCornerShape(d.dp(14))
            Row(Modifier.clip(shape).background(Color.White.copy(alpha = 0.10f), shape).border(1.dp, Palette.Border, shape).padding(horizontal = d.dp(20), vertical = d.dp(10)), verticalAlignment = Alignment.CenterVertically) {
                TvText("Downloader code", type.caption, color = Palette.TextTertiary)
                Spacer(Modifier.width(d.dp(16)))
                TvText(code, type.title.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Palette.TextPrimary)
            }
        }
        Spacer(Modifier.height(d.dp(8)))
        TvText("Official page: $officialUrl", type.caption, color = Palette.TextTertiary, maxLines = 1)
        DialogButtons(Triple("Open official page", ButtonKind.Primary, onOpenPage), Triple("Back", ButtonKind.Secondary, onDismiss))
    }
}

@Composable
fun ErrorDialog(title: String, body: String, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val type = LocalType.current
    ModalFrame(title, Glyph.Warning, Palette.Danger) {
        TvText(body, type.subtitle, color = Palette.TextSecondary, maxLines = 5)
        if (canRetry) DialogButtons(Triple("Try Again", ButtonKind.Primary, onRetry), Triple("Back", ButtonKind.Secondary, onDismiss))
        else DialogButtons(Triple("Back", ButtonKind.Primary, onDismiss))
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalFrame(title, Glyph.Stop, Palette.Warning) {
        TvText(body, LocalType.current.subtitle, color = Palette.TextSecondary, maxLines = 4)
        DialogButtons(Triple(confirmLabel, ButtonKind.Primary, onConfirm), Triple("Cancel", ButtonKind.Secondary, onDismiss))
    }
}
