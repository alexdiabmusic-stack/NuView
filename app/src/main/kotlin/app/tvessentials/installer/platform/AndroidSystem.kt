package app.tvessentials.installer.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import android.provider.Settings
import app.tvessentials.core.install.ConnectivityGateway
import app.tvessentials.core.install.StorageGateway
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

class AndroidStorage : StorageGateway {
    override fun freeBytes(dir: File): Long = try {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L // unknown: the download proceeds and the write itself reports a full disk
    }
}

class AndroidConnectivity(context: Context) : ConnectivityGateway {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true // never claim "offline" on a platform hiccup; real requests will report their own failures
    }

    /** Live connectivity for the header indicator. */
    val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(isOnline()) }
        }
        trySend(isOnline())
        try { cm.registerDefaultNetworkCallback(callback) } catch (_: Exception) { /* indicator simply stays at its initial value */ }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}

object MotionSettings {
    /** True when the user has turned system animations off (Fire TV: Settings > Accessibility). */
    fun reduced(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
}
