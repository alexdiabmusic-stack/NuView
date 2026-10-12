package app.tvessentials.core.catalog

import app.tvessentials.core.trust.TrustPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Channel {
    @SerialName("stable") STABLE,
    @SerialName("beta") BETA,
}

@Serializable
data class Catalog(
    val schemaVersion: Int,
    val apps: List<AppDefinition>,
)

/**
 * Everything the installer needs to know about one app. Adding an app means adding one of these
 * to the catalog file; no installer code changes.
 */
@Serializable
data class AppDefinition(
    /** Stable internal id, e.g. "nuvio-tv". Used for cache keys and UI state. */
    val id: String,
    val displayName: String,
    /** Short line shown on the home card. */
    val tagline: String,
    /** One or two sentences shown on the details screen. */
    val description: String,
    val publisher: String,
    val websiteUrl: String,
    /** Extra disclosure shown on details, e.g. "independent third-party client". */
    val disclosure: String? = null,
    /** Key into the neutral glyph set used when no logo is bundled for the app and its own launcher icon is not on the device yet. */
    val iconKey: String,
    /** Accent colour as #RRGGBB. */
    val accentColor: String,
    val minAndroidSdk: Int = 21,
    /** Which package id each channel installs. Beta may be absent for apps without one. */
    val channels: Map<Channel, ChannelSpec>,
    val source: SourceConfig,
    /** Every host (including redirect targets) the app's downloads and metadata may touch. */
    val allowedHosts: List<String>,
    val trust: TrustPolicy,
    /** Shown when automatic installation can't be completed safely. */
    val guided: GuidedInstall? = null,
) {
    fun packageId(channel: Channel): String? = channels[channel]?.packageId
    val supportedChannels: Set<Channel> get() = channels.keys
    val allPackageIds: Set<String> get() = channels.values.map { it.packageId }.toSet()
}

@Serializable
data class ChannelSpec(val packageId: String)

/** Instructions for the official manual route; used instead of guessing when an automatic route is unsafe or blocked. */
@Serializable
data class GuidedInstall(
    val officialUrl: String,
    val downloaderCode: String? = null,
    val steps: List<String> = emptyList(),
)

@Serializable
enum class AbiPolicy {
    /** Choose the build for the device's own ABI; use universal only as a last resort. */
    @SerialName("prefer-device-abi") PREFER_DEVICE_ABI,

    /** Choose the universal build when it covers this device; otherwise the device-specific build. */
    @SerialName("prefer-universal") PREFER_UNIVERSAL,
}

@Serializable
enum class PrereleaseMode {
    @SerialName("exclude") EXCLUDE,
    @SerialName("only") ONLY,
    @SerialName("include") INCLUDE,
}

@Serializable
sealed interface SourceConfig

/** Releases published on GitHub, resolved through the public releases API. */
@Serializable
@SerialName("github-releases")
data class GitHubReleasesSource(
    val owner: String,
    val repo: String,
    val abiPolicy: AbiPolicy = AbiPolicy.PREFER_DEVICE_ABI,
    /** ABIs the "universal" build actually contains. Empty means "trust the label". */
    val universalAbis: List<String> = emptyList(),
    val channelRules: Map<Channel, GitHubChannelRule>,
) : SourceConfig

@Serializable
data class GitHubChannelRule(
    val prerelease: PrereleaseMode,
    /** Regex the release tag must contain a match for. Null accepts any tag. */
    val tagPattern: String? = null,
    /** Regex with ONE capture group extracting the version from the tag. Null uses the tag itself. */
    val tagVersionPattern: String? = null,
    /** Regex the whole asset file name must match. */
    val assetNamePattern: String,
    val excludeAssetPatterns: List<String> = emptyList(),
)

/** A single official URL that serves the APK directly (possibly through redirects within [AppDefinition.allowedHosts]). */
@Serializable
@SerialName("direct-apk")
data class DirectApkSource(
    val url: String,
    /** Optional regex with ONE group to read a version from the served file name. */
    val fileNameVersionPattern: String? = null,
    val abiPolicy: AbiPolicy = AbiPolicy.PREFER_DEVICE_ABI,
) : SourceConfig

/**
 * A remote JSON manifest signed with a pinned ECDSA P-256 key (future use). The manifest can only
 * point at hosts already on the app's allow-list and at the app's own package ids; it cannot widen trust.
 */
@Serializable
@SerialName("signed-manifest")
data class SignedManifestSource(
    val manifestUrl: String,
    val signatureUrl: String,
    /** Base64 X.509 SubjectPublicKeyInfo of the ECDSA P-256 key that signs the manifest. */
    val publicKeyBase64: String,
    val maxAgeDays: Int = 30,
) : SourceConfig
