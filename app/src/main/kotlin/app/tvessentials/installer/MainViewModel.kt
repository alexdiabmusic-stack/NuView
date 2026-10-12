package app.tvessentials.installer

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tvessentials.core.install.Effect
import app.tvessentials.core.state.AppState
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.Phase
import app.tvessentials.installer.platform.MotionSettings
import app.tvessentials.installer.ui.DetailsUi
import app.tvessentials.installer.ui.Format
import app.tvessentials.installer.ui.HomeUi
import app.tvessentials.installer.ui.Modal
import app.tvessentials.installer.ui.NavState
import app.tvessentials.installer.ui.SettingsUi
import app.tvessentials.installer.ui.UiActions
import app.tvessentials.installer.ui.UiText
import app.tvessentials.installer.ui.deservesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val app: TvEssentialsApp) : ViewModel() {
    private val c = app.container
    private val manager = c.manager

    val nav = NavState()
    val toast = MutableStateFlow<String?>(null)
    val reducedMotion = MutableStateFlow(MotionSettings.reduced(app))

    private val icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val logos = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val iconKeys = HashMap<String, Long>()
    private val cacheBytes = MutableStateFlow(c.downloads.sizeBytes())
    private val autoCheck = MutableStateFlow(true)
    private val preferStable = MutableStateFlow(true)

    val home: StateFlow<HomeUi> = combine(manager.state, c.connectivity.online, icons, logos) { states, online, icons, logos ->
        HomeUi(
            cards = states.map { UiMapper.card(it, icons[it.app.id], logos[it.app.id]) },
            online = online,
            checking = states.any { it.checking },
            installerVersion = c.versionName,
            banner = banner(states, online),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi(emptyList(), true, false, c.versionName))

    val details: StateFlow<Map<String, DetailsUi>> = combine(manager.state, icons, logos) { states, icons, logos ->
        val now = System.currentTimeMillis()
        states.associate { it.app.id to UiMapper.details(it, icons[it.app.id], logos[it.app.id], now) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val settings: StateFlow<SettingsUi> = combine(manager.state, autoCheck, preferStable, cacheBytes) { states, auto, stable, bytes ->
        val newest = states.mapNotNull { it.checkedAtMs }.maxOrNull()
        SettingsUi(
            autoCheck = auto, preferStable = stable, cacheSize = Format.bytes(bytes), installerVersion = c.versionName,
            lastChecked = Format.ago(System.currentTimeMillis(), newest),
            attributions = c.catalog.apps.map { it.displayName to it.publisher },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi(true, true, "0 KB", c.versionName, "Never", emptyList()))

    /** True while a download/verify/install is running, so the screen can be kept awake. */
    val busy: StateFlow<Boolean> = manager.state.map { list -> list.any(UiMapper::isBusy) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val actions = UiActions(
        perform = { id, action -> manager.perform(id, action) },
        refresh = { manager.checkAll(force = true) },
        setAutoCheck = { v -> autoCheck.value = v; manager.autoCheck = v; viewModelScope.launch { c.prefs.setAutoCheck(v) } },
        setPreferStable = { v -> preferStable.value = v; manager.setPreferStable(v); viewModelScope.launch { c.prefs.setPreferStable(v) } },
        clearCache = {
            viewModelScope.launch {
                val freed = withContext(Dispatchers.IO) { manager.clearDownloads() }
                cacheBytes.value = c.downloads.sizeBytes()
                say("Freed ${Format.bytes(freed)}")
            }
        },
        openInstallSettings = { if (!c.installer.openInstallPermissionSettings()) say("Couldn't open Settings on this device") },
        openUrl = { url -> openUrl(url) },
        checkAll = { manager.checkAll(force = true) },
    )

    private val lastPhase = HashMap<String, Phase>()

    init {
        viewModelScope.launch(Dispatchers.IO) { logos.value = BrandLogos.loadAll(app) }
        viewModelScope.launch {
            val (auto, stable) = c.prefs.current()
            autoCheck.value = auto; preferStable.value = stable
            manager.autoCheck = auto
            if (!stable) manager.setPreferStable(false)
            manager.start()
        }
        viewModelScope.launch {
            manager.effects.collect { e ->
                when (e) {
                    is Effect.NeedInstallPermission -> nav.show(Modal.InstallPermission(e.appId))
                    is Effect.ShowGuide -> nav.show(Modal.Guide(e.appId))
                    is Effect.LaunchFailed -> say("Couldn't open ${nameOf(e.appId)}")
                }
            }
        }
        viewModelScope.launch {
            manager.state.collect { states ->
                states.forEach { s ->
                    loadIcon(s)
                    routeErrors(s)
                }
                cacheBytes.value = c.downloads.sizeBytes()
            }
        }
    }

    /** Called when the activity returns to the foreground (from a system settings page, installer dialog, screensaver…). */
    fun onResumed() {
        reducedMotion.value = MotionSettings.reduced(app)
        manager.onResumed()
        nav.refocus()
    }

    // ---- helpers

    private fun nameOf(appId: String) = c.catalog.apps.firstOrNull { it.id == appId }?.displayName ?: "the app"

    private fun say(message: String) {
        toast.value = message
        viewModelScope.launch { delay(3_500); if (toast.value == message) toast.value = null }
    }

    private fun banner(states: List<AppState>, online: Boolean): String? = when {
        !online -> "You're offline. Showing the last information we have."
        states.any { it.releaseFailure != null && it.release != null && !it.checking } -> "Couldn't refresh just now. Showing saved information."
        else -> null
    }

    /** Failures of something the user started deserve a clear, friendly dialog; background check failures stay quiet. */
    private fun routeErrors(s: AppState) {
        val previous = lastPhase.put(s.app.id, s.phase)
        if (previous == s.phase) return
        val error = when (val p = s.phase) {
            is Phase.Failed -> p.error
            is Phase.DownloadFailed -> p.error
            else -> null
        } ?: return
        if (previous?.javaClass == s.phase.javaClass) return
        if (!error.deservesDialog() || nav.modal != null) return
        val retry = if (s.phase is Phase.DownloadFailed || UiText.retryable(error)) CardAction.RETRY else null
        nav.show(Modal.Error(s.app.id, error, retry))
    }

    private fun loadIcon(s: AppState) {
        val inst = s.installed ?: return
        val pkg = s.packageId ?: return
        if (iconKeys[s.app.id] == inst.versionCode) return
        iconKeys[s.app.id] = inst.versionCode
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = c.packages.launcherIcon(pkg)?.let { runCatching { it.toBitmap(256, 256).asImageBitmap() }.getOrNull() } ?: return@launch
            icons.value = icons.value + (s.app.id to bitmap)
        }
    }

    private fun openUrl(url: String): Boolean = try {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        say("No web browser is available on this device")
        false
    }

    companion object {
        fun factory(app: TvEssentialsApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
        }
    }
}
