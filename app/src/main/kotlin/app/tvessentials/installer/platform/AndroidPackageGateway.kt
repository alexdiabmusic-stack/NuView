package app.tvessentials.installer.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.install.PackageGateway
import app.tvessentials.core.util.Digests
import java.io.File

/**
 * Reads installed and downloaded packages through [PackageManager] only. Package visibility is limited to the
 * handful of ids declared in the manifest's <queries>, so no broad "list all apps" permission is needed.
 */
class AndroidPackageGateway(private val context: Context) : PackageGateway {
    private val pm: PackageManager get() = context.packageManager

    @Suppress("DEPRECATION")
    private val signatureFlags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    override fun installed(packageId: String): InstalledPackage? = try {
        val info = pm.getPackageInfo(packageId, signatureFlags)
        val (signers, lineage) = certificates(info)
        InstalledPackage(packageId, info.versionName, versionCode(info), signers, lineage)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    @Suppress("DEPRECATION")
    override fun archiveInfo(file: File): ArchiveInfo? = try {
        val info = pm.getPackageArchiveInfo(file.absolutePath, signatureFlags)
        if (info == null) null else {
            val (signers, lineage) = certificates(info)
            ArchiveInfo(
                packageId = info.packageName,
                versionName = info.versionName,
                versionCode = versionCode(info),
                minSdk = if (Build.VERSION.SDK_INT >= 24) info.applicationInfo?.minSdkVersion else null,
                signerSha256 = signers,
                lineageSha256 = lineage,
            )
        }
    } catch (_: RuntimeException) {
        null
    }

    override fun launch(packageId: String): Boolean = try {
        // TV-only apps often have no regular launcher activity, so ask for the leanback one first.
        val intent = pm.getLeanbackLaunchIntentForPackage(packageId) ?: pm.getLaunchIntentForPackage(packageId)
        if (intent == null) false else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: Exception) {
        false
    }

    override fun openAppInfo(packageId: String): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) == null) false else { context.startActivity(intent); true }
    } catch (_: Exception) {
        false
    }

    /** The app's own launcher icon as the system shows it, or null. Used only on-device; never redistributed. */
    fun launcherIcon(packageId: String): android.graphics.drawable.Drawable? = try {
        pm.getApplicationIcon(packageId)
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** (current signer digests, older certificates in the signing lineage). Digests are SHA-256 of the DER certificate. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Pair<Set<String>, Set<String>> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            if (si != null) {
                val current = si.apkContentsSigners?.map(::digest)?.toSet().orEmpty()
                val history = if (si.hasMultipleSigners()) emptySet() else si.signingCertificateHistory?.map(::digest)?.toSet().orEmpty()
                if (current.isNotEmpty()) return current to (history - current)
            }
        }
        val legacy = info.signatures?.map(::digest)?.toSet().orEmpty()
        return legacy to emptySet()
    }

    private fun digest(sig: Signature): String = Digests.sha256(sig.toByteArray())
}
