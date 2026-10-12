package app.tvessentials.core.state

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.source.ResolvedRelease
import app.tvessentials.core.source.SourceFailure
import java.io.File

/** Why something did not work, in terms the UI turns into plain, friendly language. Never carries raw exceptions. */
enum class ErrorKind {
    NO_INTERNET,
    SLOW_CONNECTION,
    RATE_LIMITED,
    SOURCE_UNAVAILABLE,
    BROWSER_CHECK_REQUIRED,
    NO_COMPATIBLE_RELEASE,
    DOWNLOAD_INTERRUPTED,
    INSUFFICIENT_STORAGE,
    INVALID_APK,
    WRONG_PACKAGE,
    SIGNATURE_FAILED,
    SIGNER_MISMATCH,
    NOT_REVIEWED,
    UNSUPPORTED_DEVICE,
    PERMISSION_DENIED,
    INSTALL_BLOCKED,
    INSTALL_CONFLICT,
    INSTALL_INCOMPATIBLE,
    INSTALL_FAILED,
    INSTALL_NOT_CONFIRMED,
    INSTALL_CANCELLED,
    ALREADY_CURRENT,
    DOWNGRADE_BLOCKED,
    UNEXPECTED,
}

/** A verified APK on disk, ready to hand to the system installer. */
data class StagedApk(val file: File, val release: ResolvedRelease, val archive: ArchiveInfo, val sha256: String)

/** What the installer is doing for one app right now. */
sealed interface Phase {
    data object Idle : Phase
    data object Preparing : Phase
    data object NeedsInstallPermission : Phase
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : Phase

    /** The user stopped the download; the partial file is kept and can be resumed. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long?) : Phase
    data class DownloadFailed(val error: ErrorKind, val downloadedBytes: Long, val totalBytes: Long?) : Phase
    data object Verifying : Phase
    data class ReadyToInstall(val staged: StagedApk, val afterCancel: Boolean = false) : Phase
    data object Installing : Phase
    data class Succeeded(val versionName: String?) : Phase
    data class Failed(val error: ErrorKind) : Phase
}

/** Everything known about one app. The UI never reads anything else. */
data class AppState(
    val app: AppDefinition,
    val channel: Channel,
    val installed: InstalledPackage?,
    /** Whether the installed copy is signed by a reviewed certificate; null when no policy exists to judge by. */
    val installedSignerTrusted: Boolean?,
    val release: ResolvedRelease?,
    val checking: Boolean,
    val releaseFailure: SourceFailure?,
    val checkedAtMs: Long?,
    val phase: Phase,
    val supported: Boolean,
) {
    val packageId: String? get() = app.packageId(channel)
    /** Automatic installs require a reviewed signing policy; without one only the guided route is offered. */
    val canAutoInstall: Boolean get() = app.trust.signers.isNotEmpty()
}

enum class CardAction {
    NONE,
    INSTALL,
    UPDATE,
    OPEN,
    CANCEL,
    RETRY,
    RESUME,
    INSTALL_NOW,
    OPEN_INSTALL_SETTINGS,
    SHOW_GUIDE,
    CHECK_UPDATES,
    OPEN_APP_INFO,
    DISMISS,
}

enum class CardKind {
    CHECKING,
    INSTALL_AVAILABLE,
    GUIDED_ONLY,
    DOWNLOADING,
    DOWNLOAD_PAUSED,
    DOWNLOAD_FAILED,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALLING,
    NEEDS_PERMISSION,
    INSTALL_FAILED,
    JUST_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    SIGNER_MISMATCH,
    UNSUPPORTED_DEVICE,
    SOURCE_UNAVAILABLE,
}

enum class VersionNote {
    /** The version comes from the official source's own metadata. */
    VERIFIED,

    /** A source exists but its version can't be confirmed; the UI must say so, never show a guess. */
    UNVERIFIED,
    NONE,
}

data class ProgressModel(val downloadedBytes: Long, val totalBytes: Long?) {
    /** 0..1, or null when the total is unknown (indeterminate). */
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

/** Presentation-ready description of one card, derived purely from [AppState]. */
data class CardModel(
    val appId: String,
    val kind: CardKind,
    val installed: Boolean,
    val installedVersion: String?,
    val availableVersion: String?,
    val versionNote: VersionNote,
    val sizeBytes: Long?,
    val primary: CardAction,
    val primaryEnabled: Boolean,
    val secondary: List<CardAction>,
    val progress: ProgressModel?,
    val error: ErrorKind?,
    /** A background check is running; shown as a subtle spinner on top of whatever else is displayed. */
    val busyChecking: Boolean,
    /** The shown release data is cached and the last refresh failed. */
    val stale: Boolean,
)
