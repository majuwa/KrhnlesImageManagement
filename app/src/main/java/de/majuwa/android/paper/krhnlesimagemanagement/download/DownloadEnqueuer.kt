package de.majuwa.android.paper.krhnlesimagemanagement.download

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueueEntry
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.downloadQueueFilesDir
import de.majuwa.android.paper.krhnlesimagemanagement.worker.isDownloadQueueFileName
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.queueTempFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** What the album flow asks to be saved to the device; implemented by [DownloadEnqueuer]. */
interface DownloadRequester {
    /** Starts saving the photos at [hrefs] of the album at [albumHref] in the background. */
    fun requestDownload(
        albumHref: String,
        hrefs: List<String>,
    ): Job?
}

/** Unique work name of the serialized download chain. */
internal const val DOWNLOAD_UNIQUE_WORK_NAME = "photo_download"

/** Tag of every download work request (never the upload tag, so the upload cancel path cannot reach it). */
internal const val DOWNLOAD_WORK_TAG = "photo_download"

/** Prefix of the per-queue tag (`download_queue:<queue file name>`). */
internal const val DOWNLOAD_QUEUE_TAG_PREFIX = "download_queue:"

/** First back-off delay of a retried download run; doubles per run. */
internal const val DOWNLOAD_BACKOFF_DELAY_SECONDS = 60L

private const val CONFIG_READ_TIMEOUT_MS = 3_000L
private const val SWEEP_LOOKUP_TIMEOUT_MS = 5_000L
private const val ENQUEUE_LOCK_TIMEOUT_MS = 30_000L

internal fun downloadQueueTag(queueFileName: String): String = "$DOWNLOAD_QUEUE_TAG_PREFIX$queueFileName"

/**
 * The one download work request for [queueFile]: a network constraint (any connected network, the
 * Wi-Fi-only setting is for uploads), the download tag, a per-queue tag and an explicit exponential
 * back-off. The input carries only the queue file path.
 */
internal fun downloadWorkRequest(queueFile: File): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(DownloadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DOWNLOAD_BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
        .addTag(DOWNLOAD_WORK_TAG)
        .addTag(downloadQueueTag(queueFile.name))
        .build()

/**
 * Process-scoped download enqueuing: [requestDownload] returns at once and does all of its work in
 * [scope] (the application scope), so a download is never lost when the calling screen is destroyed.
 *
 * Per request it writes a queue file (WorkManager `Data` is limited to 10 KB) and appends one
 * `DownloadWorker` to the unique chain [DOWNLOAD_UNIQUE_WORK_NAME]. `APPEND_OR_REPLACE` (the one
 * allowed use, see `NoUploadWorkCancelOutsideCancelPathRegressionTest`) keeps a chain with a
 * cancelled predecessor usable: it never stops running work. A cancel cascades to the batches
 * queued after the cancelled one; their queue files are removed by [sweepOrphans] at the next
 * request. The sweep and the enqueue run under one [Mutex] acquired with a timeout.
 */
class DownloadEnqueuer(
    private val appContext: Context,
    private val credentialRepository: CredentialRepository,
    private val scope: CoroutineScope,
    private val workManager: () -> WorkManager = { WorkManager.getInstance(appContext) },
    private val writer: () -> LocalMediaWriter = { LocalMediaWriter(appContext) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val sweepLookupTimeoutMs: Long = SWEEP_LOOKUP_TIMEOUT_MS,
) : DownloadRequester {
    private val lock = Mutex()

    override fun requestDownload(
        albumHref: String,
        hrefs: List<String>,
    ): Job? {
        val distinct = hrefs.distinct()
        if (distinct.isEmpty()) return null
        return scope.launch {
            val config = readConfig()
            if (config == null) {
                toast(appContext.getString(R.string.download_not_configured))
                return@launch
            }
            val started =
                try {
                    withTimeoutOrNull(ENQUEUE_LOCK_TIMEOUT_MS) {
                        lock.withLock { enqueue(config, albumHref, distinct) }
                    } == true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Any failure (I/O, WorkManager, an unexpected runtime error) must reach the user:
                    // an uncaught exception would crash the application scope.
                    Log.w(TAG, "Download could not be enqueued: ${e.javaClass.simpleName}")
                    false
                }
            toast(
                if (started) {
                    appContext.resources.getQuantityString(R.plurals.download_saving, distinct.size, distinct.size)
                } else {
                    appContext.getString(R.string.download_enqueue_failed)
                },
            )
        }
    }

    private suspend fun readConfig(): WebDavConfig? =
        runCatching { withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) { credentialRepository.webDavConfig.first() } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it.isValid }

    /** Sweeps, writes the queue file and awaits the enqueue; deletes the file if the enqueue fails. */
    private suspend fun enqueue(
        config: WebDavConfig,
        albumHref: String,
        hrefs: List<String>,
    ): Boolean {
        runCatching { sweepOrphans() }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Sweeping old download queues failed: ${it.javaClass.simpleName}")
        }
        val queueFile = writeQueueFile(config, albumHref, hrefs)
        return try {
            workManager()
                .enqueueUniqueWork(
                    DOWNLOAD_UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    downloadWorkRequest(queueFile),
                ).await()
            true
        } catch (e: CancellationException) {
            deleteFile(queueFile)
            throw e
        } catch (e: Exception) {
            // No worker will ever read this queue file.
            Log.w(TAG, "Download work could not be enqueued: ${e.javaClass.simpleName}")
            deleteFile(queueFile)
            false
        }
    }

    internal suspend fun writeQueueFile(
        config: WebDavConfig,
        albumHref: String,
        hrefs: List<String>,
    ): File =
        withContext(ioDispatcher) {
            val queue = DownloadQueue(config.url, albumHref, hrefs.map { DownloadQueueEntry(it) })
            File(
                downloadQueueFilesDir(appContext),
                "download_queue_${System.currentTimeMillis()}_${UUID.randomUUID()}.json",
            ).also { it.writeText(queue.toJson().toString()) }
        }

    /**
     * Deletes download queue files whose work is finished (cancelled with its predecessor, never
     * enqueued after a process death, pruned): work lookup bounded to [sweepLookupTimeoutMs] per
     * file and fail-closed (a timeout or error keeps the file). A recorded `pendingItem` is
     * discarded first. Enqueued or running work is never touched.
     */
    internal suspend fun sweepOrphans() {
        val files =
            withContext(ioDispatcher) {
                downloadQueueFilesDir(appContext).listFiles().orEmpty().filter { isDownloadQueueFileName(it.name) }
            }
        files.forEach { file ->
            val infos =
                runCatching {
                    withTimeoutOrNull(sweepLookupTimeoutMs) {
                        workManager().getWorkInfosByTagFlow(downloadQueueTag(file.name)).first()
                    }
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (infos != null && infos.all { it.state.isFinished }) discard(file)
        }
    }

    private suspend fun discard(file: File) {
        withContext(NonCancellable + ioDispatcher) {
            val leftover = runCatching { parseDownloadQueue(file.readText())?.pendingItem }.getOrNull()
            leftover?.let { writer().discardLeftover(it) }
            deleteFile(file)
        }
    }

    private suspend fun deleteFile(file: File) {
        withContext(NonCancellable + ioDispatcher) {
            file.delete()
            queueTempFile(file).delete()
        }
    }

    private suspend fun toast(message: String) {
        withContext(mainDispatcher) { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "DownloadEnqueuer"
    }
}
