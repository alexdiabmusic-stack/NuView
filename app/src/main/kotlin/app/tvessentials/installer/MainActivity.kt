package app.tvessentials.installer

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.tvessentials.installer.ui.MotionPrefs
import app.tvessentials.installer.ui.Screen
import app.tvessentials.installer.ui.TvEssentialsRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.factory(application as TvEssentialsApp) }
    private val events get() = (application as TvEssentialsApp).container.installEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#0B0F17")))

        // Android's install-confirmation screen is started from the visible activity, never from the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                events.launchRequests.collect { runCatching { startActivity(it) } }
            }
        }

        setContent {
            val home by vm.home.collectAsStateWithLifecycle()
            val detailsMap by vm.details.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val toast by vm.toast.collectAsStateWithLifecycle()
            val reduced by vm.reducedMotion.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()

            // A long download must not be interrupted by the screensaver.
            DisposableEffect(busy) {
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            // Covers the system's own Back routing (predictive-back platforms) in addition to the key handling inside the UI.
            BackHandler(enabled = vm.nav.canGoBack) { vm.nav.back() }

            val screen = vm.nav.screen
            TvEssentialsRoot(
                nav = vm.nav, home = home,
                details = (screen as? Screen.Details)?.let { detailsMap[it.appId] },
                settings = settings, actions = vm.actions, motion = MotionPrefs(reduced), toast = toast,
            )
        }
    }

    override fun onStart() { super.onStart(); events.activityStarted() }
    override fun onStop() { events.activityStopped(); super.onStop() }
    override fun onResume() { super.onResume(); vm.onResumed() }
}
