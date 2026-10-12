package app.tvessentials.installer

import android.app.Application
import android.os.Build
import app.tvessentials.core.catalog.CatalogParser
import app.tvessentials.core.download.ApkDownloader
import app.tvessentials.core.download.DownloadCache
import app.tvessentials.core.install.InstallManager
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.source.DirectApkResolver
import app.tvessentials.core.source.FileHttpCache
import app.tvessentials.core.source.FileReleaseStore
import app.tvessentials.core.source.GitHubReleasesResolver
import app.tvessentials.core.source.ReleaseRepository
import app.tvessentials.core.source.SignedManifestResolver
import app.tvessentials.installer.platform.AndroidConnectivity
import app.tvessentials.installer.platform.AndroidInstallerGateway
import app.tvessentials.installer.platform.AndroidPackageGateway
import app.tvessentials.installer.platform.AndroidStorage
import app.tvessentials.installer.platform.InstallEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired object graph: small enough that a DI framework would only cost start-up time on old Fire TV hardware. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val versionName: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }

    val catalog = app.assets.open("catalog/catalog.json").use { CatalogParser.parse(it) }
    val device = DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // No device identifiers: just the app name and version.
    val http = SafeHttp(okHttp, userAgent = "TVEssentials/$versionName")

    val prefs = Prefs(app)
    val installEvents = InstallEvents()
    val packages = AndroidPackageGateway(app)
    val connectivity = AndroidConnectivity(app)
    private val storage = AndroidStorage()
    val installer = AndroidInstallerGateway(app, installEvents)

    val downloads = DownloadCache(File(app.filesDir, "apks"))
    private val releaseStore = FileReleaseStore(File(app.filesDir, "releases"))
    private val httpCache = FileHttpCache(File(app.filesDir, "http-cache"))

    val repository = ReleaseRepository(
        github = GitHubReleasesResolver(http, httpCache),
        direct = DirectApkResolver(http),
        manifest = SignedManifestResolver(http),
        store = releaseStore,
    )

    val manager = InstallManager(
        catalog = catalog, device = device, repository = repository,
        downloader = ApkDownloader(http, storage), downloads = downloads,
        packages = packages, installer = installer, connectivity = connectivity, storage = storage,
        pending = SharedPrefsPending(app), scope = scope,
        log = { android.util.Log.i("TVEssentials", it) },
    )

    fun clearAllCaches() {
        repository.clearCache()
        httpCache.clear()
    }
}
