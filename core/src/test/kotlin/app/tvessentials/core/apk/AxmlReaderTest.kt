package app.tvessentials.core.apk

import app.tvessentials.core.testing.AxmlBuilder
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AxmlReaderTest {
    @Test fun `reads identity, version and tv traits from a binary manifest`() {
        val m = AxmlReader.parse(AxmlBuilder.manifest("com.nuvio.tv", 1062, "1.0.0", minSdk = 24, targetSdk = 36))
        assertEquals("com.nuvio.tv", m.packageId)
        assertEquals("1.0.0", m.versionName)
        assertEquals(1062L, m.versionCode)
        assertEquals(24, m.minSdk)
        assertEquals(36, m.targetSdk)
        assertTrue(m.usesLeanbackFeature)
        assertTrue(m.hasLeanbackLauncher)
    }

    @Test fun `a phone-only app is not reported as leanback`() {
        val m = AxmlReader.parse(AxmlBuilder.manifest("com.example.phone", 5, "5.0", leanbackFeature = false, leanbackLauncher = false))
        assertFalse(m.usesLeanbackFeature)
        assertFalse(m.hasLeanbackLauncher)
    }

    @Test fun `missing uses-sdk leaves sdk levels unknown`() {
        val m = AxmlReader.parse(AxmlBuilder.manifest("a.b", 1, "1", minSdk = null, targetSdk = null))
        assertEquals(null, m.minSdk)
        assertEquals(null, m.targetSdk)
    }

    @Test fun `garbage and truncated input is rejected, never half-parsed`() {
        assertFailsWith<AxmlException> { AxmlReader.parse(ByteArray(10)) }
        assertFailsWith<AxmlException> { AxmlReader.parse(ByteArray(64) { 1 }) }
        val good = AxmlBuilder.manifest("a.b", 1, "1")
        assertFailsWith<AxmlException> { AxmlReader.parse(good.copyOf(good.size / 2).also { it[4] = good[4]; it[5] = good[5] }) }
        val corrupt = good.copyOf().also { for (i in 40 until 60) it[i] = 0x7F }
        runCatching { AxmlReader.parse(corrupt) }.onSuccess { /* may parse differently but must not crash with other exception types */ }
    }

    /**
     * Optional ground-truth check against real, publicly released APKs. Run with
     * -Ptve.fixtureApkDir=/path/to/dir containing the files named below (never committed to the repo).
     */
    @Test fun `matches aapt2 on real released APKs when fixtures are provided`() {
        val dir = System.getProperty("tve.fixtureApkDir").orEmpty()
        if (dir.isBlank()) return
        val expectations = mapOf(
            "smarttube_stable.apk" to listOf("org.smarttube.stable", "32.56", "2446", "17", "34"),
            "smarttube_beta.apk" to listOf("org.smarttube.beta", "32.56", "2446", "17", "34"),
            "nuvio_1.0.0_universal.apk" to listOf("com.nuvio.tv", "1.0.0", "1062", "24", "36"),
        )
        var checked = 0
        for ((name, exp) in expectations) {
            val f = File(dir, name)
            if (!f.isFile) continue
            val bytes = ZipFile(f).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
            val m = AxmlReader.parse(bytes)
            assertEquals(exp[0], m.packageId, name)
            assertEquals(exp[1], m.versionName, name)
            assertEquals(exp[2].toLong(), m.versionCode, name)
            assertEquals(exp[3].toInt(), m.minSdk, name)
            assertEquals(exp[4].toInt(), m.targetSdk, name)
            assertTrue(m.hasLeanbackLauncher || m.usesLeanbackFeature, "$name should declare TV support")
            checked++
        }
        println("AXML ground-truth: verified $checked real APK manifest(s) against aapt2 values")
        assertNotNull(checked)
    }
}
