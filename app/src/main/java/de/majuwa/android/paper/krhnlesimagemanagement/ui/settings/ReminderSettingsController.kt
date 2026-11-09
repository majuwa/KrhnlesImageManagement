package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.data.NewPhotosReminderRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.NewPhotosReminderScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ReminderSettings"
private const val REMINDER_LOCK_TIMEOUT_MS = 5_000L

/**
 * Applies a change of the new-photos reminder from Settings: persists first, then schedules (`UPDATE`) or
 * cancels the periodic check. Calls are serialized by one [Mutex] acquired with a timeout, so a quick
 * on / off / on reaches WorkManager in order and the last stored value wins; a timed-out acquisition drops
 * the call (logged). A failed write is logged by type and schedules nothing: the switch shows the stored value.
 */
class ReminderSettingsController(
    private val scope: CoroutineScope,
    private val store: NewPhotosReminderRepository,
    private val scheduler: NewPhotosReminderScheduling,
) {
    private val lock = Mutex()

    fun change(
        enabled: Boolean,
        interval: ReminderInterval,
    ) {
        scope.launch {
            if (withTimeoutOrNull(REMINDER_LOCK_TIMEOUT_MS) { lock.lock() } == null) {
                Log.w(TAG, "Reminder change dropped: lock timeout")
                return@launch
            }
            try {
                val saved =
                    runCatching { store.saveNewPhotosReminder(enabled, interval) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "Saving the reminder failed: ${it.javaClass.simpleName}")
                        }.isSuccess
                if (saved) {
                    if (enabled) scheduler.schedule(interval) else scheduler.cancel()
                }
            } finally {
                lock.unlock()
            }
        }
    }
}
