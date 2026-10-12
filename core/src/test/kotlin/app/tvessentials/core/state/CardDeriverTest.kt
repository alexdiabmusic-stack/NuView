package app.tvessentials.core.state

import app.tvessentials.core.apk.ArchiveInfo
import app.tvessentials.core.apk.InstalledPackage
import app.tvessentials.core.catalog.Channel
import app.tvessentials.core.source.ReleaseAsset
import app.tvessentials.core.source.ResolvedRelease
import app.tvessentials.core.source.SourceFailure
import app.tvessentials.core.testing.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardDeriverTest {
    private val nuvio = Fixtures.app("nuvio-tv")
    private val implayer = Fixtures.app("implayer")
    private val unreviewedApp = Fixtures.unreviewed("implayer")
    private val signer = nuvio.trust.signers.single().sha256

    private fun release(version: String? = "1.0.0", code: Long? = null, size: Long? = 73_746_627, app: String = "nuvio-tv", pkg: String = "com.nuvio.tv") = ResolvedRelease(
        appId = app, channel = Channel.STABLE, packageId = pkg, versionName = version, versionCode = code,
        asset = ReleaseAsset("a.apk", "https://github.com/x/y/releases/download/1/a.apk", size, null, "arm64-v8a"), resolvedAtMs = 1,
    )
    private fun installed(v: String = "1.0.0", code: Long = 1062) = InstalledPackage("com.nuvio.tv", v, code, setOf(signer))

    private fun state(
        app: app.tvessentials.core.catalog.AppDefinition = nuvio, installed: InstalledPackage? = null, trusted: Boolean? = if (installed != null) true else null,
        release: ResolvedRelease? = null, checking: Boolean = false, failure: SourceFailure? = null, phase: Phase = Phase.Idle, supported: Boolean = true,
    ) = AppState(app, Channel.STABLE, installed, trusted, release, checking, failure, null, phase, supported)

    private fun card(s: AppState) = CardDeriver.derive(s)

    @Test fun `not installed with a verified release offers Install`() {
        val c = card(state(release = release()))
        assertEquals(CardKind.INSTALL_AVAILABLE, c.kind)
        assertEquals(CardAction.INSTALL, c.primary)
        assertFalse(c.installed)
        assertEquals("1.0.0", c.availableVersion)
        assertEquals(VersionNote.VERIFIED, c.versionNote)
        assertEquals(73_746_627L, c.sizeBytes)
    }

    @Test fun `checking with nothing known shows a disabled checking state`() {
        val c = card(state(checking = true))
        assertEquals(CardKind.CHECKING, c.kind)
        assertFalse(c.primaryEnabled)
    }

    @Test fun `a background check on a known card is a spinner, not a state change`() {
        val c = card(state(release = release(), checking = true))
        assertEquals(CardKind.INSTALL_AVAILABLE, c.kind)
        assertTrue(c.busyChecking)
        assertTrue(c.primaryEnabled)
    }

    @Test fun `installed and older than the release shows Update Available with Open as secondary`() {
        val c = card(state(installed = installed("0.9.0", 900), release = release("1.0.0")))
        assertEquals(CardKind.UPDATE_AVAILABLE, c.kind)
        assertEquals(CardAction.UPDATE, c.primary)
        assertEquals(listOf(CardAction.OPEN), c.secondary)
        assertEquals("0.9.0", c.installedVersion)
        assertEquals("1.0.0", c.availableVersion)
    }

    @Test fun `installed and current is Up To Date with Open and an optional check`() {
        val c = card(state(installed = installed("1.0.0", 1062), release = release("1.0.0")))
        assertEquals(CardKind.UP_TO_DATE, c.kind)
        assertEquals(CardAction.OPEN, c.primary)
        assertTrue(CardAction.CHECK_UPDATES in c.secondary)
    }

    @Test fun `a newer installed beta on the stable channel is up to date, never an update`() {
        val c = card(state(installed = installed("1.1.0-beta.5", 1068), release = release("1.0.0")))
        assertEquals(CardKind.UP_TO_DATE, c.kind)
    }

    @Test fun `installed but the latest version is unknown says so honestly`() {
        val unverified = card(state(installed = installed(), release = release(version = null)))
        assertEquals(CardKind.INSTALLED, unverified.kind)
        assertEquals(VersionNote.UNVERIFIED, unverified.versionNote)
        assertNull(unverified.availableVersion)
        assertEquals(CardAction.OPEN, unverified.primary)

        val offline = card(state(installed = installed(), failure = SourceFailure.Offline))
        assertEquals(CardKind.INSTALLED, offline.kind)
        assertEquals(CardAction.OPEN, offline.primary)
    }

    @Test fun `unsupported devices cannot act`() {
        val c = card(state(supported = false, release = release()))
        assertEquals(CardKind.UNSUPPORTED_DEVICE, c.kind)
        assertEquals(CardAction.NONE, c.primary)
        assertFalse(c.primaryEnabled)
    }

    @Test fun `source unavailable carries a friendly reason and a retry`() {
        val c = card(state(failure = SourceFailure.Offline))
        assertEquals(CardKind.SOURCE_UNAVAILABLE, c.kind)
        assertEquals(ErrorKind.NO_INTERNET, c.error)
        assertEquals(CardAction.RETRY, c.primary)
        assertEquals(ErrorKind.RATE_LIMITED, card(state(failure = SourceFailure.RateLimited(1))).error)
        assertEquals(ErrorKind.SLOW_CONNECTION, card(state(failure = SourceFailure.Timeout)).error)
        assertEquals(ErrorKind.NO_COMPATIBLE_RELEASE, card(state(failure = SourceFailure.NoCompatibleRelease("x"))).error)
    }

    @Test fun `downloading shows real progress and a cancel action`() {
        val c = card(state(release = release(), phase = Phase.Downloading(32_000_000, 47_000_000)))
        assertEquals(CardKind.DOWNLOADING, c.kind)
        assertEquals(CardAction.CANCEL, c.primary)
        assertEquals(0.68f, c.progress!!.fraction!!, 0.01f)
    }

    @Test fun `unknown size gives indeterminate progress`() {
        val c = card(state(release = release(size = null), phase = Phase.Downloading(5_000_000, null)))
        assertNull(c.progress!!.fraction)
        assertEquals(CardKind.DOWNLOADING, c.kind)
    }

    @Test fun `paused and failed downloads are resumable`() {
        val paused = card(state(release = release(), phase = Phase.Paused(10, 100)))
        assertEquals(CardKind.DOWNLOAD_PAUSED, paused.kind); assertEquals(CardAction.RESUME, paused.primary)
        val failed = card(state(release = release(), phase = Phase.DownloadFailed(ErrorKind.DOWNLOAD_INTERRUPTED, 10, 100)))
        assertEquals(CardKind.DOWNLOAD_FAILED, failed.kind); assertEquals(CardAction.RETRY, failed.primary)
        assertEquals(ErrorKind.DOWNLOAD_INTERRUPTED, failed.error)
    }

    @Test fun `ready to install exposes the install button, and installing disables it`() {
        val staged = StagedApk(File("x.apk"), release(), ArchiveInfo("com.nuvio.tv", "1.0.0", 1062, 24, setOf(signer)), "ab")
        assertEquals(CardAction.INSTALL_NOW, card(state(release = release(), phase = Phase.ReadyToInstall(staged))).primary)
        val installing = card(state(release = release(), phase = Phase.Installing))
        assertEquals(CardKind.INSTALLING, installing.kind); assertFalse(installing.primaryEnabled)
        assertEquals(CardKind.VERIFYING, card(state(release = release(), phase = Phase.Verifying)).kind)
    }

    @Test fun `permission needed offers the settings button`() {
        val c = card(state(release = release(), phase = Phase.NeedsInstallPermission))
        assertEquals(CardKind.NEEDS_PERMISSION, c.kind)
        assertEquals(CardAction.OPEN_INSTALL_SETTINGS, c.primary)
    }

    @Test fun `just installed offers Open`() {
        val c = card(state(installed = installed(), release = release(), phase = Phase.Succeeded("1.0.0")))
        assertEquals(CardKind.JUST_INSTALLED, c.kind)
        assertEquals(CardAction.OPEN, c.primary)
    }

    @Test fun `validation failures are not retryable but transient ones are`() {
        assertEquals(CardAction.RETRY, card(state(release = release(), phase = Phase.Failed(ErrorKind.INSTALL_FAILED))).primary)
        assertEquals(CardAction.RETRY, card(state(release = release(), phase = Phase.Failed(ErrorKind.NO_INTERNET))).primary)
        // A signature failure offers the manual route, never a blind retry.
        assertEquals(CardAction.SHOW_GUIDE, card(state(release = release(), phase = Phase.Failed(ErrorKind.SIGNATURE_FAILED))).primary)
        assertEquals(CardAction.SHOW_GUIDE, card(state(release = release(), phase = Phase.Failed(ErrorKind.WRONG_PACKAGE))).primary)
    }

    @Test fun `a differently signed installed copy is flagged and cannot be updated`() {
        val c = card(state(installed = installed(), trusted = false, release = release("2.0.0")))
        assertEquals(CardKind.SIGNER_MISMATCH, c.kind)
        assertEquals(CardAction.OPEN_APP_INFO, c.primary)
    }

    @Test fun `iMPlayer is offered for one-press install like the other apps`() {
        val c = card(state(app = implayer, release = release(app = "implayer", pkg = "com.myiptvonline.implayer", version = "4.1.6")))
        assertEquals(CardKind.INSTALL_AVAILABLE, c.kind)
        assertEquals(CardAction.INSTALL, c.primary)
    }

    @Test fun `without a reviewed certificate only the guided route is offered`() {
        val c = card(state(app = unreviewedApp, release = release(app = "implayer", pkg = "com.myiptvonline.implayer", version = null)))
        assertEquals(CardKind.GUIDED_ONLY, c.kind)
        assertEquals(CardAction.SHOW_GUIDE, c.primary)
        assertEquals(VersionNote.UNVERIFIED, c.versionNote)
    }

    @Test fun `iMPlayer behind a browser check leads straight to the guide`() {
        val c = card(state(app = implayer, failure = SourceFailure.BrowserCheckRequired("https://implayer.tv/")))
        assertEquals(CardKind.SOURCE_UNAVAILABLE, c.kind)
        assertEquals(CardAction.SHOW_GUIDE, c.primary)
        assertEquals(ErrorKind.BROWSER_CHECK_REQUIRED, c.error)
    }

    @Test fun `iMPlayer installed manually is detected and openable even though it can't be auto-updated`() {
        val inst = InstalledPackage("com.myiptvonline.implayer", "4.1.5", 415, setOf("a".repeat(64)))
        val c = card(state(app = unreviewedApp, installed = inst, trusted = null, release = release(app = "implayer", pkg = "com.myiptvonline.implayer", version = null)))
        assertEquals(CardKind.INSTALLED, c.kind)
        assertEquals(CardAction.OPEN, c.primary)
    }

    @Test fun `a stale cached release is marked stale only when a refresh failed`() {
        assertTrue(card(state(release = release(), failure = SourceFailure.Offline)).stale)
        assertFalse(card(state(release = release())).stale)
        assertFalse(card(state(release = release(), failure = SourceFailure.Offline, checking = true)).stale)
    }
}
