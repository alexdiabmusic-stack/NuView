package app.tvessentials.installer

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Black-box helpers: the tests drive the app only through what a person with a remote can do and see. */
class TvDriver {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val ctx: Context = instrumentation.targetContext
    val pkg: String = ctx.packageName

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    fun launchFresh() {
        device.pressHome()
        val intent = checkNotNull(ctx.packageManager.getLaunchIntentForPackage(pkg)) { "no launcher intent: the app is not a launcher app" }
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 20_000)) { "app did not come to the foreground" }
    }

    fun bringBack() {
        val intent = checkNotNull(ctx.packageManager.getLaunchIntentForPackage(pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 20_000))
    }

    fun waitFor(selector: BySelector, timeoutMs: Long = 20_000): UiObject2? = device.wait(Until.findObject(selector), timeoutMs)

    fun hasText(text: String, timeoutMs: Long = 20_000) = device.wait(Until.hasObject(By.pkg(pkg).text(text)), timeoutMs)

    fun focusedDescription(timeoutMs: Long = 5_000): String? {
        device.wait(Until.hasObject(By.pkg(pkg).focused(true)), timeoutMs)
        return device.findObject(By.pkg(pkg).focused(true))?.let { it.contentDescription ?: it.text }
    }

    /** True once the focused element's description starts with [prefix]. */
    fun focusIs(prefix: String, timeoutMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (focusedDescription(300)?.startsWith(prefix) == true) return true
            Thread.sleep(150)
        }
        return false
    }

    fun cardDescription(name: String, timeoutMs: Long = 20_000): UiObject2? =
        device.wait(Until.findObject(By.pkg(pkg).descStartsWith(name)), timeoutMs)

    fun cardSays(name: String, fragment: String, timeoutMs: Long = 60_000): Boolean =
        device.wait(Until.hasObject(By.pkg(pkg).descStartsWith(name).descContains(fragment)), timeoutMs)

    private val installerPkg: Pattern = Pattern.compile(".*packageinstaller.*")

    fun systemInstallButton(label: String, timeoutMs: Long = 180_000): UiObject2? =
        device.wait(Until.findObject(By.pkg(installerPkg).text(Pattern.compile(label, Pattern.CASE_INSENSITIVE))), timeoutMs)

    fun isInstalled(pkgId: String): Boolean = shell("pm list packages $pkgId").lines().any { it.trim() == "package:$pkgId" }

    fun uninstall(pkgId: String) { shell("pm uninstall $pkgId") }

    fun setInstallPermission(allowed: Boolean) {
        shell("appops set $pkg REQUEST_INSTALL_PACKAGES ${if (allowed) "allow" else "deny"}")
    }

    fun screenshot(name: String) {
        shell("screencap -p /sdcard/Download/tve_$name.png")
    }

    fun focusCard(index: Int) {
        // Initial focus is on the first card; step right to the wanted one.
        repeat(index) { device.pressDPadRight(); Thread.sleep(120) }
    }

    val liveInstall: Boolean
        get() = InstrumentationRegistry.getArguments().getString("liveInstall") == "true"
}
