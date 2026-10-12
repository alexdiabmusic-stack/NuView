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

    /** Description (or text) of whichever focused node has one; the host view can be flagged focused too and has neither. */
    fun focusedDescription(timeoutMs: Long = 5_000): String? {
        device.wait(Until.hasObject(By.pkg(pkg).focused(true)), timeoutMs)
        val focused = device.findObjects(By.pkg(pkg).focused(true))
        focused.firstNotNullOfOrNull { it.contentDescription ?: it.text }?.let { return it }
        // Compose exposes a card as a focusable box plus a sibling node that carries its label (a screen reader merges
        // them). The label of the focused area is the largest described node lying inside the focused node's bounds.
        val all = device.findObjects(By.pkg(pkg))
        for (f in focused) {
            val area = runCatching { f.visibleBounds }.getOrNull() ?: continue
            val inside = all.mapNotNull { n ->
                runCatching {
                    val b = n.visibleBounds
                    val label = n.contentDescription?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    if (area.contains(b)) label to (b.width().toLong() * b.height()) else null
                }.getOrNull()
            }
            inside.maxByOrNull { it.second }?.let { return it.first }
        }
        return null
    }

    /** True once a focused element's description starts with [prefix]. On failure the whole UI tree goes to logcat (tag TVE-TEST). */
    fun focusIs(prefix: String, timeoutMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (focusedDescription(300)?.startsWith(prefix) == true) return true
            Thread.sleep(150)
        }
        diagnose("focusIs($prefix)")
        return false
    }

    /** One logcat line per accessibility node: what a screen reader (and UiAutomator) actually sees. */
    fun diagnose(tag: String) {
        val out = java.io.ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        val attr = Regex("(class|text|content-desc|focused|focusable|clickable|bounds)=\"([^\"]*)\"")
        out.toString().split("<node").drop(1).forEach { n ->
            android.util.Log.w("TVE-TEST", "[$tag] " + attr.findAll(n).joinToString(" ") { "${it.groupValues[1]}=${it.groupValues[2].take(90)}" })
        }
    }

    fun cardDescription(name: String, timeoutMs: Long = 20_000): UiObject2? =
        device.wait(Until.findObject(By.pkg(pkg).descStartsWith(name)), timeoutMs)

    /** True once the card named [name] describes itself with [fragment] (a selector may only carry one desc condition, so one regex). */
    fun cardSays(name: String, fragment: String, timeoutMs: Long = 60_000): Boolean {
        val ok = device.wait(Until.hasObject(By.pkg(pkg).desc(Pattern.compile("(?s)${Pattern.quote(name)}.*${Pattern.quote(fragment)}.*"))), timeoutMs)
        if (!ok) { screenshot("card_never_said_${name}_${fragment}".replace(Regex("[^A-Za-z0-9_]"), "_")); diagnose("cardSays($name, $fragment)") }
        return ok
    }

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
