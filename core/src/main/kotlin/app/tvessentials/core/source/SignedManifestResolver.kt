package app.tvessentials.core.source

import app.tvessentials.core.catalog.AbiPolicy
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.SignedManifestSource
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.HttpRequest
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.util.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Optional, future-facing source: a remote JSON manifest accepted only if it carries a valid ECDSA P-256
 * signature from the key pinned in the catalog.
 *
 * Even a correctly signed manifest cannot widen trust: its download URLs must pass the app's own host
 * allow-list, its package id must equal the catalog's, and APK acceptance still depends on the signing
 * certificate policy. It is not wired to any of the three launch apps.
 */
class SignedManifestResolver(
    private val http: SafeHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policyFactory: (AppDefinition) -> HostPolicy = { HostPolicy(it.allowedHosts) },
) : ReleaseSource<SignedManifestSource> {

    @Serializable
    private data class Manifest(
        val schema: Int,
        @SerialName("issued_at_ms") val issuedAtMs: Long,
        val entries: List<Entry>,
    )

    @Serializable
    private data class Entry(
        @SerialName("app_id") val appId: String,
        val channel: String,
        @SerialName("package_id") val packageId: String,
        @SerialName("version_name") val versionName: String,
        @SerialName("version_code") val versionCode: Long? = null,
        val assets: List<EntryAsset>,
    )

    @Serializable
    private data class EntryAsset(val name: String, val url: String, val size: Long? = null, val sha256: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(app: AppDefinition, source: SignedManifestSource, channel: Channel, device: DeviceProfile): SourceResult {
        val packageId = app.packageId(channel) ?: return SourceResult.Failure(SourceFailure.Unsupported("No package for channel"))
        val policy = policyFactory(app)
        val (body, sig) = try {
            val b = http.fetch(HttpRequest(source.manifestUrl), policy) { it.readBytes(1024 * 1024) }
            val s = http.fetch(HttpRequest(source.signatureUrl), policy) { it.readText(16 * 1024).trim() }
            b to s
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetFailure) {
            return SourceResult.Failure(e.toSourceFailure())
        }
        if (!verify(source.publicKeyBase64, body, sig)) {
            return SourceResult.Failure(SourceFailure.InvalidResponse("Manifest signature is not valid"))
        }
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return SourceResult.Failure(SourceFailure.InvalidResponse("Manifest could not be read"))
        }
        val now = clock()
        if (manifest.schema != 1 || now - manifest.issuedAtMs > source.maxAgeDays * 86_400_000L || manifest.issuedAtMs - now > 86_400_000L) {
            return SourceResult.Failure(SourceFailure.InvalidResponse("Manifest is outdated or from the future"))
        }
        val entry = manifest.entries.firstOrNull { it.appId == app.id && it.channel.equals(channel.name, true) && it.packageId == packageId }
            ?: return SourceResult.Failure(SourceFailure.NoCompatibleRelease("Manifest has no entry for this app"))

        val candidates = entry.assets.mapNotNull { a ->
            val u = a.url.toHttpUrlOrNull() ?: return@mapNotNull null
            if (policy.check(u) != null) return@mapNotNull null // hosts outside the app's allow-list are dropped, not trusted
            AssetCandidate(a.name, a.url, a.size, Hex.normalizeDigest(a.sha256))
        }
        val selector = AssetSelector(Regex(".*\\.apk"), emptyList(), AbiPolicy.PREFER_DEVICE_ABI, emptyList())
        return when (val sel = selector.select(candidates, device)) {
            is AssetSelection.Chosen -> SourceResult.Success(
                ResolvedRelease(
                    appId = app.id, channel = channel, packageId = packageId,
                    versionName = entry.versionName, versionCode = entry.versionCode,
                    asset = ReleaseAsset(sel.chosen.asset.name, sel.chosen.asset.url, sel.chosen.asset.sizeBytes, sel.chosen.asset.sha256, sel.chosen.abi),
                    releasePageUrl = app.websiteUrl, resolvedAtMs = now,
                ),
            )
            is AssetSelection.None -> SourceResult.Failure(SourceFailure.NoCompatibleRelease(sel.reason))
        }
    }

    companion object {
        fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(payload)
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        } catch (_: Exception) { false }
    }
}
