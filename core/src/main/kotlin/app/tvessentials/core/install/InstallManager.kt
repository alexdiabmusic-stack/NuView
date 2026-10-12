package app.tvessentials.core.install

import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.catalog.AppDefinition
import app.tvessentials.core.catalog.Catalog
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.download.ApkDownloader
import app.tvessentials.core.download.DownloadCache
import app.tvessentials.core.download.DownloadFailure
import app.tvessentials.core.download.DownloadRequest
import app.tvessentials.core.net.HostPolicy
import app.tvessentials.core.net.NetFailure
import app.tvessentials.core.source.AbiTokens
import app.tvessentials.core.source.DeviceProfile
import app.tvessentials.core.source.ReleaseRepository
import app.tvessentials.core.source.ResolvedRelease
import app.tvessentials.core.state.AppState
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.CardDeriver
import app.tvessentials.core.state.ErrorKind
import app.tvessentials.core.state.Phase
import app.tvessentials.core.state.StagedApk
import app.tvessentials.core.trust.TrustEvaluator
import app.tvessentials.core.trust.TrustVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Remembers an install that was waiting for the user to allow "install unknown apps", across process death. */
interface PendingInstallStore {
    fun get(): PendingInstall?
    fun set(appId: String, atMs: Long)
    fun clear()
}

data class PendingInstall(val appId: String, val createdAtMs: Long)

/** One-shot things the UI must react to (as opposed to state it merely displays). */
sealed interface Effect {
    data class NeedInstallPermission(val appId: String) : Effect
    data class ShowGuide(val appId: String) : Effect
    data class LaunchFailed(val appId: String) : Effect
}

/**
 * Runs the whole install/update workflow for every app in the catalog and exposes the result as state.
 *
 * Guarantees:
 *  - success is reported only after the package manager itself shows the expected version installed
 *  - nothing is handed to the system installer unless it passed [ApkVerifier]
 *  - cancelling a download keeps the partial file; cancelling the system dialog keeps the verified file
 *  - one operation per app at a time; apps never block each other
 */
class InstallManager(
    catalog: Catalog,
    private val device: DeviceProfile,
    private val repository: ReleaseRepository,
    private val downloader: ApkDownloader,
    private val downloads: DownloadCache,
    private val packages: PackageGateway,
    private val installer: InstallerGateway,
    private val connectivity: ConnectivityGateway,
    private val storage: StorageGateway,
    private val pending: PendingInstallStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verifier: ApkVerifier = ApkVerifier(),
    private val log: (String) -> Unit = {},
    private val readyHoldMs: Long = 700,
    private val successDisplayMs: Long = 3_000,
    private val pendingValidMs: Long = 30 * 60_000L,
    private val abandonedAfterMs: Long = 7 * 86_400_000L,
    private val policyFactory: (AppDefinition) -> HostPolicy = { HostPolicy(it.allowedHosts) },
) {
    private val _state = MutableStateFlow(catalog.apps.map { initial(it, Channel.STABLE) })
    val state: StateFlow<List<AppState>> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val checks = ConcurrentHashMap<String, Job>()

    @Volatile var preferStable: Boolean = true
        private set
    @Volatile var autoCheck: Boolean = true

    private fun supported(app: AppDefinition) = device.sdkInt >= app.minAndroidSdk && AbiTokens.usable(device.supportedAbis).isNotEmpty()

    private fun initial(app: AppDefinition, channel: Channel) = AppState(
        app = app, channel = channel, installed = null, installedSignerTrusted = null, release = null, checking = false,
        releaseFailure = null, checkedAtMs = null, phase = Phase.Idle, supported = supported(app),
    )

    private fun channelFor(app: AppDefinition): Channel = if (preferStable || Channel.BETA !in app.channels) Channel.STABLE else Channel.BETA

    // ---------------------------------------------------------------- lifecycle

    /** Shows what is already known immediately (installed packages, cached releases), then refreshes in the background. */
    fun start() {
        scope.launch {
            downloads.purgeAbandoned(abandonedAfterMs, clock())
            _state.value.forEach { loadKnown(it.app.id) }
            resumePending()
            if (autoCheck) checkAll(force = false)
        }
    }

    /** Called whenever the app returns to the foreground (e.g. from a system settings or installer screen). */
    fun onResumed() {
        scope.launch {
            _state.value.forEach { s ->
                refreshInstalled(s.app.id)
                if (s.phase is Phase.NeedsInstallPermission && installer.canRequestInstalls()) startInstall(s.app.id)
            }
            if (autoCheck) checkAll(force = false)
        }
    }

    fun setPreferStable(value: Boolean) {
        preferStable = value
        scope.launch {
            _state.value.forEach { s ->
                val ch = channelFor(s.app)
                if (ch != s.channel && jobs[s.app.id]?.isActive != true) {
                    _state.update { list -> list.map { if (it.app.id == s.app.id) initial(it.app, ch).copy(phase = Phase.Idle) else it } }
                    loadKnown(s.app.id)
                }
            }
            if (autoCheck) checkAll(force = false)
        }
    }

    private suspend fun loadKnown(appId: String) {
        val s = get(appId)
        val channel = channelFor(s.app)
        val (inst, trusted) = detectInstalled(s.app, channel)
        val cached = repository.cached(s.app, channel)
        modify(appId) { it.copy(channel = channel, installed = inst, installedSignerTrusted = trusted, release = cached?.release, checkedAtMs = cached?.checkedAtMs) }
    }

    private suspend fun resumePending() {
        val p = pending.get() ?: return
        if (clock() - p.createdAtMs > pendingValidMs) { pending.clear(); return }
        val s = _state.value.firstOrNull { it.app.id == p.appId } ?: run { pending.clear(); return }
        if (installer.canRequestInstalls()) {
            pending.clear()
            startInstall(s.app.id)
        } else {
            modify(s.app.id) { it.copy(phase = Phase.NeedsInstallPermission) }
        }
    }

    // ---------------------------------------------------------------- checks

    fun checkAll(force: Boolean) {
        _state.value.forEach { s -> checkApp(s.app.id, force) }
    }

    fun checkApp(appId: String, force: Boolean) {
        val s = get(appId)
        if (!s.supported) return
        val existing = checks[appId]
        if (existing?.isActive == true) return
        checks[appId] = scope.launch {
            modify(appId) { it.copy(checking = true) }
            try {
                refreshInstalled(appId)
                val out = repository.check(s.app, s.channel, device, force)
                modify(appId) {
                    if (it.channel != s.channel) it.copy(checking = false)
                    else it.copy(checking = false, release = out.release ?: it.release, releaseFailure = out.failure, checkedAtMs = out.checkedAtMs ?: it.checkedAtMs)
                }
            } catch (e: CancellationException) {
                modify(appId) { it.copy(checking = false) }
                throw e
            }
        }
    }

    private suspend fun refreshInstalled(appId: String) {
        val s = get(appId)
        val (inst, trusted) = detectInstalled(s.app, s.channel)
        modify(appId) { it.copy(installed = inst, installedSignerTrusted = trusted) }
    }

    private suspend fun detectInstalled(app: AppDefinition, channel: Channel): Pair<InstalledPackage?, Boolean?> = withContext(Dispatchers.IO) {
        val pkg = app.packageId(channel) ?: return@withContext null to null
        val inst = packages.installed(pkg) ?: return@withContext null to null
        val trusted = when (TrustEvaluator.evaluate(app.trust, inst.signerSha256, inst.lineageSha256)) {
            is TrustVerdict.Trusted -> true
            TrustVerdict.PolicyNotConfigured -> null
            else -> false
        }
        inst to trusted
    }

    // ---------------------------------------------------------------- actions

    fun perform(appId: String, action: CardAction) {
        val s = get(appId)
        when (action) {
            CardAction.INSTALL, CardAction.UPDATE, CardAction.INSTALL_NOW, CardAction.RESUME -> startInstall(appId)
            CardAction.RETRY -> if (s.phase is Phase.Idle && s.release == null) checkApp(appId, force = true) else startInstall(appId)
            CardAction.CANCEL -> cancel(appId)
            CardAction.OPEN -> scope.launch {
                val pkg = s.packageId
                val ok = pkg != null && withContext(Dispatchers.IO) { packages.launch(pkg) }
                if (!ok) _effects.emit(Effect.LaunchFailed(appId))
            }
            CardAction.OPEN_INSTALL_SETTINGS -> installer.openInstallPermissionSettings()
            CardAction.SHOW_GUIDE -> _effects.tryEmit(Effect.ShowGuide(appId))
            CardAction.CHECK_UPDATES -> checkApp(appId, force = true)
            CardAction.OPEN_APP_INFO -> s.packageId?.let { packages.openAppInfo(it) }
            CardAction.DISMISS -> dismiss(appId)
            CardAction.NONE -> Unit
        }
    }

    /** Stops an in-progress download. The partial file is kept so the next attempt resumes. */
    fun cancel(appId: String) {
        jobs[appId]?.cancel()
    }

    /** Leaves any error/permission/ready state and returns the card to its resting state. */
    fun dismiss(appId: String) {
        if (jobs[appId]?.isActive == true && get(appId).phase !is Phase.NeedsInstallPermission) return
        pending.clear()
        modify(appId) { if (it.phase is Phase.Downloading || it.phase is Phase.Installing || it.phase is Phase.Verifying) it else it.copy(phase = Phase.Idle) }
    }

    /** Deletes every downloaded APK. Returns the bytes freed. */
    fun clearDownloads(): Long {
        _state.value.forEach { s -> if (s.phase is Phase.ReadyToInstall || s.phase is Phase.Paused || s.phase is Phase.DownloadFailed) cancel(s.app.id) }
        val freed = downloads.clear()
        _state.update { list -> list.map { if (it.phase is Phase.ReadyToInstall || it.phase is Phase.Paused || it.phase is Phase.DownloadFailed) it.copy(phase = Phase.Idle) else it } }
        return freed
    }

    private fun startInstall(appId: String) {
        synchronized(jobs) {
            val previous = jobs[appId]
            // A job that has already published a resting state may still be unwinding for a few microseconds;
            // a press in that window must queue behind it, not be dropped. A genuinely busy job ignores presses.
            if (previous?.isActive == true && !isResting(get(appId).phase)) return
            jobs[appId] = scope.launch {
                previous?.join()
                try {
                    install(appId)
                } catch (e: CancellationException) {
                    val p = get(appId).phase
                    when (p) {
                        is Phase.Downloading -> modify(appId) { it.copy(phase = Phase.Paused(p.downloadedBytes, p.totalBytes)) }
                        is Phase.Preparing, is Phase.Verifying -> modify(appId) { it.copy(phase = Phase.Idle) }
                        else -> Unit
                    }
                    throw e
                } catch (e: Exception) {
                    log("install failed unexpectedly for $appId: ${e::class.simpleName}: ${e.message}")
                    fail(appId, ErrorKind.UNEXPECTED)
                }
            }
        }
    }

    private fun isResting(p: Phase): Boolean = when (p) {
        is Phase.Idle, is Phase.Paused, is Phase.DownloadFailed, is Phase.Failed, is Phase.NeedsInstallPermission, is Phase.Succeeded -> true
        is Phase.ReadyToInstall -> p.afterCancel
        else -> false
    }

    // ---------------------------------------------------------------- the install workflow

    private suspend fun install(appId: String) {
        val start = get(appId)
        val app = start.app
        val channel = start.channel
        val pkg = start.packageId ?: return fail(appId, ErrorKind.UNEXPECTED)
        val priorStaged = (start.phase as? Phase.ReadyToInstall)?.staged?.takeIf { it.file.isFile }

        setPhase(appId, Phase.Preparing)

        // 1. device supported
        if (!start.supported) return fail(appId, ErrorKind.UNSUPPORTED_DEVICE)

        // Apps without a reviewed signing policy are never installed automatically.
        if (!start.canAutoInstall) {
            setPhase(appId, Phase.Idle)
            _effects.emit(Effect.ShowGuide(appId))
            return
        }

        // 2-4. resolve the newest verified release (storage and connectivity are checked where the data is known)
        var staged = priorStaged
        if (staged == null) {
            val release = resolveRelease(appId) ?: return
            val size = release.asset.sizeBytes
            if (size != null) {
                val free = storage.freeBytes(downloads.dir.also { it.mkdirs() })
                val needed = size * 2 + 48L * 1024 * 1024
                if (free in 0 until needed) return fail(appId, ErrorKind.INSUFFICIENT_STORAGE)
            }

            // 5. permission to request installs (checked before the large download, so no work is wasted)
            if (!installer.canRequestInstalls()) {
                pending.set(appId, clock())
                setPhase(appId, Phase.NeedsInstallPermission)
                _effects.emit(Effect.NeedInstallPermission(appId))
                return
            }
            pending.clear()

            // 6-7. download with live progress
            val file = download(appId, app, release) ?: return

            // 8. validate
            staged = verify(appId, app, pkg, release, file) ?: return
        } else if (!installer.canRequestInstalls()) {
            pending.set(appId, clock())
            setPhase(appId, Phase.NeedsInstallPermission)
            _effects.emit(Effect.NeedInstallPermission(appId))
            return
        }

        // 9-10. hand over to the system installer and wait for its verdict
        setPhase(appId, Phase.ReadyToInstall(staged))
        if (readyHoldMs > 0) delay(readyHoldMs)
        setPhase(appId, Phase.Installing)
        val outcome = try {
            installer.install(staged.file, pkg, staged.sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("installer threw for $appId: ${e::class.simpleName}: ${e.message}")
            InstallOutcome.Failed(InstallFailureKind.OTHER, null)
        }

        when (outcome) {
            InstallOutcome.Success -> confirm(appId, app, pkg, staged)
            InstallOutcome.Cancelled -> setPhase(appId, Phase.ReadyToInstall(staged, afterCancel = true))
            is InstallOutcome.Failed -> {
                log("system installer failed for $appId: ${outcome.kind} ${outcome.detail}")
                if (outcome.kind == InstallFailureKind.INVALID) staged.file.delete()
                fail(appId, when (outcome.kind) {
                    InstallFailureKind.BLOCKED -> ErrorKind.INSTALL_BLOCKED
                    InstallFailureKind.STORAGE -> ErrorKind.INSUFFICIENT_STORAGE
                    InstallFailureKind.CONFLICT -> ErrorKind.INSTALL_CONFLICT
                    InstallFailureKind.INCOMPATIBLE -> ErrorKind.INSTALL_INCOMPATIBLE
                    InstallFailureKind.INVALID -> ErrorKind.INVALID_APK
                    InstallFailureKind.TIMEOUT, InstallFailureKind.OTHER -> ErrorKind.INSTALL_FAILED
                })
            }
        }
    }

    private suspend fun resolveRelease(appId: String): ResolvedRelease? {
        val s = get(appId)
        val out = repository.check(s.app, s.channel, device, force = false)
        modify(appId) { it.copy(release = out.release ?: it.release, releaseFailure = out.failure, checkedAtMs = out.checkedAtMs ?: it.checkedAtMs) }
        val release = out.release
        if (release != null) return release
        fail(appId, if (out.failure == null && !connectivity.isOnline()) ErrorKind.NO_INTERNET else CardDeriver.errorFor(out.failure))
        return null
    }

    private suspend fun download(appId: String, app: AppDefinition, release: ResolvedRelease): File? {
        val dest = downloads.fileFor(app.id, release.versionName, release.asset)
        val partial = File(dest.path + ".part").takeIf { it.isFile }?.length() ?: 0L
        setPhase(appId, Phase.Downloading(partial, release.asset.sizeBytes))
        return try {
            downloader.download(
                DownloadRequest(release.asset.url, dest, release.asset.sizeBytes, release.asset.sha256, policyFactory(app)),
            ) { p -> setPhase(appId, Phase.Downloading(p.downloadedBytes, p.totalBytes)) }.file
        } catch (e: DownloadFailure) {
            log("download failed for $appId: ${e.kind} ${e.message}")
            val have = File(dest.path + ".part").takeIf { it.isFile }?.length() ?: 0L
            val error = when (e.kind) {
                DownloadFailure.Kind.INSUFFICIENT_STORAGE -> ErrorKind.INSUFFICIENT_STORAGE
                DownloadFailure.Kind.HASH_MISMATCH, DownloadFailure.Kind.SIZE_MISMATCH -> ErrorKind.INVALID_APK
                DownloadFailure.Kind.TRUNCATED -> ErrorKind.DOWNLOAD_INTERRUPTED
                DownloadFailure.Kind.IO -> ErrorKind.UNEXPECTED
                DownloadFailure.Kind.NETWORK -> when (e.net?.kind) {
                    NetFailure.Kind.OFFLINE -> ErrorKind.NO_INTERNET
                    NetFailure.Kind.TIMEOUT -> ErrorKind.SLOW_CONNECTION
                    NetFailure.Kind.RATE_LIMITED -> ErrorKind.RATE_LIMITED
                    NetFailure.Kind.BROWSER_CHECK -> ErrorKind.BROWSER_CHECK_REQUIRED
                    NetFailure.Kind.BLOCKED_HOST, NetFailure.Kind.INSECURE_REDIRECT, NetFailure.Kind.TOO_MANY_REDIRECTS, NetFailure.Kind.NOT_FOUND, NetFailure.Kind.HTTP_ERROR, NetFailure.Kind.TLS -> ErrorKind.SOURCE_UNAVAILABLE
                    else -> ErrorKind.DOWNLOAD_INTERRUPTED
                }
            }
            if (e.kind == DownloadFailure.Kind.HASH_MISMATCH || e.kind == DownloadFailure.Kind.SIZE_MISMATCH) {
                setPhase(appId, Phase.Failed(error))
            } else {
                setPhase(appId, Phase.DownloadFailed(error, have, release.asset.sizeBytes))
            }
            null
        }
    }

    private suspend fun verify(appId: String, app: AppDefinition, pkg: String, release: ResolvedRelease, file: File): StagedApk? {
        setPhase(appId, Phase.Verifying)
        val installedNow = withContext(Dispatchers.IO) { packages.installed(pkg) }
        val result = withContext(Dispatchers.IO) {
            verifier.verify(
                file = file,
                expected = ExpectedApk(pkg, release.versionName, release.asset.sizeBytes, release.asset.sha256),
                policy = app.trust, device = device, installed = installedNow, archive = packages::archiveInfo,
            )
        }
        return when (result) {
            is Verification.Passed -> StagedApk(file, release, result.archive, result.sha256)
            is Verification.Failed -> {
                log("verification failed for $appId: ${result.error} (${result.detail})")
                when (result.error) {
                    VerifyError.ALREADY_CURRENT -> {
                        refreshInstalled(appId); setPhase(appId, Phase.Idle)
                    }
                    VerifyError.WOULD_DOWNGRADE -> fail(appId, ErrorKind.DOWNGRADE_BLOCKED)
                    else -> {
                        file.delete()
                        fail(appId, when (result.error) {
                            VerifyError.WRONG_PACKAGE -> ErrorKind.WRONG_PACKAGE
                            VerifyError.UNSIGNED, VerifyError.SIGNER_NOT_TRUSTED, VerifyError.SIGNER_REVOKED -> ErrorKind.SIGNATURE_FAILED
                            VerifyError.SIGNER_CHANGED -> ErrorKind.SIGNER_MISMATCH
                            VerifyError.TRUST_NOT_CONFIGURED -> ErrorKind.NOT_REVIEWED
                            VerifyError.UNSUPPORTED_ANDROID, VerifyError.INCOMPATIBLE_ABI -> ErrorKind.UNSUPPORTED_DEVICE
                            else -> ErrorKind.INVALID_APK
                        })
                    }
                }
                null
            }
        }
    }

    /** Success is only ever declared after the package manager itself shows the new version. */
    private suspend fun confirm(appId: String, app: AppDefinition, pkg: String, staged: StagedApk) {
        val now = withContext(Dispatchers.IO) { packages.installed(pkg) }
        val ok = now != null && now.versionCode >= staged.archive.versionCode &&
            TrustEvaluator.isSameSigningIdentity(now.signerSha256, staged.archive.signerSha256, staged.archive.lineageSha256)
        if (!ok) {
            log("installer reported success but $pkg is not at the expected version")
            return fail(appId, ErrorKind.INSTALL_NOT_CONFIRMED)
        }
        downloads.removeForApp(app.id)
        pending.clear()
        refreshInstalled(appId)
        setPhase(appId, Phase.Succeeded(now.versionName))
        scope.launch {
            delay(successDisplayMs)
            modify(appId) { if (it.phase is Phase.Succeeded) it.copy(phase = Phase.Idle) else it }
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun get(appId: String): AppState = _state.value.first { it.app.id == appId }

    private fun modify(appId: String, f: (AppState) -> AppState) {
        _state.update { list -> list.map { if (it.app.id == appId) f(it) else it } }
    }

    private fun setPhase(appId: String, phase: Phase) = modify(appId) { it.copy(phase = phase) }

    private fun fail(appId: String, error: ErrorKind) = setPhase(appId, Phase.Failed(error))
}
