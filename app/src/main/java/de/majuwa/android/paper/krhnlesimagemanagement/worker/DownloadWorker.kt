package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadBody
import de.majuwa.android.paper.krhnlesimagemanagement.data.DownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriteException
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.data.PendingMediaItem
import de.majuwa.android.paper.krhnlesimagemanagement.data.StorageFullException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnexpectedContentException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.pinFor
import de.majuwa.android.paper.krhnlesimagemanagement.data.toDownloadFailure
import de.majuwa.android.paper.krhnlesimagemanagement.download.DOWNLOAD_RELATIVE_ROOT
import de.majuwa.android.paper.krhnlesimagemanagement.download.DownloadImageType
import de.majuwa.android.paper.krhnlesimagemanagement.download.SIGNATURE_BYTES
import de.majuwa.android.paper.krhnlesimagemanagement.download.albumFolderName
import de.majuwa.android.paper.krhnlesimagemanagement.download.downloadDisplayName
import de.majuwa.android.paper.krhnlesimagemanagement.download.downloadImageTypeOf
import de.majuwa.android.paper.krhnlesimagemanagement.download.matchesSignature
import de.majuwa.android.paper.krhnlesimagemanagement.download.resolveDownloadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

private const val DOWNLOAD_WORKER_TAG = "DownloadWorker"
private const val CONFIG_READ_TIMEOUT_MS = 3_000L
private const val FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024
private const val FREE_SPACE_FLOOR_BYTES = 50L * 1024 * 1024
private const val CHECK_INTERVAL_BYTES = 4L * 1024 * 1024

/** Consecutive connection / server failures after which the rest of a batch is not requested. */
internal const val MAX_DOWNLOAD_CONSECUTIVE_SERVER_FAILURES = 5

/**
 * Test seams of [DownloadWorker]; every `null` means the real rule.
 *
 * @property userCancelled whether the stop is the user's cancel (real: `STOP_REASON_CANCELLED_BY_APP`)
 * @property stopped whether the work is stopped (real: WorkManager's `isStopped`)
 * @property networkAvailable whether a validated network is active (real: [hasValidatedNetwork]); without
 *   one a connection failure is a pause, not a failed photo
 */
internal class DownloadWorkerSeams(
    val userCancelled: (() -> Boolean)? = null,
    val stopped: (() -> Boolean)? = null,
    val networkAvailable: (() -> Boolean)? = null,
)

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
    private val seams: DownloadWorkerSeams = DownloadWorkerSeams(),
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

    private enum class LoopEnd { Completed, Stopped, Interrupted, NetworkLost }

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
            if (!terminal && cancelledByUser) reportCancelled(queueFile)
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.createDownloadNotificationChannel()
        return foregroundInfo(0, 0)
    }

    private val stoppedNow: Boolean get() = seams.stopped?.invoke() ?: isStopped

    private val cancelledByUser: Boolean
        get() = seams.userCancelled?.invoke() ?: (stoppedNow && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP)

    private fun validQueueFile(): File? =
        inputData
            .getString(KEY_QUEUE_FILE)
            ?.let(::File)
            ?.takeIf { isDownloadQueueFile(applicationContext, it) && it.isFile }

    private suspend fun runBatch(queueFile: File): Result {
        discardLeftoverItem(queueFile)
        val queue = checkNotNull(liveQueue)
        if (queue.pendingIndices.isEmpty()) return finish(queueFile)
        applicationContext.createDownloadNotificationChannel()
        return when (processEntries(queueFile)) {
            LoopEnd.Completed, LoopEnd.Stopped -> {
                finish(queueFile)
            }

            LoopEnd.Interrupted -> {
                interrupted(queueFile)
            }

            LoopEnd.NetworkLost -> {
                throwIfStopped()
                Result.retry()
            }
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
        var run: RunClient? = null
        var consecutiveServerFailures = 0
        for (index in checkNotNull(liveQueue).pendingIndices) {
            val config = currentConfig()
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
            if (pausesForNetwork(failure)) return LoopEnd.NetworkLost
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

    /**
     * The configuration this run may use now: valid, the server the batch was queued for and, when
     * the queue is bound to an account, that account. Null = the rest of the batch must stop.
     */
    private suspend fun currentConfig(): WebDavConfig? {
        val queue = checkNotNull(liveQueue)
        return readConfig()?.takeIf {
            it.isValid && it.url == queue.serverUrl &&
                (queue.account == null || it.accountFingerprint() == queue.account)
        }
    }

    /**
     * Without a validated network a connection failure is a pause (the lost network), not a failed
     * photo: the queue and its markers are kept and WorkManager retries. A backstop on
     * `runAttemptCount` ends the pause for a network that never validates.
     */
    private fun pausesForNetwork(failure: DownloadFailure): Boolean =
        failure == DownloadFailure.Connection &&
            runAttemptCount < MAX_DOWNLOAD_RUN_ATTEMPTS_BACKSTOP &&
            !(seams.networkAvailable?.invoke() ?: applicationContext.hasValidatedNetwork())

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
                val folder = "$DOWNLOAD_RELATIVE_ROOT${albumFolderName(queue.albumHref)}/"
                download(queueFile, run.client, target, Destination(folder, name, type), index)
            }
        }
    }

    private class Destination(
        val relativePath: String,
        val displayName: String,
        val type: DownloadImageType,
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
                    val head = readVerifiedHead(body, destination.type, freeBytes())
                    val created =
                        writer.insertPending(
                            destination.relativePath,
                            destination.displayName,
                            destination.type.mimeType,
                        )
                    item = created
                    // No byte is written while the item is not recorded: a killed run could not discard it.
                    if (!update(queueFile) { it.withPendingItem(created.uriString) }) throw LocalMediaWriteException()
                    created.write { out ->
                        val guarded = SpaceGuardedOutputStream(out, freeBytes)
                        guarded.write(head.bytes, 0, head.length)
                        body.stream.copyTo(guarded)
                    }
                }
            result.exceptionOrNull()?.let { error ->
                // A network error caused by the system stopping the work is a stop, not a failure.
                if (stoppedNow) throw CancellationException("Work stopped")
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
                    // The record stays while the item could not be deleted: a later run or sweep retries.
                    val removed = item?.discard() ?: true
                    if (removed) update(queueFile) { it.withPendingItem(null) }
                }
            }
        }
    }

    private suspend fun readConfig(): WebDavConfig? =
        runCatching { withTimeoutOrNull(CONFIG_READ_TIMEOUT_MS) { credentialRepository.webDavConfig.first() } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    /** Applies [transform] to the live queue and saves it; false if the file could not be written. */
    private fun update(
        queueFile: File,
        transform: (DownloadQueue) -> DownloadQueue,
    ): Boolean {
        val next = transform(checkNotNull(liveQueue))
        liveQueue = next
        return rewriteQueueFile(queueFile, next.toJson().toString())
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

    /** A stop (the user's cancel or the system's) is never an interruption and never counted. */
    private fun throwIfStopped() {
        if (cancelledByUser) throw CancellationException("Download cancelled by the user")
        if (stoppedNow) throw CancellationException("Work stopped")
    }

    private suspend fun interrupted(queueFile: File): Result {
        throwIfStopped()
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

private fun readQueue(queueFile: File): DownloadQueue? =
    try {
        parseDownloadQueue(queueFile.readText())
    } catch (e: IOException) {
        Log.w(DOWNLOAD_WORKER_TAG, "Queue file unreadable: ${e.javaClass.simpleName}")
        null
    }

private fun publishQuietly(item: PendingMediaItem): Boolean =
    runCatching { item.publish() }
        .onFailure { Log.w(DOWNLOAD_WORKER_TAG, "Publish failed: ${it.javaClass.simpleName}") }
        .isSuccess

/** The first bytes of a body (at most [SIGNATURE_BYTES]). */
private class Head(
    val bytes: ByteArray,
    val length: Int,
)

/**
 * Checks the free space for [body] and reads and verifies its first bytes against [type] before
 * anything is stored: [StorageFullException] if it does not fit, [UnexpectedContentException] if
 * the bytes are not that image type.
 */
private fun readVerifiedHead(
    body: DownloadBody,
    type: DownloadImageType,
    freeBytes: Long,
): Head {
    if (!hasRoomFor(body.contentLength, freeBytes)) throw StorageFullException()
    val bytes = ByteArray(SIGNATURE_BYTES)
    val length = body.stream.readUpTo(bytes)
    if (!type.matchesSignature(bytes, length)) throw UnexpectedContentException()
    return Head(bytes, length)
}

private fun deleteDownloadQueueFile(queueFile: File) {
    queueFile.delete()
    queueTempFile(queueFile).delete()
}

/**
 * False when the announced size does not fit the free space minus a safety margin. An unknown size
 * (`-1`) needs more than the margin to be free; [SpaceGuardedOutputStream] then watches the rest.
 */
internal fun hasRoomFor(
    announcedBytes: Long,
    freeBytes: Long,
): Boolean =
    if (announcedBytes < 0L) {
        freeBytes > FREE_SPACE_MARGIN_BYTES
    } else {
        announcedBytes <= freeBytes - FREE_SPACE_MARGIN_BYTES
    }

/** Reads until [buffer] is full or the stream ends; returns the number of bytes read. */
private fun InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}

/**
 * Fails with [StorageFullException] once the free space (re-read every [CHECK_INTERVAL_BYTES]
 * written) falls to [FREE_SPACE_FLOOR_BYTES]: a body of unknown length cannot be checked up front.
 */
private class SpaceGuardedOutputStream(
    delegate: OutputStream,
    private val freeBytes: () -> Long,
) : FilterOutputStream(delegate) {
    private var sinceCheck = 0L

    override fun write(b: Int) {
        out.write(b)
        count(1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        count(len.toLong())
    }

    private fun count(bytes: Long) {
        sinceCheck += bytes
        if (sinceCheck < CHECK_INTERVAL_BYTES) return
        sinceCheck = 0L
        if (freeBytes() <= FREE_SPACE_FLOOR_BYTES) throw StorageFullException()
    }
}

/** True while a validated network is active (`NET_CAPABILITY_VALIDATED`); unknown counts as true. */
internal fun Context.hasValidatedNetwork(): Boolean =
    try {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        if (manager == null) {
            true
        } else {
            network != null &&
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ==
                true
        }
    } catch (e: SecurityException) {
        Log.w(DOWNLOAD_WORKER_TAG, "Network state unknown: ${e.javaClass.simpleName}")
        true
    }
