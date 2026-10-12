package app.tvessentials.installer.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import app.tvessentials.core.state.CardModel

/** One app card, fully prepared for display. The UI layer never touches installer internals. */
@Immutable
data class AppCardUi(
    val card: CardModel,
    val name: String,
    val tagline: String,
    val glyph: Glyph,
    val accent: Color,
    val channelLabel: String,
    /** The app's own launcher icon, read from the user's device (installed app or the verified download); null until available. */
    val officialIcon: ImageBitmap? = null,
    val guide: GuideUi? = null,
)

/** The official manual-install route for apps that can't be installed automatically. */
@Immutable
data class GuideUi(val officialUrl: String, val code: String?, val steps: List<String>)

@Immutable
data class DetailsUi(
    val card: AppCardUi,
    val description: String,
    val publisher: String,
    val website: String,
    val disclosure: String?,
    val installedVersion: String,
    val latestVersion: String,
    val size: String,
    val channel: String,
    val lastChecked: String,
    val packageId: String,
    val signer: String,
)

@Immutable
data class HomeUi(
    val cards: List<AppCardUi>,
    val online: Boolean,
    val checking: Boolean,
    val installerVersion: String,
    val banner: String? = null,
)

@Immutable
data class SettingsUi(
    val autoCheck: Boolean,
    val preferStable: Boolean,
    val cacheSize: String,
    val installerVersion: String,
    val lastChecked: String,
    val attributions: List<Pair<String, String>>,
)

/** Where the user is. Held outside composition so it survives focus loss, rotation and process restarts of the view. */
sealed interface Screen {
    data object Home : Screen
    data class Details(val appId: String) : Screen
    data object Settings : Screen
}

/** A modal the UI is showing; at most one at a time. */
sealed interface Modal {
    data class InstallPermission(val appId: String) : Modal
    data class Guide(val appId: String) : Modal
    data class Error(val appId: String, val error: app.tvessentials.core.state.ErrorKind, val retry: app.tvessentials.core.state.CardAction?) : Modal
    data class ConfirmClearCache(val size: String) : Modal
}

/** Focus targets on the home screen; remembered so returning from any screen or dialog lands where the user left. */
sealed interface HomeFocus {
    data class Card(val index: Int) : HomeFocus
    data class Details(val index: Int) : HomeFocus
    data object Refresh : HomeFocus
    data object Settings : HomeFocus
}
