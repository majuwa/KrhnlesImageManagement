package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.data.NewPhotosReminderRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ReminderSettings"
private const val REMINDER_LOCK_TIMEOUT_MS = 5_000L
private const val REMINDER_READ_TIMEOUT_MS = 3_000L

/**
 * The one writer of the new-photos reminder state (setting + WorkManager schedule). It is process-scoped
 * (the application owns it, [scope] is the application scope), so leaving Settings between the save and the
 * schedule can never leave the reminder stored "on" but not scheduled. Settings changes, the once-per-process
 * [reconcileOnce] and the logout reset all go through one [Mutex] acquired with a timeout, so they reach
 * WorkManager in order and the last stored value wins. A timed-out acquisition drops a change / reconcile
 * (logged). A failed write is logged by type and schedules nothing: the switch shows the stored value.
 */
class ReminderSettingsController(
    private val scope: CoroutineScope,
    private val store: NewPhotosReminderRepository,
    private val scheduler: NewPhotosReminderScheduling,
) {
    private val lock = Mutex()
    private val reconcileStarted = AtomicBoolean(false)

    fun change(
        enabled: Boolean,
        interval: ReminderInterval,
    ) {
        scope.launch {
            withLock("Reminder change") {
                val saved =
                    runCatching { store.saveNewPhotosReminder(enabled, interval) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "Saving the reminder failed: ${it.javaClass.simpleName}")
                        }.isSuccess
                if (saved) {
                    if (enabled) scheduler.schedule(interval) else scheduler.cancel()
                }
            }
        }
    }

    /**
     * Brings the periodic check in line with the stored setting, once per process (later calls return
     * `null`). The setting is read under the lock, bounded (3 s); an unreadable setting changes nothing.
     */
    fun reconcileOnce(): Job? {
        if (!reconcileStarted.compareAndSet(false, true)) return null
        return scope.launch {
            withLock("Reminder reconcile") {
                val settings =
                    runCatching { withTimeoutOrNull(REMINDER_READ_TIMEOUT_MS) { store.newPhotosReminder.first() } }
                        .onFailure {
                            if (it is CancellationException) throw it
                            Log.w(TAG, "Reading the reminder setting failed: ${it.javaClass.simpleName}")
                        }.getOrNull()
                if (settings != null) scheduler.reconcile(settings)
            }
        }
    }

    /**
     * Logout: stores "off" and cancels the check under the same lock, so a change still in flight finishes
     * first and cannot schedule afterwards. Runs non-cancellable (leaving Settings during logout must not
     * stop it). When the lock cannot be acquired the check is cancelled anyway (cancelling is idempotent);
     * the credential clear that follows resets the rest.
     */
    suspend fun resetForLogout() {
        withContext(NonCancellable) {
            val locked = acquire()
            try {
                if (!locked) Log.w(TAG, "Reminder reset without the lock: lock timeout")
                runCatching { store.saveNewPhotosReminder(false, ReminderInterval.DEFAULT) }
                    .onFailure { Log.w(TAG, "Resetting the reminder failed: ${it.javaClass.simpleName}") }
                scheduler.cancel()
            } finally {
                if (locked) lock.unlock()
            }
        }
    }

    private suspend fun acquire(): Boolean = withTimeoutOrNull(REMINDER_LOCK_TIMEOUT_MS) { lock.lock() } != null

    private suspend fun withLock(
        what: String,
        block: suspend () -> Unit,
    ) {
        if (!acquire()) {
            Log.w(TAG, "$what dropped: lock timeout")
            return
        }
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
