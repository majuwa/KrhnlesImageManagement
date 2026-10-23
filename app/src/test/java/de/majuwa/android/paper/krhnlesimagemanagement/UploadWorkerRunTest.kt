package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import de.majuwa.android.paper.krhnlesimagemanagement.worker.RetryUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
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
import java.io.FileNotFoundException
import java.util.UUID

/** Test provider whose `denied` photo throws [SecurityException] and `gone` photo [FileNotFoundException]. */
class UnreadablePhotoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? =
        when (uri.lastPathSegment) {
            "denied" -> throw SecurityException("grant revoked")
            else -> throw FileNotFoundException("gone")
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
}

private class RecordingHistory : UploadHistoryRepository {
    private val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

/** Runs the real [UploadWorker] against MockWebServer with a fake credential repository. */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerRunTest {
    private class Photo(
        val id: Long,
        val name: String,
        val uri: String = "content://media/external/images/media/$id",
    )

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val failingNames = mutableSetOf<String>()
    private var mkcolCode = 201
    private val uploadedPhotos = FakeUploadedPhotosRepository()
    private val history = RecordingHistory()

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
                    return when {
                        request.method == "MKCOL" -> MockResponse().setResponseCode(mkcolCode)

                        failingNames.any {
                            request.path.orEmpty().endsWith(
                                "/$it",
                            )
                        } -> MockResponse().setResponseCode(500)

                        else -> MockResponse().setResponseCode(201)
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        context.filesDir
            .listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.forEach { it.delete() }
    }

    private fun configuredRepo(wifiOnly: Boolean = false) =
        FakeCredentialRepository(
            initialConfig = WebDavConfig(url = server.url("/").toString(), username = "u", password = "p"),
            initialWifiOnly = wifiOnly,
        )

    private val progressUpdates = mutableListOf<Int>()

    // The tests call doWork() directly instead of going through WorkManager: the work-testing
    // scheduler would run the worker on its own executor and would not give us the Result.
    private fun worker(
        queueFile: File,
        repo: FakeCredentialRepository = configuredRepo(),
    ): UploadWorker {
        return TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setProgressUpdater(
                object : ProgressUpdater {
                    override fun updateProgress(
                        context: Context,
                        id: UUID,
                        data: Data,
                    ): ListenableFuture<Void?> {
                        progressUpdates += data.getInt(UploadWorker.KEY_PROGRESS, -1)
                        return Futures.immediateFuture(null)
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
                        history,
                        { networkMetered },
                    )
                },
            ).build()
    }

    private fun queueFile(
        prefix: String,
        folder: String,
        photos: List<Photo>,
    ): File {
        val name =
            if (prefix ==
                "retry"
            ) {
                "retry_queue_${UUID.randomUUID()}.json"
            } else {
                "upload_queue_1_${UUID.randomUUID()}.json"
            }
        val file = File(context.filesDir, name)
        val arr = JSONArray()
        photos.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("uri", it.uri)
                    .put("mimeType", "image/jpeg")
                    .put("displayName", it.name),
            )
            if (it.uri.toUri().authority != UNREADABLE_AUTHORITY) {
                shadowOf(
                    context.contentResolver,
                ).registerInputStream(it.uri.toUri(), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }
        }
        file.writeText(JSONObject().put("folderName", folder).put("photos", arr).toString())
        return file
    }

    private fun run(
        queueFile: File,
        repo: FakeCredentialRepository = configuredRepo(),
    ): ListenableWorker.Result = runBlocking { worker(queueFile, repo).doWork() }

    private fun retryFiles() =
        context.filesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") }

    private fun retryNames(file: File): List<String> {
        val photos = JSONObject(file.readText()).getJSONArray("photos")
        return (0 until photos.length()).map { photos.getJSONObject(it).getString("displayName") }
    }

    private fun uploadedIds() = runBlocking { uploadedPhotos.uploadedPhotoIds.first() }

    private fun postedNotifications() = context.getSystemService(NotificationManager::class.java).activeNotifications

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun partialFailure_writesRetryFileWithOnlyFailedEntriesAndSameFolder() {
        failingNames += "b.jpg"
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "b.jpg"), Photo(3, "c.jpg")))

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(1, data.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertFalse(queue.exists())
        val retry = retryFiles().single()
        assertEquals(listOf("b.jpg"), retryNames(retry))
        assertEquals("Trip", JSONObject(retry.readText()).getString("folderName"))
        assertEquals(setOf(1L, 3L), uploadedIds())
    }

    @Test
    fun unreadablePhotosAreFailedAndRemainingPhotosUpload() {
        val denied = Photo(2, "denied.jpg", "content://$UNREADABLE_AUTHORITY/photos/denied")
        val gone = Photo(3, "gone.jpg", "content://$UNREADABLE_AUTHORITY/photos/gone")
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), denied, gone))

        val result = run(queue)

        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(2, data.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertEquals(listOf("denied.jpg", "gone.jpg"), retryNames(retryFiles().single()))
        assertEquals(setOf(1L), uploadedIds())
    }

    @Test
    fun retryRun_uploadsOnlyListedPhotosToStoredAutoDateFolderAndMarksNewIds() {
        runBlocking { uploadedPhotos.markAsUploaded(setOf(10L)) }
        failingNames += "y.jpg"
        val retry = queueFile("retry", "2026/05-May", listOf(Photo(5, "x.jpg"), Photo(6, "y.jpg")))

        val result = run(retry)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf("MKCOL /2026", "MKCOL /2026/05-May", "PUT /2026/05-May/x.jpg", "PUT /2026/05-May/y.jpg"),
            requests.map { it.replace("%2F", "/") },
        )
        assertFalse(retry.exists())
        assertEquals(setOf(10L, 5L), uploadedIds())
        assertEquals(listOf("y.jpg"), retryNames(retryFiles().single()))
    }

    @Test
    fun missingQueueFile_failsWithoutRequests() {
        val missing = File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json")

        assertTrue(run(missing) is ListenableWorker.Result.Failure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun malformedQueueFile_failsAndIsDeleted() {
        val file = File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json")
        file.writeText("{not json")

        assertTrue(run(file) is ListenableWorker.Result.Failure)
        assertFalse(file.exists())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun queueFileInSubdirectory_isRejectedAndKept() {
        val sub = File(context.filesDir, "sub").also { it.mkdirs() }
        val file = File(sub, "retry_queue_${UUID.randomUUID()}.json")
        file.writeText("""{"folderName":"x","photos":[]}""")

        assertTrue(run(file) is ListenableWorker.Result.Failure)
        assertTrue(file.exists())
        assertTrue(requests.isEmpty())
        file.delete()
        sub.delete()
    }

    private fun expectedFolderFailureText(reasonRes: Int) =
        context.getString(R.string.notification_create_folder_failed, context.getString(reasonRes))

    @Test
    fun folderCreationForbidden_showsFolderUnavailableMessageUnderQueueTag() {
        mkcolCode = 403
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue) is ListenableWorker.Result.Failure)

        val posted = postedNotifications().single()
        assertEquals(queue.name, posted.tag)
        assertEquals(UploadWorker.RESULT_NOTIFICATION_ID, posted.id)
        assertEquals(expectedFolderFailureText(R.string.upload_error_folder_unavailable), text(posted.notification))
        assertFalse(text(posted.notification).contains("HTTP"))
        assertFalse(text(posted.notification).contains("album", ignoreCase = true))
    }

    @Test
    fun folderCreationNotFound_doesNotSayAlbumNotFound() {
        mkcolCode = 404
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        run(queue)

        val message = text(postedNotifications().single().notification)
        assertEquals(expectedFolderFailureText(R.string.upload_error_folder_unavailable), message)
        assertFalse(message.contains("album", ignoreCase = true))
    }

    @Test
    fun dotDotFolderName_isInvalidFolderNameWithoutAnyRequest() {
        val queue = queueFile("upload", "..", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue) is ListenableWorker.Result.Failure)

        assertTrue(requests.isEmpty())
        val message = text(postedNotifications().single().notification)
        assertEquals(expectedFolderFailureText(R.string.upload_error_invalid_folder_name), message)
        assertFalse(message.contains("URL", ignoreCase = true))
        assertEquals(1, runBlocking { history.entries.first() }.single().failedCount)
    }

    @Test
    fun unconfiguredServer_showsNotConfiguredAndDeletesQueue() {
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue, FakeCredentialRepository()) is ListenableWorker.Result.Failure)

        assertFalse(queue.exists())
        assertTrue(requests.isEmpty())
        assertEquals(
            expectedFolderFailureText(R.string.upload_error_not_configured),
            text(postedNotifications().single().notification),
        )
    }

    @Test
    fun progressAdvancesForFailedPhotosToo() {
        failingNames += "b.jpg"
        val denied = Photo(3, "denied.jpg", "content://$UNREADABLE_AUTHORITY/photos/denied")
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "b.jpg"), denied))

        run(queue)

        assertEquals(listOf(1, 2, 3), progressUpdates)
    }

    @Test
    fun wifiOnlyOnMeteredNetwork_retriesLaterAndKeepsQueueFile() {
        setActiveNetworkMetered(true)
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue, configuredRepo(wifiOnly = true)) is ListenableWorker.Result.Retry)

        assertTrue(queue.exists())
        assertTrue(requests.isEmpty())
        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun wifiOnlyOnUnmeteredNetwork_uploads() {
        setActiveNetworkMetered(false)
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue, configuredRepo(wifiOnly = true)) is ListenableWorker.Result.Success)
        assertFalse(queue.exists())
    }

    @Test
    fun wifiOffOnMeteredNetwork_uploads() {
        setActiveNetworkMetered(true)
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        assertTrue(run(queue, configuredRepo(wifiOnly = false)) is ListenableWorker.Result.Success)
    }

    @Test
    fun fileUriInQueue_countsAsFailedAndIsNeverRead() {
        val privateFile = File(context.filesDir, "secret.txt").also { it.writeText("secret") }
        val fileUri = privateFile.toURI().toString().replace("file:/", "file:///")
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "secret.jpg", fileUri)))

        val result = run(queue)

        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(1, data.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertFalse(requests.any { it.contains("secret.jpg") })
        assertEquals(listOf("secret.jpg"), retryNames(retryFiles().single()))
        privateFile.delete()
    }

    private var networkMetered = false

    private fun setActiveNetworkMetered(metered: Boolean) {
        networkMetered = metered
    }

    @Test
    fun partialFailureNotification_usesOwnIdAndRetryActionCancelsExactlyThatId() {
        failingNames += "b.jpg"
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "b.jpg")))

        run(queue)

        val posted = postedNotifications().single()
        assertEquals(UploadWorker.RESULT_NOTIFICATION_ID, posted.id)
        assertEquals(queue.name, posted.tag)
        assertTrue(posted.id != UploadWorker.NOTIFICATION_ID)
        val action = posted.notification.actions.single()
        assertEquals(context.getString(R.string.notification_retry_failed, 1), action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(
            UploadWorker.RESULT_NOTIFICATION_ID,
            intent.getIntExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, -1),
        )
        assertEquals(queue.name, intent.getStringExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG))
        assertEquals(retryFiles().single().absolutePath, intent.getStringExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE))
    }

    @Test
    fun fullSuccessNotification_hasNoRetryActionAndNotForegroundId() {
        val queue = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))

        run(queue)

        val posted = postedNotifications().single()
        assertTrue(posted.id != UploadWorker.NOTIFICATION_ID)
        assertNull(posted.notification.actions)
        assertTrue(retryFiles().isEmpty())
    }

    @Test
    fun retryActionsOfTwoBatchesAreDistinct() {
        val w = worker(File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json"))
        val a = "retry_queue_${UUID.randomUUID()}.json"
        val b = "retry_queue_${UUID.randomUUID()}.json"

        val first = w.buildCompletionNotification(2, 1, File(context.filesDir, a).absolutePath, "tag-a")
        val second = w.buildCompletionNotification(2, 1, File(context.filesDir, b).absolutePath, "tag-b")

        val intentA = shadowOf(first.actions.single().actionIntent).savedIntent
        val intentB = shadowOf(second.actions.single().actionIntent).savedIntent
        assertNotNull(intentA.data)
        assertTrue(intentA.data != intentB.data)
        assertFalse(intentA.filterEquals(intentB))
        // The PendingIntents themselves must differ, otherwise the later one overwrites the earlier.
        assertFalse(first.actions.single().actionIntent == second.actions.single().actionIntent)
    }

    @Test
    fun twoBatches_haveSeparateNotificationsWithSameIdButDifferentTags() {
        failingNames += "b.jpg"
        val first = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "b.jpg")))
        val second = queueFile("upload", "Trip2", listOf(Photo(3, "c.jpg"), Photo(4, "b.jpg")))

        run(first)
        run(second)

        val posted = postedNotifications()
        assertEquals(2, posted.size)
        assertEquals(setOf(first.name, second.name), posted.map { it.tag }.toSet())
        assertEquals(setOf(UploadWorker.RESULT_NOTIFICATION_ID), posted.map { it.id }.toSet())
        assertTrue(UploadWorker.RESULT_NOTIFICATION_ID != UploadWorker.NOTIFICATION_ID)
    }

    private companion object {
        const val UNREADABLE_AUTHORITY = "krhnles.test.unreadable"
    }
}
