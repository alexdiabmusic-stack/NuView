package app.tvessentials.core.catalog

import app.tvessentials.core.testing.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps the Android manifest's security-relevant declarations in lock-step with the catalog and the requirements. */
class ManifestConsistencyTest {
    private val manifest: String = File(checkNotNull(System.getProperty("tve.manifest"))).readText()

    private fun values(regex: Regex) = regex.findAll(manifest).map { it.groupValues[1] }.toList()

    @Test fun `package visibility is exactly the catalog's package ids`() {
        val block = Regex("<queries>.*?</queries>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        val queried = Regex("<package\\s+android:name=\"([^\"]+)\"").findAll(block).map { m -> m.groupValues[1] }.toSet()
        val catalog = Fixtures.catalog.apps.flatMap { it.allPackageIds }.toSet()
        assertEquals(catalog, queried, "<queries> must list every catalog package and nothing else")
    }

    @Test fun `only the three permissions the product needs are requested`() {
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.REQUEST_INSTALL_PACKAGES"),
            values(Regex("<uses-permission\\s+android:name=\"([^\"]+)\"")).toSet(),
        )
        assertTrue("QUERY_ALL_PACKAGES" !in manifest && "INSTALL_PACKAGES\"" !in manifest.replace("REQUEST_INSTALL_PACKAGES", ""), "no privileged install permission")
    }

    @Test fun `traffic and data hygiene flags are set`() {
        assertTrue("android:usesCleartextTraffic=\"false\"" in manifest)
        assertTrue("android:allowBackup=\"false\"" in manifest)
        assertTrue("android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
    }

    @Test fun `it is a tv launcher app that needs no touch screen`() {
        assertTrue("android.intent.category.LEANBACK_LAUNCHER" in manifest)
        assertTrue(Regex("android.hardware.touchscreen\"\\s+android:required=\"false\"").containsMatchIn(manifest))
        assertTrue("android:banner=\"@drawable/tv_banner\"" in manifest)
    }

    @Test fun `only the launcher activity is exported`() {
        val exported = Regex("<(activity|receiver|service|provider)[^>]*?android:name=\"([^\"]+)\"[^>]*?android:exported=\"(true|false)\"", RegexOption.DOT_MATCHES_ALL).findAll(manifest)
            .map { it.groupValues[2] to it.groupValues[3] }.toList()
        assertEquals(listOf(".MainActivity" to "true", ".platform.InstallResultReceiver" to "false"), exported)
        assertTrue("FileProvider" !in manifest, "no file sharing surface: installs stream from private storage into the installer session")
    }
}
