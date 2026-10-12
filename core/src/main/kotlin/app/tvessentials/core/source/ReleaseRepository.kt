package app.tvessentials.core.source

import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.DirectApkSource
import app.tvessentials.core.catalog.GitHubReleasesSource
import app.tvessentials.core.catalog.SignedManifestSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CachedRelease(val release: ResolvedRelease, val checkedAtMs: Long)

/** Persists the last good answer per app and channel so the home screen can render instantly and offline. */
interface ReleaseStore {
    fun get(appId: String, channel: Channel): CachedRelease?
    fun put(appId: String, channel: Channel, value: CachedRelease)
    fun clear()
}

class InMemoryReleaseStore : ReleaseStore {
    private val map = ConcurrentHashMap<String, CachedRelease>()
    override fun get(appId: String, channel: Channel) = map["$appId/$channel"]
    override fun put(appId: String, channel: Channel, value: CachedRelease) { map["$appId/$channel"] = value }
    override fun clear() = map.clear()
}

class FileReleaseStore(private val dir: File) : ReleaseStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(appId: String, channel: Channel) = File(dir, "${appId.filter { it.isLetterOrDigit() || it == '-' }}-${channel.name.lowercase()}.json")

    override fun get(appId: String, channel: Channel): CachedRelease? = try {
        file(appId, channel).takeIf { it.isFile }?.let { json.decodeFromString(CachedRelease.serializer(), it.readText()) }
    } catch (_: Exception) { null } // a corrupt cache entry is simply a cache miss

    override fun put(appId: String, channel: Channel, value: CachedRelease) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "tmp-${System.nanoTime()}")
            tmp.writeText(json.encodeToString(CachedRelease.serializer(), value))
            val dest = file(appId, channel)
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        } catch (_: Exception) { /* losing a cache write is harmless */ }
    }

    override fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/** What a check produced: the best known release (fresh or cached) and why it may not be fresh. */
data class CheckOutcome(
    val release: ResolvedRelease?,
    val checkedAtMs: Long?,
    /** True when [release] came from cache without a successful network round trip this time. */
    val fromCache: Boolean,
    /** Set when a network attempt failed or was withheld; [release] may still be a usable cached value. */
    val failure: SourceFailure?,
)

/**
 * The single place that decides *whether* to hit the network.
 *
 *  - fresh enough cache wins over the network (no check spam on every foreground)
 *  - manual refreshes are debounced
 *  - after a rate-limit response the affected host is left alone until it says it is ready
 *  - any failure falls back to the last good answer so the UI never goes blank
 */
class ReleaseRepository(
    private val github: ReleaseSource<GitHubReleasesSource>,
    private val direct: ReleaseSource<DirectApkSource>,
    private val manifest: ReleaseSource<SignedManifestSource>?,
    private val store: ReleaseStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshForMs: Long = 15 * 60_000L,
    private val manualDebounceMs: Long = 20_000L,
) {
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()
    private val hostBlockedUntil = ConcurrentHashMap<String, Long>()

    fun cached(app: AppDefinition, channel: Channel): CachedRelease? = store.get(app.id, channel)

    /** Time of the last successful check for [app], regardless of channel. */
    fun lastSuccessfulCheck(app: AppDefinition): Long? =
        Channel.entries.mapNotNull { store.get(app.id, it)?.checkedAtMs }.maxOrNull()

    fun clearCache() { store.clear(); lastAttemptMs.clear(); hostBlockedUntil.clear() }

    suspend fun check(app: AppDefinition, channel: Channel, device: DeviceProfile, force: Boolean): CheckOutcome {
        val now = clock()
        val cached = store.get(app.id, channel)
        val key = "${app.id}/$channel"
        val groupKey = hostGroup(app)

        fun fromCache(failure: SourceFailure? = null) =
            CheckOutcome(cached?.release, cached?.checkedAtMs, fromCache = true, failure = failure)

        if (cached != null) {
            val age = now - cached.checkedAtMs
            if (!force && age in 0 until freshForMs) return fromCache()
            val sinceAttempt = now - (lastAttemptMs[key] ?: Long.MIN_VALUE / 2)
            if (force && sinceAttempt in 0 until manualDebounceMs) return fromCache()
        }
        hostBlockedUntil[groupKey]?.let { until ->
            if (now < until) return fromCache(SourceFailure.RateLimited(until))
        }

        lastAttemptMs[key] = now
        val result = try {
            when (val s = app.source) {
                is GitHubReleasesSource -> github.resolve(app, s, channel, device)
                is DirectApkSource -> direct.resolve(app, s, channel, device)
                is SignedManifestSource -> manifest?.resolve(app, s, channel, device)
                    ?: SourceResult.Failure(SourceFailure.Unsupported("Manifest sources are not enabled in this build"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceResult.Failure(SourceFailure.InvalidResponse(e.message ?: "unexpected error"))
        }

        return when (result) {
            is SourceResult.Success -> {
                val entry = CachedRelease(result.release, clock())
                store.put(app.id, channel, entry)
                hostBlockedUntil.remove(groupKey)
                CheckOutcome(entry.release, entry.checkedAtMs, fromCache = false, failure = null)
            }
            is SourceResult.Failure -> {
                (result.failure as? SourceFailure.RateLimited)?.let { rl ->
                    hostBlockedUntil[groupKey] = rl.retryAtMs ?: (now + 5 * 60_000L)
                }
                fromCache(result.failure)
            }
        }
    }

    private fun hostGroup(app: AppDefinition): String = when (val s = app.source) {
        is GitHubReleasesSource -> "github"
        is DirectApkSource -> s.url.substringAfter("://").substringBefore('/')
        is SignedManifestSource -> s.manifestUrl.substringAfter("://").substringBefore('/')
    }
}
