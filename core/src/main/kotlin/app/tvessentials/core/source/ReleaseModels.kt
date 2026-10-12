package app.tvessentials.core.source

import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.SourceConfig
import app.tvessentials.core.net.NetFailure
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseAsset(
    val fileName: String,
    val url: String,
    val sizeBytes: Long? = null,
    /** SHA-256 published by the host, lower-case hex. Null when the source offers no trustworthy hash. */
    val sha256: String? = null,
    val abi: String,
)

/** The newest verified-compatible build of one app on one channel, as discovered from its official source. */
@Serializable
data class ResolvedRelease(
    val appId: String,
    val channel: Channel,
    val packageId: String,
    /** Null means the version could not be verified; the UI must say so rather than guess. */
    val versionName: String?,
    val versionCode: Long? = null,
    val asset: ReleaseAsset,
    val publishedAt: String? = null,
    val releasePageUrl: String? = null,
    val resolvedAtMs: Long,
)

sealed interface SourceFailure {
    data object Offline : SourceFailure
    data object Timeout : SourceFailure
    data object TlsError : SourceFailure
    data class RateLimited(val retryAtMs: Long?) : SourceFailure
    data class ServerError(val httpCode: Int?) : SourceFailure
    data object NotFound : SourceFailure
    data class NoCompatibleRelease(val detail: String) : SourceFailure

    /** The official endpoint wants an interactive browser check, which this app never tries to bypass. */
    data class BrowserCheckRequired(val officialUrl: String?) : SourceFailure
    data class BlockedHost(val detail: String) : SourceFailure
    data class InvalidResponse(val detail: String) : SourceFailure
    data class Unsupported(val detail: String) : SourceFailure
}

sealed interface SourceResult {
    data class Success(val release: ResolvedRelease) : SourceResult
    data class Failure(val failure: SourceFailure) : SourceResult
}

fun NetFailure.toSourceFailure(officialUrl: String? = null): SourceFailure = when (kind) {
    NetFailure.Kind.OFFLINE -> SourceFailure.Offline
    NetFailure.Kind.TIMEOUT -> SourceFailure.Timeout
    NetFailure.Kind.TLS -> SourceFailure.TlsError
    NetFailure.Kind.RATE_LIMITED -> SourceFailure.RateLimited(retryAtMs)
    NetFailure.Kind.NOT_FOUND -> SourceFailure.NotFound
    NetFailure.Kind.BROWSER_CHECK -> SourceFailure.BrowserCheckRequired(officialUrl)
    NetFailure.Kind.BLOCKED_HOST, NetFailure.Kind.INSECURE_REDIRECT, NetFailure.Kind.TOO_MANY_REDIRECTS ->
        SourceFailure.BlockedHost(message ?: "blocked")
    NetFailure.Kind.HTTP_ERROR -> SourceFailure.ServerError(httpCode)
    NetFailure.Kind.RESPONSE_TOO_LARGE, NetFailure.Kind.MALFORMED -> SourceFailure.InvalidResponse(message ?: "malformed")
    NetFailure.Kind.OTHER -> SourceFailure.Offline
}

/** Resolves the newest installable release of an app from one kind of official source. */
interface ReleaseSource<C : SourceConfig> {
    suspend fun resolve(app: AppDefinition, source: C, channel: Channel, device: DeviceProfile): SourceResult
}
