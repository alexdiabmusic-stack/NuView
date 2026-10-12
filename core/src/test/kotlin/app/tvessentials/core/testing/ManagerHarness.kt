package app.tvessentials.core.testing

import app.tvessentials.core.apk.ApkInspector
import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.catalog.DirectApkSource
import app.tvessentials.core.catalog.GitHubReleasesSource
import app.tvessentials.core.download.ApkDownloader
import app.tvessentials.core.download.DownloadCache
import app.tvessentials.core.install.ConnectivityGateway
import app.tvessentials.core.install.Effect
import app.tvessentials.core.install.InstallManager
import app.tvessentials.core.install.InstallOutcome
import app.tvessentials.core.install.InstallerGateway
import app.tvessentials.core.install.PackageGateway
import app.tvessentials.core.install.PendingInstall
import app.tvessentials.core.install.PendingInstallStore
import app.tvessentials.core.install.StorageGateway
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.source.InMemoryReleaseStore
import app.tvessentials.core.source.ReleaseAsset
import app.tvessentials.core.source.ReleaseRepository
import app.tvessentials.core.source.ReleaseSource
import app.tvessentials.core.source.ResolvedRelease
import app.tvessentials.core.source.SourceFailure
import app.tvessentials.core.source.SourceResult
import app.tvessentials.core.state.AppState
import app.tvessentials.core.state.CardDeriver
import app.tvessentials.core.state.CardModel
import app.tvessentials.core.util.Digests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class FakePackages : PackageGateway {
    val installedMap = java.util.concurrent.ConcurrentHashMap<String, InstalledPackage>()
    var archiveFor: (File) -> ArchiveInfo? = { null }
    val launched = CopyOnWriteArrayList<String>()
    var launchWorks = true
    val appInfoOpened = CopyOnWriteArrayList<String>()
    override fun installed(packageId: String) = installedMap[packageId]
    override fun archiveInfo(file: File) = archiveFor(file)
    override fun launch(packageId: String): Boolean { launched += packageId; return launchWorks }
    override fun openAppInfo(packageId: String): Boolean { appInfoOpened += packageId; return true }
}

class FakeInstaller(private val packages: FakePackages) : InstallerGateway {
    @Volatile var allowed = true
    val settingsOpened = java.util.concurrent.atomic.AtomicInteger()
    val installCalls = java.util.concurrent.atomic.AtomicInteger()
    val seenSha = CopyOnWriteArrayList<String>()
    /** Simulates the system dialog. By default the user confirms and Android registers the package. */
    @Volatile var behaviour: suspend (File, String, String) -> InstallOutcome = { file, pkg, _ ->
        val info = checkNotNull(packages.archiveFor(file))
        packages.installedMap[pkg] = InstalledPackage(pkg, info.versionName, info.versionCode, info.signerSha256, info.lineageSha256)
        InstallOutcome.Success
    }
    override fun canRequestInstalls() = allowed
    override fun openInstallPermissionSettings(): Boolean { settingsOpened.incrementAndGet(); return true }
    override suspend fun install(file: File, packageId: String, verifiedSha256: String): InstallOutcome {
        installCalls.incrementAndGet(); seenSha += verifiedSha256
        return behaviour(file, packageId, verifiedSha256)
    }
}

class FakePending : PendingInstallStore {
    @Volatile var value: PendingInstall? = null
    override fun get() = value
    override fun set(appId: String, atMs: Long) { value = PendingInstall(appId, atMs) }
    override fun clear() { value = null }
}

/** Serves a canned release; mirrors what a real resolver would return for the harness's APK. */
class FakeSource(var result: () -> SourceResult) : ReleaseSource<GitHubReleasesSource> {
    var calls = 0
    override suspend fun resolve(app: AppDefinition, source: GitHubReleasesSource, channel: Channel, device: DeviceProfile): SourceResult { calls++; return result() }
}
class FakeDirectSource(var result: () -> SourceResult) : ReleaseSource<DirectApkSource> {
    var calls = 0
    override suspend fun resolve(app: AppDefinition, source: DirectApkSource, channel: Channel, device: DeviceProfile): SourceResult { calls++; return result() }
}

/** Wires a real InstallManager (real downloader + HTTPS) to fakes for the Android-only gateways. */
class ManagerHarness(
    val tmp: File,
    val fx: TlsFixture,
    val appId: String = "nuvio-tv",
    val device: DeviceProfile = DeviceProfile(28, listOf("arm64-v8a", "armeabi-v7a")),
    http: SafeHttp = fx.http,
    var freeBytes: Long = Long.MAX_VALUE / 4,
    var online: Boolean = true,
    val pending: FakePending = FakePending(),
    val packages: FakePackages = FakePackages(),
    preferStable: Boolean = true,
) {
    val app: AppDefinition = Fixtures.app(appId)
    val goodSigner: String = app.trust.signers.firstOrNull()?.sha256 ?: "9".repeat(64)
    val installer = FakeInstaller(packages)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val apkVersion = "1.0.0"
    val apkCode = 1062
    val packageId: String = app.packageId(Channel.STABLE)!!
    val apk: File = ApkFixture.apk(File(tmp, "served.apk"), packageId, apkCode, apkVersion, abis = listOf("arm64-v8a"), padding = 400_000)
    val apkBytes: ByteArray = apk.readBytes()
    val apkSha: String = Digests.sha256(apkBytes)
    var requests = 0

    var releaseResult: () -> SourceResult = { SourceResult.Success(release()) }
    val source = FakeSource { releaseResult() }
    val directSource = FakeDirectSource { releaseResult() }
    var now = 1_000_000L
    val store = InMemoryReleaseStore()
    val repository = ReleaseRepository(source, directSource, null, store, clock = { now })
    val cache = DownloadCache(File(tmp, "downloads"))
    val downloader = ApkDownloader(http, StorageGateway { freeBytes }, retryDelayMs = { 0 }, safetyMarginBytes = 0)

    fun release(version: String = apkVersion, sha: String? = apkSha, size: Long? = apkBytes.size.toLong(), url: String = fx.url("github.com", "/dl/app.apk")) = ResolvedRelease(
        appId = appId, channel = Channel.STABLE, packageId = packageId, versionName = version, versionCode = null,
        asset = ReleaseAsset("app-full-arm64-v8a-release.apk", url, size, sha, "arm64-v8a"), resolvedAtMs = now,
    )

    init {
        packages.archiveFor = { f ->
            val m = ApkInspector.inspect(f).manifest
            m?.let { ArchiveInfo(it.packageId.orEmpty(), it.versionName, it.versionCode ?: 0, it.minSdk, setOf(goodSigner)) }
        }
        fx.dispatch(rangeDispatch(apkBytes) { requests++ })
    }

    val manager = InstallManager(
        catalog = Fixtures.catalog, device = device, repository = repository, downloader = downloader, downloads = cache,
        packages = packages, installer = installer, connectivity = ConnectivityGateway { online },
        storage = StorageGateway { freeBytes }, pending = pending, scope = scope, clock = { now },
        readyHoldMs = 40, successDisplayMs = 80, policyFactory = { fx.policy("api.github.com", "github.com", "*.githubusercontent.com", "implayer.tv") },
    ).also { it.setPreferStableForTest(preferStable) }

    val effects = CopyOnWriteArrayList<Effect>()
    val history = CopyOnWriteArrayList<AppState>()

    init {
        scope.launch { manager.effects.collect { effects += it } }
        scope.launch { manager.state.collect { list -> list.firstOrNull { it.app.id == appId }?.let { history += it } } }
    }

    fun state(): AppState = manager.state.value.first { it.app.id == appId }
    fun card(): CardModel = CardDeriver.derive(state())

    suspend fun awaitState(timeoutMs: Long = 8_000, what: String = "condition", predicate: (AppState) -> Boolean): AppState = try {
        withTimeout(timeoutMs) { manager.state.first { l -> l.firstOrNull { it.app.id == appId }?.let(predicate) == true }.first { it.app.id == appId } }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        error("Timed out waiting for $what. Last: phase=${state().phase} installed=${state().installed} release=${state().release?.versionName} history=${history.map { it.phase::class.simpleName }}")
    }

    fun close() { scope.cancel(); fx.close() }
}

private fun InstallManager.setPreferStableForTest(v: Boolean) { if (!v) setPreferStable(false) }
