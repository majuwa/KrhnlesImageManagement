package de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation

import android.content.Intent
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Action of the new-photos reminder's tap; namespaced, a constant (never built from a runtime package name). */
const val ACTION_SHOW_NEW_MEDIA = "de.majuwa.android.paper.krhnlesimagemanagement.action.SHOW_NEW_MEDIA"

/** What the app was asked to do by the intent that launched it, besides showing the start screen. */
sealed interface AppLaunchRequest {
    /** Open the photo grid with the "show only new photos" filter on. */
    data object ShowNewMedia : AppLaunchRequest
}

/**
 * The request carried by [intent], or null. Only the action is read: this activity is exported, so
 * extras and data of a foreign intent are never interpreted. Share intents are checked before this.
 */
fun launchRequestOf(intent: Intent?): AppLaunchRequest? =
    when {
        // Re-opened from the recents screen: the intent is the old one, the user did not tap the reminder.
        intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> null

        intent.action == ACTION_SHOW_NEW_MEDIA -> AppLaunchRequest.ShowNewMedia

        else -> null
    }

/**
 * Holds a launch request until the destination that applies it consumes it. Activity-scoped, so the
 * request survives configuration changes in between and is applied exactly once.
 */
class LaunchRequestViewModel : ViewModel() {
    private val _pending = MutableStateFlow<AppLaunchRequest?>(null)
    val pending: StateFlow<AppLaunchRequest?> = _pending.asStateFlow()

    fun offer(request: AppLaunchRequest) {
        _pending.value = request
    }

    /** Drops [request] if it is still the pending one (a newer request is never dropped by an older consume). */
    fun consume(request: AppLaunchRequest? = null) {
        if (request == null || _pending.value == request) _pending.value = null
    }
}
