package app.tvessentials.installer.platform

import android.content.Context
import android.content.Intent
import app.tvessentials.core.install.InstallOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge between Android's asynchronous installer callbacks (delivered to a manifest receiver) and the
 * coroutine that is waiting for a verdict. Also carries the system confirmation screen to a visible activity.
 */
class InstallEvents {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()
    private val _launch = MutableSharedFlow<Intent>(extraBufferCapacity = 8)

    /** Intents (the system's confirmation screen) that a visible activity should start. */
    val launchRequests: SharedFlow<Intent> = _launch.asSharedFlow()

    private val visibleActivities = AtomicInteger(0)
    fun activityStarted() { visibleActivities.incrementAndGet() }
    fun activityStopped() { visibleActivities.updateAndGet { (it - 1).coerceAtLeast(0) } }

    fun expect(sessionId: Int): CompletableDeferred<InstallOutcome> =
        CompletableDeferred<InstallOutcome>().also { waiting[sessionId] = it }

    fun forget(sessionId: Int) { waiting.remove(sessionId) }

    fun complete(sessionId: Int, outcome: InstallOutcome) {
        waiting.remove(sessionId)?.complete(outcome)
    }

    /** Show the system's install confirmation: through the foreground activity if there is one, else directly. */
    fun requestUserAction(context: Context, confirm: Intent) {
        if (visibleActivities.get() > 0 && _launch.tryEmit(confirm)) return
        try {
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Nothing more can be done; the session will time out and the card returns to a retryable state.
        }
    }
}
