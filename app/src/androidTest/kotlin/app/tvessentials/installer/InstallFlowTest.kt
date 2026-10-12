package app.tvessentials.installer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing, end to end, on an emulator: live download from the publisher's official source, verification against the
 * reviewed signing key, Android's own PackageInstaller and confirmation screen, the result callback, package detection and launch.
 * Run with -Pandroid.testInstrumentationRunnerArguments.liveInstall=true. Needs internet.
 *
 * The test (not the app) presses the buttons in Android's own installer dialog, standing in for the person holding the remote.
 */
@RunWith(AndroidJUnit4::class)
class InstallFlowTest {
    private val tv = TvDriver()
    private val smartTube = "org.smarttube.stable"

    @Before fun setUp() {
        assumeTrue("live install tests are opt-in", tv.liveInstall)
        tv.setInstallPermission(true)
        tv.uninstall(smartTube)
        tv.launchFresh()
        tv.focusCard(2)
        assertTrue(tv.focusIs("SmartTube"))
    }

    @After fun tearDown() {
        tv.setInstallPermission(true)
        if (tv.liveInstall) tv.uninstall(smartTube)
        tv.device.pressHome()
    }

    private fun waitForInstallable() {
        assertTrue("SmartTube becomes installable once the release is known", tv.cardSays("SmartTube", "Not installed", 60_000) && tv.cardSays("SmartTube", "Latest 32.", 60_000))
        tv.screenshot("smarttube_ready")
    }

    /**
     * One realistic sequence, because the app (and its install state) lives in the same process as this test and carries over
     * between tests: the person presses Install, changes their mind at Android's confirmation, presses again, confirms, and opens the app.
     */
    @Test fun cancelAtTheSystemDialogThenInstallEndToEndAndOpen() {
        waitForInstallable()
        tv.device.pressDPadCenter()
        assertNotNull("Android's install confirmation appeared (download + verification succeeded)", tv.systemInstallButton("install"))
        tv.screenshot("system_installer")
        assertFalse("nothing is installed before the user confirms", tv.isInstalled(smartTube))

        // Changing their mind must never look like success and must stay recoverable.
        tv.systemInstallButton("cancel", 10_000)!!.click()
        assertTrue("card goes back to a recoverable ready state", tv.cardSays("SmartTube", "cancelled", 40_000))
        assertFalse("not installed after cancelling", tv.isInstalled(smartTube))
        tv.screenshot("smarttube_cancelled")

        // One press on the same button finishes the job without downloading again.
        assertTrue(tv.focusIs("SmartTube"))
        tv.device.pressDPadCenter()
        val confirm = tv.systemInstallButton("install")
        assertNotNull("Android asks again", confirm)
        confirm!!.click()

        assertTrue("card reports success only after Android did", tv.cardSays("SmartTube", "Installed", 120_000))
        assertTrue("package really is installed", tv.isInstalled(smartTube))
        tv.screenshot("smarttube_installed")

        assertTrue("card settles to a state with Open", tv.cardSays("SmartTube", "Open", 30_000))
        tv.device.pressDPadCenter()
        assertTrue("Open App launches the real SmartTube", tv.device.wait(Until.hasObject(By.pkg(smartTube)), 40_000))
    }

    // Not covered here on purpose: "install permission missing -> explained -> install resumes". Android kills an app's process
    // when its "install unknown apps" setting changes, and an instrumentation test runs inside that same process, so flipping the
    // setting from the test ends the whole run (observed in CI). The flow, including the cold-start resume from the persisted
    // pending install, is covered by InstallManagerTest in :core; it has not been exercised on a device or emulator.
}
