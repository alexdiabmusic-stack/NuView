package app.tvessentials.core.source

import app.tvessentials.core.catalog.AbiPolicy
import app.tvessentials.core.catalog.CatalogValidator

/** One downloadable file offered by a release. */
data class AssetCandidate(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
    /** Lower-case hex SHA-256 published by the host (e.g. GitHub's asset digest), if any. */
    val sha256: String?,
)

data class ChosenAsset(val asset: AssetCandidate, val abi: String)

sealed interface AssetSelection {
    data class Chosen(val chosen: ChosenAsset) : AssetSelection
    data class None(val reason: String) : AssetSelection
}

object AbiTokens {
    const val UNIVERSAL = "universal"

    // Longest first so "x86_64" is never read as "x86". Boundaries are non-alphanumerics, so a token
    // can't hide inside a longer word.
    private val token = Regex("(?<![A-Za-z0-9])(arm64-v8a|armeabi-v7a|x86_64|x86|universal)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    /** The ABI (or "universal") a file name declares, or null if it declares none. */
    fun of(fileName: String): String? = token.find(fileName)?.value?.lowercase()

    /** Device ABIs we know how to match, in the device's order. */
    fun usable(deviceAbis: List<String>): List<String> =
        deviceAbis.map { it.lowercase() }.filter { it in CatalogValidator.KNOWN_ABIS }.distinct()
}

/**
 * Picks the APK that fits this device from a release's files.
 *
 * Accepts only `.apk` files that match the app's name pattern, never bundles (.xapk/.apks/.aab),
 * and never a build for an ABI the device can't run.
 */
class AssetSelector(
    private val namePattern: Regex,
    private val excludePatterns: List<Regex>,
    private val abiPolicy: AbiPolicy,
    private val universalAbis: List<String>,
) {
    fun select(candidates: List<AssetCandidate>, device: DeviceProfile): AssetSelection {
        val eligible = candidates
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .filter { namePattern.matches(it.name) }
            .filter { c -> excludePatterns.none { it.containsMatchIn(c.name) } }
            .distinctBy { it.name }
            .sortedBy { it.name }
        if (eligible.isEmpty()) return AssetSelection.None("The release has no APK matching the expected file name")

        val deviceAbis = AbiTokens.usable(device.supportedAbis)
        if (deviceAbis.isEmpty()) return AssetSelection.None("This device reports no supported ABI we can match")

        val byAbi = HashMap<String, AssetCandidate>()
        var universal: AssetCandidate? = null
        var unspecified: AssetCandidate? = null
        for (c in eligible) {
            when (val abi = AbiTokens.of(c.name)) {
                null -> if (unspecified == null) unspecified = c
                AbiTokens.UNIVERSAL -> if (universal == null) universal = c
                else -> byAbi.putIfAbsent(abi, c)
            }
        }

        val universalFits = universalAbis.isEmpty() || deviceAbis.any { it in universalAbis }
        val exact: ChosenAsset? = deviceAbis.firstNotNullOfOrNull { abi -> byAbi[abi]?.let { ChosenAsset(it, abi) } }
        val uni: ChosenAsset? = universal?.takeIf { universalFits }?.let { ChosenAsset(it, AbiTokens.UNIVERSAL) }
        val any: ChosenAsset? = unspecified?.let { ChosenAsset(it, "any") }

        val ordered = when (abiPolicy) {
            AbiPolicy.PREFER_DEVICE_ABI -> listOf(exact, uni, any)
            AbiPolicy.PREFER_UNIVERSAL -> listOf(uni, exact, any)
        }
        ordered.firstOrNull { it != null }?.let { return AssetSelection.Chosen(it) }

        val offered = eligible.joinToString { AbiTokens.of(it.name) ?: "unspecified" }
        return AssetSelection.None("No build for this device (device: ${deviceAbis.joinToString()}; release offers: $offered)")
    }
}
