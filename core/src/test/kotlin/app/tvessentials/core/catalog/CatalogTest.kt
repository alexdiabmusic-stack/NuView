package app.tvessentials.core.catalog

import app.tvessentials.core.net.HostPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatalogTest {
    private val text = checkNotNull(javaClass.classLoader.getResourceAsStream("catalog/catalog.json")) { "catalog.json not on test classpath" }
        .readBytes().toString(Charsets.UTF_8)
    private val catalog = CatalogParser.parse(text)

    @Test fun `the shipped catalog parses and validates`() {
        assertEquals(listOf("nuvio-tv", "implayer", "smarttube"), catalog.apps.map { it.id })
    }

    @Test fun `identities match what the official APKs were measured to be`() {
        val nuvio = catalog.apps.first { it.id == "nuvio-tv" }
        assertEquals("com.nuvio.tv", nuvio.packageId(Channel.STABLE))
        assertEquals("1cba06fbdf978d0e3f9ad58de6ba3d69e24a948384adde3aed95451e830e5210", nuvio.trust.signers.single().sha256)
        val st = catalog.apps.first { it.id == "smarttube" }
        assertEquals("org.smarttube.stable", st.packageId(Channel.STABLE))
        assertEquals("org.smarttube.beta", st.packageId(Channel.BETA))
        assertEquals("109735aa24fe85e2e1623ebb3b1ecc4bf4263f4c6374e4411d646bfa2eda482a", st.trust.signers.single().sha256)
        assertEquals("com.myiptvonline.implayer", catalog.apps.first { it.id == "implayer" }.packageId(Channel.STABLE))
    }

    @Test fun `smarttube carries the required independence disclosure`() {
        val d = catalog.apps.first { it.id == "smarttube" }.disclosure.orEmpty()
        assertTrue("not affiliated" in d && "Google" in d && "YouTube" in d)
    }

    @Test fun `sources point at the official repositories and no third party mirrors`() {
        val nuvio = assertIs<GitHubReleasesSource>(catalog.apps.first { it.id == "nuvio-tv" }.source)
        assertEquals("NuvioMedia" to "NuvioTV", nuvio.owner to nuvio.repo) // not NuvioMobile
        val st = assertIs<GitHubReleasesSource>(catalog.apps.first { it.id == "smarttube" }.source)
        assertEquals("yuliskov" to "SmartTube", st.owner to st.repo)
        val im = assertIs<DirectApkSource>(catalog.apps.first { it.id == "implayer" }.source)
        assertEquals("https://implayer.tv/d", im.url)
        for (app in catalog.apps) {
            val policy = HostPolicy(app.allowedHosts)
            for (bad in listOf("apkmirror.com", "apkpure.com", "implayer.us", "aptoide.com", "softonic.com")) {
                assertTrue(!policy.isAllowed(bad) && !policy.isAllowed("www.$bad"), "${app.id} must not allow $bad")
            }
        }
    }

    @Test fun `iMPlayer's pin is exactly the measured certificate and says whose decision it was`() {
        val im = catalog.apps.first { it.id == "implayer" }
        assertEquals("e1cacb9974064e78d6aefe2d78b5eea6f30592887589a1254648749973d7d766", im.trust.signers.single().sha256)
        assertTrue(im.trust.review.contains("OWNER-APPROVED"), "a pin without independent corroboration must say so")
        assertTrue(!im.trust.allowLineageRotation, "no key rotation is accepted without a reviewed catalog change")
        assertEquals("82375", im.guided?.downloaderCode, "the guided route stays as a fallback")
        assertEquals(setOf("implayer.tv", "*.implayer.tv", "implayer.nyc3.cdn.digitaloceanspaces.com"), im.allowedHosts.toSet())
    }

    private fun mutate(f: (String) -> String) = CatalogParser.parse(f(text))

    @Test fun `validation rejects insecure or sloppy catalog edits`() {
        assertFailsWith<CatalogException> { mutate { it.replace("\"url\": \"https://implayer.tv/d\"", "\"url\": \"http://implayer.tv/d\"") } }
        assertFailsWith<CatalogException> { mutate { it.replace("\"url\": \"https://implayer.tv/d\"", "\"url\": \"https://cdn.other.example/d\"") } }
        assertFailsWith<CatalogException> { mutate { it.replace("1cba06fbdf978d0e3f9ad58de6ba3d69e24a948384adde3aed95451e830e5210", "zzzz") } }
        assertFailsWith<CatalogException> { mutate { it.replaceFirst("\"*.githubusercontent.com\"", "\"*\"") } }
        assertFailsWith<CatalogException> { mutate { it.replace("\"smarttube\"", "\"nuvio-tv\"") } }
        assertFailsWith<CatalogException> { mutate { it.replace("org.smarttube.beta", "com.nuvio.tv") } }
        assertFailsWith<CatalogException> { mutate { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") } }
        assertFailsWith<CatalogException> { mutate { it.replace("\"accentColor\": \"#7C8CFF\"", "\"accentColor\": \"purple\"") } }
    }

    @Test fun `unknown fields are errors, so a typo in a security field cannot silently disable it`() {
        assertFailsWith<CatalogException> { mutate { it.replace("\"allowLineageRotation\": false,\n        \"review\": \"Pinned", "\"allowLineageRotaton\": true,\n        \"review\": \"Pinned") } }
    }

    @Test fun `a signer cannot be both trusted and revoked`() {
        val sha = "1cba06fbdf978d0e3f9ad58de6ba3d69e24a948384adde3aed95451e830e5210"
        assertFailsWith<CatalogException> {
            mutate { it.replaceFirst("\"revoked\": []", "\"revoked\": [{\"sha256\": \"$sha\", \"reason\": \"x\"}]") }
        }
    }
}
