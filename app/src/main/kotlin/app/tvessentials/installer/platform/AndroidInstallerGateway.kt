package app.tvessentials.installer.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.tvessentials.core.install.InstallFailureKind
import app.tvessentials.core.install.InstallOutcome
import app.tvessentials.core.install.InstallerGateway
import app.tvessentials.core.util.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs through [PackageInstaller] sessions. The APK bytes are streamed straight from the app's private
 * storage into the session (no world-readable file, no shared URI needed) and re-hashed on the way, so what the
 * system installs is provably what was verified. Android always shows its own confirmation; nothing is silent.
 */
class AndroidInstallerGateway(
    private val context: Context,
    private val events: InstallEvents,
    private val userDecisionTimeoutMs: Long = 15 * 60_000L,
) : InstallerGateway {

    private val pm get() = context.packageManager

    override fun canRequestInstalls(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        pm.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    override fun openInstallPermissionSettings(): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 26) add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (i in candidates) {
            try {
                if (i.resolveActivity(pm) != null) {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                }
            } catch (_: Exception) { /* try the next, broader screen */ }
        }
        return false
    }

    override suspend fun install(file: File, packageId: String, verifiedSha256: String): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        // Tidy up sessions an earlier run may have left half-done.
        runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageId) // the system itself rejects an APK for any other package
            setSize(file.length())
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            return@withContext InstallOutcome.Failed(InstallFailureKind.OTHER, "createSession: ${e.message}")
        } catch (e: SecurityException) {
            return@withContext InstallOutcome.Failed(InstallFailureKind.BLOCKED, "createSession: ${e.message}")
        }

        var committed = false
        try {
            val session = installer.openSession(sessionId)
            session.use { s ->
                val md = MessageDigest.getInstance("SHA-256")
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    s.fsync(out)
                }
                if (!Hex.encode(md.digest()).equals(verifiedSha256, ignoreCase = true)) {
                    // The file changed between verification and installation. Refuse.
                    return@withContext InstallOutcome.Failed(InstallFailureKind.INVALID, "file changed after verification")
                }

                val result = events.expect(sessionId)
                s.commit(statusIntent(sessionId).intentSender)
                committed = true
                val outcome = withTimeoutOrNull(userDecisionTimeoutMs) { result.await() }
                outcome ?: InstallOutcome.Failed(InstallFailureKind.TIMEOUT, "no decision from the system installer")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            InstallOutcome.Failed(InstallFailureKind.STORAGE, "write: ${e.message}")
        } catch (e: SecurityException) {
            InstallOutcome.Failed(InstallFailureKind.BLOCKED, "session: ${e.message}")
        } catch (e: RuntimeException) {
            InstallOutcome.Failed(InstallFailureKind.OTHER, "session: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            events.forget(sessionId)
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(ACTION_STATUS)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    companion object {
        const val ACTION_STATUS = "app.tvessentials.installer.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "app.tvessentials.installer.SESSION_ID"
    }
}
