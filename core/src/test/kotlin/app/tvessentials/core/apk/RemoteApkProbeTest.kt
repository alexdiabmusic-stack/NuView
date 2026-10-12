package app.tvessentials.core.apk

import app.tvessentials.core.testing.ApkFixture
import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.rangeDispatch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteApkProbeTest {
    private val fx = TlsFixture()
    @get:Rule val tmp = TemporaryFolder()
    @AfterTest fun tearDown() = fx.close()

    private fun probe(total: Long) = runBlocking {
        RemoteApkProbe(fx.http, fx.policy("github.com")).readManifest(fx.url("github.com", "/a.apk"), total)
    }

    @Test fun `reads the manifest of a multi megabyte apk with only a few small range requests`() {
        val apk = ApkFixture.apk(tmp.newFile("big.apk"), "com.example.big", 321, "3.2.1", minSdk = 21, abis = listOf("arm64-v8a"), padding = 3_000_000).readBytes()
        var requests = 0; var bytesServed = 0L
        fx.dispatch { req ->
            requests++
            rangeDispatch(apk)(req).also { bytesServed += it.body?.contentLength ?: 0 }
        }
        val m = probe(apk.size.toLong())
        assertEquals("com.example.big", m?.packageId)
        assertEquals("3.2.1", m?.versionName)
        assertEquals(321L, m?.versionCode)
        assertEquals(21, m?.minSdk)
        assertTrue(requests <= 4, "used $requests requests")
        assertTrue(bytesServed < 200_000, "transferred $bytesServed bytes for a ${apk.size}-byte file")
    }

    @Test fun `a server that ignores ranges is never read in full`() {
        val apk = ApkFixture.apk(tmp.newFile("x.apk"), padding = 1_000_000).readBytes()
        fx.dispatch(rangeDispatch(apk, ignoreRange = true))
        assertNull(probe(apk.size.toLong()))
    }

    @Test fun `junk and lies about the size give null instead of exceptions`() {
        fx.dispatch(rangeDispatch(ByteArray(5000) { 3 }))
        assertNull(probe(5000))
        assertNull(probe(10))
        assertNull(probe(10_000_000_000))
    }

    /** Optional ground truth: a real, publicly released AGP-built APK (has an APK Signing Block before the central directory). */
    @Test fun `reads the manifest of a real released apk over ranges when a fixture is provided`() {
        val dir = System.getProperty("tve.fixtureApkDir").orEmpty()
        val f = java.io.File(dir, "smarttube_stable.apk")
        if (dir.isBlank() || !f.isFile) return
        val bytes = f.readBytes()
        var served = 0L
        fx.dispatch { req -> rangeDispatch(bytes)(req).also { served += it.body?.contentLength ?: 0 } }
        val m = probe(bytes.size.toLong())
        assertEquals("org.smarttube.stable", m?.packageId)
        assertEquals("32.56", m?.versionName)
        assertEquals(2446L, m?.versionCode)
        assertEquals(17, m?.minSdk)
        assertTrue(served < 400_000, "read $served of ${bytes.size} bytes")
        println("RemoteApkProbe ground-truth: read the manifest of a ${bytes.size / 1_000_000} MB real APK using $served bytes")
    }
}
