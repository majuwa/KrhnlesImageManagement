package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.upload.resolveAutoDateUploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseUploadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.Collections

/** Provider that serves [content] with a declared length through `openAssetFile` (size known). */
class SizedMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor {
        // The first call opens the stream; later ones are the worker's size lookup.
        if (openCalls++ > 0) lengthFailure?.let { throw it }
        val file = File.createTempFile("sized", ".bin").apply { deleteOnExit() }
        file.writeBytes(content)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, content.size.toLong())
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

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
        const val AUTHORITY = "krhnles.test.sized"

        @Volatile
        var content: ByteArray = ByteArray(0)

        /** Thrown by every `openAssetFile` after the first one (the size lookup); null = none. */
        @Volatile
        var lengthFailure: RuntimeException? = null

        @Volatile
        var openCalls = 0
    }
}

/** Generates [size] bytes lazily; records the largest single read request (bounded = streamed). */
private class GeneratedStream(
    private val size: Long,
) : InputStream() {
    var position = 0L
        private set
    var largestRead = 0
        private set

    override fun read(): Int = if (position >= size) -1 else (position++ and 0x7F).toInt()

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        largestRead = maxOf(largestRead, len)
        if (position >= size) return -1
        val n = minOf(len.toLong(), size - position).toInt()
        position += n
        return n
    }
}

private class VideoTestHistory : UploadHistoryRepository {
    private val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/** Videos go through the same queue, worker, retry and uploaded markers as photos (issues #15 / #34). */
@RunWith(RobolectricTestRunner::class)
class UploadVideoTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val failingNames = Collections.synchronizedSet(mutableSetOf<String>())

    /** Status per file name for PUTs (e.g. 413 / 507); others answer 201 (or 500 if failing). */
    private val statusByName = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val uploaded = FakeUploadedPhotosRepository()
    private val may1 = LocalDate.of(2026, 5, 1)
    private val collidingRawId = 42L
    private val videoId = (1L shl 62) + collidingRawId

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Robolectric.setupContentProvider(SizedMediaProvider::class.java, SizedMediaProvider.AUTHORITY)
        server = MockWebServer()
        // Keep no request body in memory; bodySize still counts every byte.
        server.bodyLimit = 0
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val name = request.path.orEmpty().substringAfterLast('/')
                    val failing = name in failingNames
                    return MockResponse().setResponseCode(statusByName[name] ?: if (failing) 500 else 201)
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        SizedMediaProvider.lengthFailure = null
        SizedMediaProvider.openCalls = 0
        server.shutdown()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { it.delete() }
        }
    }

    private fun repo() =
        FakeCredentialRepository(
            initialConfig = WebDavConfig(server.url("/").toString(), "u", "p", ""),
        )

    private fun image(
        rawId: Long,
        date: LocalDate = may1,
    ) = Photo(
        id = rawId,
        uri = Uri.parse("content://media/external/images/media/$rawId"),
        displayName = "$rawId.jpg",
        dateTaken = date,
        size = 3,
        mimeType = "image/jpeg",
    )

    private fun video(
        rawId: Long,
        date: LocalDate = may1,
        mimeType: String = "video/mp4",
        uri: Uri = Uri.parse("content://media/external/video/media/$rawId"),
    ) = Photo(
        id = rawId or Photo.VIDEO_ID_FLAG,
        uri = uri,
        displayName = "$rawId.mp4",
        dateTaken = date,
        size = 3,
        mimeType = mimeType,
        mediaKind = MediaKind.Video,
        durationMs = 5_000,
    )

    private fun register(
        photo: Photo,
        stream: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
    ) = shadowOf(context.contentResolver).registerInputStream(photo.uri, stream)

    private fun writeQueue(batch: UploadBatch): File {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            return runBlocking { UploadEnqueuer(context, repo(), scope).writeQueueFile(batch) }
        } finally {
            scope.cancel()
        }
    }

    private fun run(queueFile: File): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<UploadWorker>(context)
                .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = UploadWorker(
                            appContext,
                            workerParameters,
                            repo(),
                            ::WebDavClient,
                            uploaded,
                            VideoTestHistory(),
                            { false },
                        )
                    },
                ).build()
                .doWork()
        }

    private fun puts() = requests.filter { it.method == "PUT" }

    private fun retryFile() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .single { it.name.startsWith("retry_queue_") }

    @Test
    fun mixedSelection_isOneQueueWithEveryItemAndUploadsIntoTheOccasion() {
        val items = listOf(image(1), image(2), video(3))
        items.forEach { register(it) }

        val queue = writeQueue(UploadBatch.Occasion("Trip", items))
        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf("/Trip/1.jpg", "/Trip/2.jpg", "/Trip/3.mp4"),
            puts().map { it.path },
        )
        assertEquals("video/mp4", puts().last().getHeader("Content-Type"))
    }

    @Test
    fun videoQueueEntry_keepsCompositeIdAndMime_andRoundTripsThroughRetryJson() {
        val queue = writeQueue(UploadBatch.Occasion("Trip", listOf(video(collidingRawId, mimeType = "video/*"))))

        val json = JSONObject(queue.readText()).getJSONArray("photos").getJSONObject(0)
        assertEquals(videoId, json.getLong("id"))
        assertEquals("video/*", json.getString("mimeType"))
        assertEquals("content://media/external/video/media/42", json.getString("uri"))

        val parsed = parseUploadQueue(queue.readText())!!
        assertEquals(videoId, parsed.entries.single().id)
        val retried = parseUploadQueue(parsed.toRetryJson(listOf(0)).toString())!!
        assertEquals(videoId, retried.entries.single().id)
        assertEquals("video/*", retried.entries.single().mimeType)
    }

    @Test
    fun imageAndVideoWithTheSameRawId_onlyTheUploadedOneIsMarked() {
        val photo = image(collidingRawId)
        val clip = video(collidingRawId)
        register(photo)
        register(clip)
        failingNames += photo.displayName

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(photo, clip))))

        assertEquals(setOf(videoId), runBlocking { uploaded.uploadedPhotoIds.first() })
    }

    @Test
    fun failedVideo_retryFileKeepsItsMimeAndIdAndReuploadsIt() {
        val clip = video(collidingRawId, mimeType = "video/*")
        register(clip)
        failingNames += clip.displayName

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(clip))))

        val retry = retryFile()
        val entry = JSONObject(retry.readText()).getJSONArray("photos").getJSONObject(0)
        assertEquals("video/*", entry.getString("mimeType"))
        assertEquals(videoId, entry.getLong("id"))

        failingNames.clear()
        requests.clear()
        register(clip)
        assertTrue(run(retry) is ListenableWorker.Result.Success)
        assertEquals("video/*", puts().single().getHeader("Content-Type"))
        assertEquals(setOf(videoId), runBlocking { uploaded.uploadedPhotoIds.first() })
    }

    private fun resultText(): String =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .single { it.id == UploadWorker.RESULT_NOTIFICATION_ID }
            .notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()

    @Test
    fun http413_failsTheItemWithTheTooLargeMessage() {
        val clip = video(1)
        register(clip)
        statusByName[clip.displayName] = 413

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(clip))))

        val text = resultText()
        assertTrue(text, text.contains(context.getString(R.string.upload_failure_too_large)))
        assertFalse(text.contains(context.getString(R.string.upload_failure_storage_full)))
        assertTrue(runBlocking { uploaded.uploadedPhotoIds.first() }.isEmpty())
    }

    @Test
    fun http507_failsTheItemWithTheStorageFullMessage() {
        val clip = video(1)
        val photo = image(2)
        register(clip)
        register(photo)
        statusByName[clip.displayName] = 507

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(photo, clip))))

        val text = resultText()
        assertTrue(text, text.contains(context.getString(R.string.upload_failure_storage_full)))
        assertTrue(
            text,
            text.startsWith(context.resources.getQuantityString(R.plurals.notification_upload_partial, 1, 1, 1)),
        )
    }

    @Test
    fun otherFailures_haveNoSpecificMessage() {
        val clip = video(1)
        register(clip)
        failingNames += clip.displayName

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(clip))))

        assertEquals(context.resources.getQuantityString(R.plurals.notification_upload_partial, 0, 0, 1), resultText())
    }

    @Test
    fun largeVideo_isStreamedInSmallReadsNotBuffered() {
        val size = 32L * 1024 * 1024
        val stream = GeneratedStream(size)
        val clip = video(7)
        register(clip, stream)

        val result = run(writeQueue(UploadBatch.Occasion("Trip", listOf(clip))))

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(size, puts().single().bodySize)
        assertEquals(size, stream.position)
        // Read chunk by chunk (never the whole file at once).
        assertTrue("largest read ${stream.largestRead}", stream.largestRead <= 64 * 1024)
    }

    @Test
    fun videoWithReportedSize_sendsContentLength_unknownSizeIsChunked() {
        SizedMediaProvider.content = ByteArray(2048) { 1 }
        val sized = video(8, uri = "content://${SizedMediaProvider.AUTHORITY}/video/8".toUri())
        val unsized = video(9)
        register(unsized)

        run(writeQueue(UploadBatch.Occasion("Trip", listOf(sized, unsized))))

        val (first, second) = puts()
        assertEquals("2048", first.getHeader("Content-Length"))
        assertNull(first.getHeader("Transfer-Encoding"))
        assertEquals(2048L, first.bodySize)
        assertNull(second.getHeader("Content-Length"))
        assertEquals("chunked", second.getHeader("Transfer-Encoding"))
    }

    @Test
    fun autoDateFolders_putVideosIntoTheirDayFolder_andUndatedVideosInto1970() {
        val dated = video(1, date = may1)
        val undated = video(2, date = Photo.UNKNOWN_DATE)
        val photo = image(3, date = may1)
        listOf(dated, undated, photo).forEach { register(it) }

        val batch = resolveAutoDateUploadBatch(listOf(dated, undated, photo))
        assertEquals(listOf("1970/01/01", "2026/05/01", "2026/05/01"), batch.folders)

        run(writeQueue(batch))

        assertEquals(
            listOf("/1970/01/01/2.mp4", "/2026/05/01/3.jpg", "/2026/05/01/1.mp4"),
            puts().map { it.path!!.replace("%2F", "/") },
        )
    }

    @Test
    fun unexpectedFailureOfTheSizeLookup_uploadsChunkedInsteadOfFailingTheRun() {
        SizedMediaProvider.content = ByteArray(2048) { 1 }
        SizedMediaProvider.openCalls = 0
        SizedMediaProvider.lengthFailure = IllegalStateException("provider bug")
        val clip = video(8, uri = "content://${SizedMediaProvider.AUTHORITY}/video/8".toUri())

        val result = run(writeQueue(UploadBatch.Occasion("Trip", listOf(clip))))

        assertTrue(result is ListenableWorker.Result.Success)
        // The size lookup really ran (and threw).
        assertTrue(SizedMediaProvider.openCalls >= 2)
        val put = puts().single()
        assertNull(put.getHeader("Content-Length"))
        assertEquals("chunked", put.getHeader("Transfer-Encoding"))
        assertEquals(2048L, put.bodySize)
    }
}
