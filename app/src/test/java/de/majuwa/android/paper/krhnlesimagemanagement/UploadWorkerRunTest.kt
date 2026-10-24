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
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.upload.resolveAutoDateUploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.worker.RetryUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
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
import java.io.FileNotFoundException
import java.time.LocalDate
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

    /** MKCOL answers per decoded request path (e.g. 405 for existing levels); others use [mkcolCode]. */
    private val mkcolCodesByPath = mutableMapOf<String, Int>()
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
                        request.method == "MKCOL" -> {
                            MockResponse().setResponseCode(mkcolCodesByPath[request.path] ?: mkcolCode)
                        }

                        failingNames.any {
                            request.path.orEmpty().endsWith(
                                "/$it",
                            )
                        } -> {
                            MockResponse().setResponseCode(500)
                        }

                        else -> {
                            MockResponse().setResponseCode(201)
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir
                .listFiles()
                ?.filter { it.name.endsWith(".json") }
                ?.forEach { it.delete() }
        }
    }

    private fun configuredRepo(
        wifiOnly: Boolean = false,
        baseFolder: String = "",
        serverPath: String = "/",
    ) = FakeCredentialRepository(
        initialConfig =
            WebDavConfig(
                url = server.url(serverPath).toString(),
                username = "u",
                password = "p",
                baseFolder = baseFolder,
            ),
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

    private fun registerPhoto(photo: Photo) {
        if (photo.uri.toUri().authority != UNREADABLE_AUTHORITY) {
            shadowOf(context.contentResolver)
                .registerInputStream(photo.uri.toUri(), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
    }

    /**
     * Writes a queue file with raw JSON values: an entry folder or [label] / [topFolder] of [ABSENT]
     * is left out, `null` is written as `JSONObject.NULL`.
     */
    private fun rawQueueFile(
        entries: List<Pair<Photo, Any?>>,
        label: Any? = ABSENT,
        topFolder: Any? = ABSENT,
    ): File {
        val name = "upload_queue_1_${UUID.randomUUID()}.json"
        val arr = JSONArray()
        entries.forEach { (photo, folder) ->
            val obj =
                JSONObject()
                    .put("id", photo.id)
                    .put("uri", photo.uri)
                    .put("mimeType", "image/jpeg")
                    .put("displayName", photo.name)
            if (folder !== ABSENT) obj.put("folderName", folder ?: JSONObject.NULL)
            arr.put(obj)
            registerPhoto(photo)
        }
        val root = JSONObject()
        if (topFolder !== ABSENT) root.put("folderName", topFolder ?: JSONObject.NULL)
        if (label !== ABSENT) root.put("label", label ?: JSONObject.NULL)
        root.put("photos", arr)
        return File(context.filesDir, name).also { it.writeText(root.toString()) }
    }

    private fun decodedRequests() = requests.map { it.replace("%2F", "/") }

    private fun historyEntries() = runBlocking { history.entries.first() }

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
    fun perPhotoQueue_oneFailedPhoto_postsOneResultNotificationWithRetryActionCount() {
        failingNames += "a.jpg"
        val entries = listOf(Photo(1, "a.jpg") to "2026/05/13", Photo(2, "b.jpg") to "2026/05/14")

        run(rawQueueFile(entries, label = "2026/05/13 – 2026/05/14"))

        val posted = postedNotifications().filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }
        assertEquals(1, posted.size)
        assertEquals(
            context.getString(R.string.notification_retry_failed, 1),
            posted
                .single()
                .notification.actions
                .single()
                .title
                .toString(),
        )
    }

    @Test
    fun retryFile_isWrittenToNoBackupFilesDir() {
        failingNames += "a.jpg"

        run(queueFile("upload", "Trip", listOf(Photo(1, "a.jpg"), Photo(2, "b.jpg"))))

        val retry = retryFiles().single()
        assertEquals(context.noBackupFilesDir.canonicalFile, retry.canonicalFile.parentFile)
        assertTrue(
            context.filesDir
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith("retry_queue_") },
        )
    }

    private fun movedQueueFile(dir: File): File {
        val source = queueFile("upload", "Trip", listOf(Photo(1, "a.jpg")))
        return File(dir, source.name).also { source.copyTo(it) }.also { source.delete() }
    }

    @Test
    fun queueFileInNoBackupFilesDir_runs_butOtherParentIsRejectedAndKept() {
        assertTrue(run(movedQueueFile(context.noBackupFilesDir)) is ListenableWorker.Result.Success)
        requests.clear()
        val other = movedQueueFile(context.cacheDir)

        assertTrue(run(other) is ListenableWorker.Result.Failure)
        assertTrue(other.exists() && requests.isEmpty())
        other.delete()
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

    // ── Per-photo (auto date day folder) queues, issues #18 / #37 ─────────

    private val twoDays =
        listOf(
            Photo(1, "a.jpg") to "2026/04/30",
            Photo(2, "b.jpg") to "2026/04/30",
            Photo(3, "c.jpg") to "2026/05/01",
        )

    private fun expectedTwoDayRequests(root: String) =
        listOf(
            "MKCOL ${root}Photos",
            "MKCOL ${root}Photos/Krohn",
            "MKCOL ${root}Photos/Krohn/2026",
            "MKCOL ${root}Photos/Krohn/2026/04",
            "MKCOL ${root}Photos/Krohn/2026/04/30",
            "MKCOL ${root}Photos/Krohn/2026/05",
            "MKCOL ${root}Photos/Krohn/2026/05/01",
            "PUT ${root}Photos/Krohn/2026/04/30/a.jpg",
            "PUT ${root}Photos/Krohn/2026/04/30/b.jpg",
            "PUT ${root}Photos/Krohn/2026/05/01/c.jpg",
        )

    @Test
    fun perPhotoQueue_createsEveryFolderLevelOnceThenPutsEachPhotoIntoItsDayFolder() {
        // Shared levels already exist: 405 must not fail the upload.
        listOf("/Photos", "/Photos/Krohn", "/Photos/Krohn/2026").forEach { mkcolCodesByPath[it] = 405 }
        val queue = rawQueueFile(twoDays, label = "2026/04/30 – 2026/05/01")

        val result = run(queue, configuredRepo(baseFolder = "Photos/Krohn"))

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(expectedTwoDayRequests("/"), decodedRequests())
        assertEquals(listOf(1, 2, 3), progressUpdates)
        assertEquals(1, postedNotifications().size)
        val entry = historyEntries().single()
        assertEquals("2026/04/30 – 2026/05/01", entry.occasionName)
        assertEquals(3, entry.photoCount)
        assertEquals(0, entry.failedCount)
        assertEquals(setOf(1L, 2L, 3L), uploadedIds())
        assertFalse(queue.exists())
    }

    @Test
    fun perPhotoQueue_withServerPath_createsAndPutsBelowThatPath() {
        val queue = rawQueueFile(twoDays, label = "2026/04/30 – 2026/05/01")

        val result = run(queue, configuredRepo(baseFolder = "Photos/Krohn", serverPath = "/remote.php/dav/files/u/"))

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(expectedTwoDayRequests("/remote.php/dav/files/u/"), decodedRequests())
    }

    @Test
    fun perPhotoRetry_keepsEachPhotosFolderAndLabelAndMarksOnlyNewSuccesses() {
        failingNames += setOf("a.jpg", "b.jpg")
        val entries =
            listOf(
                Photo(1, "a.jpg") to "2026/05/13",
                Photo(2, "b.jpg") to "2026/05/14",
                Photo(3, "c.jpg") to "2026/05/14",
            )
        run(rawQueueFile(entries, label = "2026/05/13 – 2026/05/14"))

        val retry = retryFiles().single()
        val json = JSONObject(retry.readText())
        assertFalse(json.has("folderName"))
        assertEquals("2026/05/13 – 2026/05/14", json.getString("label"))
        val photos = json.getJSONArray("photos")
        assertEquals(
            listOf("a.jpg" to "2026/05/13", "b.jpg" to "2026/05/14"),
            (0 until photos.length()).map {
                photos.getJSONObject(it).getString("displayName") to photos.getJSONObject(it).getString("folderName")
            },
        )
        assertEquals(setOf(3L), uploadedIds())

        failingNames.clear()
        requests.clear()
        entries.forEach { registerPhoto(it.first) }
        val result = run(retry)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf("PUT /2026/05/13/a.jpg", "PUT /2026/05/14/b.jpg"),
            decodedRequests().filter { it.startsWith("PUT") },
        )
        assertEquals(setOf(1L, 2L, 3L), uploadedIds())
        assertEquals("2026/05/13 – 2026/05/14", historyEntries().last().occasionName)
        assertFalse(retry.exists())
    }

    @Test
    fun perPhotoQueue_invalidLastFolder_failsWithoutAnyRequest() {
        val entries =
            listOf(
                Photo(1, "a.jpg") to "2026/05/01",
                Photo(2, "b.jpg") to "2026/05/02",
                Photo(3, "c.jpg") to "2026/../x",
            )
        val queue = rawQueueFile(entries, label = "x")

        assertTrue(run(queue) is ListenableWorker.Result.Failure)

        assertTrue(requests.isEmpty())
        assertFalse(queue.exists())
        val entry = historyEntries().single()
        assertEquals(3, entry.photoCount)
        assertEquals(3, entry.failedCount)
        assertEquals(
            expectedFolderFailureText(R.string.upload_error_invalid_folder_name),
            text(postedNotifications().single().notification),
        )
    }

    private fun assertMalformed(queue: File) {
        assertTrue(run(queue) is ListenableWorker.Result.Failure)
        assertFalse(queue.exists())
        assertTrue(requests.isEmpty())
        assertTrue(historyEntries().isEmpty())
    }

    @Test
    fun perPhotoQueue_nonStringEntryFolder_isMalformed() {
        listOf<Any?>(null, 5, JSONArray(), JSONObject()).forEach { folder ->
            assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to folder), label = "x"))
            // Without a label the folder key alone selects the per-photo format.
            assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to folder)))
        }
    }

    @Test
    fun perPhotoQueue_blankEntryFolder_isMalformed() {
        assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to " "), label = "x"))
    }

    @Test
    fun entryWithoutFolderAndNoCommonFolder_isMalformed() {
        assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to ABSENT)))
    }

    @Test
    fun mixedQueues_areMalformed() {
        assertMalformed(
            rawQueueFile(listOf(Photo(1, "a.jpg") to "2026/05/01", Photo(2, "b.jpg") to ABSENT)),
        )
        assertMalformed(
            rawQueueFile(listOf(Photo(1, "a.jpg") to "2026/05/01"), topFolder = "Trip"),
        )
        assertMalformed(
            rawQueueFile(listOf(Photo(1, "a.jpg") to ABSENT), label = "x", topFolder = "Trip"),
        )
        assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to ABSENT), label = "x"))
    }

    @Test
    fun nonStringCommonFolder_isMalformed() {
        assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to ABSENT), topFolder = 5))
        assertMalformed(rawQueueFile(listOf(Photo(1, "a.jpg") to ABSENT), topFolder = null))
    }

    @Test
    fun nonStringLabel_isDroppedNotCoerced() {
        listOf<Any?>(5, null, JSONArray()).forEach { label ->
            history.entries.value = emptyList()
            val queue = rawQueueFile(listOf(Photo(1, "a.jpg") to "2026/05/01"), label = label)

            assertTrue(run(queue) is ListenableWorker.Result.Success)

            assertEquals("", historyEntries().single().occasionName)
        }
    }

    @Test
    fun longLabel_isRecordedTruncatedTo200Characters() {
        val label = "L".repeat(250)
        val queue = rawQueueFile(listOf(Photo(1, "a.jpg") to "2026/05/01"), label = label)

        run(queue)

        assertEquals("L".repeat(200), historyEntries().single().occasionName)
    }

    @Test
    fun perPhotoQueue_mkcolForbiddenOnSecondDay_failsBeforeAnyPut() {
        mkcolCodesByPath["/2026/05/01"] = 403
        val entries = listOf(Photo(1, "a.jpg") to "2026/04/30", Photo(2, "b.jpg") to "2026/05/01")
        val queue = rawQueueFile(entries, label = "2026/04/30 – 2026/05/01")

        assertTrue(run(queue) is ListenableWorker.Result.Failure)

        assertTrue(decodedRequests().none { it.startsWith("PUT") })
        assertTrue(decodedRequests().contains("MKCOL /2026/05/01"))
        assertEquals(
            expectedFolderFailureText(R.string.upload_error_folder_unavailable),
            text(postedNotifications().single().notification),
        )
        assertEquals(2, historyEntries().single().failedCount)
        assertTrue(retryFiles().isEmpty())
    }

    @Test
    fun queueWrittenByUploadEnqueuer_isUploadedIntoTheDayFolders() {
        val photos =
            listOf(
                modelPhoto(2, LocalDate.of(2026, 5, 1)),
                modelPhoto(1, de.majuwa.android.paper.krhnlesimagemanagement.model.Photo.UNKNOWN_DATE),
            )
        photos.forEach { registerPhoto(Photo(it.id, it.displayName, it.uri.toString())) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enqueuer = UploadEnqueuer(context, configuredRepo(), scope)
        val written = runBlocking { enqueuer.writeQueueFile(resolveAutoDateUploadBatch(photos)) }
        scope.cancel()

        val result = run(written)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(
                "MKCOL /1970",
                "MKCOL /1970/01",
                "MKCOL /1970/01/01",
                "MKCOL /2026",
                "MKCOL /2026/05",
                "MKCOL /2026/05/01",
                "PUT /1970/01/01/1.jpg",
                "PUT /2026/05/01/2.jpg",
            ),
            decodedRequests(),
        )
        assertEquals("1970/01/01 – 2026/05/01", historyEntries().single().occasionName)
    }

    private fun modelPhoto(
        id: Long,
        date: LocalDate,
    ) = de.majuwa.android.paper.krhnlesimagemanagement.model.Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "$id.jpg",
        dateTaken = date,
        size = 1L,
        mimeType = "image/jpeg",
    )

    private companion object {
        const val UNREADABLE_AUTHORITY = "krhnles.test.unreadable"

        /** Marker for "leave this JSON key out" in [rawQueueFile]. */
        val ABSENT = Any()
    }
}
