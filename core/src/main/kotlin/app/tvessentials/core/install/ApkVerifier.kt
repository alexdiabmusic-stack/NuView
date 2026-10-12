package app.tvessentials.core.install

import app.tvessentials.core.apk.ApkInspector
import app.tvessentials.core.apk.ApkStructure
import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.apk.Problem
import app.tvessentials.core.source.AbiTokens
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.trust.TrustEvaluator
import app.tvessentials.core.trust.TrustPolicy
import app.tvessentials.core.trust.TrustVerdict
import app.tvessentials.core.trust.TrustedSigner
import app.tvessentials.core.util.Digests
import app.tvessentials.core.version.Version
import java.io.File

/** What the release metadata promised about a file. Everything is checked against the real file. */
data class ExpectedApk(
    val packageId: String,
    val versionName: String?,
    val sizeBytes: Long?,
    val sha256: String?,
)

enum class VerifyError {
    FILE_MISSING,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    NOT_AN_APK,
    BUNDLE_NOT_SUPPORTED,
    UNREADABLE_BY_SYSTEM,
    WRONG_PACKAGE,
    UNSIGNED,
    SIGNER_NOT_TRUSTED,
    SIGNER_REVOKED,
    TRUST_NOT_CONFIGURED,
    UNSUPPORTED_ANDROID,
    INCOMPATIBLE_ABI,
    VERSION_MISMATCH,
    WOULD_DOWNGRADE,
    ALREADY_CURRENT,
    SIGNER_CHANGED,
    INCONSISTENT_METADATA,
}

sealed interface Verification {
    data class Passed(val archive: ArchiveInfo, val signer: TrustedSigner, val sha256: String) : Verification
    data class Failed(val error: VerifyError, val detail: String) : Verification
}

/**
 * The gate between "a file was downloaded" and "the system installer sees it".
 * Every check fails closed; the first failure wins and is reported with a machine-readable reason.
 */
class ApkVerifier(
    private val inspect: (File) -> ApkStructure = ApkInspector::inspect,
) {
    fun verify(
        file: File,
        expected: ExpectedApk,
        policy: TrustPolicy,
        device: DeviceProfile,
        installed: InstalledPackage?,
        archive: (File) -> ArchiveInfo?,
    ): Verification {
        if (!file.isFile) return fail(VerifyError.FILE_MISSING, "File not found")
        if (expected.sizeBytes != null && file.length() != expected.sizeBytes) {
            return fail(VerifyError.SIZE_MISMATCH, "Expected ${expected.sizeBytes} bytes, got ${file.length()}")
        }
        val actualSha = Digests.sha256(file)
        if (expected.sha256 != null && !expected.sha256.equals(actualSha, ignoreCase = true)) {
            return fail(VerifyError.HASH_MISMATCH, "SHA-256 differs from the published value")
        }

        val structure = inspect(file)
        if (!structure.isAcceptable) {
            val bundle = Problem.BUNDLE_NOT_SUPPORTED in structure.problems
            return fail(if (bundle) VerifyError.BUNDLE_NOT_SUPPORTED else VerifyError.NOT_AN_APK, structure.problems.joinToString())
        }
        val manifest = structure.manifest ?: return fail(VerifyError.NOT_AN_APK, "No manifest")

        val info = archive(file) ?: return fail(VerifyError.UNREADABLE_BY_SYSTEM, "The system could not read or verify this APK")
        if (info.packageId != expected.packageId) {
            return fail(VerifyError.WRONG_PACKAGE, "Expected ${expected.packageId} but the file is ${info.packageId}")
        }
        // Two independent parsers must agree; a differential is treated as a malformed file.
        if (manifest.packageId != info.packageId || (manifest.versionCode != null && manifest.versionCode != info.versionCode)) {
            return fail(VerifyError.INCONSISTENT_METADATA, "Manifest disagrees with the system's parse")
        }

        val signer = when (val v = TrustEvaluator.evaluate(policy, info.signerSha256, info.lineageSha256)) {
            is TrustVerdict.Trusted -> v.signer
            is TrustVerdict.Revoked -> return fail(VerifyError.SIGNER_REVOKED, v.reason)
            is TrustVerdict.UntrustedSigner -> return fail(VerifyError.SIGNER_NOT_TRUSTED, "Signing certificate is not on the reviewed list")
            TrustVerdict.Unsigned -> return fail(VerifyError.UNSIGNED, "No valid signature")
            TrustVerdict.PolicyNotConfigured -> return fail(VerifyError.TRUST_NOT_CONFIGURED, "No reviewed signing certificate for this app")
        }

        val minSdk = info.minSdk ?: manifest.minSdk
        if (minSdk != null && minSdk > device.sdkInt) {
            return fail(VerifyError.UNSUPPORTED_ANDROID, "Needs Android API $minSdk, device has ${device.sdkInt}")
        }
        if (structure.nativeAbis.isNotEmpty()) {
            val deviceAbis = AbiTokens.usable(device.supportedAbis).toSet()
            if (structure.nativeAbis.none { it in deviceAbis }) {
                return fail(VerifyError.INCOMPATIBLE_ABI, "APK ships ${structure.nativeAbis.joinToString()}, device runs ${deviceAbis.joinToString()}")
            }
        }

        // The release feed's version label must match what is actually inside the file.
        val labelled = Version.parseOrNull(expected.versionName)
        val actual = Version.parseOrNull(info.versionName)
        if (labelled != null && actual != null && !labelled.isEquivalentTo(actual)) {
            return fail(VerifyError.VERSION_MISMATCH, "Release says ${expected.versionName}, file is ${info.versionName}")
        }

        if (installed != null) {
            if (!TrustEvaluator.isSameSigningIdentity(installed.signerSha256, info.signerSha256, info.lineageSha256)) {
                return fail(VerifyError.SIGNER_CHANGED, "Installed copy is signed by a different certificate")
            }
            if (info.versionCode < installed.versionCode) return fail(VerifyError.WOULD_DOWNGRADE, "File is older than the installed version")
            if (info.versionCode == installed.versionCode) return fail(VerifyError.ALREADY_CURRENT, "This version is already installed")
        }
        return Verification.Passed(info, signer, actualSha)
    }

    private fun fail(error: VerifyError, detail: String) = Verification.Failed(error, detail)
}
