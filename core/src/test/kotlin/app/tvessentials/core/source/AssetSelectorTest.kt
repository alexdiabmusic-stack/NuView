package app.tvessentials.core.source

import app.tvessentials.core.catalog.AbiPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetSelectorTest {
    private fun a(name: String) = AssetCandidate(name, "https://github.com/o/r/releases/download/t/$name", 1, null)

    // The exact asset names published in NuvioMedia/NuvioTV release 1.0.0 (recon, 2026-10-12).
    private val nuvio = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86", "x86_64").map { a("app-full-$it-release.apk") }
    private val nuvioSelector = AssetSelector(
        Regex("^app-full-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)-release\\.apk$"), emptyList(),
        AbiPolicy.PREFER_DEVICE_ABI, listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
    )

    // Exact assets of yuliskov/SmartTube release 32.56s.
    private val smart = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86").map { a("SmartTube_stable_32.56_$it.apk") }
    private val smartSelector = AssetSelector(
        Regex("^SmartTube_stable_\\d+(?:\\.\\d+)*_(arm64-v8a|armeabi-v7a|x86|universal)\\.apk$"), emptyList(),
        AbiPolicy.PREFER_UNIVERSAL, listOf("arm64-v8a", "armeabi-v7a"),
    )

    private fun device(vararg abis: String) = DeviceProfile(sdkInt = 28, supportedAbis = abis.toList())
    private fun chosen(sel: AssetSelection) = assertIs<AssetSelection.Chosen>(sel).chosen

    @Test fun `nuvio picks the device's own build, never the 223 MB universal one when avoidable`() {
        assertEquals("app-full-arm64-v8a-release.apk", chosen(nuvioSelector.select(nuvio, device("arm64-v8a", "armeabi-v7a", "armeabi"))).asset.name)
    }

    @Test fun `a 32-bit Fire TV userland gets the armv7 build even on 64-bit capable hardware`() {
        // Fire OS 7 sticks report only 32-bit ABIs.
        assertEquals("app-full-armeabi-v7a-release.apk", chosen(nuvioSelector.select(nuvio, device("armeabi-v7a", "armeabi"))).asset.name)
    }

    @Test fun `x86 and x86_64 are never confused with each other`() {
        assertEquals("app-full-x86_64-release.apk", chosen(nuvioSelector.select(nuvio, device("x86_64", "x86"))).asset.name)
        assertEquals("app-full-x86-release.apk", chosen(nuvioSelector.select(nuvio, device("x86"))).asset.name)
    }

    @Test fun `falls back to universal when the exact build is not offered`() {
        val only = nuvio.filter { it.name.contains("universal") || it.name.contains("x86") }
        val c = chosen(nuvioSelector.select(only, device("arm64-v8a")))
        assertEquals("universal", c.abi)
    }

    @Test fun `smarttube prefers the universal build when it covers the device`() {
        assertEquals("SmartTube_stable_32.56_universal.apk", chosen(smartSelector.select(smart, device("armeabi-v7a", "armeabi"))).asset.name)
        assertEquals("SmartTube_stable_32.56_universal.apk", chosen(smartSelector.select(smart, device("arm64-v8a", "armeabi-v7a"))).asset.name)
    }

    @Test fun `smarttube's universal build lacks x86 libraries so an x86 device gets the x86 build`() {
        assertEquals("SmartTube_stable_32.56_x86.apk", chosen(smartSelector.select(smart, device("x86_64", "x86"))).asset.name)
    }

    @Test fun `bundles, other formats and non matching names are never eligible`() {
        val junk = listOf("app-full-universal-release.aab", "app-full-universal-release.xapk", "app-full-universal-release.apks",
            "app-full-universal-debug.apk", "mobile-universal-release.apk", "SmartTube_beta_32.56_universal.apk").map(::a)
        assertIs<AssetSelection.None>(nuvioSelector.select(junk, device("arm64-v8a")))
        assertIs<AssetSelection.None>(smartSelector.select(junk, device("arm64-v8a")))
    }

    @Test fun `exclusion patterns remove mobile or debug builds`() {
        val sel = AssetSelector(Regex(".*\\.apk"), listOf(Regex("(?i)mobile|debug")), AbiPolicy.PREFER_DEVICE_ABI, emptyList())
        val c = chosen(sel.select(listOf(a("app-mobile-arm64-v8a.apk"), a("app-tv-arm64-v8a.apk")), device("arm64-v8a")))
        assertEquals("app-tv-arm64-v8a.apk", c.asset.name)
    }

    @Test fun `a single unlabelled apk is accepted as any-abi`() {
        val sel = AssetSelector(Regex(".*\\.apk"), emptyList(), AbiPolicy.PREFER_DEVICE_ABI, emptyList())
        assertEquals("any", chosen(sel.select(listOf(a("app.apk")), device("arm64-v8a"))).abi)
    }

    @Test fun `explanations are provided when nothing fits`() {
        val none = assertIs<AssetSelection.None>(nuvioSelector.select(nuvio.filter { it.name.contains("x86") && !it.name.contains("64") }, device("arm64-v8a")))
        assertTrue(none.reason.isNotBlank())
    }

    @Test fun `abi tokens are read with proper word boundaries`() {
        assertEquals("x86_64", AbiTokens.of("app-full-x86_64-release.apk"))
        assertEquals("x86", AbiTokens.of("SmartTube_stable_32.56_x86.apk"))
        assertEquals("arm64-v8a", AbiTokens.of("x-arm64-v8a.apk"))
        assertEquals(null, AbiTokens.of("max86thing.apk"))
        assertEquals(null, AbiTokens.of("universalapp.apk"))
    }
}
