package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Unique name of the periodic reminder check; never used by upload or download work. */
internal const val NEW_PHOTOS_REMINDER_WORK_NAME = "new_photos_reminder"

private const val REMINDER_WORK_TAG = "new_photos_reminder"
private const val WORK_OPERATION_TIMEOUT_MS = 5_000L
private const val TAG = "NewPhotosReminder"

/** Keeps the one periodic reminder check consistent with the stored setting. */
interface NewPhotosReminderScheduling {
    /** Schedules (or updates the period of) the check; the first run is one interval after the first call. */
    suspend fun schedule(interval: ReminderInterval)

    /** Cancels the check and removes a shown reminder notification. */
    suspend fun cancel()

    /** Brings the check in line with [settings] without resetting the timing of an existing one. */
    suspend fun reconcile(settings: NewPhotosReminderSettings)
}

/**
 * The WorkManager side of the reminder. Only [ExistingPeriodicWorkPolicy.UPDATE] is used: it replaces the period
 * and keeps the enqueue time and a running instance. Every call is bounded and failures (e.g. WorkManager not
 * initialised) are logged by type and never thrown.
 */
class NewPhotosReminderScheduler internal constructor(
    private val workManager: () -> WorkManager,
    private val notifications: ReminderNotifications,
) : NewPhotosReminderScheduling {
    constructor(context: Context) : this(
        { WorkManager.getInstance(context.applicationContext) },
        ReminderNotifier(context),
    )

    override suspend fun schedule(interval: ReminderInterval) {
        guarded("schedule") {
            val request =
                PeriodicWorkRequestBuilder<NewPhotosReminderWorker>(interval.days, TimeUnit.DAYS)
                    .setInitialDelay(interval.days, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .addTag(REMINDER_WORK_TAG)
                    .build()
            workManager()
                .enqueueUniquePeriodicWork(NEW_PHOTOS_REMINDER_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                .await()
        }
    }

    /**
     * The only cancel of the reminder's own unique work (the guard test allows exactly this call). The
     * notification goes first: when the worker cancels itself through here, nothing may run after the cancel.
     */
    override suspend fun cancel() {
        guarded("notification") { notifications.cancel() }
        guarded("cancel") { workManager().cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME).await() }
    }

    override suspend fun reconcile(settings: NewPhotosReminderSettings) {
        if (settings.enabled) schedule(settings.interval) else cancel()
    }

    private suspend fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        runCatching { withTimeoutOrNull(WORK_OPERATION_TIMEOUT_MS) { block() } }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Reminder $what failed: ${it.javaClass.simpleName}")
            }
    }
}
