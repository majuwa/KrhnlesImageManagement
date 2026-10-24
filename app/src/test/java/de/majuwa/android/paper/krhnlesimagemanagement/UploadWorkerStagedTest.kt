package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.UUID

private class NoHistory : UploadHistoryRepository {
    override val entries = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())

    override suspend fun addEntry(entry: UploadHistoryEntry) = Unit

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/**
 * The real [UploadWorker] with staged (shared) photos, issues #16 / #35: the share grant is gone,
 * only the app-private copy is readable.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerStagedTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val putBodies = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failingNames = mutableSetOf<String>()
    private var mkcolCode = 201
    private val uploadedPhotos = FakeUploadedPhotosRepository()

    /** When set, the progress update with this value is cancelled: WorkManager stops the worker there. */
    private var stopAtProgress: Int? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Robolectric.setupContentProvider(UnreadablePhotoProvider::class.java, UNREADABLE_AUTHORITY)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    if (request.method == "MKCOL") return MockResponse().setResponseCode(mkcolCode)
                    val path = request.path.orEmpty()
                    putBodies[path] = request.body.readUtf8()
                    val failing = failingNames.any { path.endsWith("/$it") }
                    return MockResponse().setResponseCode(if (failing) 500 else 201)
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        context.noBackupFilesDir
            .listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.forEach { it.delete() }
        shareStagingDir(context).deleteRecursively()
    }

    private fun configuredRepo() =
        FakeCredentialRepository(
            initialConfig = WebDavConfig(url = server.url("/").toString(), username = "u", password = "p"),
        )

    private fun worker(
        queueFile: File,
        repo: FakeCredentialRepository,
    ): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setProgressUpdater(
                object : ProgressUpdater {
                    override fun updateProgress(
                        context: Context,
                        id: UUID,
                        data: Data,
                    ): ListenableFuture<Void?> =
                        if (data.getInt(UploadWorker.KEY_PROGRESS, -1) == stopAtProgress) {
                            Futures.immediateCancelledFuture()
                        } else {
                            Futures.immediateFuture(null)
                        }
                },
            ).setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = UploadWorker(
                        appContext,
                        workerParameters,
                        repo,
                        ::WebDavClient,
                        uploadedPhotos,
                        NoHistory(),
                        { false },
                    )
                },
            ).build()

    private fun run(
        queueFile: File,
        repo: FakeCredentialRepository = configuredRepo(),
    ): ListenableWorker.Result = runBlocking { worker(queueFile, repo).doWork() }

    private fun retryFiles() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") }

    private fun retryNames(file: File): List<String> {
        val photos = JSONObject(file.readText()).getJSONArray("photos")
        return (0 until photos.length()).map { photos.getJSONObject(it).getString("displayName") }
    }

    private fun uploadedIds() = runBlocking { uploadedPhotos.uploadedPhotoIds.first() }

    /** A shared photo: its URI grant is gone (reading it fails), only the staged copy is readable. */
    private class Shared(
        val name: String,
        val content: String? = "data-$name",
        val staged: Any? = UUID.randomUUID().toString(),
        val id: Long? = null,
    )

    private val revokedUri = "content://$UNREADABLE_AUTHORITY/photos/denied"

    private fun stagedFile(name: String) = File(shareStagingDir(context), name)

    private fun stagedQueueFile(
        shared: List<Shared>,
        prefix: String = "upload",
        folder: Any = "Trip",
    ): File {
        shareStagingDir(context).mkdirs()
        val arr = JSONArray()
        shared.forEach { photo ->
            val obj =
                JSONObject()
                    .put("uri", revokedUri)
                    .put("mimeType", "image/jpeg")
                    .put("displayName", photo.name)
                    .put("staged", photo.staged ?: JSONObject.NULL)
            photo.id?.let { obj.put("id", it) }
            arr.put(obj)
            val staged = photo.staged
            if (photo.content != null && staged is String && !staged.contains('/')) {
                stagedFile(staged).writeText(photo.content)
            }
        }
        val name =
            if (prefix ==
                "retry"
            ) {
                "retry_queue_${UUID.randomUUID()}.json"
            } else {
                "upload_queue_1_${UUID.randomUUID()}.json"
            }
        return File(context.noBackupFilesDir, name).also {
            it.writeText(JSONObject().put("folderName", folder).put("photos", arr).toString())
        }
    }

    private fun stagedNames(shared: List<Shared>) = shared.map { it.staged as String }

    @Test
    fun stagedQueue_uploadsTheStagedCopiesWithoutTheGrant_andMarksNoMediaStorePhoto() {
        val shared = listOf(Shared("a.jpg"), Shared("b.jpg"))
        val queue = stagedQueueFile(shared)

        val result = run(queue)

        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(mapOf("/Trip/a.jpg" to "data-a.jpg", "/Trip/b.jpg" to "data-b.jpg"), putBodies.toMap())
        assertEquals(0, uploadedPhotos.markCalls)
        assertTrue(uploadedIds().isEmpty())
        assertFalse(queue.exists())
        assertTrue(stagedNames(shared).none { stagedFile(it).exists() })
    }

    @Test
    fun stoppedAndRerunWorker_uploadsAllPhotosFromStaging() {
        val shared = listOf(Shared("a.jpg"), Shared("b.jpg"), Shared("c.jpg"))
        val queue = stagedQueueFile(shared)
        stopAtProgress = 1

        val stopped = runCatching { run(queue) }

        assertTrue(stopped.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(queue.exists())
        assertTrue(stagedNames(shared).all { stagedFile(it).exists() })

        stopAtProgress = null
        putBodies.clear()
        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(setOf("/Trip/a.jpg", "/Trip/b.jpg", "/Trip/c.jpg"), putBodies.keys.toSet())
        assertEquals("data-c.jpg", putBodies["/Trip/c.jpg"])
        assertFalse(queue.exists())
        assertTrue(stagedNames(shared).none { stagedFile(it).exists() })
    }

    @Test
    fun failedStagedPhoto_survivesQueueDeletion_andTheRetryUploadsItFromStaging() {
        failingNames += "b.jpg"
        val shared = listOf(Shared("a.jpg"), Shared("b.jpg"))
        val (stagedA, stagedB) = stagedNames(shared)

        run(stagedQueueFile(shared))

        val retry = retryFiles().single()
        val entry = JSONObject(retry.readText()).getJSONArray("photos").getJSONObject(0)
        assertEquals(stagedB, entry.getString("staged"))
        assertFalse(entry.has("id"))
        assertFalse(stagedFile(stagedA).exists())
        assertTrue(stagedFile(stagedB).exists())

        failingNames.clear()
        putBodies.clear()
        val result = run(retry)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(mapOf("/Trip/b.jpg" to "data-b.jpg"), putBodies.toMap())
        assertFalse(retry.exists())
        assertFalse(stagedFile(stagedB).exists())
        assertEquals(0, uploadedPhotos.markCalls)
    }

    @Test
    fun missingStagedFile_failsThatPhotoWithoutReadingTheUri() {
        val readable = "content://media/external/images/media/77"
        shadowOf(context.contentResolver).registerInputStream(readable.toUri(), ByteArrayInputStream(byteArrayOf(1)))
        val queue = stagedQueueFile(listOf(Shared("a.jpg"), Shared("gone.jpg", content = null)))
        // Point the missing photo at a readable URI: it must still not be read.
        val json = JSONObject(queue.readText())
        json.getJSONArray("photos").getJSONObject(1).put("uri", readable)
        queue.writeText(json.toString())

        val result = run(queue)

        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(1, data.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertFalse(putBodies.containsKey("/Trip/gone.jpg"))
        assertEquals(listOf("gone.jpg"), retryNames(retryFiles().single()))
    }

    @Test
    fun invalidStagedEntries_areRejected() {
        listOf(
            Shared("a.jpg", staged = "../escape"),
            Shared("a.jpg", staged = "not-a-uuid"),
            Shared("a.jpg", staged = UUID.randomUUID().toString().uppercase()),
            Shared("a.jpg", staged = 5),
            Shared("a.jpg", staged = null),
            Shared("a.jpg", id = 5),
        ).forEach { photo ->
            val queue = stagedQueueFile(listOf(photo))

            assertTrue(run(queue) is ListenableWorker.Result.Failure)
            assertFalse(queue.exists())
        }
        assertTrue(requests.isEmpty())
        assertEquals(0, uploadedPhotos.markCalls)
    }

    @Test
    fun notConfigured_deletesTheStagedFilesWithTheQueue() {
        val shared = listOf(Shared("a.jpg"))
        val queue = stagedQueueFile(shared)

        assertTrue(run(queue, FakeCredentialRepository()) is ListenableWorker.Result.Failure)

        assertFalse(queue.exists())
        assertFalse(stagedFile(stagedNames(shared).single()).exists())
    }

    @Test
    fun malformedQueue_stillDeletesItsStagedFiles() {
        val shared = listOf(Shared("a.jpg"))
        val queue = stagedQueueFile(shared, folder = 5)

        assertTrue(run(queue) is ListenableWorker.Result.Failure)

        assertFalse(queue.exists())
        assertFalse(stagedFile(stagedNames(shared).single()).exists())
    }

    @Test
    fun folderCreationFailure_deletesTheStagedFiles() {
        mkcolCode = 403
        val shared = listOf(Shared("a.jpg"))

        assertTrue(run(stagedQueueFile(shared)) is ListenableWorker.Result.Failure)

        assertFalse(stagedFile(stagedNames(shared).single()).exists())
    }

    @Test
    fun sharedBatchWrittenByUploadEnqueuer_uploadsFromStaging() {
        val name = UUID.randomUUID().toString()
        shareStagingDir(context).mkdirs()
        stagedFile(name).writeText("shared")
        val photo =
            de.majuwa.android.paper.krhnlesimagemanagement.model.Photo(
                id = -1L,
                uri = revokedUri.toUri(),
                displayName = "s.jpg",
                dateTaken = LocalDate.of(2026, 5, 1),
                size = 6L,
                mimeType = "image/jpeg",
                stagedName = name,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val written =
            runBlocking {
                UploadEnqueuer(context, configuredRepo(), scope).writeQueueFile(
                    de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
                        .Occasion("Trip", listOf(photo)),
                )
            }
        scope.cancel()

        assertTrue(run(written) is ListenableWorker.Result.Success)
        assertEquals(mapOf("/Trip/s.jpg" to "shared"), putBodies.toMap())
        assertEquals(0, uploadedPhotos.markCalls)
    }

    private companion object {
        const val UNREADABLE_AUTHORITY = "krhnles.test.unreadable.staged"
    }
}
