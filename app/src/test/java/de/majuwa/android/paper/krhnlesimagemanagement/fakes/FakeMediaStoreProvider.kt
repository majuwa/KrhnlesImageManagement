package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * A `media` authority provider for Robolectric tests of `MediaRepository`: one [MatrixCursor] per
 * collection (Images / Video), built from [imageRows] / [videoRows] with the requested projection.
 * [videoFailure] is thrown by the video query (e.g. a [SecurityException] without video access).
 */
class FakeMediaStoreProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        itemRows[uri.toString()]?.let { row -> return itemCursor(uri, projection, row) }
        inserted[uri.toString()]?.let { return itemCursor(uri, projection, it.toRow()) }
        val rows =
            when (uri) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> {
                    imageRows
                }

                MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                    videoFailure?.let { throw it }
                    videoRows
                }

                else -> {
                    return null
                }
            }
        val columns = projection ?: ALL_COLUMNS
        return MatrixCursor(columns).apply {
            // A real provider sorts by DATE_TAKEN DESC; the fake keeps the given order.
            rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) }
        }
    }

    /**
     * Single-item query with query args (the production trash confirmation passes
     * `QUERY_ARG_MATCH_TRASHED`). Like the platform, a query on an item uri returns trashed and
     * pending rows whatever the query args say, so callers must read `IS_TRASHED` / `IS_PENDING`.
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        val row =
            itemRows[uri.toString()] ?: inserted[uri.toString()]?.toRow()
                ?: return super.query(uri, projection, queryArgs, cancellationSignal)
        return itemCursor(uri, projection, row)
    }

    private fun InsertedItem.toRow(): Map<String, Any?> =
        mapOf(
            MediaStore.MediaColumns.SIZE to file.length(),
            MediaStore.MediaColumns.DATE_MODIFIED to 1L,
            MediaStore.MediaColumns.IS_TRASHED to 0,
            MediaStore.MediaColumns.IS_PENDING to if (pending) 1 else 0,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME to owner,
            MediaStore.MediaColumns.DISPLAY_NAME to displayName,
            MediaStore.MediaColumns.RELATIVE_PATH to relativePath,
            MediaStore.MediaColumns.MIME_TYPE to mimeType,
        )

    private fun itemCursor(
        uri: Uri,
        projection: Array<String>?,
        row: Map<String, Any?>,
    ): Cursor {
        itemQueries.merge(uri.toString(), 1, Int::plus)
        itemFailures[uri.toString()]?.let { throw it }
        val columns = projection ?: arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        return MatrixCursor(columns).apply { addRow(columns.map { row[it] }.toTypedArray()) }
    }

    /**
     * Serves the bytes of [files] (keyed by the uri without its query, so the `requireOriginal`
     * variant gets the same content) with their real length, like the platform's descriptor. Other
     * uris keep the default behaviour (not openable through the provider).
     */
    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? {
        val content =
            files[
                uri
                    .buildUpon()
                    .clearQuery()
                    .build()
                    .toString(),
            ] ?: return super.openAssetFile(uri, mode)
        val file = File.createTempFile("media", ".bin").apply { deleteOnExit() }
        file.writeBytes(content)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, content.size.toLong())
    }

    override fun getType(uri: Uri): String? = null

    /**
     * Inserts into `images/media` like MediaProvider: records the values, owns the item by this
     * app's package and gives a taken `RELATIVE_PATH` + `DISPLAY_NAME` a ` (1)`, ` (2)` suffix.
     * [insertFailure] makes the insert fail.
     */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? {
        if (values == null || uri.path?.endsWith("/images/media") != true) return null
        insertFailure?.let { throw it }
        val relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
        val name = uniqueName(relativePath, values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty())
        val id = nextId.getAndIncrement()
        val itemUri = Uri.parse("content://$AUTHORITY/${uri.pathSegments.first()}/images/media/$id")
        inserted[itemUri.toString()] =
            InsertedItem(
                relativePath = relativePath,
                displayName = name,
                mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE),
                pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1,
                owner = ownerPackage ?: context?.packageName.orEmpty(),
                file = File.createTempFile("saved", ".bin").apply { deleteOnExit() },
            )
        return itemUri
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        val item = inserted.remove(uri.toString()) ?: return 0
        item.file.delete()
        deletes += uri.toString()
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        val item = inserted[uri.toString()] ?: return 0
        updateFailure?.let { throw it }
        values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.let { item.pending = it == 1 }
        return 1
    }

    /** Write access to an inserted item's backing file; failure injection via [openFailure]. */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val item = inserted[uri.toString()] ?: return super.openFile(uri, mode)
        openFailure?.let { throw it }
        return ParcelFileDescriptor.open(
            item.file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    private fun uniqueName(
        relativePath: String,
        requested: String,
    ): String {
        val taken =
            inserted.values
                .filter { it.relativePath == relativePath }
                .map { it.displayName.lowercase() }
                .toSet()
        val stem = requested.substringBeforeLast('.', requested)
        val extension = requested.substring(stem.length)
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) requested else "$stem ($it)$extension" }
            .first { it.lowercase() !in taken }
    }

    /** One item inserted through [insert]. */
    class InsertedItem(
        val relativePath: String,
        val displayName: String,
        val mimeType: String?,
        var pending: Boolean,
        val owner: String,
        val file: File,
    ) {
        val bytes: ByteArray get() = file.readBytes()
    }

    companion object {
        const val AUTHORITY = MediaStore.AUTHORITY

        private val ALL_COLUMNS =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Video.VideoColumns.DURATION,
            )

        /** File contents by uri string (without query), opened with their length by [openAssetFile]. */
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        /** MediaStore item rows by uri string (`content://media/external/images/media/<id>`). */
        val itemRows = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

        /** Items inserted through [insert] by uri string. */
        val inserted = java.util.concurrent.ConcurrentHashMap<String, InsertedItem>()

        /** Uris deleted through [delete], in order. */
        val deletes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        private val nextId =
            java.util.concurrent.atomic
                .AtomicLong(1000L)

        @Volatile
        var insertFailure: RuntimeException? = null

        @Volatile
        var updateFailure: RuntimeException? = null

        @Volatile
        var openFailure: java.io.FileNotFoundException? = null

        /** Owner of newly inserted items; null = this app's package. */
        @Volatile
        var ownerPackage: String? = null

        @Volatile
        var imageRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoRows: List<Map<String, Any?>> = emptyList()

        @Volatile
        var videoFailure: RuntimeException? = null

        /** Thrown by the item query of a uri (e.g. a crashed provider for one item). */
        val itemFailures = java.util.concurrent.ConcurrentHashMap<String, RuntimeException>()

        /** Number of item queries per uri string. */
        val itemQueries = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun reset() {
            itemRows.clear()
            files.clear()
            itemFailures.clear()
            itemQueries.clear()
            inserted.clear()
            deletes.clear()
            insertFailure = null
            updateFailure = null
            openFailure = null
            ownerPackage = null
            imageRows = emptyList()
            videoRows = emptyList()
            videoFailure = null
        }

        /** The per-item row for [itemRows]: the values the upload / removal checks read. */
        fun item(
            size: Long,
            dateModified: Long,
            trashed: Boolean = false,
            pending: Boolean = false,
        ): Map<String, Any?> =
            mapOf(
                MediaStore.MediaColumns.SIZE to size,
                MediaStore.MediaColumns.DATE_MODIFIED to dateModified,
                MediaStore.MediaColumns.IS_TRASHED to if (trashed) 1 else 0,
                MediaStore.MediaColumns.IS_PENDING to if (pending) 1 else 0,
            )

        /** One cursor row; null values stay SQL NULL. */
        fun row(
            id: Long,
            name: String,
            dateTaken: Long?,
            mimeType: String?,
            size: Long = 100L,
            durationMs: Long? = null,
        ): Map<String, Any?> =
            mapOf(
                MediaStore.MediaColumns._ID to id,
                MediaStore.MediaColumns.DISPLAY_NAME to name,
                MediaStore.MediaColumns.DATE_TAKEN to dateTaken,
                MediaStore.MediaColumns.SIZE to size,
                MediaStore.MediaColumns.MIME_TYPE to mimeType,
                MediaStore.Video.VideoColumns.DURATION to durationMs,
            )
    }
}
