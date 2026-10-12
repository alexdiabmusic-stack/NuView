package app.tvessentials.installer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.tvessentials.core.state.CardAction
import app.tvessentials.core.state.ErrorKind

/**
 * Where the user is and what has focus, kept outside composition so it survives focus loss, system screens
 * and recomposition. The host owns one instance for the life of the activity's view model.
 */
class NavState {
    var screen: Screen by mutableStateOf(Screen.Home); private set
    var modal: Modal? by mutableStateOf(null); private set
    var homeFocus: HomeFocus by mutableStateOf(HomeFocus.Card(0))
    /** Bumped whenever focus should be re-landed on the remembered element (dialog closed, app resumed, screen changed). */
    var focusEpoch: Int by mutableIntStateOf(0); private set

    val canGoBack: Boolean get() = modal != null || screen != Screen.Home

    fun open(screen: Screen) { this.screen = screen; focusEpoch++ }
    fun show(modal: Modal) { this.modal = modal }
    fun dismissModal() { modal = null; focusEpoch++ }
    fun refocus() { focusEpoch++ }

    /** Steps back one level. Returns false when already at the top (the system should then leave the app). */
    fun back(): Boolean = when {
        modal != null -> { dismissModal(); true }
        screen != Screen.Home -> { screen = Screen.Home; focusEpoch++; true }
        else -> false
    }
}

/** Everything the UI can ask the app to do. */
class UiActions(
    val perform: (appId: String, action: CardAction) -> Unit,
    val refresh: () -> Unit,
    val setAutoCheck: (Boolean) -> Unit,
    val setPreferStable: (Boolean) -> Unit,
    val clearCache: () -> Unit,
    val openInstallSettings: () -> Unit,
    val openUrl: (String) -> Boolean,
    val checkAll: () -> Unit,
)

@Composable
fun TvEssentialsRoot(
    nav: NavState,
    home: HomeUi,
    details: DetailsUi?,
    settings: SettingsUi,
    actions: UiActions,
    motion: MotionPrefs = MotionPrefs(false),
    toast: String? = null,
) {
    TvTheme(motion) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // Back (and Escape on keyboards): one level up, but only when there is a level to go to, so the
                // system's own behaviour (leaving the app) stays intact at the top.
                if ((e.key == Key.Back || e.key == Key.Escape) && nav.canGoBack) {
                    if (e.type == KeyEventType.KeyUp) nav.back()
                    true
                } else false
            },
        ) {
            val modalOpen = nav.modal != null
            when (val s = nav.screen) {
                Screen.Home -> FadeIn(nav.screen) {
                    HomeScreen(
                        ui = home, focus = nav.homeFocus, focusEpoch = nav.focusEpoch, inputEnabled = !modalOpen,
                        onFocus = { nav.homeFocus = it },
                        onCardClick = { i -> home.cards.getOrNull(i)?.let { handlePrimary(it, nav, actions) } },
                        onCardMenu = { i -> home.cards.getOrNull(i)?.let { nav.open(Screen.Details(it.card.appId)) } },
                        onDetails = { i -> home.cards.getOrNull(i)?.let { nav.open(Screen.Details(it.card.appId)) } },
                        onRefresh = actions.refresh,
                        onSettings = { nav.open(Screen.Settings) },
                    )
                }
                is Screen.Details -> FadeIn(s) {
                    if (details != null) {
                        DetailsScreen(
                            ui = details, focusEpoch = nav.focusEpoch, inputEnabled = !modalOpen,
                            onAction = { a -> handleAction(details.card, a, nav, actions) },
                            onBack = { nav.back() },
                        )
                    }
                }
                Screen.Settings -> FadeIn(s) {
                    SettingsScreen(
                        ui = settings, focusEpoch = nav.focusEpoch, inputEnabled = !modalOpen,
                        onCheckUpdates = actions.checkAll,
                        onAutoCheck = actions.setAutoCheck,
                        onPreferStable = actions.setPreferStable,
                        onClearCache = { nav.show(Modal.ConfirmClearCache(settings.cacheSize)) },
                        onBack = { nav.back() },
                    )
                }
            }

            nav.modal?.let { m -> FadeIn(m) { ModalHost(m, home, nav, actions) } }

            if (toast != null) {
                Box(Modifier.fillMaxSize().padding(bottom = LocalDimens.current.dp(64)), contentAlignment = Alignment.BottomCenter) {
                    val shape = RoundedCornerShape(50)
                    Box(Modifier.clip(shape).background(Palette.TextPrimary, shape).padding(horizontal = LocalDimens.current.dp(26), vertical = LocalDimens.current.dp(12))) {
                        TvText(toast, LocalType.current.bodyStrong, color = Palette.Background, maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun handlePrimary(card: AppCardUi, nav: NavState, actions: UiActions) = handleAction(card, card.card.primary, nav, actions)

/** Routes a card action: some are handled by the UI itself (guide, permission explanation), the rest go to the installer. */
private fun handleAction(card: AppCardUi, action: CardAction, nav: NavState, actions: UiActions) {
    when (action) {
        CardAction.SHOW_GUIDE -> if (card.guide != null) nav.show(Modal.Guide(card.card.appId)) else actions.perform(card.card.appId, CardAction.RETRY)
        CardAction.OPEN_INSTALL_SETTINGS -> actions.openInstallSettings()
        CardAction.NONE -> Unit
        else -> actions.perform(card.card.appId, action)
    }
}

@Composable
private fun ModalHost(modal: Modal, home: HomeUi, nav: NavState, actions: UiActions) {
    fun card(id: String) = home.cards.firstOrNull { it.card.appId == id }
    when (modal) {
        is Modal.InstallPermission -> PermissionDialog(
            appName = card(modal.appId)?.name ?: "this app",
            onOpenSettings = { actions.openInstallSettings() },
            onDismiss = { actions.perform(modal.appId, CardAction.DISMISS); nav.dismissModal() },
        )
        is Modal.Guide -> {
            val c = card(modal.appId)
            val g = c?.guide
            if (c != null && g != null) {
                GuideDialog(c.name, g.steps, g.code, g.officialUrl, onOpenPage = { actions.openUrl(g.officialUrl) }, onDismiss = { nav.dismissModal() })
            }
        }
        is Modal.Error -> {
            val c = card(modal.appId)
            val name = c?.name ?: "this app"
            ErrorDialog(
                title = UiText.errorTitle(modal.error),
                body = UiText.errorBody(modal.error, name),
                canRetry = modal.retry != null,
                onRetry = { modal.retry?.let { actions.perform(modal.appId, it) }; nav.dismissModal() },
                onDismiss = { actions.perform(modal.appId, CardAction.DISMISS); nav.dismissModal() },
            )
        }
        is Modal.ConfirmClearCache -> ConfirmDialog(
            title = "Clear downloaded files?",
            body = "This removes ${modal.size} of downloaded installers. Installed apps are not affected.",
            confirmLabel = "Clear", onConfirm = { actions.clearCache(); nav.dismissModal() }, onDismiss = { nav.dismissModal() },
        )
    }
}

/** A short fade-in when a screen or dialog appears. Instant under reduced motion. Never delays focus. */
@Composable
private fun FadeIn(key: Any, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    var shown by remember(key) { mutableStateOf(motion.reduced) }
    LaunchedEffect(key) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(motion.duration(220), easing = FastOutSlowInEasing), label = "fade")
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) { content() }
}

/** Models the user-visible subset of [ErrorKind] that deserves a modal (user-initiated actions that failed). */
fun ErrorKind.deservesDialog(): Boolean = when (this) {
    ErrorKind.NO_INTERNET, ErrorKind.SLOW_CONNECTION, ErrorKind.RATE_LIMITED, ErrorKind.SOURCE_UNAVAILABLE, ErrorKind.BROWSER_CHECK_REQUIRED,
    ErrorKind.NO_COMPATIBLE_RELEASE -> false // shown inline on the card; background checks must not interrupt the user
    ErrorKind.UNSUPPORTED_DEVICE, ErrorKind.PERMISSION_DENIED, ErrorKind.ALREADY_CURRENT -> false
    else -> true
}
