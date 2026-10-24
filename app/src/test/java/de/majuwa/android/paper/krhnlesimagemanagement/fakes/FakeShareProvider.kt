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
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocks a provider call until [release] — or, if [honoursCancel], until the caller cancels the
 * call's `CancellationSignal` (the call then throws `OperationCanceledException` like a real
 * provider). With `honoursCancel = false` it models a provider that ignores the signal.
 */
class ProviderGate(
    private val honoursCancel: Boolean = true,
) {
    val started = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** True once the caller cancelled the call's signal. */
    @Volatile
    var cancelled = false
        private set

    fun release() = released.countDown()

    fun await(signal: CancellationSignal?) {
        signal?.setOnCancelListener {
            cancelled = true
            if (honoursCancel) released.countDown()
        }
        started.countDown()
        released.await(20, TimeUnit.SECONDS)
        if (honoursCancel) signal?.throwIfCanceled()
    }
}

/**
 * A sharing app's provider for Robolectric tests: [items] (keyed by the last path segment) define
 * the reported type, name, size, failures, gates and the [Item.content] served through
 * `openTypedAssetFile(…, CancellationSignal)` (what `ContentResolver.openAssetFileDescriptor(uri,
 * "r", signal)` calls). An item without content cannot be opened.
 */
class FakeShareProvider : ContentProvider() {
    class Item(
        val type: String? = "image/jpeg",
        val name: String? = null,
        val size: Long? = null,
        val typeFailure: RuntimeException? = null,
        val queryFailure: RuntimeException? = null,
        val openFailure: RuntimeException? = null,
        val content: ByteArray? = null,
        val openGate: ProviderGate? = null,
        val queryGate: ProviderGate? = null,
    )

    override fun onCreate() = true

    private fun item(uri: Uri): Item? = uri.lastPathSegment?.let { items[it] }

    override fun getType(uri: Uri): String? {
        val item = item(uri) ?: return null
        item.typeFailure?.let { throw it }
        return item.type
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        val item = item(uri) ?: return null
        item.queryGate?.await(cancellationSignal)
        item.queryFailure?.let { throw it }
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(item.name, item.size))
        }
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val item = item(uri) ?: throw FileNotFoundException("unknown")
        item.openFailure?.let { throw it }
        item.openGate?.await(signal)
        val content = item.content ?: throw FileNotFoundException("no content")
        val file = File.createTempFile("shared", ".bin").apply { deleteOnExit() }
        file.writeBytes(content)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = throw FileNotFoundException("use openTypedAssetFile")

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "krhnles.test.share"
        val items = ConcurrentHashMap<String, Item>()

        fun uri(id: String): Uri = Uri.parse("content://$AUTHORITY/items/$id")
    }
}
