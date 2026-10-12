package app.tvessentials.core.apk

/** A package as the platform reports it once installed. */
data class InstalledPackage(
    val packageId: String,
    val versionName: String?,
    val versionCode: Long,
    /** SHA-256 digests of the certificates that currently sign the installed package. */
    val signerSha256: Set<String>,
    /** Older certificates in the signing lineage, if the package rotated keys. */
    val lineageSha256: Set<String> = emptySet(),
)

/** What the platform's own parser says about an APK file that is not installed yet. */
data class ArchiveInfo(
    val packageId: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val signerSha256: Set<String>,
    val lineageSha256: Set<String> = emptySet(),
)

/** Facts read directly from an APK's binary manifest. */
data class ManifestInfo(
    val packageId: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val usesLeanbackFeature: Boolean,
    val hasLeanbackLauncher: Boolean,
)
