package app.tvessentials.installer

import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.state.AppState
import app.tvessentials.core.state.CardDeriver
import app.tvessentials.core.state.CardKind
import app.tvessentials.core.state.Phase
import app.tvessentials.core.state.VersionNote
import app.tvessentials.installer.ui.AppCardUi
import app.tvessentials.installer.ui.DetailsUi
import app.tvessentials.installer.ui.Format
import app.tvessentials.installer.ui.Glyph
import app.tvessentials.installer.ui.GuideUi
import app.tvessentials.installer.ui.Palette
import androidx.compose.ui.graphics.ImageBitmap

/** Turns installer state into display models. Pure, so it is covered by plain unit tests. */
object UiMapper {

    fun glyph(iconKey: String): Glyph = when (iconKey) {
        "library" -> Glyph.Library
        "antenna" -> Glyph.Antenna
        "play" -> Glyph.Play
        else -> Glyph.Download
    }

    fun card(s: AppState, icon: ImageBitmap?): AppCardUi {
        val app = s.app
        return AppCardUi(
            card = CardDeriver.derive(s),
            name = app.displayName,
            tagline = app.tagline,
            glyph = glyph(app.iconKey),
            accent = Palette.accent(app.accentColor),
            channelLabel = if (s.channel == Channel.BETA && Channel.BETA in app.channels) "Beta" else "",
            officialIcon = icon,
            guide = app.guided?.let { GuideUi(it.officialUrl, it.downloaderCode, it.steps) },
        )
    }

    fun details(s: AppState, icon: ImageBitmap?, nowMs: Long): DetailsUi {
        val c = card(s, icon)
        val model = c.card
        return DetailsUi(
            card = c,
            description = s.app.description,
            publisher = s.app.publisher,
            website = s.app.websiteUrl,
            disclosure = s.app.disclosure,
            installedVersion = s.installed?.let { it.versionName ?: "Installed" } ?: "Not installed",
            latestVersion = when (model.versionNote) {
                VersionNote.VERIFIED -> model.availableVersion.orEmpty()
                VersionNote.UNVERIFIED -> "Can't be verified"
                VersionNote.NONE -> if (model.kind == CardKind.CHECKING) "Checking…" else "Unavailable"
            },
            size = model.sizeBytes?.let(Format::bytes) ?: "Unknown",
            channel = if (s.channel == Channel.BETA) "Beta" else "Stable",
            lastChecked = Format.ago(nowMs, s.checkedAtMs),
            packageId = s.packageId.orEmpty(),
            signer = s.app.trust.signers.firstOrNull()?.label.orEmpty(),
        )
    }

    /** Any state in which the screen should be kept awake so a long download isn't interrupted by the screensaver. */
    fun isBusy(s: AppState): Boolean = s.phase is Phase.Downloading || s.phase is Phase.Verifying || s.phase is Phase.Installing
}
