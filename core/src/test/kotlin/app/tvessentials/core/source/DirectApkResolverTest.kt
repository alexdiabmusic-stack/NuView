package app.tvessentials.core.source

import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.DirectApkSource
import app.tvessentials.core.testing.ApkFixture
import app.tvessentials.core.testing.Fixtures
import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.rangeDispatch
import app.tvessentials.core.testing.response
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DirectApkResolverTest {
    private val fx = TlsFixture()
    @get:Rule val tmp = TemporaryFolder()
    @AfterTest fun tearDown() = fx.close()

    private val app = Fixtures.app("implayer")
    private val device = DeviceProfile(28, listOf("armeabi-v7a"))
    private fun resolver() = DirectApkResolver(fx.http, clock = { 5L }, policyFactory = { fx.policy("implayer.tv", "*.implayer.tv") })
    private fun resolve(url: String = fx.url("implayer.tv", "/d")): SourceResult = runBlocking {
        resolver().resolve(app, DirectApkSource(url), Channel.STABLE, device)
    }

    @Test fun `a real apk behind the official link yields its verified version without downloading it`() {
        val apk = ApkFixture.apk(tmp.newFile("im.apk"), "com.myiptvonline.implayer", 405, "4.0.5", padding = 200_000).readBytes()
        var bytesRequested = 0L
        fx.dispatch(rangeDispatch(apk) { r -> if (r.headers["Range"] == null) bytesRequested += apk.size })
        val r = assertIs<SourceResult.Success>(resolve()).release
        assertEquals("4.0.5", r.versionName)
        assertEquals(405L, r.versionCode)
        assertEquals(apk.size.toLong(), r.asset.sizeBytes)
        assertNull(r.asset.sha256, "the origin publishes no hash, so none may be invented")
        assertEquals(0L, bytesRequested, "no full-file request may be made just to check a version")
    }

    @Test fun `the file served is for a different app than the catalog says`() {
        val apk = ApkFixture.apk(tmp.newFile("o.apk"), "com.someone.else", 1, "1.0").readBytes()
        fx.dispatch(rangeDispatch(apk))
        assertIs<SourceFailure.InvalidResponse>(assertIs<SourceResult.Failure>(resolve()).failure)
    }

    @Test fun `when the server cannot serve ranges the version is honestly unknown`() {
        val apk = ApkFixture.apk(tmp.newFile("n.apk"), "com.myiptvonline.implayer", 9, "9.9").readBytes()
        fx.dispatch(rangeDispatch(apk, ignoreRange = true))
        val r = assertIs<SourceResult.Success>(resolve()).release
        assertNull(r.versionName)
        assertNull(r.versionCode)
    }

    @Test fun `a browser check is surfaced as such with the official page to use instead`() {
        fx.dispatch { response(403, "<title>Just a moment...</title>", mapOf("cf-mitigated" to "challenge")) }
        val f = assertIs<SourceFailure.BrowserCheckRequired>(assertIs<SourceResult.Failure>(resolve()).failure)
        assertEquals("https://implayer.tv/", f.officialUrl)
    }

    @Test fun `an html page is not an apk even with a 200`() {
        fx.dispatch { response(200, "<html>welcome</html>", mapOf("Content-Type" to "text/html")) }
        assertIs<SourceFailure.InvalidResponse>(assertIs<SourceResult.Failure>(resolve()).failure)
    }

    @Test fun `redirects to the publisher's own cdn are followed, anything else is refused`() {
        val apk = ApkFixture.apk(tmp.newFile("c.apk"), "com.myiptvonline.implayer", 3, "3.0").readBytes()
        fx.dispatch { req ->
            when {
                req.url.encodedPath == "/d" -> response(302, headers = mapOf("Location" to fx.url("cdn.implayer.tv", "/files/implayer-3.0.apk")))
                req.url.encodedPath == "/bad" -> response(302, headers = mapOf("Location" to fx.url("evil.example", "/x.apk")))
                else -> rangeDispatch(apk)(req)
            }
        }
        assertEquals("3.0", assertIs<SourceResult.Success>(resolve()).release.versionName)
        val blocked = assertIs<SourceResult.Failure>(resolve(fx.url("implayer.tv", "/bad"))).failure
        assertIs<SourceFailure.BlockedHost>(blocked)
    }

    @Test fun `the file name is sanitised before it is shown or used`() {
        val apk = ApkFixture.apk(tmp.newFile("s.apk"), "com.myiptvonline.implayer", 1, "1.0").readBytes()
        fx.dispatch { req -> rangeDispatch(apk)(req).newBuilder().addHeader("Content-Disposition", "attachment; filename=\"../../etc/pa ss<>wd.apk\"").build() }
        val name = assertIs<SourceResult.Success>(resolve()).release.asset.fileName
        assertEquals("passwd.apk", name)
    }
}
