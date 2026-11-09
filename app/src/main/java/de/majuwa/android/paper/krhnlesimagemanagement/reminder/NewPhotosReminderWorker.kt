package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.NewPhotosReminderRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.MediaAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val STORE_READ_TIMEOUT_MS = 3_000L

/** The device state the worker reads, replaceable in tests. */
internal class ReminderWorkerSeams(
    val mediaAccess: () -> MediaAccess,
    val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this({ MediaAccess.of(context) })
}

private const val TAG = "NewPhotosReminderWorker"

/**
 * The periodic reminder check: counts photos and videos added since the last upload that are not marked
 * uploaded and, if there are new ones, posts one notification with the count. Local only: no network, no
 * credentials (its settings source cannot even read them), never starts an upload. Always
 * `Result.success()`: a missed reminder needs no retry. Reads are bounded; an unreadable store means "post
 * nothing" (an empty marker set would over-count).
 */
class NewPhotosReminderWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val settings: NewPhotosReminderRepository,
    private val markers: UploadedPhotosRepositoryContract,
    private val history: UploadHistoryRepository,
    private val query: NewMediaSource,
    private val notifier: ReminderNotifications,
    private val scheduler: NewPhotosReminderScheduling,
    private val seams: ReminderWorkerSeams = ReminderWorkerSeams(context.applicationContext),
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        CredentialStore(context.applicationContext),
        UploadedPhotosStore(context.applicationContext),
        UploadHistoryStore(context.applicationContext),
        NewMediaQuery(context.contentResolver, context.packageName),
        ReminderNotifier(context),
        NewPhotosReminderScheduler(context),
    )

    override suspend fun doWork(): Result {
        val current = readBounded { settings.newPhotosReminder.first() } ?: return Result.success()
        if (!current.enabled) {
            // Last step: cancelling the unique work stops this coroutine, so nothing may follow it.
            scheduler.cancel()
            return Result.success()
        }
        runCatching { check(current) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Reminder check failed: ${it.javaClass.simpleName}")
            }
        return Result.success()
    }

    private class Counted(
        val count: NewMediaCount,
        val baselineMillis: Long,
    )

    private suspend fun check(current: NewPhotosReminderSettings) {
        val counted = countNew(current) ?: return
        if (counted.count.total <= 0 || !counted.count.hasNewSinceLastReminder) return
        val latest = readBounded { settings.newPhotosReminder.first() }
        // Disabled (or turned off and on again) while counting: post nothing.
        if (latest == null || !latest.enabled || latest.sinceMillis != current.sinceMillis) return
        notifier.post(counted.count, sinceLastUpload = counted.baselineMillis > current.sinceMillis)
        runCatching { withTimeoutOrNull(STORE_READ_TIMEOUT_MS) { settings.markNewPhotosReminderShown(seams.clock()) } }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Saving the reminder time failed: ${it.javaClass.simpleName}")
            }
    }

    /** The count, or null when nothing may be queried or the answer is unreliable. */
    private suspend fun countNew(current: NewPhotosReminderSettings): Counted? {
        if (!notifier.canPost()) return null
        val kinds = queryableKinds(seams.mediaAccess())
        if (kinds.isEmpty()) return null
        val entries = readBounded { history.entries.first() }.orEmpty()
        val uploaded = readBounded { markers.uploadedPhotoIds.first() } ?: return null
        val baseline = reminderBaseline(current.sinceMillis, entries)
        val found = query.query(kinds, baseline) as? NewMediaQueryResult.Rows ?: return null
        return Counted(
            countNewMedia(found.rows, uploaded, baseline, current.lastShownMillis, found.capReached),
            baseline,
        )
    }

    private suspend fun <T : Any> readBounded(read: suspend () -> T): T? =
        runCatching { withTimeoutOrNull(STORE_READ_TIMEOUT_MS) { read() } }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Reading a store failed: ${it.javaClass.simpleName}")
            }.getOrNull()
}
