package app.tvessentials.core.source

import app.tvessentials.core.apk.ApkInspector
import app.tvessentials.core.apk.RemoteApkProbe
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.DirectApkSource
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.HttpRequest
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.version.Version
import kotlinx.coroutines.CancellationException

/**
 * Checks a single official URL that serves an APK directly.
 *
 * It asks for just the first four bytes, confirms they are a ZIP/APK, and learns the version from the
 * file name or by reading the manifest with range requests. If the server answers with a browser
 * check or a web page instead of a file, the result is a clear failure. Nothing is ever worked around:
 * no challenge solving, no alternate mirrors, no hidden fallbacks.
 */
class DirectApkResolver(
    private val http: SafeHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policyFactory: (AppDefinition) -> HostPolicy = { HostPolicy(it.allowedHosts) },
) : ReleaseSource<DirectApkSource> {

    override suspend fun resolve(
        app: AppDefinition,
        source: DirectApkSource,
        channel: Channel,
        device: DeviceProfile,
    ): SourceResult {
        val packageId = app.packageId(channel)
            ?: return SourceResult.Failure(SourceFailure.Unsupported("No package for channel"))
        val policy = policyFactory(app)

        data class Probe(val fileName: String, val total: Long?, val acceptsRanges: Boolean, val isZip: Boolean, val typeOk: Boolean)

        val probe = try {
            http.fetch(HttpRequest(source.url, rangeStart = 0, rangeEnd = 3), policy) { r ->
                val magic = ByteArray(4)
                var read = 0
                val s = r.byteStream()
                while (read < 4) {
                    val n = s.read(magic, read, 4 - read)
                    if (n < 0) break
                    read += n
                }
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: if (r.code == 200) r.contentLength.takeIf { it > 0 } else null
                val type = r.contentType.orEmpty().lowercase()
                Probe(
                    fileName = fileNameOf(r.header("Content-Disposition"), r.url.pathSegments.lastOrNull().orEmpty()),
                    total = total,
                    acceptsRanges = r.code == 206,
                    isZip = read == 4 && ApkInspector.looksLikeZip(magic),
                    typeOk = !type.startsWith("text/") && !type.contains("html") && !type.contains("json"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetFailure) {
            return SourceResult.Failure(e.toSourceFailure(officialUrl = app.guided?.officialUrl ?: source.url))
        }

        if (!probe.isZip || !probe.typeOk) {
            return SourceResult.Failure(SourceFailure.InvalidResponse("The official link did not return an APK file"))
        }

        var manifestVersionName: String? = null
        var manifestVersionCode: Long? = null
        if (probe.acceptsRanges && probe.total != null) {
            val manifest = RemoteApkProbe(http, policy).readManifest(source.url, probe.total)
            if (manifest != null) {
                if (manifest.packageId != null && manifest.packageId != packageId) {
                    return SourceResult.Failure(SourceFailure.InvalidResponse("The official link serves a different app"))
                }
                manifestVersionName = manifest.versionName
                manifestVersionCode = manifest.versionCode
            }
        }
        val fromName = source.fileNameVersionPattern?.let { Regex(it).find(probe.fileName)?.groupValues?.getOrNull(1) }
            ?.takeIf { Version.parseOrNull(it) != null }
        val versionName = manifestVersionName?.takeIf { Version.parseOrNull(it) != null } ?: fromName

        return SourceResult.Success(
            ResolvedRelease(
                appId = app.id,
                channel = channel,
                packageId = packageId,
                versionName = versionName,
                versionCode = manifestVersionCode,
                asset = ReleaseAsset(
                    fileName = probe.fileName.ifBlank { "${app.id}.apk" },
                    url = source.url,
                    sizeBytes = probe.total,
                    sha256 = null, // the origin publishes no hash; integrity rests on signature + package checks
                    abi = "any",
                ),
                releasePageUrl = app.guided?.officialUrl ?: app.websiteUrl,
                resolvedAtMs = clock(),
            ),
        )
    }

    private fun fileNameOf(disposition: String?, lastSegment: String): String {
        val fromHeader = disposition?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val candidate = (fromHeader ?: lastSegment).substringAfterLast('/').substringAfterLast('\\')
        // Keep only a safe file-name charset; this value is shown in the UI and used in a cache file name.
        return candidate.filter { it.isLetterOrDigit() || it in "._-" }.take(100)
    }
}
