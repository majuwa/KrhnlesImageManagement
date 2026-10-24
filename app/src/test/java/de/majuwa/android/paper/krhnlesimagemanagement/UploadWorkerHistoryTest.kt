package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
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
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

private class SwitchableHistory : UploadHistoryRepository {
    val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state
    var failOnAdd = false

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        check(!failOnAdd) { "history store unavailable" }
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/**
 * History gaps not covered by `UploadWorkerRunTest` (at detekt's class size limit): a retry run
 * records its own entry, and a failing history store never changes the upload result (issue #11).
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerHistoryTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val failingNames = mutableSetOf<String>()
    private val uploaded = FakeUploadedPhotosRepository()
    private val history = SwitchableHistory()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(
                        if (failingNames.any { request.path.orEmpty().endsWith("/$it") }) 500 else 201,
                    )
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { it.delete() }
        }
    }

    private fun queueFile(
        prefix: String,
        vararg names: String,
    ): File {
        val photos = JSONArray()
        names.forEachIndexed { i, name ->
            val uri = Uri.parse("content://media/external/images/media/${i + 1}")
            photos.put(
                JSONObject()
                    .put("id", i + 1L)
                    .put("uri", uri.toString())
                    .put("mimeType", "image/jpeg")
                    .put("displayName", name),
            )
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        return File(context.filesDir, "${prefix}_${UUID.randomUUID()}.json").also {
            it.writeText(JSONObject().put("folderName", "Trip").put("photos", photos).toString())
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
                            FakeCredentialRepository(
                                initialConfig = WebDavConfig(server.url("/").toString(), "u", "p", ""),
                            ),
                            ::WebDavClient,
                            uploaded,
                            history,
                            { false },
                        )
                    },
                ).build()
                .doWork()
        }

    private fun retryFiles() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") }

    @Test
    fun retryRun_addsItsOwnEntryCoveringOnlyTheRetriedPhotos() {
        failingNames += "b.jpg"
        assertTrue(run(queueFile("upload_queue_1", "a.jpg", "b.jpg", "c.jpg")) is ListenableWorker.Result.Success)
        val firstEntry = history.state.value.single()
        assertEquals(3, firstEntry.photoCount)
        assertEquals(1, firstEntry.failedCount)

        failingNames.clear()
        val retry = retryFiles().single()
        shadowOf(context.contentResolver).registerInputStream(
            Uri.parse("content://media/external/images/media/2"),
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
        )
        assertTrue(run(retry) is ListenableWorker.Result.Success)

        val entries = history.state.value
        assertEquals(2, entries.size)
        assertEquals(1, entries.last().photoCount)
        assertEquals(0, entries.last().failedCount)
        assertEquals("Trip", entries.last().occasionName)
    }

    @Test
    fun persistFailure_doesNotChangeResultOrNotification() {
        history.failOnAdd = true
        failingNames += "b.jpg"

        val result = run(queueFile("upload_queue_1", "a.jpg", "b.jpg"))

        assertTrue(result is ListenableWorker.Result.Success)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(1, data.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        val posted =
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }
        assertEquals(1, posted.size)
        assertEquals(setOf(1L), runBlocking { uploaded.uploadedPhotoIds.first() })
        assertTrue(history.state.value.isEmpty())
    }
}
