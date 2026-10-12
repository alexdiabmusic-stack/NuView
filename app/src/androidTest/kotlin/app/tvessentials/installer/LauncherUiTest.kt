package app.tvessentials.installer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Remote-control behaviour, verified by pressing the same keys a Fire TV remote sends. */
@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    private val tv = TvDriver()

    @Before fun start() { tv.launchFresh() }
    @After fun finish() { tv.device.pressHome() }

    @Test fun appIsRegisteredAsALeanbackLauncherWithABanner() {
        val out = tv.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
        assertTrue("listed in the Leanback launcher: $out", out.contains(tv.pkg))
        val info = tv.ctx.packageManager.getActivityBanner(tv.ctx.packageManager.getLaunchIntentForPackage(tv.pkg)!!)
        assertNotNull("TV banner is present", info)
    }

    @Test fun homeShowsHeroAndAllThreeCards() {
        assertTrue("hero title", tv.hasText("Everything you need. One place."))
        assertTrue("subtitle", tv.hasText("Install and keep your favourite TV apps up to date."))
        assertTrue("footer", tv.hasText("Only install apps from sources you trust."))
        for (name in listOf("Nuvio TV", "iMPlayer", "SmartTube")) assertNotNull("card $name", tv.cardDescription(name))
        assertTrue("version in the footer", tv.device.hasObject(androidx.test.uiautomator.By.pkg(tv.pkg).textStartsWith("v1.")))
        tv.screenshot("home")
    }

    @Test fun focusStartsOnTheFirstCardAndNeverGetsLost() {
        assertTrue("initial focus on first card", tv.focusIs("Nuvio TV"))
        tv.device.pressDPadDown(); assertTrue("down goes to Details", tv.focusIs("Details"))
        tv.device.pressDPadUp(); assertTrue("up returns to the card", tv.focusIs("Nuvio TV"))
        // Mash every direction; focus must always remain on something.
        repeat(12) { i ->
            when (i % 4) { 0 -> tv.device.pressDPadRight(); 1 -> tv.device.pressDPadDown(); 2 -> tv.device.pressDPadLeft(); else -> tv.device.pressDPadUp() }
            assertNotNull("focus vanished after key $i", tv.focusedDescription())
        }
    }

    @Test fun dpadMovesAcrossTheCardsAndStopsAtTheEdges() {
        assertTrue(tv.focusIs("Nuvio TV"))
        tv.device.pressDPadRight(); assertTrue(tv.focusIs("iMPlayer"))
        tv.device.pressDPadRight(); assertTrue(tv.focusIs("SmartTube"))
        tv.device.pressDPadRight(); assertTrue("right edge keeps focus", tv.focusIs("SmartTube"))
        tv.screenshot("focus_smarttube")
        tv.device.pressDPadLeft(); tv.device.pressDPadLeft(); assertTrue(tv.focusIs("Nuvio TV"))
        tv.device.pressDPadLeft(); assertTrue("left edge keeps focus", tv.focusIs("Nuvio TV"))
    }

    @Test fun headerButtonsAreReachableAndFocusReturnsToTheCards() {
        tv.device.pressDPadUp(); assertTrue("up reaches Refresh", tv.focusIs("Refresh"))
        tv.device.pressDPadRight(); assertTrue(tv.focusIs("Settings"))
        tv.device.pressDPadDown(); assertNotNull(tv.focusedDescription())
        assertTrue("down returns to a card, not nowhere", listOf("Nuvio TV", "iMPlayer", "SmartTube").any { tv.focusIs(it, 2_000) })
    }

    @Test fun settingsOpensAndBackRestoresFocusToTheSettingsButton() {
        tv.device.pressDPadUp(); tv.device.pressDPadRight(); assertTrue(tv.focusIs("Settings"))
        tv.device.pressDPadCenter()
        assertTrue("settings screen", tv.hasText("Settings"))
        assertTrue("shows the app version row", tv.hasText("Application version"))
        assertTrue("shows both switches", tv.device.hasObject(androidx.test.uiautomator.By.pkg(tv.pkg).descStartsWith("Automatic update checks")))
        tv.screenshot("settings")
        tv.device.pressBack()
        assertTrue("back on home", tv.hasText("Everything you need. One place."))
        assertTrue("focus restored to Settings", tv.focusIs("Settings"))
    }

    @Test fun detailsOpensFromTheDetailsLinkAndFromTheMenuKey() {
        tv.device.pressDPadDown(); assertTrue(tv.focusIs("Details"))
        tv.device.pressDPadCenter()
        assertTrue("details screen", tv.hasText("Latest verified version"))
        tv.screenshot("details_nuvio")
        tv.device.pressBack()
        assertTrue(tv.hasText("Everything you need. One place."))
        assertTrue("focus restored to the Details link", tv.focusIs("Details"))
        tv.device.pressDPadUp(); assertTrue(tv.focusIs("Nuvio TV"))
        tv.device.pressMenu()
        assertTrue("menu key opens details too", tv.hasText("Latest verified version"))
        tv.device.pressBack()
    }

    @Test fun smartTubeDetailsShowTheDescriptionAndFacts() {
        tv.focusCard(2); assertTrue(tv.focusIs("SmartTube"))
        tv.device.pressMenu()
        assertTrue("details screen", tv.hasText("Latest verified version"))
        assertTrue("description", tv.hasText("Enjoy YouTube videos without ads, with background playback and additional features.", 5_000))
        assertTrue("package row", tv.hasText("org.smarttube.stable", 5_000))
        tv.screenshot("details_smarttube")
        tv.device.pressBack()
        assertTrue("back on the card", tv.focusIs("SmartTube"))
    }

    @Test fun backOnTheHomeScreenLeavesTheApp() {
        assertTrue(tv.focusIs("Nuvio TV"))
        tv.device.pressBack()
        assertTrue("left the app", tv.device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.pkg(tv.pkg).depth(0)), 10_000))
        assertEquals(false, tv.device.hasObject(androidx.test.uiautomator.By.pkg(tv.pkg).depth(0)))
    }

    @Test fun iMPlayerIsOfferedForOnePressInstallAndKeepsTheGuidedRouteAsFallback() {
        tv.focusCard(1); assertTrue(tv.focusIs("iMPlayer"))
        // The card first checks the official site; once it knows the release it offers Install, exactly like the other two.
        // (Pressing it would download ~98 MB; the live install path is exercised with SmartTube in InstallFlowTest.)
        assertTrue("card offers Install for the official file", tv.cardSays("iMPlayer", "Latest 4.", 90_000) && tv.cardSays("iMPlayer", "Install", 5_000))
        tv.screenshot("implayer_ready")
        tv.device.pressMenu()
        assertTrue("details screen", tv.hasText("Latest verified version"))
        assertTrue("the guided route remains available as a fallback", tv.hasText("How to install", 5_000))
        tv.screenshot("details_implayer")
        tv.device.pressBack()
        assertTrue("back on the card", tv.focusIs("iMPlayer"))
    }
}
