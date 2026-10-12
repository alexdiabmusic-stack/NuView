package app.tvessentials.core.live

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.DirectApkSource
import app.tvessentials.core.catalog.GitHubReleasesSource
import app.tvessentials.core.download.ApkDownloader
import app.tvessentials.core.download.DownloadRequest
import app.tvessentials.core.install.ApkVerifier
import app.tvessentials.core.install.ExpectedApk
import app.tvessentials.core.install.StorageGateway
import app.tvessentials.core.install.Verification
import app.tvessentials.core.install.VerifyError
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.source.DirectApkResolver
import app.tvessentials.core.source.GitHubReleasesResolver
import app.tvessentials.core.source.InMemoryHttpCache
import app.tvessentials.core.source.ResolvedRelease
import app.tvessentials.core.source.SourceFailure
import app.tvessentials.core.source.SourceResult
import app.tvessentials.core.testing.Fixtures
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LIVE check against the real internet: official sources, real APKs, the production verifier and the real trust policy.
 * Skipped unless -Ptve.live=true. Needs the Android SDK build-tools (ANDROID_HOME) as an independent signature oracle.
 * Not part of the normal build because it downloads ~200 MB and depends on third-party servers being up.
 */
class LiveSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val live = System.getProperty("tve.live") == "true"
    private val http = SafeHttp(
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
        userAgent = "TVEssentials-CI/1.0",
    )
    private val cache = InMemoryHttpCache()
    private val github = GitHubReleasesResolver(http, cache)
    private val direct = DirectApkResolver(http)
    private val summary = StringBuilder("| App | Channel | Device | Result |\n|---|---|---|---|\n")

    private val arm64 = "arm64-v8a" to DeviceProfile(34, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val fire32 = "fire-tv-32bit" to DeviceProfile(28, listOf("armeabi-v7a", "armeabi"))
    private val x86 = "x86_64-emulator" to DeviceProfile(34, listOf("x86_64", "x86"))

    private suspend fun resolve(app: AppDefinition, channel: Channel, device: DeviceProfile): SourceResult = when (val s = app.source) {
        is GitHubReleasesSource -> github.resolve(app, s, channel, device)
        is DirectApkSource -> direct.resolve(app, s, channel, device)
        else -> error("unexpected source")
    }

    private fun sdkTool(name: String): File {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: error("ANDROID_HOME not set")
        val bt = File(home, "build-tools").listFiles()!!.filter { it.isDirectory }.maxBy { dir -> dir.name.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { a, n -> a * 1000 + n } }
        return File(bt, name)
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /** Independent oracle: the SDK's own tools decide package id, version, minSdk and signing certificates. */
    private fun archiveInfo(file: File): ArchiveInfo? {
        val (code, signer) = run(sdkTool("apksigner").path, "verify", "--print-certs", file.path)
        if (code != 0) return null
        val digests = Regex("certificate SHA-256 digest:\\s*([0-9a-fA-F]{64})").findAll(signer).map { it.groupValues[1].lowercase() }.toSet()
        val (_, badging) = run(sdkTool("aapt2").path, "dump", "badging", file.path)
        val pkg = Regex("package: name='([^']+)' versionCode='(\\d+)' versionName='([^']*)'").find(badging) ?: return null
        val minSdk = Regex("minSdkVersion:'(\\d+)'").find(badging)?.groupValues?.get(1)?.toInt()
        return ArchiveInfo(pkg.groupValues[1], pkg.groupValues[3], pkg.groupValues[2].toLong(), minSdk, digests)
    }

    @Test fun `official sources resolve for every app, channel and device class`() = runBlocking {
        assumeTrue("live checks are opt-in (-Ptve.live=true)", live)
        var problems = 0
        for (app in catalog()) for (channel in app.supportedChannels) for ((label, device) in listOf(arm64, fire32, x86)) {
            val r = resolve(app, channel, device)
            val text = when (r) {
                is SourceResult.Success -> "OK ${r.release.versionName ?: "version unverified"} -> ${r.release.asset.fileName} (${r.release.asset.abi}, ${r.release.asset.sizeBytes?.div(1_000_000) ?: "?"} MB)"
                is SourceResult.Failure -> "NOT RESOLVED: ${r.failure}"
            }
            println("LIVE ${app.id} ${channel.name.lowercase()} [$label] $text")
            summary.append("| ${app.displayName} | ${channel.name.lowercase()} | $label | $text |\n")
            if (r is SourceResult.Failure && !expectedFailure(app, r.failure)) problems++
        }
        publish()
        assertTrue(problems == 0, "$problems unexpected resolution failures; see log")
    }

    @Test fun `real downloads verify against the reviewed trust policy`() = runBlocking {
        assumeTrue("live checks are opt-in (-Ptve.live=true)", live)
        val verifier = ApkVerifier()
        val downloader = ApkDownloader(http, StorageGateway { Long.MAX_VALUE / 4 })
        var failures = 0
        for (app in catalog().filter { it.trust.signers.isNotEmpty() }) for (channel in app.supportedChannels) {
            // One download per distinct file; every device class that would receive that file verifies it (ABI, minSdk, signer).
            val devicesByUrl = LinkedHashMap<String, MutableList<Pair<String, DeviceProfile>>>()
            val releaseByUrl = HashMap<String, ResolvedRelease>()
            for ((label, device) in listOf(arm64, fire32, x86)) {
                val r = (resolve(app, channel, device) as? SourceResult.Success)?.release
                if (r == null) { failures++; println("LIVE-VERIFY ${app.id} ${channel.name.lowercase()} [$label]: could not resolve"); continue }
                devicesByUrl.getOrPut(r.asset.url) { mutableListOf() } += label to device
                releaseByUrl[r.asset.url] = r
            }
            for ((url, devices) in devicesByUrl) {
                val r = releaseByUrl.getValue(url)
                val dest = File(tmp.root, "${app.id}-${channel.name.lowercase()}-${(url.hashCode().toLong() and 0xffffffffL)}.apk")
                val dl = downloader.download(DownloadRequest(r.asset.url, dest, r.asset.sizeBytes, r.asset.sha256, HostPolicy(app.allowedHosts))) {}
                for ((label, device) in devices) {
                    val v = verifier.verify(dl.file, ExpectedApk(r.packageId, r.versionName, r.asset.sizeBytes, r.asset.sha256), app.trust, device, installed = null, archive = ::archiveInfo)
                    val text = when (v) {
                        is Verification.Passed -> "PASSED v${v.archive.versionName} (code ${v.archive.versionCode}) pkg=${v.archive.packageId} signer=${v.signer.label} sha256=${v.sha256.take(16)}…"
                        // An x86 emulator legitimately cannot run an arm-only build; the installer says so instead of installing it.
                        is Verification.Failed -> if (v.error == VerifyError.INCOMPATIBLE_ABI && label == x86.first) "NOT RUNNABLE on $label (expected): ${v.detail}"
                        else "REJECTED ${v.error}: ${v.detail}".also { failures++ }
                    }
                    println("LIVE-VERIFY ${app.id} ${channel.name.lowercase()} [$label] ${r.asset.fileName}: $text")
                    summary.append("| ${app.displayName} | ${channel.name.lowercase()} | verified download [$label] | $text |\n")
                }
                dest.delete()
            }
        }
        publish()
        assertTrue(failures == 0, "$failures real APK(s) failed verification against the catalog's trust policy")
    }

    /**
     * Apps without a reviewed signing certificate are never installed by the app, but a maintainer needs the measured facts
     * to review one. This only REPORTS what the official download contains (package id, version, signer); it never pins
     * anything. A digest obtained from the same channel as the APK proves nothing about authenticity by itself.
     */
    @Test fun `unpinned apps are measured and reported but never trusted`() = runBlocking {
        assumeTrue("live checks are opt-in (-Ptve.live=true)", live)
        val downloader = ApkDownloader(http, StorageGateway { Long.MAX_VALUE / 4 })
        for (app in catalog().filter { it.trust.signers.isEmpty() }) for (channel in app.supportedChannels) {
            val r = (resolve(app, channel, arm64.second) as? SourceResult.Success)?.release
            if (r == null) { println("LIVE-MEASURE ${app.id} ${channel.name.lowercase()}: could not resolve (fails closed)"); continue }
            val dest = File(tmp.root, "${app.id}-measure.apk")
            val dl = runCatching { downloader.download(DownloadRequest(r.asset.url, dest, r.asset.sizeBytes, r.asset.sha256, HostPolicy(app.allowedHosts))) {} }
                .getOrElse { println("LIVE-MEASURE ${app.id} ${channel.name.lowercase()}: download refused: $it"); continue }
            val info = archiveInfo(dl.file)
            println("LIVE-MEASURE ${app.id} ${channel.name.lowercase()}: ${r.asset.fileName} file-sha256=${dl.sha256} pkg=${info?.packageId} version=${info?.versionName} code=${info?.versionCode} minSdk=${info?.minSdk} signers=${info?.signerSha256}")
            summary.append("| ${app.displayName} | ${channel.name.lowercase()} | measured (NOT trusted) | pkg=${info?.packageId} v${info?.versionName} signers=${info?.signerSha256} |\n")
            dest.delete()
        }
        publish()
    }

    /** iMPlayer's official endpoint may answer datacenter clients with a Cloudflare browser check; that is expected and must fail closed. */
    private fun expectedFailure(app: AppDefinition, f: SourceFailure) =
        app.id == "implayer" && (f is SourceFailure.BrowserCheckRequired || f is SourceFailure.InvalidResponse || f is SourceFailure.ServerError)

    private fun catalog() = Fixtures.catalog.apps

    private fun publish() {
        System.getenv("GITHUB_STEP_SUMMARY")?.let { runCatching { File(it).appendText("\n### Live source verification\n$summary\n") } }
    }
}
