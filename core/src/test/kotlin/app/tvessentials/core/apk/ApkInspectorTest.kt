package app.tvessentials.core.apk

import app.tvessentials.core.testing.ApkFixture
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a well formed apk is accepted and described`() {
        val f = ApkFixture.apk(tmp.newFile("a.apk"), "com.example.app", 7, "2.1.0", abis = listOf("arm64-v8a", "armeabi-v7a"))
        val s = ApkInspector.inspect(f)
        assertTrue(s.isAcceptable, s.problems.toString())
        assertEquals("com.example.app", s.manifest?.packageId)
        assertEquals(7L, s.manifest?.versionCode)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), s.nativeAbis)
        assertEquals(1, s.dexCount)
    }

    @Test fun `html masquerading as an apk is rejected by content, not by name`() {
        val f = tmp.newFile("fake.apk").also { it.writeText("<!DOCTYPE html><html>Just a moment...</html>") }
        assertEquals(listOf(Problem.NOT_A_ZIP), ApkInspector.inspect(f).problems)
    }

    @Test fun `empty and truncated files are rejected`() {
        assertEquals(listOf(Problem.NOT_A_ZIP), ApkInspector.inspect(tmp.newFile("empty.apk")).problems)
        val good = ApkFixture.apk(tmp.newFile("good.apk"), padding = 5000).readBytes()
        val cut = tmp.newFile("cut.apk").also { it.writeBytes(good.copyOf(good.size / 2)) }
        assertEquals(listOf(Problem.NOT_A_ZIP), ApkInspector.inspect(cut).problems)
    }

    @Test fun `split bundles are not installable and say so`() {
        assertTrue(Problem.BUNDLE_NOT_SUPPORTED in ApkInspector.inspect(ApkFixture.bundle(tmp.newFile("b.xapk"))).problems)
    }

    @Test fun `a zip without a manifest or without code is rejected`() {
        assertTrue(Problem.NO_MANIFEST in ApkInspector.inspect(ApkFixture.zipWithEntries(tmp.newFile("z.apk"), "readme.txt")).problems)
        val nocode = ApkFixture.apk(tmp.newFile("nocode.apk"), withDex = false)
        assertTrue(Problem.NO_CODE in ApkInspector.inspect(nocode).problems)
    }

    @Test fun `a corrupt manifest is rejected`() {
        val f = ApkFixture.apk(tmp.newFile("c.apk"), manifestOverride = ByteArray(200) { 9 })
        assertTrue(Problem.MANIFEST_UNREADABLE in ApkInspector.inspect(f).problems)
    }

    @Test fun `path traversal entry names are flagged`() {
        val f = ApkFixture.zipWithEntries(tmp.newFile("slip.apk"), "../../evil.so", "AndroidManifest.xml")
        assertTrue(Problem.SUSPICIOUS_ENTRY_NAME in ApkInspector.inspect(f).problems)
    }
}
