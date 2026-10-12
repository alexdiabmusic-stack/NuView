package app.tvessentials.core.source

import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.GitHubReleasesSource
import app.tvessentials.core.catalog.PrereleaseMode
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.HttpRequest
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.util.Hex
import app.tvessentials.core.version.Version
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Finds the newest suitable release in a GitHub repository's public release feed.
 *
 * Trust notes: the feed is data, not authority. Download URLs must live under the configured
 * repository's own `releases/download/` path, versions are compared numerically, and nothing here
 * decides whether a file is *installable*: that is the job of signature/package verification.
 */
class GitHubReleasesResolver(
    private val http: SafeHttp,
    private val cache: HttpCacheStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val apiBase: String = "https://api.github.com",
    private val policyFactory: (AppDefinition) -> HostPolicy = { HostPolicy(it.allowedHosts) },
) : ReleaseSource<GitHubReleasesSource> {

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val size: Long = -1,
        val state: String = "uploaded",
        @SerialName("browser_download_url") val url: String = "",
        val digest: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun resolve(
        app: AppDefinition,
        source: GitHubReleasesSource,
        channel: Channel,
        device: DeviceProfile,
    ): SourceResult {
        val rule = source.channelRules[channel]
            ?: return SourceResult.Failure(SourceFailure.Unsupported("${app.displayName} has no ${channel.name.lowercase()} channel"))
        val packageId = app.packageId(channel)
            ?: return SourceResult.Failure(SourceFailure.Unsupported("No package for channel"))
        val policy = policyFactory(app)

        val body = try {
            fetchReleaseFeed(source, policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetFailure) {
            return SourceResult.Failure(e.toSourceFailure())
        }

        val releases = try {
            json.decodeFromString(ListSerializer(GhRelease.serializer()), body)
        } catch (e: Exception) {
            return SourceResult.Failure(SourceFailure.InvalidResponse("Release feed could not be read"))
        }

        val tagFilter = rule.tagPattern?.let(::Regex)
        val tagVersion = rule.tagVersionPattern?.let(::Regex)
        val selector = AssetSelector(
            namePattern = Regex(rule.assetNamePattern),
            excludePatterns = rule.excludeAssetPatterns.map(::Regex),
            abiPolicy = source.abiPolicy,
            universalAbis = source.universalAbis,
        )
        val downloadPrefix = "https://github.com/${source.owner}/${source.repo}/releases/download/".lowercase()

        data class Candidate(val release: GhRelease, val version: Version)

        val candidates = releases.asSequence()
            .filter { !it.draft && it.tagName.isNotBlank() }
            .filter { tagFilter == null || tagFilter.containsMatchIn(it.tagName) }
            .mapNotNull { r ->
                val raw = tagVersion?.find(r.tagName)?.groupValues?.getOrNull(1) ?: r.tagName
                Version.parseOrNull(raw)?.let { Candidate(r, it) }
            }
            .filter {
                when (rule.prerelease) {
                    // Publishers are inconsistent about the GitHub flag (e.g. "0.9.5-beta" marked as a full release),
                    // so "stable" also excludes versions that are pre-releases by their own number.
                    PrereleaseMode.EXCLUDE -> !it.release.prerelease && !it.version.isPreRelease
                    PrereleaseMode.ONLY -> it.release.prerelease
                    PrereleaseMode.INCLUDE -> true
                }
            }
            .sortedByDescending { it.version }
            .toList()

        if (candidates.isEmpty()) {
            return SourceResult.Failure(SourceFailure.NoCompatibleRelease("No ${channel.name.lowercase()} release is published"))
        }

        var lastReason = "No compatible file"
        for (c in candidates.take(8)) {
            val assets = c.release.assets
                .filter { it.state == "uploaded" && it.url.lowercase().startsWith(downloadPrefix) }
                .map {
                    AssetCandidate(
                        name = it.name,
                        url = it.url,
                        sizeBytes = it.size.takeIf { s -> s > 0 },
                        sha256 = it.digest?.takeIf { d -> d.startsWith("sha256:") }?.let { d -> Hex.normalizeDigest(d.removePrefix("sha256:")) },
                    )
                }
            when (val sel = selector.select(assets, device)) {
                is AssetSelection.Chosen -> {
                    val a = sel.chosen.asset
                    return SourceResult.Success(
                        ResolvedRelease(
                            appId = app.id,
                            channel = channel,
                            packageId = packageId,
                            versionName = c.version.raw.removePrefix("v").removePrefix("V"),
                            asset = ReleaseAsset(a.name, a.url, a.sizeBytes, a.sha256, sel.chosen.abi),
                            publishedAt = c.release.publishedAt,
                            releasePageUrl = c.release.htmlUrl?.takeIf {
                                it.lowercase().startsWith("https://github.com/${source.owner}/${source.repo}/".lowercase())
                            },
                            resolvedAtMs = clock(),
                        ),
                    )
                }
                is AssetSelection.None -> lastReason = sel.reason
            }
        }
        return SourceResult.Failure(SourceFailure.NoCompatibleRelease(lastReason))
    }

    private suspend fun fetchReleaseFeed(source: GitHubReleasesSource, policy: HostPolicy): String {
        val url = "$apiBase/repos/${source.owner}/${source.repo}/releases?per_page=30"
        val cached = cache.get(url)
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            cached?.etag?.let { put("If-None-Match", it) }
        }
        return http.fetch(HttpRequest(url, headers = headers), policy) { r ->
            when {
                r.code == 304 && cached != null -> cached.body
                r.isSuccessful -> {
                    val text = r.readText(6 * 1024 * 1024)
                    cache.put(url, CachedBody(r.header("ETag"), text, clock()))
                    text
                }
                else -> throw NetFailure(NetFailure.Kind.HTTP_ERROR, "HTTP ${r.code}", httpCode = r.code)
            }
        }
    }
}
