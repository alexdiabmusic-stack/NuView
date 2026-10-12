package app.tvessentials.installer.ui

import androidx.compose.ui.graphics.Color
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.CardKind
import app.tvessentials.core.state.CardModel
import app.tvessentials.core.state.ErrorKind
import app.tvessentials.core.state.VersionNote

/** All user-facing wording in one place: plain, calm, never a stack trace. */
object UiText {
    const val APP_NAME = "TV Essentials"
    const val TAGLINE = "Your apps, made simple."
    const val HERO_TITLE = "Everything you need. One place."
    const val HERO_SUBTITLE = "Install and keep your favourite TV apps up to date."
    const val FOOTER = "Only install apps from sources you trust."

    const val SECURITY_NOTE =
        "TV Essentials downloads each app straight from its publisher's official source over HTTPS, checks it against " +
            "the publisher's reviewed signing certificate, and then hands it to Android, which always asks you to confirm. " +
            "It never installs anything silently."
    const val INDEPENDENCE_NOTE =
        "TV Essentials is an independent installer. It is not affiliated with, endorsed by, or sponsored by the publishers of the apps it lists."

    fun statusLabel(c: CardModel, afterCancel: Boolean = false): String = when (c.kind) {
        CardKind.CHECKING -> "Checking…"
        CardKind.INSTALL_AVAILABLE -> "Not installed"
        CardKind.GUIDED_ONLY -> "Not installed"
        CardKind.DOWNLOADING -> "Downloading"
        CardKind.DOWNLOAD_PAUSED -> "Download paused"
        CardKind.DOWNLOAD_FAILED -> "Download interrupted"
        CardKind.VERIFYING -> "Verifying download"
        CardKind.READY_TO_INSTALL -> if (afterCancel) "Installation cancelled" else "Ready to install"
        CardKind.INSTALLING -> "Installing"
        CardKind.NEEDS_PERMISSION -> "Permission needed"
        CardKind.INSTALL_FAILED -> c.error?.let { errorTitle(it) } ?: "Installation failed"
        CardKind.JUST_INSTALLED -> "Installed"
        CardKind.INSTALLED -> "Installed"
        CardKind.UPDATE_AVAILABLE -> "Update available"
        CardKind.UP_TO_DATE -> "Up to date"
        CardKind.SIGNER_MISMATCH -> "Different signature"
        CardKind.UNSUPPORTED_DEVICE -> "Not supported"
        CardKind.SOURCE_UNAVAILABLE -> c.error?.let { errorTitle(it) } ?: "Source unavailable"
    }

    fun statusColor(kind: CardKind): Color = when (kind) {
        CardKind.UP_TO_DATE, CardKind.JUST_INSTALLED, CardKind.INSTALLED -> Palette.Success
        CardKind.UPDATE_AVAILABLE, CardKind.READY_TO_INSTALL -> Palette.Info
        CardKind.DOWNLOADING, CardKind.VERIFYING, CardKind.INSTALLING, CardKind.CHECKING -> Palette.Info
        CardKind.DOWNLOAD_PAUSED, CardKind.NEEDS_PERMISSION, CardKind.GUIDED_ONLY -> Palette.Warning
        CardKind.DOWNLOAD_FAILED, CardKind.INSTALL_FAILED, CardKind.SIGNER_MISMATCH, CardKind.SOURCE_UNAVAILABLE, CardKind.UNSUPPORTED_DEVICE -> Palette.Danger
        CardKind.INSTALL_AVAILABLE -> Palette.TextTertiary
    }

    fun primaryLabel(c: CardModel): String = when (c.primary) {
        CardAction.INSTALL -> "Install"
        CardAction.UPDATE -> "Update"
        CardAction.OPEN -> if (c.kind == CardKind.UP_TO_DATE) "Open" else "Open App"
        CardAction.CANCEL -> "Cancel"
        CardAction.RETRY -> "Try Again"
        CardAction.RESUME -> "Resume"
        CardAction.INSTALL_NOW -> "Install"
        CardAction.OPEN_INSTALL_SETTINGS -> "Open Settings"
        CardAction.SHOW_GUIDE -> if (c.kind == CardKind.UPDATE_AVAILABLE) "How to update" else "How to install"
        CardAction.CHECK_UPDATES -> "Check for Updates"
        CardAction.OPEN_APP_INFO -> "App Info"
        CardAction.DISMISS -> "Back"
        CardAction.NONE -> when (c.kind) {
            CardKind.CHECKING -> "Checking…"
            CardKind.VERIFYING -> "Verifying…"
            CardKind.INSTALLING -> "Installing…"
            CardKind.UNSUPPORTED_DEVICE -> "Unavailable"
            else -> "Please wait"
        }
    }

    fun actionLabel(a: CardAction, c: CardModel? = null): String = when (a) {
        CardAction.OPEN -> "Open App"
        CardAction.CHECK_UPDATES -> "Check for Updates"
        CardAction.SHOW_GUIDE -> "How to install"
        CardAction.OPEN_APP_INFO -> "App Info"
        else -> primaryLabel((c ?: error("needs card")).copy(primary = a))
    }

    /** First line: what is on the device. Second: what is available, never an unverified guess. */
    fun installedLine(c: CardModel): String? = c.installedVersion?.let { "Installed $it" } ?: if (c.installed) "Installed" else null

    fun availableLine(c: CardModel, includeSize: Boolean = true): String {
        val size = if (includeSize) c.sizeBytes?.let { " · ${Format.bytes(it)}" }.orEmpty() else ""
        return when (c.versionNote) {
            VersionNote.VERIFIED -> "Latest ${c.availableVersion}$size"
            VersionNote.UNVERIFIED -> "Latest version can't be verified" + if (includeSize) c.sizeBytes?.let { " · ${Format.bytes(it)}" }.orEmpty() else ""
            VersionNote.NONE -> if (c.kind == CardKind.CHECKING) "Looking for the latest version…" else "Latest version unavailable"
        }
    }

    fun errorTitle(e: ErrorKind): String = when (e) {
        ErrorKind.NO_INTERNET -> "No internet connection"
        ErrorKind.SLOW_CONNECTION -> "Connection too slow"
        ErrorKind.RATE_LIMITED -> "Too many update checks"
        ErrorKind.SOURCE_UNAVAILABLE -> "Download source unavailable"
        ErrorKind.BROWSER_CHECK_REQUIRED -> "Browser check required"
        ErrorKind.NO_COMPATIBLE_RELEASE -> "No compatible version"
        ErrorKind.DOWNLOAD_INTERRUPTED -> "Download interrupted"
        ErrorKind.INSUFFICIENT_STORAGE -> "Not enough storage"
        ErrorKind.INVALID_APK -> "Invalid download"
        ErrorKind.WRONG_PACKAGE -> "Wrong app package"
        ErrorKind.SIGNATURE_FAILED -> "Signature check failed"
        ErrorKind.SIGNER_MISMATCH -> "Different signing key"
        ErrorKind.NOT_REVIEWED -> "Guided install only"
        ErrorKind.UNSUPPORTED_DEVICE -> "Not supported on this device"
        ErrorKind.PERMISSION_DENIED -> "Permission needed"
        ErrorKind.INSTALL_BLOCKED -> "Installation blocked"
        ErrorKind.INSTALL_CONFLICT -> "Can't update this copy"
        ErrorKind.INSTALL_INCOMPATIBLE -> "Not compatible"
        ErrorKind.INSTALL_FAILED -> "Installation didn't finish"
        ErrorKind.INSTALL_NOT_CONFIRMED -> "Couldn't confirm the installation"
        ErrorKind.INSTALL_CANCELLED -> "Installation cancelled"
        ErrorKind.ALREADY_CURRENT -> "Already up to date"
        ErrorKind.DOWNGRADE_BLOCKED -> "Newer version installed"
        ErrorKind.UNEXPECTED -> "Something went wrong"
    }

    fun errorBody(e: ErrorKind, app: String, needMb: Long? = null): String = when (e) {
        ErrorKind.NO_INTERNET -> "Connect your TV to the internet, then try again."
        ErrorKind.SLOW_CONNECTION -> "We couldn't reach the server in time. Check your connection and try again."
        ErrorKind.RATE_LIMITED -> "The download service is limiting requests from your network for a short while. We'll check again automatically later."
        ErrorKind.SOURCE_UNAVAILABLE -> "The official download for $app isn't responding right now. Nothing was installed. Try again in a little while."
        ErrorKind.BROWSER_CHECK_REQUIRED -> "$app's official site asks for a browser check before it will serve the file, which a TV app can't complete. Use the guided steps to install it."
        ErrorKind.NO_COMPATIBLE_RELEASE -> "None of the recent releases of $app has a version that fits this device."
        ErrorKind.DOWNLOAD_INTERRUPTED -> "We couldn't finish downloading $app. Check your connection and try again."
        ErrorKind.INSUFFICIENT_STORAGE -> if (needMb != null) "$app needs about $needMb MB free to install. Remove an app or some files, then try again." else "There isn't enough free space to install $app. Remove an app or some files, then try again."
        ErrorKind.INVALID_APK -> "The downloaded file isn't a valid app package, so it was discarded. Nothing was installed."
        ErrorKind.WRONG_PACKAGE -> "The downloaded file doesn't belong to $app, so it was discarded. Nothing was installed."
        ErrorKind.SIGNATURE_FAILED -> "This file isn't signed by $app's publisher, so it was blocked and discarded. Nothing was installed."
        ErrorKind.SIGNER_MISMATCH -> "The copy of $app on this device was signed with a different key than the official one. To install the official version, uninstall the current copy first (Settings › Applications)."
        ErrorKind.NOT_REVIEWED -> "We can't verify $app's signing key yet, so it isn't installed automatically. Use the guided steps."
        ErrorKind.UNSUPPORTED_DEVICE -> "$app can't run on this device."
        ErrorKind.PERMISSION_DENIED -> "Allow TV Essentials to install apps, then come back to continue."
        ErrorKind.INSTALL_BLOCKED -> "Your device blocked the installation. Check Settings › Security for restrictions on installing apps."
        ErrorKind.INSTALL_CONFLICT -> "Android won't update the copy of $app on this device with this file. Uninstall the current copy first, then install again."
        ErrorKind.INSTALL_INCOMPATIBLE -> "This build of $app isn't compatible with your device."
        ErrorKind.INSTALL_FAILED -> "Android couldn't complete the installation of $app. Try again."
        ErrorKind.INSTALL_NOT_CONFIRMED -> "Android didn't report $app as installed. Nothing has changed. Try again."
        ErrorKind.INSTALL_CANCELLED -> "You cancelled the installation. The download is ready whenever you want to continue."
        ErrorKind.ALREADY_CURRENT -> "$app is already up to date."
        ErrorKind.DOWNGRADE_BLOCKED -> "A newer version of $app is already installed, so nothing was changed."
        ErrorKind.UNEXPECTED -> "Please try again. If it keeps happening, restart the app."
    }

    fun retryable(e: ErrorKind): Boolean = when (e) {
        ErrorKind.NO_INTERNET, ErrorKind.SLOW_CONNECTION, ErrorKind.RATE_LIMITED, ErrorKind.SOURCE_UNAVAILABLE, ErrorKind.DOWNLOAD_INTERRUPTED,
        ErrorKind.INSUFFICIENT_STORAGE, ErrorKind.INSTALL_FAILED, ErrorKind.INSTALL_NOT_CONFIRMED, ErrorKind.UNEXPECTED, ErrorKind.INSTALL_BLOCKED, ErrorKind.INVALID_APK -> true
        else -> false
    }

    fun a11y(c: AppCardUi): String = buildString {
        append(c.name).append(", ").append(statusLabel(c.card)).append(". ")
        installedLine(c.card)?.let { append(it).append(". ") }
        append(availableLine(c.card)).append(". ")
        append(primaryLabel(c.card).trimEnd('…'))
    }
}
