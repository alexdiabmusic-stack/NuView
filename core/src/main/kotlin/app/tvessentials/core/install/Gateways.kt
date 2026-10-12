package app.tvessentials.core.install

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import java.io.File

/** Everything the installer needs from Android's package manager. Implemented in the app module. */
interface PackageGateway {
    /** The installed package, or null if absent. Never throws for "not installed". */
    fun installed(packageId: String): InstalledPackage?

    /** The platform's own parse of an APK on disk, including verified signing certificates; null if it can't be parsed/verified. */
    fun archiveInfo(file: File): ArchiveInfo?

    /** Starts the app's launcher activity. Returns false if it has none or can't be started. */
    fun launch(packageId: String): Boolean

    /** Opens the system "App info" page for [packageId]. */
    fun openAppInfo(packageId: String): Boolean
}

sealed interface InstallOutcome {
    data object Success : InstallOutcome

    /** The user dismissed or declined the system confirmation. Nothing was installed. */
    data object Cancelled : InstallOutcome
    data class Failed(val kind: InstallFailureKind, val detail: String?) : InstallOutcome
}

enum class InstallFailureKind {
    /** The system refused because installs from this app aren't allowed. */
    BLOCKED,
    STORAGE,
    /** Signature conflict with the installed copy or incompatible package. */
    CONFLICT,
    INCOMPATIBLE,
    INVALID,
    TIMEOUT,
    OTHER,
}

/** Hands a verified APK to Android's package installer. The platform always asks the user to confirm. */
interface InstallerGateway {
    /** Whether the user has allowed this app to request installs ("install unknown apps"). */
    fun canRequestInstalls(): Boolean

    /** Opens the system screen where the user can allow this app to install apps. */
    fun openInstallPermissionSettings(): Boolean

    /**
     * Streams [file] into an install session and suspends until the system reports a final result.
     * The implementation must refuse to install if the bytes it reads do not hash to [verifiedSha256].
     */
    suspend fun install(file: File, packageId: String, verifiedSha256: String): InstallOutcome
}

fun interface StorageGateway {
    fun freeBytes(dir: File): Long
}

fun interface ConnectivityGateway {
    fun isOnline(): Boolean
}
