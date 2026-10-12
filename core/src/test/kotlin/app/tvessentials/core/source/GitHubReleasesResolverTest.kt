package app.tvessentials.core.source

import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.GitHubReleasesSource
import app.tvessentials.core.testing.Fixtures
import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.response
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubReleasesResolverTest {
    private val fx = TlsFixture()
    private val cache = InMemoryHttpCache()
    @AfterTest fun tearDown() = fx.close()

    private fun resolver() = GitHubReleasesResolver(
        fx.http, cache, clock = { 1_000L }, apiBase = "https://api.github.com:${fx.port}",
        policyFactory = { fx.policy("api.github.com", "github.com", "*.githubusercontent.com") },
    )
    private val arm64 = DeviceProfile(28, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    private val fire32 = DeviceProfile(28, listOf("armeabi-v7a", "armeabi"))

    private fun resolve(appId: String, channel: Channel, device: DeviceProfile = arm64): SourceResult = runBlocking {
        val app = Fixtures.app(appId)
        resolver().resolve(app, app.source as GitHubReleasesSource, channel, device)
    }

    private fun ok(r: SourceResult) = assertIs<SourceResult.Success>(r).release
    private fun fail(r: SourceResult) = assertIs<SourceResult.Failure>(r).failure

    @Test fun `nuvio stable picks 1_0_0 and the device's own abi build with its published sha256`() {
        fx.dispatch { response(200, Fixtures.nuvioFeed(), mapOf("ETag" to "\"e1\"")) }
        val r = ok(resolve("nuvio-tv", Channel.STABLE))
        assertEquals("1.0.0", r.versionName)
        assertEquals("com.nuvio.tv", r.packageId)
        assertEquals("app-full-arm64-v8a-release.apk", r.asset.fileName)
        assertEquals("arm64-v8a", r.asset.abi)
        assertEquals(73_746_627L, r.asset.sizeBytes)
        assertEquals(64, r.asset.sha256?.length)
        assertTrue(r.asset.url.startsWith("https://github.com/NuvioMedia/NuvioTV/releases/download/1.0.0/"))
        assertTrue(r.releasePageUrl!!.endsWith("/releases/tag/1.0.0"))
    }

    @Test fun `nuvio beta picks the newest prerelease`() {
        fx.dispatch { response(200, Fixtures.nuvioFeed()) }
        assertEquals("1.1.0-beta.5", ok(resolve("nuvio-tv", Channel.BETA)).versionName)
    }

    @Test fun `stable ignores drafts and pre-release versions even when the publisher mislabels them`() {
        val tricky = listOf(
            Fixtures.release("NuvioMedia", "NuvioTV", "9.9.9", draft = true, assets = emptyList()),
            Fixtures.release("NuvioMedia", "NuvioTV", "1.0.1-beta", prerelease = false, assets = listOf(
                Fixtures.asset("NuvioMedia", "NuvioTV", "1.0.1-beta", "app-full-arm64-v8a-release.apk", 1))),
        )
        fx.dispatch { response(200, Fixtures.nuvioFeed(tricky)) }
        assertEquals("1.0.0", ok(resolve("nuvio-tv", Channel.STABLE)).versionName)
    }

    @Test fun `a 32-bit fire tv gets the armv7 build`() {
        fx.dispatch { response(200, Fixtures.nuvioFeed()) }
        assertEquals("app-full-armeabi-v7a-release.apk", ok(resolve("nuvio-tv", Channel.STABLE, fire32)).asset.fileName)
    }

    @Test fun `smarttube stable uses the s-suffixed tag and prefers the universal build`() {
        fx.dispatch { response(200, Fixtures.smartTubeFeed()) }
        val r = ok(resolve("smarttube", Channel.STABLE, fire32))
        assertEquals("32.56", r.versionName)
        assertEquals("org.smarttube.stable", r.packageId)
        assertEquals("SmartTube_stable_32.56_universal.apk", r.asset.fileName)
    }

    @Test fun `smarttube beta resolves a different package and different assets`() {
        fx.dispatch { response(200, Fixtures.smartTubeFeed()) }
        val r = ok(resolve("smarttube", Channel.BETA))
        assertEquals("org.smarttube.beta", r.packageId)
        assertEquals("SmartTube_beta_32.56_universal.apk", r.asset.fileName)
    }

    @Test fun `the rolling latest release and asset-less notices are never offered`() {
        fx.dispatch { response(200, Fixtures.smartTubeFeed()) }
        val stable = ok(resolve("smarttube", Channel.STABLE))
        assertTrue("latest" !in stable.asset.url && "smarttube_stable.apk" != stable.asset.fileName)
    }

    @Test fun `asset links that leave the official repository are ignored`() {
        val evil = Fixtures.release("NuvioMedia", "NuvioTV", "2.0.0", assets = listOf(
            Fixtures.asset("NuvioMedia", "NuvioTV", "2.0.0", "app-full-arm64-v8a-release.apk", 5, host = "https://evil.example"),
            Fixtures.asset("someone-else", "NuvioMobile", "2.0.0", "app-full-universal-release.apk", 5),
        ))
        fx.dispatch { response(200, "[$evil]") }
        assertIs<SourceFailure.NoCompatibleRelease>(fail(resolve("nuvio-tv", Channel.STABLE)))
    }

    @Test fun `if the newest release has no build for this device an older compatible one is used`() {
        val newest = Fixtures.release("NuvioMedia", "NuvioTV", "1.0.1", assets = listOf(Fixtures.asset("NuvioMedia", "NuvioTV", "1.0.1", "app-full-x86-release.apk", 5)))
        val older = Fixtures.release("NuvioMedia", "NuvioTV", "1.0.0", assets = listOf(Fixtures.asset("NuvioMedia", "NuvioTV", "1.0.0", "app-full-arm64-v8a-release.apk", 5)))
        fx.dispatch { response(200, "[$newest,$older]") }
        assertEquals("1.0.0", ok(resolve("nuvio-tv", Channel.STABLE)).versionName)
    }

    @Test fun `assets that are still uploading are skipped`() {
        val r = Fixtures.release("NuvioMedia", "NuvioTV", "1.0.0", assets = listOf(
            Fixtures.asset("NuvioMedia", "NuvioTV", "1.0.0", "app-full-arm64-v8a-release.apk", 5, state = "starter")))
        fx.dispatch { response(200, "[$r]") }
        assertIs<SourceFailure.NoCompatibleRelease>(fail(resolve("nuvio-tv", Channel.STABLE)))
    }

    @Test fun `conditional requests reuse the cached feed when nothing changed`() {
        var calls = 0
        var sawIfNoneMatch: String? = null
        fx.dispatch { req ->
            calls++
            sawIfNoneMatch = req.headers["If-None-Match"]
            if (req.headers["If-None-Match"] == "\"e1\"") response(304) else response(200, Fixtures.nuvioFeed(), mapOf("ETag" to "\"e1\""))
        }
        val first = ok(resolve("nuvio-tv", Channel.STABLE))
        val second = ok(resolve("nuvio-tv", Channel.STABLE))
        assertEquals(first.versionName, second.versionName)
        assertEquals(2, calls)
        assertEquals("\"e1\"", sawIfNoneMatch)
    }

    @Test fun `rate limiting, outages and garbage are reported distinctly`() {
        fx.dispatch { response(403, "{}", mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1900000000")) }
        assertEquals(1_900_000_000_000L, assertIs<SourceFailure.RateLimited>(fail(resolve("nuvio-tv", Channel.STABLE))).retryAtMs)
        fx.dispatch { response(500) }
        assertIs<SourceFailure.ServerError>(fail(resolve("nuvio-tv", Channel.STABLE)))
        fx.dispatch { response(200, "<html>not json</html>") }
        assertIs<SourceFailure.InvalidResponse>(fail(resolve("nuvio-tv", Channel.STABLE)))
        fx.dispatch { response(200, "[]") }
        assertIs<SourceFailure.NoCompatibleRelease>(fail(resolve("nuvio-tv", Channel.STABLE)))
    }

    @Test fun `an app without a beta channel reports it plainly`() {
        val f = fail(runBlocking {
            val app = Fixtures.app("implayer")
            resolver().resolve(app, GitHubReleasesSource("x", "y", channelRules = emptyMap()), Channel.BETA, arm64)
        })
        assertIs<SourceFailure.Unsupported>(f)
        assertNotNull(f)
    }
}
