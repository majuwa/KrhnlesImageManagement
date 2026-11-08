package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** The device has no space left for the photo being written. */
class StorageFullException(
    cause: Throwable? = null,
) : IOException("Not enough storage on the device", cause)

/** A MediaStore insert, write or update for a saved photo failed. */
class LocalMediaWriteException(
    cause: Throwable? = null,
) : IOException("Could not write the photo to the device", cause)

private const val TAG = "LocalMediaWriter"
private const val MEDIA_AUTHORITY = "media"
private const val SEGMENT_COUNT = 4
private const val SEGMENT_COLLECTION = 1
private const val SEGMENT_MEDIA = 2
private const val SEGMENT_RAW_ID = 3
private const val COLLECTION_IMAGES = "images"

/**
 * Writes saved album photos into the shared `MediaStore.Images` collection as items owned by this
 * app. An app needs no storage permission to insert, write, update and delete *its own* items.
 *
 * A photo is inserted as a pending item (invisible to other apps), written, and only then
 * published ([PendingMediaItem.publish]); a failure or cancellation discards it
 * ([PendingMediaItem.discard]). Deleting is limited to [PendingMediaItem.discard] and
 * [discardLeftover], which only accepts a still-pending item owned by this package: a user's own
 * photos are never deleted (guarded by `DownloadOwnItemsOnlyRegressionTest`).
 */
class LocalMediaWriter internal constructor(
    private val context: Context,
    /** Test seam for the destination stream of an item (`openOutputStream(uri, "w")` by default). */
    private val outputOpener: (
        ContentResolver,
        Uri,
    ) -> OutputStream? = { resolver, uri -> resolver.openOutputStream(uri, "w") },
) {
    constructor(context: Context) : this(context, { resolver, uri -> resolver.openOutputStream(uri, "w") })

    private val resolver = context.contentResolver

    /**
     * Inserts a pending image below [relativePath] (e.g. `Pictures/KroehnSync/Album/`) as
     * [displayName]. MediaProvider never overwrites: a taken name gets ` (1)`, ` (2)`, ….
     *
     * @throws LocalMediaWriteException if the provider refuses the insert.
     */
    fun insertPending(
        relativePath: String,
        displayName: String,
        mimeType: String,
    ): PendingMediaItem {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            providerCall("insert") {
                resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            }
        return PendingMediaItem(this, uri ?: throw LocalMediaWriteException())
    }

    /**
     * Deletes the item [uriString] recorded by an earlier, interrupted run, but only if it is a
     * `media` `<volume>/images/media/<id>` uri that is still pending and owned by this package.
     * Returns true if it was deleted; every other case (published, foreign, another authority, a
     * video uri, a failing provider) deletes nothing.
     */
    suspend fun discardLeftover(uriString: String): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val uri = uriString.toOwnImageUriOrNull() ?: return@withContext false
            if (!isPendingOwnItem(uri)) return@withContext false
            deleteItem(uri)
        }

    /**
     * Bytes available for a new file on the primary external volume (allocatable bytes, which may
     * count reclaimable cache), falling back to the usable space of its directory.
     */
    fun freeBytes(): Long {
        val dir = context.getExternalFilesDir(null) ?: return 0L
        // Any failure of the platform query (I/O, security, an unsupported volume) falls back to the usable space.
        return runCatching {
            val manager = context.getSystemService(StorageManager::class.java)
            manager.getAllocatableBytes(manager.getUuidForPath(dir))
        }.getOrElse {
            Log.w(TAG, "Allocatable bytes unknown: ${it.javaClass.simpleName}")
            dir.usableSpace
        }
    }

    internal fun openForWrite(uri: Uri): OutputStream =
        try {
            outputOpener(resolver, uri) ?: throw LocalMediaWriteException()
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore open failed: ${e.javaClass.simpleName}")
            throw LocalMediaWriteException()
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "MediaStore open failed: ${e.javaClass.simpleName}")
            throw LocalMediaWriteException()
        }

    internal fun markPublished(uri: Uri) {
        val updated =
            providerCall("publish") {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        if (updated == null || updated < 1) throw LocalMediaWriteException()
    }

    /** The one place that deletes: used for this app's own pending items only. */
    internal fun deleteItem(uri: Uri): Boolean =
        providerCall("discard") { resolver.delete(uri, null, null) > 0 } == true

    private fun isPendingOwnItem(uri: Uri): Boolean =
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                cursor.moveToFirst() &&
                    !cursor.isNull(0) &&
                    cursor.getInt(0) == 1 &&
                    cursor.getString(1) == context.packageName
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: an item that cannot be proven to be ours and pending is never deleted.
            Log.w(TAG, "Leftover check failed: ${e.javaClass.simpleName}")
            false
        }

    private fun String.toOwnImageUriOrNull(): Uri? {
        val uri = runCatching { toUri() }.getOrNull() ?: return null
        val segments = uri.pathSegments
        val valid =
            uri.scheme == "content" &&
                uri.authority == MEDIA_AUTHORITY &&
                uri.query == null &&
                uri.fragment == null &&
                segments.size == SEGMENT_COUNT &&
                segments[0].isNotBlank() &&
                segments[SEGMENT_COLLECTION] == COLLECTION_IMAGES &&
                segments[SEGMENT_MEDIA] == "media" &&
                (segments[SEGMENT_RAW_ID].toLongOrNull() ?: -1L) >= 0L
        return uri.takeIf { valid }
    }

    /** Runs a provider call; a refusing provider yields null (logged by exception type only). */
    private fun <T> providerCall(
        step: String,
        call: () -> T,
    ): T? =
        try {
            call()
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore $step failed: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MediaStore $step failed: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaStore $step failed: ${e.javaClass.simpleName}")
            null
        }
}

/**
 * One inserted, not yet published image. [write] fills it, [publish] makes it visible, [discard]
 * removes it. [uriString] is what a queue file records so that a killed run can be cleaned up.
 */
class PendingMediaItem internal constructor(
    private val writer: LocalMediaWriter,
    private val uri: Uri,
) {
    val uriString: String get() = uri.toString()

    /**
     * Passes the item's output stream to [block]. Failures of the *output* (full storage, a
     * provider error) become [StorageFullException] / [LocalMediaWriteException]; failures of the
     * block itself, for example reading the network, keep their type.
     */
    fun write(block: (OutputStream) -> Unit) {
        GuardedOutputStream(writer.openForWrite(uri)).use { out -> block(out) }
    }

    /** Makes the item visible to other apps (`IS_PENDING = 0`). */
    fun publish() = writer.markPublished(uri)

    /** Deletes this pending item; runs to its end even if the calling coroutine is cancelled. */
    suspend fun discard(): Boolean = withContext(NonCancellable + Dispatchers.IO) { writer.deleteItem(uri) }
}

/** Maps every failure of the destination stream to a typed exception; the source is not touched. */
private class GuardedOutputStream(
    delegate: OutputStream,
) : FilterOutputStream(delegate) {
    override fun write(b: Int) = guarded { out.write(b) }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = guarded { out.write(b, off, len) }

    override fun flush() = guarded { out.flush() }

    override fun close() = guarded { out.close() }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            throw if (e.isOutOfSpaceError()) StorageFullException(e) else LocalMediaWriteException(e)
        }
    }
}

/** True if this failure or one of its causes is an `ENOSPC` errno (no space left on the device). */
internal fun Throwable.isOutOfSpaceError(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any {
        it is ErrnoException &&
            it.errno == OsConstants.ENOSPC
    }

private const val MAX_CAUSE_DEPTH = 10
