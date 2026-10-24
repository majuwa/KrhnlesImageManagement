package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.storage.StorageManager
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.util.abandonableCall
import de.majuwa.android.paper.krhnlesimagemanagement.worker.deleteStagedFiles
import de.majuwa.android.paper.krhnlesimagemanagement.worker.hasUnfinishedRetryWork
import de.majuwa.android.paper.krhnlesimagemanagement.worker.isRetryQueueFileName
import de.majuwa.android.paper.krhnlesimagemanagement.worker.isUploadQueueFileName
import de.majuwa.android.paper.krhnlesimagemanagement.worker.queueFilesDir
import de.majuwa.android.paper.krhnlesimagemanagement.worker.retryWorkName
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import de.majuwa.android.paper.krhnlesimagemanagement.worker.stagedNamesOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ShareStaging"
private const val MIB = 1024L * 1024L
private const val BUFFER_BYTES = 64 * 1024

/** Per-file cap of a staged copy, in MiB (shown to the user as "MB"). */
const val MAX_STAGED_FILE_MIB = 100L

/** Per-share cap of all staged copies, in MiB (shown to the user as "MB"). */
const val MAX_SHARE_MIB = 1024L

/** Byte bounds of the share staging folder; all enforced on the bytes actually copied. */
data class StagingLimits(
    val maxFileBytes: Long = MAX_STAGED_FILE_MIB * MIB,
    val maxShareBytes: Long = MAX_SHARE_MIB * MIB,
    val maxFolderBytes: Long = 2048 * MIB,
    /** Free space that must remain on the volume after staging. */
    val safetyMarginBytes: Long = 200 * MIB,
    /** Staged files older than this and not referenced by an upload queue are swept (the retry window). */
    val retentionMillis: Long = TimeUnit.DAYS.toMillis(7),
    /**
     * Opening and copying one item (provider calls included) must finish within this time, else
     * the item is skipped and its call abandoned (a provider may ignore the cancellation signal).
     */
    val itemTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(2),
)

/** Remaining bytes of one share: [shareRemaining] (skip when exceeded) and [spaceRemaining] (fail). */
class ShareBudget internal constructor(
    shareRemaining: Long,
    spaceRemaining: Long,
) {
    var shareRemaining: Long = shareRemaining
        private set
    var spaceRemaining: Long = spaceRemaining
        private set

    internal fun consume(bytes: Long) {
        shareRemaining -= bytes
        spaceRemaining -= bytes
    }
}

/** Why a shared photo was left out of the share. */
enum class SkipReason {
    /** The provider failed, denied the read or did not answer within the item timeout. */
    Unreadable,

    /** Larger than [StagingLimits.maxFileBytes]. */
    TooLarge,

    /** It did not fit into the rest of the share's [StagingLimits.maxShareBytes]. */
    ShareLimit,
}

/** Outcome of staging one shared photo. */
sealed interface StageResult {
    /** The copy is complete and named [name] in the staging folder. */
    data class Staged(
        val name: String,
        val bytes: Long,
    ) : StageResult

    /** The item is left out for [reason]; the share continues. */
    data class Skipped(
        val reason: SkipReason,
    ) : StageResult

    /** Writing failed or there is no space left: the whole share stops. */
    data object Failed : StageResult
}

private sealed interface CopyOutcome {
    data class Copied(
        val bytes: Long,
    ) : CopyOutcome

    data object TooLarge : CopyOutcome

    data object ReadFailed : CopyOutcome

    data object WriteFailed : CopyOutcome

    /** The call's [CancellationSignal] was cancelled (the caller abandoned it). */
    data object Cancelled : CopyOutcome
}

/** Opens a shared item for reading with a [CancellationSignal]; null if the provider returns none. */
fun interface SharedSourceOpener {
    fun open(
        contentResolver: ContentResolver,
        uri: Uri,
        signal: CancellationSignal,
    ): InputStream?
}

/** Production opener: `openAssetFileDescriptor(uri, "r", signal)`, so a provider can abort the open. */
internal val ProviderSourceOpener =
    SharedSourceOpener { contentResolver, uri, signal ->
        contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream()
    }

/**
 * Copies shared photos into the app-private staging folder while the sender's read grant is still
 * valid, so the upload can run later (after `finish()`, process death or a WorkManager re-run).
 *
 * Staged names are random UUIDs. A copy never leaves a partial or orphaned file behind (failure,
 * size cap, timeout, cancellation — also when the result races a cancellation). The provider calls
 * run as an [abandonableCall]: cancellation or the item timeout cancels their [CancellationSignal]
 * (which also closes the source stream, so a blocked read returns) and returns at once, even if
 * the provider ignores the signal. Staged files are deleted by the worker together with their
 * queue file; [sweep] removes old leftovers (crash during a copy, retry never tapped).
 *
 * @param hasUnfinishedRetryWork whether the unique retry work (by work name) of a retry file is still
 *   enqueued or running; null if unknown (the sweep then keeps the file).
 */
class ShareStaging(
    val dir: File,
    private val queueDirs: List<File>,
    private val freeBytes: (File) -> Long,
    private val limits: StagingLimits = StagingLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hasUnfinishedRetryWork: (String) -> Boolean? = { false },
    private val sourceOpener: SharedSourceOpener = ProviderSourceOpener,
) {
    constructor(context: Context) : this(
        dir = shareStagingDir(context),
        queueDirs = listOf(queueFilesDir(context)),
        freeBytes = { allocatableBytes(context, it) },
        hasUnfinishedRetryWork = { hasUnfinishedRetryWork(context, it) },
    )

    /**
     * A new budget for one share: at most [StagingLimits.maxShareBytes], and no more than the
     * folder cap (existing staged bytes count) and the free space minus the safety margin allow.
     * Null if there is no room at all (or the folder cannot be created).
     */
    suspend fun newBudget(): ShareBudget? =
        withContext(ioDispatcher) {
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "Staging folder could not be created")
                return@withContext null
            }
            val folderBytes = dir.listFiles().orEmpty().sumOf { it.length() }
            val space =
                minOf(limits.maxFolderBytes - folderBytes, freeBytes(dir) - limits.safetyMarginBytes)
            if (space <= 0L) null else ShareBudget(limits.maxShareBytes, space)
        }

    /**
     * Copies [uri] into a new staged file. Items whose [reportedSize] already exceeds the per-file
     * cap or the rest of the share budget are skipped without being opened. An item that is not
     * copied within [StagingLimits.itemTimeoutMillis] is skipped as unreadable. Cancellation is
     * rethrown at once; the abandoned copy deletes its file whenever it returns.
     */
    suspend fun stage(
        contentResolver: ContentResolver,
        uri: Uri,
        reportedSize: Long,
        budget: ShareBudget,
    ): StageResult {
        if (reportedSize > limits.maxFileBytes) return StageResult.Skipped(SkipReason.TooLarge)
        if (reportedSize > budget.shareRemaining) return StageResult.Skipped(SkipReason.ShareLimit)
        val name = UUID.randomUUID().toString()
        val target = File(dir, name)
        val limit = minOf(limits.maxFileBytes, budget.shareRemaining, budget.spaceRemaining)
        val outcome =
            abandonableCall(
                ioDispatcher,
                limits.itemTimeoutMillis,
                // A complete copy whose result never reached the caller must not stay behind.
                onAbandoned = { if (it is CopyOutcome.Copied) target.delete() },
            ) { signal -> copyFromProvider(contentResolver, uri, target, limit, signal) }
                ?: return StageResult.Skipped(SkipReason.Unreadable)
        return when (outcome) {
            is CopyOutcome.Copied -> {
                budget.consume(outcome.bytes)
                StageResult.Staged(name, outcome.bytes)
            }

            CopyOutcome.TooLarge -> {
                when {
                    budget.spaceRemaining < minOf(limits.maxFileBytes, budget.shareRemaining) -> StageResult.Failed
                    budget.shareRemaining < limits.maxFileBytes -> StageResult.Skipped(SkipReason.ShareLimit)
                    else -> StageResult.Skipped(SkipReason.TooLarge)
                }
            }

            CopyOutcome.ReadFailed, CopyOutcome.Cancelled -> {
                StageResult.Skipped(SkipReason.Unreadable)
            }

            CopyOutcome.WriteFailed -> {
                StageResult.Failed
            }
        }
    }

    /**
     * Blocking: opens [uri] and copies it into [target]. Cancelling [signal] aborts the open (if the
     * provider honours it) and closes the source stream, so a blocked read returns. [target] is
     * deleted unless the copy is complete.
     */
    private fun copyFromProvider(
        contentResolver: ContentResolver,
        uri: Uri,
        target: File,
        limit: Long,
        signal: CancellationSignal,
    ): CopyOutcome {
        val input = openSource(contentResolver, uri, signal) ?: return CopyOutcome.ReadFailed
        // Runs at once if the signal is already cancelled.
        signal.setOnCancelListener { closeQuietly(input) }
        var copied = false
        try {
            val outcome = if (signal.isCanceled) CopyOutcome.Cancelled else copyBlocking(input, target, limit, signal)
            copied = outcome is CopyOutcome.Copied && !signal.isCanceled
            return if (outcome is CopyOutcome.Copied && !copied) CopyOutcome.Cancelled else outcome
        } finally {
            signal.setOnCancelListener(null)
            closeQuietly(input)
            if (!copied) target.delete()
        }
    }

    private fun openSource(
        contentResolver: ContentResolver,
        uri: Uri,
        signal: CancellationSignal,
    ): InputStream? =
        try {
            sourceOpener.open(contentResolver, uri, signal)
        } catch (e: IOException) {
            // Includes FileNotFoundException.
            logUnreadable(e)
        } catch (e: SecurityException) {
            logUnreadable(e)
        } catch (e: IllegalArgumentException) {
            logUnreadable(e)
        } catch (e: IllegalStateException) {
            logUnreadable(e)
        } catch (e: OperationCanceledException) {
            logUnreadable(e)
        }

    /** Deletes the staged files [names] (e.g. a cancelled or failed share). */
    fun delete(names: Collection<String>) = deleteStagedFiles(dir, names)

    /**
     * Deletes staged files older than [StagingLimits.retentionMillis] that no upload queue file
     * references. One referenced by retry files is deleted together with those retry files only if
     * every one of them is itself older than the retention (judged by the retry file's own
     * modification time, which a retry tap refreshes) and has no unfinished retry work; an unknown
     * work state keeps it. Fails closed: if a queue file cannot be read or the set of queue files
     * changes during the scan, nothing is deleted. Blocking: call off the main thread.
     */
    fun sweep() {
        val now = clock()
        val old =
            dir.listFiles().orEmpty().filter { it.isFile && now - it.lastModified() > limits.retentionMillis }
        if (old.isEmpty()) return
        val references = scanQueueReferences() ?: return
        val retryFiles = mutableSetOf<File>()
        val orphans =
            old.filter { file ->
                val name = file.name
                if (name in references.uploadNames) return@filter false
                val retries = references.retryNames.filterValues { name in it }.keys
                if (retries.any { isRetryStillActive(it, now) }) return@filter false
                retryFiles += retries
                true
            }
        retryFiles.forEach { it.delete() }
        orphans.forEach { it.delete() }
    }

    /** A retry file younger than the retention or with unfinished (or unknown) work keeps its files. */
    private fun isRetryStillActive(
        retryFile: File,
        now: Long,
    ): Boolean =
        now - retryFile.lastModified() <= limits.retentionMillis ||
            hasUnfinishedRetryWork(retryWorkName(retryFile)) != false

    private class QueueReferences(
        val uploadNames: Set<String>,
        val retryNames: Map<File, Set<String>>,
    )

    private fun scanQueueReferences(): QueueReferences? {
        val before = listQueueFiles() ?: return null
        val uploadNames = mutableSetOf<String>()
        val retryNames = mutableMapOf<File, Set<String>>()
        for (file in before) {
            val names = readStagedNames(file) ?: return null
            if (isUploadQueueFileName(file.name)) uploadNames += names else retryNames[file] = names
        }
        val after = listQueueFiles() ?: return null
        return if (before.toSet() == after.toSet()) QueueReferences(uploadNames, retryNames) else null
    }

    /** Upload and retry queue files of all queue folders; null if a folder cannot be listed. */
    private fun listQueueFiles(): List<File>? {
        val files = mutableListOf<File>()
        for (queueDir in queueDirs) {
            if (!queueDir.exists()) continue
            val listed = queueDir.listFiles() ?: return null
            files += listed.filter { isUploadQueueFileName(it.name) || isRetryQueueFileName(it.name) }
        }
        return files
    }

    private fun readStagedNames(file: File): Set<String>? =
        try {
            stagedNamesOf(file.readText())
        } catch (e: IOException) {
            Log.w(TAG, "Sweep stopped, queue file unreadable: ${e.javaClass.simpleName}")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Sweep stopped, queue file malformed: ${e.javaClass.simpleName}")
            null
        }
}

private fun logUnreadable(e: Exception): InputStream? {
    Log.w(TAG, "Shared photo could not be opened: ${e.javaClass.simpleName}")
    return null
}

private fun closeQuietly(input: InputStream) {
    try {
        input.close()
    } catch (e: IOException) {
        Log.w(TAG, "Shared photo stream could not be closed: ${e.javaClass.simpleName}")
    }
}

private fun copyBlocking(
    input: InputStream,
    target: File,
    limit: Long,
    signal: CancellationSignal,
): CopyOutcome {
    val output =
        try {
            FileOutputStream(target)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Staged file could not be created: ${e.javaClass.simpleName}")
            return CopyOutcome.WriteFailed
        }
    return try {
        output.use { pump(input, it, limit, signal) }
    } catch (e: IOException) {
        // Read failures are mapped inside pump(); this is a write / close failure (e.g. ENOSPC).
        Log.w(TAG, "Staged file could not be written: ${e.javaClass.simpleName}")
        if (signal.isCanceled) CopyOutcome.Cancelled else CopyOutcome.WriteFailed
    }
}

private fun pump(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    signal: CancellationSignal,
): CopyOutcome {
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    var outcome: CopyOutcome? = null
    while (outcome == null) {
        if (signal.isCanceled) return CopyOutcome.Cancelled
        val read = readChunk(input, buffer, signal)
        when {
            read == null -> {
                outcome = if (signal.isCanceled) CopyOutcome.Cancelled else CopyOutcome.ReadFailed
            }

            read < 0 -> {
                outcome = CopyOutcome.Copied(total)
            }

            total + read > limit -> {
                outcome = CopyOutcome.TooLarge
            }

            else -> {
                total += read
                output.write(buffer, 0, read)
            }
        }
    }
    return outcome
}

/** One read; null if the source failed (also when it was closed by a cancellation). */
private fun readChunk(
    input: InputStream,
    buffer: ByteArray,
    signal: CancellationSignal,
): Int? =
    try {
        input.read(buffer)
    } catch (e: IOException) {
        if (!signal.isCanceled) Log.w(TAG, "Shared photo could not be read: ${e.javaClass.simpleName}")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Shared photo could not be read: ${e.javaClass.simpleName}")
        null
    }

private fun allocatableBytes(
    context: Context,
    dir: File,
): Long {
    val storage = context.getSystemService(StorageManager::class.java) ?: return dir.usableSpace
    return try {
        storage.getAllocatableBytes(storage.getUuidForPath(dir))
    } catch (e: IOException) {
        Log.w(TAG, "Allocatable bytes unknown: ${e.javaClass.simpleName}")
        dir.usableSpace
    }
}
