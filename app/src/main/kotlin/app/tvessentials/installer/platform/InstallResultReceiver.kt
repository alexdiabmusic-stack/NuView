package app.tvessentials.installer.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.tvessentials.core.install.InstallFailureKind
import app.tvessentials.core.install.InstallOutcome
import app.tvessentials.installer.TvEssentialsApp

/** Receives PackageInstaller status callbacks. Not exported: only the system, via our PendingIntent, can reach it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val events = (context.applicationContext as? TvEssentialsApp)?.container?.installEvents ?: return
        val sessionId = intent.getIntExtra(AndroidInstallerGateway.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) events.requestUserAction(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> events.complete(sessionId, InstallOutcome.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> events.complete(sessionId, InstallOutcome.Cancelled)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.BLOCKED, message(intent)))
            PackageInstaller.STATUS_FAILURE_CONFLICT -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.CONFLICT, message(intent)))
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.INCOMPATIBLE, message(intent)))
            PackageInstaller.STATUS_FAILURE_INVALID -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.INVALID, message(intent)))
            PackageInstaller.STATUS_FAILURE_STORAGE -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.STORAGE, message(intent)))
            else -> events.complete(sessionId, InstallOutcome.Failed(InstallFailureKind.OTHER, message(intent)))
        }
    }

    private fun message(intent: Intent): String? = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
}
