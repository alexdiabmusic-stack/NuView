package app.tvessentials.installer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the home screen at the screen sizes and text scales a TV viewer can end up with and keeps a screenshot of each,
 * so layout, overscan margins and readability can be reviewed. Every configuration must still show the core content.
 */
@RunWith(AndroidJUnit4::class)
class LayoutMatrixTest {
    private val tv = TvDriver()

    @After fun restore() {
        tv.shell("wm size reset")
        tv.shell("wm density reset")
        tv.shell("settings put system font_scale 1.0")
        tv.device.pressHome()
    }

    private fun verify(label: String, size: String?, density: Int?, fontScale: String = "1.0") {
        if (size != null) tv.shell("wm size $size") else tv.shell("wm size reset")
        if (density != null) tv.shell("wm density $density") else tv.shell("wm density reset")
        tv.shell("settings put system font_scale $fontScale")
        Thread.sleep(1_500)
        tv.launchFresh()
        assertTrue("[$label] hero visible", tv.hasText("Everything you need. One place."))
        for (name in listOf("Nuvio TV", "iMPlayer", "SmartTube")) assertNotNull("[$label] card $name", tv.cardDescription(name))
        assertTrue("[$label] footer visible", tv.hasText("Only install apps from sources you trust."))
        assertTrue("[$label] focus present", tv.focusIs("Nuvio TV"))
        Thread.sleep(600) // let the focus animation settle before the picture is taken
        tv.screenshot("layout_$label")
    }

    @Test fun at720p() = verify("720p", "1280x720", 213)
    @Test fun at1080p() = verify("1080p", "1920x1080", 213)
    @Test fun at4k() = verify("4k", "3840x2160", 320)
    @Test fun at1080pWithLargeText() = verify("1080p_font1.3", "1920x1080", 213, "1.3")
    @Test fun at720pWithLargestText() = verify("720p_font2.0", "1280x720", 213, "2.0")
}
