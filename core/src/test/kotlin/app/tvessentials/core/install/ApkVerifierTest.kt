package app.tvessentials.core.install

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.testing.ApkFixture
import app.tvessentials.core.testing.AxmlBuilder
import app.tvessentials.core.trust.RevokedSigner
import app.tvessentials.core.trust.TrustPolicy
import app.tvessentials.core.trust.TrustedSigner
import app.tvessentials.core.util.Digests
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApkVerifierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good = "1".repeat(64)
    private val other = "2".repeat(64)
    private val policy = TrustPolicy(listOf(TrustedSigner(good, "official")))
    private val device = DeviceProfile(28, listOf("arm64-v8a", "armeabi-v7a"))
    private val pkg = "com.example.app"

    private fun apk(version: String = "1.2.0", code: Int = 120, id: String = pkg, abis: List<String> = listOf("arm64-v8a"), minSdk: Int? = 24): File =
        ApkFixture.apk(File(tmp.root, "t${System.nanoTime()}.apk"), id, code, version, minSdk = minSdk, abis = abis, padding = 1000)

    private fun info(file: File, id: String = pkg, code: Long = 120, name: String? = "1.2.0", signers: Set<String> = setOf(good), lineage: Set<String> = emptySet(), minSdk: Int? = 24) =
        ArchiveInfo(id, name, code, minSdk, signers, lineage)

    private fun expected(file: File, version: String? = "1.2.0", id: String = pkg, sha: String? = Digests.sha256(file), size: Long? = file.length()) =
        ExpectedApk(id, version, size, sha)

    private fun run(
        file: File = apk(), exp: ExpectedApk = expected(file), p: TrustPolicy = policy, d: DeviceProfile = device,
        installed: InstalledPackage? = null, archive: (File) -> ArchiveInfo? = { info(it) },
    ) = ApkVerifier().verify(file, exp, p, d, installed, archive)

    private fun failed(v: Verification, e: VerifyError) {
        val f = assertIs<Verification.Failed>(v)
        assertEquals(e, f.error, f.detail)
    }

    @Test fun `a genuine, trusted, compatible apk passes and reports the hash that was verified`() {
        val f = apk()
        val v = assertIs<Verification.Passed>(run(f))
        assertEquals(Digests.sha256(f), v.sha256)
        assertEquals("official", v.signer.label)
    }

    @Test fun `missing, resized and corrupted files are rejected before anything else`() {
        failed(run(File(tmp.root, "nope.apk"), expected(apk())), VerifyError.FILE_MISSING)
        val f = apk()
        failed(run(f, expected(f, size = f.length() + 1)), VerifyError.SIZE_MISMATCH)
        failed(run(f, expected(f, sha = "0".repeat(64))), VerifyError.HASH_MISMATCH)
    }

    @Test fun `content that is not an apk is rejected even if hash and size match`() {
        val f = tmp.newFile("page.apk").also { it.writeText("<html>Just a moment</html>") }
        failed(run(f, expected(f)), VerifyError.NOT_AN_APK)
        val b = ApkFixture.bundle(tmp.newFile("b.apk"))
        failed(run(b, expected(b)), VerifyError.BUNDLE_NOT_SUPPORTED)
    }

    @Test fun `if the system cannot parse or verify the apk it is not installed`() {
        failed(run(archive = { null }), VerifyError.UNREADABLE_BY_SYSTEM)
    }

    @Test fun `a different package than promised is rejected`() {
        val f = apk(id = "com.evil.app")
        failed(run(f, expected(f), archive = { info(it, id = "com.evil.app") }), VerifyError.WRONG_PACKAGE)
    }

    @Test fun `the two manifest parsers must agree`() {
        val f = apk(code = 120)
        failed(run(f, expected(f), archive = { info(it, code = 999) }), VerifyError.INCONSISTENT_METADATA)
    }

    @Test fun `signing identity is enforced against the reviewed policy`() {
        failed(run(archive = { info(it, signers = setOf(other)) }), VerifyError.SIGNER_NOT_TRUSTED)
        failed(run(archive = { info(it, signers = emptySet()) }), VerifyError.UNSIGNED)
        failed(run(p = TrustPolicy()), VerifyError.TRUST_NOT_CONFIGURED)
        failed(run(p = TrustPolicy(listOf(TrustedSigner(good, "o")), listOf(RevokedSigner(good, "key exposed")))), VerifyError.SIGNER_REVOKED)
    }

    @Test fun `apps that need a newer android are refused`() {
        val f = apk(minSdk = 33)
        failed(run(f, expected(f), archive = { info(it, minSdk = 33) }), VerifyError.UNSUPPORTED_ANDROID)
    }

    @Test fun `native code for the wrong cpu is refused`() {
        val f = apk(abis = listOf("x86_64"))
        failed(run(f, expected(f)), VerifyError.INCOMPATIBLE_ABI)
        val pure = apk(abis = emptyList())
        assertIs<Verification.Passed>(run(pure, expected(pure)))
    }

    @Test fun `the release label has to match the file`() {
        val f = apk(version = "1.2.0")
        failed(run(f, expected(f, version = "9.9.9")), VerifyError.VERSION_MISMATCH)
        assertIs<Verification.Passed>(run(f, expected(f, version = null)))
    }

    @Test fun `an install over an existing copy needs the same signer and a newer version`() {
        val installed = InstalledPackage(pkg, "1.0.0", 100, setOf(good))
        assertIs<Verification.Passed>(run(installed = installed))
        failed(run(installed = installed.copy(signerSha256 = setOf(other))), VerifyError.SIGNER_CHANGED)
        failed(run(installed = installed.copy(versionCode = 121)), VerifyError.WOULD_DOWNGRADE)
        failed(run(installed = installed.copy(versionCode = 120)), VerifyError.ALREADY_CURRENT)
    }

    @Test fun `a real aapt style manifest with a long version code is understood`() {
        val f = ApkFixture.apk(File(tmp.root, "m.apk"), manifestOverride = AxmlBuilder.manifest(pkg, 1068, "1.1.0-beta.5"))
        assertIs<Verification.Passed>(run(f, expected(f, version = "1.1.0-beta.5"), archive = { info(it, code = 1068, name = "1.1.0-beta.5") }))
    }
}
