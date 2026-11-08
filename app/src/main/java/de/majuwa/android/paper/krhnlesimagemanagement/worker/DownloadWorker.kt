package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.data.PendingMediaItem
import de.majuwa.android.paper.krhnlesimagemanagement.data.StorageFullException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.pinFor
import de.majuwa.android.paper.krhnlesimagemanagement.data.toDownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.download.DOWNLOAD_ROOT_FOLDER
import de.majuwa.android.paper.krhnlesimagemanagement.download.albumFolderName
import de.majuwa.android.paper.krhnlesimagemanagement.download.downloadDisplayName
import de.majuwa.android.paper.krhnlesimagemanagement.download.downloadImageTypeOf
import de.majuwa.android.paper.krhnlesimagemanagement.download.resolveDownloadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val DOWNLOAD_WORKER_TAG = "DownloadWorker"
private const val CONFIG_READ_TIMEOUT_MS = 3_000L
private const val FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

/** Consecutive connection / server failures after which the rest of a batch is not requested. */
internal const val MAX_DOWNLOAD_CONSECUTIVE_SERVER_FAILURES = 5

/**
 * Saves the photos of one download queue file (see `DownloadEnqueuer`) into
 * `Pictures/KroehnSync/<album>/` through [LocalMediaWriter]. Every server-controlled input is
 * re-validated here against the configuration current at run time: hrefs
 * ([resolveDownloadTarget]), names and types, the `Content-Type` and the size cap (in
 * [WebDavClient.download]).
 *
 * The result is always `Result.success()` for a finished batch (also an all-failed one), because
 * the batches form a chain and a `failure()` would cancel the batches queued after it;
 * `Result.retry()` is returned only for an interruption. The progress notification's Cancel action
 * is the only user cancel: it stops this work with `STOP_REASON_CANCELLED_BY_APP` (and the queued
 * batches with it); every other stop keeps the queue and is re-run.
 */
class DownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val credentialRepository: CredentialRepository,
    private val trustedCertificates: TrustedCertificateRepository,
    private val clientFactory: (WebDavConfig, PinnedCertificate?) -> WebDavClient,
    private val writer: LocalMediaWriter,
    private val freeBytes: () -> Long = writer::freeBytes,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Test seam for [userCancelled]; null = the real rule (`STOP_REASON_CANCELLED_BY_APP`). */
    private val userCancelledOverride: (() -> Boolean)? = null,
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        CredentialStore(context.applicationContext),
        TrustedCertificateStore(context.applicationContext),
        ::WebDavClient,
        LocalMediaWriter(context.applicationContext),
    )

    /** The queue with every entry saved so far; what a user cancel reports. */
    @Volatile
    private var liveQueue: DownloadQueue? = null

    /** Failure reason by entry index of this run. */
    private val failures = mutableMapOf<Int, DownloadFailure>()

    /** Set once the terminal report starts: from then on a cancel no longer finalizes. */
    @Volatile
    private var terminal = false

    /** Per-file size cap; a property (not a constructor parameter) so tests can use a small one. */
    @VisibleForTesting
    internal var maxDownloadBytes: Long = WebDavClient.MAX_DOWNLOAD_BYTES

    companion object {
        const val KEY_QUEUE_FILE = "queue_file"
    }

    private enum class LoopEnd { Completed, Stopped, Interrupted }

    private class RunClient(
        val config: WebDavConfig,
        val pin: PinnedCertificate?,
        val client: WebDavClient,
    )

    override suspend fun doWork(): Result {
        val queueFile = validQueueFile() ?: return Result.success()
        val queue = readQueue(queueFile)
        if (queue == null) {
            deleteDownloadQueueFile(queueFile)
            return Result.success()
        }
        liveQueue = queue
        return try {
            runBatch(queueFile)
        } catch (e: CancellationException) {
            // Only a cancel by the app is the user's: the queue is final. Every other stop (lost
            // network, system limits) keeps the queue with its markers for the re-run.
            if (!terminal && userCancelled()) reportCancelled(queueFile)
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.createDownloadNotificationChannel()
        return foregroundInfo(0, 0)
    }

    private fun userCancelled(): Boolean =
        userCancelledOverride?.invoke() ?: (isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP)

    private fun validQueueFile(): File? =
        inputData
            .getString(KEY_QUEUE_FILE)
            ?.let(::File)
            ?.takeIf { isDownloadQueueFile(applicationContext, it) && it.isFile }

    private fun readQueue(queueFile: File): DownloadQueue? =
        try {
            parseDownloadQueue(queueFile.readText())
        } catch (e: IOException) {
            Log.w(DOWNLOAD_WORKER_TAG, "Queue file unreadable: ${e.javaClass.simpleName}")
            null
        }

    private suspend fun runBatch(queueFile: File): Result {
        discardLeftoverItem(queueFile)
        val queue = checkNotNull(liveQueue)
        if (queue.pendingIndices.isEmpty()) return finish(queueFile)
        applicationContext.createDownloadNotificationChannel()
        return when (processEntries(queueFile)) {
            LoopEnd.Completed, LoopEnd.Stopped -> finish(queueFile)
            LoopEnd.Interrupted -> interrupted(queueFile)
        }
    }

    /** A pending item recorded by a killed run is removed (if still pending and ours) before anything else. */
    private suspend fun discardLeftoverItem(queueFile: File) {
        val queue = checkNotNull(liveQueue)
        val leftover = queue.pendingItem ?: return
        withContext(NonCancellable) {
            writer.discardLeftover(leftover)
            update(queueFile) { it.withPendingItem(null) }
        }
    }

    private suspend fun processEntries(queueFile: File): LoopEnd {
        val serverUrl = checkNotNull(liveQueue).serverUrl
        var run: RunClient? = null
        var consecutiveServerFailures = 0
        for (index in checkNotNull(liveQueue).pendingIndices) {
            val config = readConfig()?.takeIf { it.isValid && it.url == serverUrl }
            if (config == null) {
                failRest(DownloadFailure.ConfigurationChanged)
                return LoopEnd.Stopped
            }
            // The pin is re-read per photo: a certificate trusted or revoked meanwhile applies at once.
            val pin = trustedCertificates.pinFor(config.url)
            run =
                run?.takeIf { it.config == config && it.pin == pin }
                    ?: RunClient(config, pin, clientFactory(config, pin))
            val queue = checkNotNull(liveQueue)
            if (!setForegroundOrFalse(foregroundInfo(queue.entries.count { it.downloaded }, queue.entries.size))) {
                return LoopEnd.Interrupted
            }
            val failure = saveEntry(queueFile, run, index)
            if (failure == null) {
                consecutiveServerFailures = 0
                continue
            }
            failures[index] = failure
            consecutiveServerFailures =
                if (failure.isServerSide()) consecutiveServerFailures + 1 else 0
            if (failure.stopsBatch() || consecutiveServerFailures >= MAX_DOWNLOAD_CONSECUTIVE_SERVER_FAILURES) {
                failRest(failure)
                return LoopEnd.Stopped
            }
        }
        return LoopEnd.Completed
    }

    /** Downloads and publishes entry [index]; null = saved, otherwise the reason it was not. */
    private suspend fun saveEntry(
        queueFile: File,
        run: RunClient,
        index: Int,
    ): DownloadFailure? {
        val queue = checkNotNull(liveQueue)
        val href = queue.entries[index].href
        val target = resolveDownloadTarget(run.config, queue.albumHref, href)
        val name = downloadDisplayName(href, clock())
        val type = name?.let(::downloadImageTypeOf)
        return when {
            target == null -> {
                DownloadFailure.Rejected
            }

            name == null || type == null -> {
                DownloadFailure.Unsupported
            }

            else -> {
                val folder = "Pictures/$DOWNLOAD_ROOT_FOLDER/${albumFolderName(queue.albumHref)}/"
                download(queueFile, run.client, target, Destination(folder, name, type.mimeType), index)
            }
        }
    }

    private class Destination(
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
    )

    /**
     * Streams [target] into a pending MediaStore item and publishes it. The item is recorded in the
     * queue file before its first byte, published only after the whole body was written, and
     * discarded (not cancellable) on every other outcome, including a cancel.
     */
    private suspend fun download(
        queueFile: File,
        client: WebDavClient,
        target: HttpUrl,
        destination: Destination,
        index: Int,
    ): DownloadFailure? {
        var item: PendingMediaItem? = null
        var published = false
        try {
            val result =
                client.download(target, maxDownloadBytes) { body ->
                    if (!hasRoomFor(body.contentLength, freeBytes())) throw StorageFullException()
                    val created =
                        writer.insertPending(destination.relativePath, destination.displayName, destination.mimeType)
                    item = created
                    update(queueFile) { it.withPendingItem(created.uriString) }
                    created.write { out -> body.stream.copyTo(out) }
                }
            result.exceptionOrNull()?.let { error ->
                // A network error caused by the system stopping the work is a stop, not a failure.
                if (isStopped) throw CancellationException("Work stopped")
                return error.toDownloadFailure()
            }
            // The finished photo is published and booked even if the work is cancelled right now.
            published = withContext(NonCancellable) { item?.let(::publishQuietly) == true }
            if (!published) return DownloadFailure.WriteFailed
            withContext(NonCancellable) { update(queueFile) { it.withDownloaded(index).withPendingItem(null) } }
            return null
        } finally {
            if (!published) {
                withContext(NonCancellable) {
                    item?.discard()
                    update(queueFile) { it.withPendingItem(null) }
                }
            }
        }
    }

    private fun publishQuietly(item: PendingMediaItem): Boolean =
        runCatching { item.publish() }
            .onFailure { Log.w(DOWNLOAD_WORKER_TAG, "Publish failed: ${it.javaClass.simpleName}") }
            .isSuccess

    private suspend fun readConfig(): WebDavConfig? =
        runCatching { withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) { credentialRepository.webDavConfig.first() } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    private fun update(
        queueFile: File,
        transform: (DownloadQueue) -> DownloadQueue,
    ) {
        val next = transform(checkNotNull(liveQueue))
        liveQueue = next
        rewriteQueueFile(queueFile, next.toJson().toString())
    }

    private fun failRest(reason: DownloadFailure) {
        checkNotNull(liveQueue).pendingIndices.filter { it !in failures }.forEach { failures[it] = reason }
    }

    private fun foregroundInfo(
        current: Int,
        total: Int,
    ): ForegroundInfo =
        ForegroundInfo(
            DOWNLOAD_NOTIFICATION_ID,
            applicationContext.buildDownloadProgressNotification(current, total, id),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private suspend fun interrupted(queueFile: File): Result {
        if (userCancelled()) throw CancellationException("Download cancelled by the user")
        val queue = checkNotNull(liveQueue)
        if (queue.interruptions + 1 < MAX_DOWNLOAD_RUN_ATTEMPTS &&
            runAttemptCount < MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP
        ) {
            val saved = rewriteQueueFile(queueFile, queue.withInterruption().toJson().toString())
            // An unsaved counter never grows: once WorkManager itself started the work this often, stop.
            if (saved || runAttemptCount < MAX_DOWNLOAD_RUN_ATTEMPTS) return Result.retry()
        }
        Log.w(DOWNLOAD_WORKER_TAG, "Download interrupted too often; giving up")
        failRest(DownloadFailure.Interrupted)
        return finish(queueFile)
    }

    /** Reports the whole batch (also entries saved by earlier runs), deletes the queue file. */
    private suspend fun finish(queueFile: File): Result {
        val queue = checkNotNull(liveQueue)
        val saved = queue.entries.count { it.downloaded }
        val summary = DownloadSummary(saved, queue.entries.size - saved, failures.values.toSet())
        report(queueFile, summary)
        return Result.success()
    }

    private suspend fun reportCancelled(queueFile: File) {
        val queue = liveQueue ?: return
        val saved = queue.entries.count { it.downloaded }
        report(queueFile, DownloadSummary(saved, queue.entries.size - saved, emptySet(), cancelled = true))
    }

    private suspend fun report(
        queueFile: File,
        summary: DownloadSummary,
    ) {
        withContext(NonCancellable) {
            terminal = true
            deleteDownloadQueueFile(queueFile)
            applicationContext.createDownloadNotificationChannel()
            applicationContext.notifyDownloadResultIfPermitted(
                applicationContext.buildDownloadResultNotification(summary),
                queueFile.name,
            )
        }
    }
}

private fun DownloadFailure.isServerSide(): Boolean =
    this == DownloadFailure.Connection || this == DownloadFailure.ServerError

private fun DownloadFailure.stopsBatch(): Boolean =
    when (this) {
        DownloadFailure.AccessDenied,
        DownloadFailure.Certificate,
        DownloadFailure.StorageFull,
        DownloadFailure.ConfigurationChanged,
        -> true

        else -> false
    }

private fun deleteDownloadQueueFile(queueFile: File) {
    queueFile.delete()
    queueTempFile(queueFile).delete()
}

/** False when the announced size does not fit the free space minus a safety margin (unknown size: true). */
internal fun hasRoomFor(
    announcedBytes: Long,
    freeBytes: Long,
): Boolean = announcedBytes < 0L || announcedBytes <= freeBytes - FREE_SPACE_MARGIN_BYTES
