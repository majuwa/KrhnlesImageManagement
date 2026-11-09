package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.NewPhotosReminderScheduling
import kotlinx.coroutines.CompletableDeferred

/** Records the scheduler calls in order; a call can be held back by [gate] to simulate a slow WorkManager. */
class FakeReminderScheduler : NewPhotosReminderScheduling {
    /** `schedule(interval)` / `cancel` / `reconcile`, in the order the calls reached the scheduler. */
    val calls = mutableListOf<String>()

    /** When non-null, every call suspends until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun schedule(interval: ReminderInterval) {
        calls += "schedule(${interval.name})"
        gate?.await()
    }

    override suspend fun cancel() {
        calls += "cancel"
        gate?.await()
    }

    override suspend fun reconcile(settings: NewPhotosReminderSettings) {
        calls += "reconcile(${settings.enabled},${settings.interval.name})"
    }
}
