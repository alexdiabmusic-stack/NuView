package app.tvessentials.core.state

import app.tvessentials.core.source.SourceFailure
import app.tvessentials.core.version.UpdateDecision
import app.tvessentials.core.version.UpdateStatus

/**
 * Turns the installer's knowledge about an app into exactly one card state.
 * Pure and exhaustive so every state, and every transition between them, can be tested.
 */
object CardDeriver {

    fun derive(s: AppState): CardModel {
        val installedVersion = s.installed?.versionName
        val rel = s.release
        val sizeBytes = rel?.asset?.sizeBytes
        val stale = rel != null && s.releaseFailure != null && !s.checking

        fun model(
            kind: CardKind,
            primary: CardAction,
            enabled: Boolean = primary != CardAction.NONE,
            secondary: List<CardAction> = emptyList(),
            progress: ProgressModel? = null,
            error: ErrorKind? = null,
            versionNote: VersionNote? = null,
        ): CardModel {
            val note = versionNote ?: when {
                rel == null -> VersionNote.NONE
                rel.versionName == null -> VersionNote.UNVERIFIED
                else -> VersionNote.VERIFIED
            }
            return CardModel(
                appId = s.app.id, kind = kind, installed = s.installed != null, installedVersion = installedVersion,
                availableVersion = rel?.versionName, versionNote = note, sizeBytes = sizeBytes,
                primary = primary, primaryEnabled = enabled, secondary = secondary, progress = progress, error = error,
                busyChecking = s.checking && kind != CardKind.CHECKING, stale = stale,
            )
        }

        if (!s.supported) return model(CardKind.UNSUPPORTED_DEVICE, CardAction.NONE, error = ErrorKind.UNSUPPORTED_DEVICE)

        // Anything actively happening wins over the resting state.
        when (val p = s.phase) {
            is Phase.Preparing -> return model(CardKind.CHECKING, CardAction.NONE, enabled = false)
            is Phase.Downloading -> return model(CardKind.DOWNLOADING, CardAction.CANCEL, progress = ProgressModel(p.downloadedBytes, p.totalBytes))
            is Phase.Paused -> return model(CardKind.DOWNLOAD_PAUSED, CardAction.RESUME, progress = ProgressModel(p.downloadedBytes, p.totalBytes))
            is Phase.DownloadFailed -> return model(
                CardKind.DOWNLOAD_FAILED, CardAction.RETRY, progress = ProgressModel(p.downloadedBytes, p.totalBytes),
                error = p.error, secondary = guideIfAvailable(s),
            )
            is Phase.Verifying -> return model(CardKind.VERIFYING, CardAction.NONE, enabled = false)
            is Phase.ReadyToInstall -> return model(CardKind.READY_TO_INSTALL, CardAction.INSTALL_NOW)
            is Phase.Installing -> return model(CardKind.INSTALLING, CardAction.NONE, enabled = false)
            is Phase.NeedsInstallPermission -> return model(CardKind.NEEDS_PERMISSION, CardAction.OPEN_INSTALL_SETTINGS, error = ErrorKind.PERMISSION_DENIED)
            is Phase.Failed -> return model(
                CardKind.INSTALL_FAILED, if (p.error.isRetryable()) CardAction.RETRY else primaryForError(s),
                error = p.error, secondary = guideIfAvailable(s),
            )
            is Phase.Succeeded -> return model(CardKind.JUST_INSTALLED, CardAction.OPEN, secondary = listOf(CardAction.CHECK_UPDATES))
            Phase.Idle -> Unit
        }

        // An installed copy signed by someone other than the reviewed publisher is never treated as "installed".
        if (s.installed != null && s.installedSignerTrusted == false) {
            return model(CardKind.SIGNER_MISMATCH, CardAction.OPEN_APP_INFO, error = ErrorKind.SIGNER_MISMATCH)
        }

        if (s.installed == null) {
            return when {
                rel != null && s.canAutoInstall -> model(CardKind.INSTALL_AVAILABLE, CardAction.INSTALL, secondary = guideIfAvailable(s))
                rel != null -> model(CardKind.GUIDED_ONLY, CardAction.SHOW_GUIDE, error = ErrorKind.NOT_REVIEWED)
                s.checking -> model(CardKind.CHECKING, CardAction.NONE, enabled = false)
                else -> {
                    val browserCheck = s.releaseFailure is SourceFailure.BrowserCheckRequired
                    model(
                        CardKind.SOURCE_UNAVAILABLE,
                        if (browserCheck && s.app.guided != null) CardAction.SHOW_GUIDE else CardAction.RETRY,
                        secondary = if (s.app.guided != null && !browserCheck) listOf(CardAction.SHOW_GUIDE) else emptyList(),
                        error = errorFor(s.releaseFailure),
                    )
                }
            }
        }

        // Installed.
        val status = if (rel == null) UpdateStatus.UNKNOWN else UpdateDecision.decide(
            s.installed.versionName, s.installed.versionCode, rel.versionName, rel.versionCode,
        )
        return when (status) {
            UpdateStatus.UPDATE_AVAILABLE -> model(
                CardKind.UPDATE_AVAILABLE,
                if (s.canAutoInstall) CardAction.UPDATE else CardAction.SHOW_GUIDE,
                secondary = listOf(CardAction.OPEN),
            )
            UpdateStatus.UP_TO_DATE, UpdateStatus.INSTALLED_IS_NEWER ->
                model(CardKind.UP_TO_DATE, CardAction.OPEN, secondary = listOf(CardAction.CHECK_UPDATES))
            UpdateStatus.UNKNOWN -> model(
                CardKind.INSTALLED, CardAction.OPEN, secondary = listOf(CardAction.CHECK_UPDATES),
                versionNote = if (rel == null && s.checking) VersionNote.NONE else if (rel?.versionName != null) VersionNote.VERIFIED else VersionNote.UNVERIFIED,
            )
        }
    }

    private fun guideIfAvailable(s: AppState): List<CardAction> = if (s.app.guided != null) listOf(CardAction.SHOW_GUIDE) else emptyList()

    private fun primaryForError(s: AppState): CardAction = if (s.app.guided != null) CardAction.SHOW_GUIDE else CardAction.RETRY

    fun errorFor(f: SourceFailure?): ErrorKind = when (f) {
        null -> ErrorKind.SOURCE_UNAVAILABLE
        SourceFailure.Offline -> ErrorKind.NO_INTERNET
        SourceFailure.Timeout -> ErrorKind.SLOW_CONNECTION
        SourceFailure.TlsError -> ErrorKind.SOURCE_UNAVAILABLE
        is SourceFailure.RateLimited -> ErrorKind.RATE_LIMITED
        is SourceFailure.ServerError, SourceFailure.NotFound -> ErrorKind.SOURCE_UNAVAILABLE
        is SourceFailure.NoCompatibleRelease -> ErrorKind.NO_COMPATIBLE_RELEASE
        is SourceFailure.BrowserCheckRequired -> ErrorKind.BROWSER_CHECK_REQUIRED
        is SourceFailure.BlockedHost, is SourceFailure.InvalidResponse, is SourceFailure.Unsupported -> ErrorKind.SOURCE_UNAVAILABLE
    }

    private fun ErrorKind.isRetryable(): Boolean = when (this) {
        ErrorKind.NO_INTERNET, ErrorKind.SLOW_CONNECTION, ErrorKind.RATE_LIMITED, ErrorKind.SOURCE_UNAVAILABLE,
        ErrorKind.DOWNLOAD_INTERRUPTED, ErrorKind.INSUFFICIENT_STORAGE, ErrorKind.INSTALL_FAILED,
        ErrorKind.INSTALL_NOT_CONFIRMED, ErrorKind.UNEXPECTED, ErrorKind.INSTALL_BLOCKED, ErrorKind.INVALID_APK -> true
        else -> false
    }
}
