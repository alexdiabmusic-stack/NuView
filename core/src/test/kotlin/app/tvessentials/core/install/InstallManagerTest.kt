package app.tvessentials.core.install

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.net.SafeHttp
import app.tvessentials.core.source.SourceFailure
import app.tvessentials.core.source.SourceResult
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.CardKind
import app.tvessentials.core.state.ErrorKind
import app.tvessentials.core.state.Phase
import app.tvessentials.core.testing.ManagerHarness
import app.tvessentials.core.testing.TlsFixture
import app.tvessentials.core.testing.rangeDispatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var h: ManagerHarness? = null
    @AfterTest fun tearDown() { h?.close() }

    private fun harness(
        appId: String = "nuvio-tv", http: ((TlsFixture) -> SafeHttp)? = null, free: Long = Long.MAX_VALUE / 4, online: Boolean = true,
        pending: app.tvessentials.core.testing.FakePending = app.tvessentials.core.testing.FakePending(),
        packages: app.tvessentials.core.testing.FakePackages = app.tvessentials.core.testing.FakePackages(),
        device: app.tvessentials.core.source.DeviceProfile = app.tvessentials.core.source.DeviceProfile(28, listOf("arm64-v8a", "armeabi-v7a")),
        preferStable: Boolean = true,
    ): ManagerHarness {
        val fx = TlsFixture()
        return ManagerHarness(tmp.root.resolve("h${System.nanoTime()}").also { it.mkdirs() }, fx, appId, device, http?.invoke(fx) ?: fx.http, free, online, pending, packages, preferStable).also { h = it }
    }

    private suspend fun ManagerHarness.startAndSettle() {
        manager.start()
        awaitState(what = "release to load") { it.release != null || it.releaseFailure != null }
        awaitState(what = "check to finish") { !it.checking }
    }

    // ------------------------------------------------------------------ happy paths

    @Test fun `first install runs the whole workflow and only claims success once the package manager agrees`(): Unit = runBlocking {
        val h = harness()
        h.startAndSettle()
        assertEquals(CardKind.INSTALL_AVAILABLE, h.card().kind)
        assertEquals(CardAction.INSTALL, h.card().primary)
        assertNull(h.state().installed)

        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "success") { it.phase is Phase.Succeeded }
        h.awaitHistory(what = "success in history") { l -> l.any { it.phase is Phase.Succeeded } }

        // The order the user experiences: prepare -> download -> verify -> ready -> installing -> success.
        // (A very fast transition may legitimately be skipped by an observer, but the order must never be violated.)
        val phases = h.history.map { it.phase::class.simpleName }.distinct().filter { it != "Idle" }
        val order = listOf("Preparing", "Downloading", "Verifying", "ReadyToInstall", "Installing", "Succeeded")
        assertEquals(phases.sortedBy { order.indexOf(it) }, phases, "phases out of order: $phases")
        assertTrue(phases.containsAll(listOf("Downloading", "Installing", "Succeeded")), "key phases missing: $phases")

        val s = h.state()
        assertEquals(h.apkVersion, s.installed?.versionName, "installed version is read back from the package manager")
        assertEquals(CardKind.JUST_INSTALLED, h.card().kind)
        assertEquals(CardAction.OPEN, h.card().primary)
        assertEquals(1, h.installer.installCalls.get())
        assertEquals(h.apkSha, h.installer.seenSha.single(), "the installer is told which exact bytes were verified")
        assertEquals(0, h.cache.sizeBytes(), "the downloaded file is cleaned up after a successful install")

        h.awaitState(what = "success banner to clear") { it.phase is Phase.Idle }
        assertEquals(CardKind.UP_TO_DATE, h.card().kind)
    }

    @Test fun `download progress is real, monotonic and ends at the true size`(): Unit = runBlocking {
        val h = harness()
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "success") { it.phase is Phase.Succeeded }
        h.awaitHistory(what = "success in history") { l -> l.any { it.phase is Phase.Succeeded } }
        val downloads = h.history.map { it.phase }.filterIsInstance<Phase.Downloading>()
        assertTrue(downloads.isNotEmpty())
        assertTrue(downloads.zipWithNext().all { (a, b) -> b.downloadedBytes >= a.downloadedBytes })
        assertEquals(h.apkBytes.size.toLong(), downloads.last().downloadedBytes)
        assertEquals(h.apkBytes.size.toLong(), downloads.last().totalBytes)
    }

    @Test fun `an update replaces an older install of the same publisher`(): Unit = runBlocking {
        val h = harness()
        h.packages.installedMap[h.packageId] = InstalledPackage(h.packageId, "0.9.0", 900, setOf(h.goodSigner))
        h.startAndSettle()
        assertEquals(CardKind.UPDATE_AVAILABLE, h.card().kind)
        assertEquals(CardAction.UPDATE, h.card().primary)
        h.manager.perform(h.appId, CardAction.UPDATE)
        h.awaitState(what = "updated") { it.phase is Phase.Succeeded }
        assertEquals("1.0.0", h.state().installed?.versionName)
    }

    @Test fun `already current installs never offer a pointless update, and newer installs are never downgraded`(): Unit = runBlocking {
        val h = harness()
        h.packages.installedMap[h.packageId] = InstalledPackage(h.packageId, "1.0.0", 1062, setOf(h.goodSigner))
        h.startAndSettle()
        assertEquals(CardKind.UP_TO_DATE, h.card().kind)
        assertEquals(CardAction.OPEN, h.card().primary)
        h.packages.installedMap[h.packageId] = InstalledPackage(h.packageId, "1.1.0-beta.5", 1068, setOf(h.goodSigner))
        h.manager.onResumed()
        h.awaitState(what = "beta recognised") { it.installed?.versionName == "1.1.0-beta.5" }
        assertEquals(CardKind.UP_TO_DATE, h.card().kind)
    }

    @Test fun `open launches the installed app and reports failure instead of pretending`(): Unit = runBlocking {
        val h = harness()
        h.packages.installedMap[h.packageId] = InstalledPackage(h.packageId, "1.0.0", 1062, setOf(h.goodSigner))
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.OPEN)
        delay(200)
        assertEquals(listOf(h.packageId), h.packages.launched.toList())
        h.packages.launchWorks = false
        h.manager.perform(h.appId, CardAction.OPEN)
        h.awaitEffect(what = "launch failure") { it is Effect.LaunchFailed }
    }

    // ------------------------------------------------------------------ the system installer

    @Test fun `if the installer says success but nothing is installed, the card does not claim success`(): Unit = runBlocking {
        val h = harness()
        h.installer.behaviour = { _, _, _ -> InstallOutcome.Success } // lies: package never appears
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "failure") { it.phase is Phase.Failed }
        assertEquals(ErrorKind.INSTALL_NOT_CONFIRMED, (h.state().phase as Phase.Failed).error)
        assertNull(h.state().installed)
        assertFalse(h.history.any { it.phase is Phase.Succeeded })
    }

    @Test fun `cancelling the system dialog does not report success and leaves a one press retry without re-downloading`(): Unit = runBlocking {
        val h = harness()
        h.installer.behaviour = { _, _, _ -> InstallOutcome.Cancelled }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        val s = h.awaitState(what = "ready after cancel") { (it.phase as? Phase.ReadyToInstall)?.afterCancel == true }
        assertEquals(CardKind.READY_TO_INSTALL, h.card().kind)
        assertEquals(CardAction.INSTALL_NOW, h.card().primary)
        assertNull(s.installed)
        assertFalse(h.history.any { it.phase is Phase.Succeeded })
        val before = h.requests

        // User presses the focused Install button again; this time they confirm.
        h.installer.behaviour = FakeInstallerDefault(h)
        h.manager.perform(h.appId, CardAction.INSTALL_NOW)
        h.awaitState(what = "success") { it.phase is Phase.Succeeded }
        assertEquals(before, h.requests, "the verified file is reused, not downloaded again")
        assertEquals(2, h.installer.installCalls.get())
    }

    @Test fun `system installer failures map to plain outcomes and keep the card recoverable`(): Unit = runBlocking {
        for ((kind, expected) in listOf(
            InstallFailureKind.BLOCKED to ErrorKind.INSTALL_BLOCKED, InstallFailureKind.STORAGE to ErrorKind.INSUFFICIENT_STORAGE,
            InstallFailureKind.CONFLICT to ErrorKind.INSTALL_CONFLICT, InstallFailureKind.INCOMPATIBLE to ErrorKind.INSTALL_INCOMPATIBLE,
            InstallFailureKind.OTHER to ErrorKind.INSTALL_FAILED,
        )) {
            val h = harness()
            h.installer.behaviour = { _, _, _ -> InstallOutcome.Failed(kind, "internal detail never shown") }
            h.startAndSettle()
            h.manager.perform(h.appId, CardAction.INSTALL)
            h.awaitState(what = "failure $kind") { it.phase is Phase.Failed }
            assertEquals(expected, (h.state().phase as Phase.Failed).error, "$kind")
            assertEquals(CardKind.INSTALL_FAILED, h.card().kind)
            h.close()
        }
    }

    // ------------------------------------------------------------------ "install unknown apps" permission

    @Test fun `without permission the app explains, then resumes the same install when the user comes back`(): Unit = runBlocking {
        val h = harness()
        h.installer.allowed = false
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "permission needed") { it.phase is Phase.NeedsInstallPermission }
        assertEquals(CardKind.NEEDS_PERMISSION, h.card().kind)
        assertEquals(CardAction.OPEN_INSTALL_SETTINGS, h.card().primary)
        h.awaitEffect(what = "permission effect") { it is Effect.NeedInstallPermission }
        assertNotNull(h.pending.get())
        assertEquals(0, h.requests - 0, "nothing is downloaded before the user has allowed installs")

        h.manager.perform(h.appId, CardAction.OPEN_INSTALL_SETTINGS)
        assertEquals(1, h.installer.settingsOpened.get())

        // The user flips the switch in Settings and returns.
        h.installer.allowed = true
        h.manager.onResumed()
        h.awaitState(what = "resumed install") { it.phase is Phase.Succeeded }
        assertNull(h.pending.get())
        assertEquals(1, h.effects.count { it is Effect.NeedInstallPermission }, "the user is asked exactly once")
    }

    @Test fun `returning without having granted permission does not nag`(): Unit = runBlocking {
        val h = harness()
        h.installer.allowed = false
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "permission needed") { it.phase is Phase.NeedsInstallPermission }
        h.manager.onResumed(); h.manager.onResumed()
        delay(300)
        h.awaitEffect(what = "permission effect") { it is Effect.NeedInstallPermission }
        assertEquals(1, h.effects.count { it is Effect.NeedInstallPermission })
        assertIs<Phase.NeedsInstallPermission>(h.state().phase)
        h.manager.perform(h.appId, CardAction.DISMISS)
        assertIs<Phase.Idle>(h.state().phase)
        assertNull(h.pending.get())
    }

    @Test fun `if the app process was killed while the user was in Settings the install still resumes`(): Unit = runBlocking {
        val pending = app.tvessentials.core.testing.FakePending()
        val first = harness(pending = pending)
        first.installer.allowed = false
        first.startAndSettle()
        first.manager.perform(first.appId, CardAction.INSTALL)
        first.awaitState(what = "permission needed") { it.phase is Phase.NeedsInstallPermission }
        assertNotNull(pending.get())
        first.close() // process death

        val second = harness(pending = pending)
        second.installer.allowed = true // permission was granted meanwhile
        second.manager.start()
        second.awaitState(what = "auto-resumed install") { it.phase is Phase.Succeeded }
        assertNull(pending.get())
    }

    @Test fun `a stale pending install is ignored`(): Unit = runBlocking {
        val pending = app.tvessentials.core.testing.FakePending().also { it.set("nuvio-tv", 0) }
        val h = harness(pending = pending)
        h.installer.allowed = true
        h.manager.start()
        h.awaitState(what = "release") { it.release != null }
        delay(300)
        assertIs<Phase.Idle>(h.state().phase)
        assertNull(pending.get())
    }

    // ------------------------------------------------------------------ downloads

    @Test fun `cancelling a download is safe, keeps the partial file, and resume finishes it`(): Unit = runBlocking {
        val h = harness()
        val slow = java.util.concurrent.atomic.AtomicBoolean(true)
        h.fx.dispatch { req ->
            h.requests++
            if (slow.get()) {
                mockwebserver3.MockResponse.Builder().code(200).body(Buffer().write(h.apkBytes))
                    .addHeader("ETag", "\"v1\"").addHeader("Accept-Ranges", "bytes")
                    .throttleBody(40_000, 100, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            } else rangeDispatch(h.apkBytes)(req)
        }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "downloading some bytes") { (it.phase as? Phase.Downloading)?.downloadedBytes?.let { b -> b > 20_000 } == true }
        h.manager.perform(h.appId, CardAction.CANCEL)
        val paused = h.awaitState(what = "paused") { it.phase is Phase.Paused }
        assertEquals(CardKind.DOWNLOAD_PAUSED, h.card().kind)
        assertEquals(CardAction.RESUME, h.card().primary)
        val kept = (paused.phase as Phase.Paused).downloadedBytes
        assertTrue(kept > 0, "partial progress is remembered")
        assertNull(h.state().installed)
        assertEquals(0, h.installer.installCalls.get())

        slow.set(false)
        h.manager.perform(h.appId, CardAction.RESUME)
        h.awaitState(what = "success after resume") { it.phase is Phase.Succeeded }
        assertEquals(1, h.installer.installCalls.get())
    }

    @Test fun `a dropped connection is recoverable with Try Again`(): Unit = runBlocking {
        val cuts = AtomicInteger(99)
        val h = harness(http = { fx ->
            SafeHttp(fx.client.newBuilder().addNetworkInterceptor(Interceptor { chain ->
                val r = chain.proceed(chain.request())
                if (cuts.get() > 0 && r.code in 200..206 && r.request.url.encodedPath.endsWith(".apk")) {
                    cuts.decrementAndGet()
                    val real = r.body.source()
                    var read = 0L
                    val failing = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (read >= 20_000) throw IOException("link dropped")
                            val n = real.read(sink, minOf(byteCount, 20_000 - read)); if (n > 0) read += n; return n
                        }
                        override fun timeout(): Timeout = real.timeout()
                        override fun close() = real.close()
                    }
                    r.newBuilder().body(failing.buffer().asResponseBody(r.body.contentType(), r.body.contentLength())).build()
                } else r
            }).build(), "TvEssentialsTest/1")
        })
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        val failed = h.awaitState(timeoutMs = 20_000, what = "download failure") { it.phase is Phase.DownloadFailed }
        assertEquals(ErrorKind.DOWNLOAD_INTERRUPTED, (failed.phase as Phase.DownloadFailed).error)
        assertEquals(CardAction.RETRY, h.card().primary)
        assertNull(h.state().installed)

        cuts.set(0) // the Wi-Fi is back
        h.manager.perform(h.appId, CardAction.RETRY)
        h.awaitState(what = "success after retry") { it.phase is Phase.Succeeded }
    }

    @Test fun `not enough storage is reported before downloading anything`(): Unit = runBlocking {
        val h = harness(free = 100_000)
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "storage failure") { it.phase is Phase.Failed }
        assertEquals(ErrorKind.INSUFFICIENT_STORAGE, (h.state().phase as Phase.Failed).error)
        assertEquals(0, h.requests)
    }

    // ------------------------------------------------------------------ hostile or broken files

    @Test fun `a file whose checksum is not the published one is rejected and deleted`(): Unit = runBlocking {
        val h = harness()
        h.releaseResult = { SourceResult.Success(h.release(sha = "0".repeat(64))) }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "rejection") { it.phase is Phase.Failed }
        assertEquals(ErrorKind.INVALID_APK, (h.state().phase as Phase.Failed).error)
        assertEquals(0, h.installer.installCalls.get())
        assertEquals(0, h.cache.sizeBytes())
    }

    @Test fun `an apk signed by anyone but the reviewed publisher never reaches the installer`(): Unit = runBlocking {
        val h = harness()
        h.packages.archiveFor = { f ->
            val m = app.tvessentials.core.apk.ApkInspector.inspect(f).manifest!!
            ArchiveInfo(m.packageId!!, m.versionName, m.versionCode!!, m.minSdk, setOf("e".repeat(64)))
        }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "rejection") { it.phase is Phase.Failed }
        assertEquals(ErrorKind.SIGNATURE_FAILED, (h.state().phase as Phase.Failed).error)
        assertEquals(0, h.installer.installCalls.get())
        assertEquals(0, h.cache.sizeBytes(), "a rejected file is deleted")
    }

    @Test fun `an apk for a different package is rejected`(): Unit = runBlocking {
        val h = harness()
        h.packages.archiveFor = { f ->
            val m = app.tvessentials.core.apk.ApkInspector.inspect(f).manifest!!
            ArchiveInfo("com.evil.other", m.versionName, m.versionCode!!, m.minSdk, setOf(h.goodSigner))
        }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "rejection") { it.phase is Phase.Failed }
        assertTrue((h.state().phase as Phase.Failed).error in setOf(ErrorKind.WRONG_PACKAGE, ErrorKind.INVALID_APK))
        assertEquals(0, h.installer.installCalls.get())
    }

    @Test fun `a copy installed with a different signing key is flagged and never overwritten`(): Unit = runBlocking {
        val h = harness()
        h.packages.installedMap[h.packageId] = InstalledPackage(h.packageId, "1.0.0", 1062, setOf("d".repeat(64)))
        h.startAndSettle()
        assertEquals(CardKind.SIGNER_MISMATCH, h.card().kind)
        assertEquals(CardAction.OPEN_APP_INFO, h.card().primary)
        h.manager.perform(h.appId, CardAction.OPEN_APP_INFO)
        assertEquals(listOf(h.packageId), h.packages.appInfoOpened.toList())
        assertEquals(0, h.installer.installCalls.get())
    }

    @Test fun `html served instead of an apk is rejected by content`(): Unit = runBlocking {
        val h = harness()
        h.fx.dispatch { req -> h.requests++; app.tvessentials.core.testing.response(200, "<html>Just a moment...</html>", mapOf("Content-Type" to "application/vnd.android.package-archive")) }
        h.releaseResult = { SourceResult.Success(h.release(sha = null, size = null)) }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "rejection") { it.phase is Phase.Failed }
        assertEquals(ErrorKind.INVALID_APK, (h.state().phase as Phase.Failed).error)
        assertEquals(0, h.installer.installCalls.get())
    }

    // ------------------------------------------------------------------ sources and devices

    @Test fun `no internet and no cache is a friendly dead end with a retry, not a blank card`(): Unit = runBlocking {
        val h = harness()
        h.releaseResult = { SourceResult.Failure(SourceFailure.Offline) }
        h.startAndSettle()
        assertEquals(CardKind.SOURCE_UNAVAILABLE, h.card().kind)
        assertEquals(ErrorKind.NO_INTERNET, h.card().error)
        assertEquals(CardAction.RETRY, h.card().primary)
        h.releaseResult = { SourceResult.Success(h.release()) }
        h.now += 60_000
        h.manager.perform(h.appId, CardAction.RETRY)
        h.awaitState(what = "recovered") { it.release != null }
        assertEquals(CardKind.INSTALL_AVAILABLE, h.card().kind)
    }

    @Test fun `with a cached release an outage keeps the card usable and marks it stale`(): Unit = runBlocking {
        val h = harness()
        h.startAndSettle()
        h.releaseResult = { SourceResult.Failure(SourceFailure.RateLimited(null)) }
        h.now += 3_600_000
        h.manager.checkApp(h.appId, force = true)
        h.awaitState(what = "failed refresh") { it.releaseFailure != null && !it.checking }
        assertEquals(CardKind.INSTALL_AVAILABLE, h.card().kind)
        assertTrue(h.card().stale)
        assertEquals("1.0.0", h.card().availableVersion)
    }

    @Test fun `unsupported devices are told so and never hit the network`(): Unit = runBlocking {
        val h = harness(device = app.tvessentials.core.source.DeviceProfile(21, listOf("arm64-v8a")))
        h.manager.start()
        delay(400)
        assertEquals(CardKind.UNSUPPORTED_DEVICE, h.card().kind)
        assertNull(h.state().release, "no release lookup for a device that can't run the app")
        h.manager.perform(h.appId, CardAction.INSTALL)
        delay(200)
        assertEquals(0, h.requests)
        assertIs<Phase.Failed>(h.state().phase.let { if (it is Phase.Failed) it else Phase.Failed(ErrorKind.UNEXPECTED) })
    }

    @Test fun `an app without a reviewed certificate is never auto installed and shows the guided route`(): Unit = runBlocking {
        val h = harness(appId = "implayer")
        h.releaseResult = { SourceResult.Success(h.release()) }
        h.manager.start()
        h.awaitState(what = "release") { it.release != null }
        assertEquals(CardKind.GUIDED_ONLY, h.card().kind)
        assertEquals(CardAction.SHOW_GUIDE, h.card().primary)
        h.manager.perform(h.appId, CardAction.SHOW_GUIDE)
        h.awaitEffect(what = "guide effect") { it is Effect.ShowGuide }
        assertEquals(0, h.requests)
        assertEquals(0, h.installer.installCalls.get())
    }

    @Test fun `a browser check at the official source leads to the guided route instead of a dead end`(): Unit = runBlocking {
        val h = harness(appId = "implayer")
        h.releaseResult = { SourceResult.Failure(SourceFailure.BrowserCheckRequired("https://implayer.tv/")) }
        h.manager.start()
        h.awaitState(what = "failure") { it.releaseFailure != null && !it.checking }
        assertEquals(CardKind.SOURCE_UNAVAILABLE, h.card().kind)
        assertEquals(ErrorKind.BROWSER_CHECK_REQUIRED, h.card().error)
        assertEquals(CardAction.SHOW_GUIDE, h.card().primary)
    }

    @Test fun `switching to the beta channel follows the beta package`(): Unit = runBlocking {
        val h = harness(appId = "smarttube", preferStable = false)
        h.manager.start()
        h.awaitState(what = "channel") { it.channel == app.tvessentials.core.catalog.Channel.BETA }
        assertEquals("org.smarttube.beta", h.state().packageId)
        assertEquals(setOf("org.smarttube.stable", "org.smarttube.beta"), h.app.allPackageIds)
    }

    @Test fun `clearing downloads frees space and drops stale ready states`(): Unit = runBlocking {
        val h = harness()
        h.installer.behaviour = { _, _, _ -> InstallOutcome.Cancelled }
        h.startAndSettle()
        h.manager.perform(h.appId, CardAction.INSTALL)
        h.awaitState(what = "ready") { it.phase is Phase.ReadyToInstall }
        assertTrue(h.cache.sizeBytes() > 0)
        val freed = h.manager.clearDownloads()
        assertTrue(freed > 0)
        assertEquals(0, h.cache.sizeBytes())
        assertIs<Phase.Idle>(h.state().phase)
    }

    private fun FakeInstallerDefault(h: ManagerHarness): suspend (java.io.File, String, String) -> InstallOutcome = { file, pkg, _ ->
        val info = checkNotNull(h.packages.archiveFor(file))
        h.packages.installedMap[pkg] = InstalledPackage(pkg, info.versionName, info.versionCode, info.signerSha256, info.lineageSha256)
        InstallOutcome.Success
    }
}
