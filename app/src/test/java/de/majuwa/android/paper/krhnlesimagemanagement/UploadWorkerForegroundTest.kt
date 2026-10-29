package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseUploadQueue
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.UUID

private class ForegroundTestHistory : UploadHistoryRepository {
    val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/**
 * The real [UploadWorker] when the system refuses its foreground service (e.g. the `dataSync`
 * time budget is used up): the run is retried later with its per-entry progress kept, a re-run
 * uploads only the remaining entries, and after [UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS] runs it
 * gives up with a retry file (issues #15 / #34). Kept out of `UploadWorkerRunTest`, which is at
 * detekt's class size limit.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerForegroundTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val uploaded = FakeUploadedPhotosRepository()
    private val history = ForegroundTestHistory()

    /** Index of the first refused `setForeground` call; null = never refused. */
    private var foregroundFailsFrom: Int? = null
    private var foregroundCalls = 0
    private val verifiedUploads = FakeVerifiedUploadsRepository()

    /** Nextcloud URL + a server hash that always matches (the PUT body is hashed by the dispatcher). */
    private var nextcloudWithHash = false
    private var wifiOnly = false
    private var networkMetered = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    val response = MockResponse().setResponseCode(201)
                    if (request.method == "PUT" && nextcloudWithHash) {
                        val hash =
                            java.security.MessageDigest
                                .getInstance("SHA-256")
                                .digest(request.body.readByteArray())
                                .joinToString("") { "%02x".format(it) }
                        response.setHeader("X-Hash-SHA256", hash)
                    }
                    return response
                }
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

    private fun queueFile(vararg names: String): File {
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
            shadowOf(context.contentResolver)
                .registerInputStream(MediaStore.setRequireOriginal(uri), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            FakeMediaStoreProvider.itemRows[uri.toString()] = FakeMediaStoreProvider.item(3L, 1_700_000_000L + i)
        }
        return File(context.filesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
            it.writeText(JSONObject().put("folderName", "Trip").put("photos", photos).toString())
        }
    }

    private fun run(
        queueFile: File,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<UploadWorker>(context)
                .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
                .setRunAttemptCount(runAttemptCount)
                .setForegroundUpdater { _, _, _ ->
                    val call = foregroundCalls++
                    val failsFrom = foregroundFailsFrom
                    if (failsFrom != null && call >= failsFrom) {
                        Futures.immediateFailedFuture(ForegroundServiceStartNotAllowedException("budget used up"))
                    } else {
                        Futures.immediateFuture(null)
                    }
                }.setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = UploadWorker(
                            appContext,
                            workerParameters,
                            FakeCredentialRepository(
                                initialConfig =
                                    WebDavConfig(
                                        server
                                            .url(
                                                if (nextcloudWithHash) "/remote.php/dav/files/u/" else "/",
                                            ).toString(),
                                        "u",
                                        "p",
                                        "",
                                    ),
                                initialWifiOnly = wifiOnly,
                            ),
                            ::WebDavClient,
                            uploaded,
                            history,
                            { networkMetered },
                            verifiedUploads,
                        )
                    },
                ).build()
                .doWork()
        }

    private fun uploadedIds() = runBlocking { uploaded.uploadedPhotoIds.first() }

    private fun retryFiles() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") }

    private fun resultNotifications() =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }

    @Test
    fun foregroundRefusedAtStart_retriesKeepsQueueAndMarksNothing() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg", "b.jpg")

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(queue.exists())
        assertTrue(uploadedIds().isEmpty())
        assertTrue(requests.none { it.startsWith("PUT") })
        assertTrue(retryFiles().isEmpty())
        assertTrue(history.state.value.isEmpty())
        assertTrue(resultNotifications().isEmpty())
    }

    private fun setInterruptions(
        queue: File,
        count: Int,
    ) = queue.writeText(JSONObject(queue.readText()).put("interruptions", count).toString())

    private fun interruptions(queue: File) = JSONObject(queue.readText()).optInt("interruptions", 0)

    private fun uploadedMarkers(queue: File): List<Boolean> {
        val photos = JSONObject(queue.readText()).getJSONArray("photos")
        return List(photos.length()) { photos.getJSONObject(it).optBoolean("uploaded", false) }
    }

    private fun notificationText() =
        resultNotifications()
            .single()
            .notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString()

    private fun notificationTitle() =
        resultNotifications()
            .single()
            .notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TITLE)
            .toString()

    @Test
    fun foregroundRefusedAfterAVerifiedEntry_keepsItsRecord_andTheRerunDoesNotUploadItAgain() {
        nextcloudWithHash = true
        foregroundFailsFrom = 1
        val queue = queueFile("a.jpg", "b.jpg")

        val first = run(queue)

        assertTrue(first is ListenableWorker.Result.Retry)
        assertEquals(listOf(1L), verifiedUploads.current.map { it.id })
        assertEquals(listOf(true, false), uploadedMarkers(queue))

        foregroundFailsFrom = null
        foregroundCalls = 0
        requests.clear()
        val second = run(queue)

        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals(
            listOf("MKCOL /remote.php/dav/files/u/Trip", "PUT /remote.php/dav/files/u/Trip/b.jpg"),
            requests.toList(),
        )
        assertEquals(setOf(1L, 2L), verifiedUploads.current.map { it.id }.toSet())
    }

    @Test
    fun foregroundRefusedDuringProgress_keepsProgress_andTheRetryUploadsOnlyTheRest() {
        // The initial call succeeds, the progress update after the first photo is refused.
        foregroundFailsFrom = 1
        val queue = queueFile("a.jpg", "b.jpg")

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Retry)
        assertTrue(queue.exists())
        assertEquals(listOf("MKCOL /Trip", "PUT /Trip/a.jpg"), requests.toList())
        // The finished entry is persisted in the queue file and marked at once.
        assertEquals(listOf(true, false), uploadedMarkers(queue))
        assertEquals(setOf(1L), uploadedIds())
        assertTrue(retryFiles().isEmpty())
        assertTrue(history.state.value.isEmpty())
        assertTrue(resultNotifications().isEmpty())

        // The retried run uploads only the remaining entry; the result covers the whole batch.
        foregroundFailsFrom = null
        foregroundCalls = 0
        requests.clear()
        val second = run(queue)
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals(listOf("MKCOL /Trip", "PUT /Trip/b.jpg"), requests.toList())
        assertEquals(2, second.outputData.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(0, second.outputData.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertEquals(setOf(1L, 2L), uploadedIds())
        assertEquals(
            2,
            history.state.value
                .single()
                .photoCount,
        )
        assertEquals(
            0,
            history.state.value
                .single()
                .failedCount,
        )
        assertEquals(
            context.resources.getQuantityString(R.plurals.notification_upload_success, 2, 2),
            notificationText(),
        )
        assertFalse(queue.exists())
    }

    @Test
    fun foregroundRefusedAfterTheLastItem_countsAsCompletion() {
        // Calls: 0 = start, 1 = after a.jpg, 2 = after b.jpg (the last one) refused.
        foregroundFailsFrom = 2
        val queue = queueFile("a.jpg", "b.jpg")

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("MKCOL /Trip", "PUT /Trip/a.jpg", "PUT /Trip/b.jpg"), requests.toList())
        assertEquals(setOf(1L, 2L), uploadedIds())
        assertEquals(1, history.state.value.size)
        assertFalse(queue.exists())
    }

    @Test
    fun queueFullyUploadedByAnEarlierRun_sendsNothingAndReportsTheBatch() {
        val queue = queueFile("a.jpg", "b.jpg")
        val json = JSONObject(queue.readText())
        val photos = json.getJSONArray("photos")
        for (i in 0 until photos.length()) photos.getJSONObject(i).put("uploaded", true)
        queue.writeText(json.toString())

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(requests.isEmpty())
        assertEquals(2, result.outputData.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(
            2,
            history.state.value
                .single()
                .photoCount,
        )
        assertFalse(queue.exists())
    }

    @Test
    fun foregroundRefusedOnTheLastAllowedRun_givesUpWithRetryFileForTheRest() {
        foregroundFailsFrom = 1
        val queue = queueFile("a.jpg", "b.jpg", "c.jpg")

        setInterruptions(queue, UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS - 1)

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(1, result.outputData.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(2, result.outputData.getInt(UploadWorker.KEY_FAILED_COUNT, -1))
        assertFalse(queue.exists())
        assertEquals(setOf(1L), uploadedIds())
        val retry = JSONObject(retryFiles().single().readText()).getJSONArray("photos")
        assertEquals(
            listOf("b.jpg", "c.jpg"),
            List(retry.length()) { retry.getJSONObject(it).getString("displayName") },
        )
        assertEquals(
            3,
            history.state.value
                .single()
                .photoCount,
        )
        assertEquals(
            2,
            history.state.value
                .single()
                .failedCount,
        )
        assertEquals(context.getString(R.string.notification_upload_failed), notificationTitle())
        assertTrue(notificationText().contains(context.getString(R.string.upload_failure_interrupted)))
    }

    @Test
    fun foregroundRefusedAtStartOnTheLastAllowedRun_givesUpWithEveryEntryInTheRetryFile() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg", "b.jpg")

        setInterruptions(queue, UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS - 1)

        val result = run(queue)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(requests.isEmpty())
        assertEquals(2, JSONObject(retryFiles().single().readText()).getJSONArray("photos").length())
        assertFalse(queue.exists())
    }

    @Test
    fun refusalIsCountedInTheQueueFile_notInTheWorkManagerRunAttempts() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg")
        assertEquals(0, interruptions(queue))

        // A high WorkManager run attempt count (metered wake-ups, constraint stops) is irrelevant.
        val result = run(queue, runAttemptCount = UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS * 2)

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(1, interruptions(queue))
        assertTrue(retryFiles().isEmpty())
        assertTrue(resultNotifications().isEmpty())
    }

    @Test
    fun meteredWakeUps_neverConsumeTheInterruptionBudget() {
        wifiOnly = true
        networkMetered = true
        val queue = queueFile("a.jpg")
        val wakeUps = UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS + 2

        repeat(wakeUps) { attempt ->
            assertTrue(run(queue, runAttemptCount = attempt) is ListenableWorker.Result.Retry)
        }
        assertTrue(queue.exists())
        assertEquals(0, interruptions(queue))
        assertTrue(requests.isEmpty())
        assertTrue(resultNotifications().isEmpty())

        // Unmetered again, one foreground refusal: still retried, not given up.
        networkMetered = false
        foregroundFailsFrom = 0
        val result = run(queue, runAttemptCount = wakeUps)
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(1, interruptions(queue))
        assertTrue(retryFiles().isEmpty())
        assertTrue(resultNotifications().isEmpty())
    }

    @Test
    fun refusalsUpToTheLimit_giveUpWithFailureNotificationAndFreshRetryBudget() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg")

        repeat(UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS - 1) {
            assertTrue(run(queue) is ListenableWorker.Result.Retry)
        }
        assertEquals(UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS - 1, interruptions(queue))
        assertTrue(resultNotifications().isEmpty())

        assertTrue(run(queue) is ListenableWorker.Result.Failure)
        assertEquals(context.getString(R.string.notification_upload_failed), notificationTitle())
        val retry = retryFiles().single()
        assertEquals(0, interruptions(retry))
        assertFalse(queue.exists())
    }

    @Test
    fun unsavableCounter_givesUpOnceWorkManagerAttemptsReachTheCap() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg")
        // A non-empty directory in place of the temp file makes every rewriteQueueFile() fail
        // (and survives its cleanup `delete()`).
        val blocker = File(queue.parentFile, "${queue.name}.tmp")
        assertTrue(blocker.mkdirs())
        assertTrue(File(blocker, "keep").createNewFile())

        repeat(UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS) { attempt ->
            assertTrue(run(queue, runAttemptCount = attempt) is ListenableWorker.Result.Retry)
        }
        assertEquals(0, interruptions(queue))
        assertTrue(resultNotifications().isEmpty())

        val result = run(queue, runAttemptCount = UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(context.getString(R.string.notification_upload_failed), notificationTitle())
        assertEquals(1, retryFiles().size)
        assertFalse(queue.exists())
    }

    @Test
    fun runAttemptCountBackstop_givesUpEvenWhenTheCounterIsSaved() {
        foregroundFailsFrom = 0
        val queue = queueFile("a.jpg")

        val result = run(queue, runAttemptCount = UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS_BACKSTOP)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(context.getString(R.string.notification_upload_failed), notificationTitle())
        assertEquals(1, retryFiles().size)
    }

    @Test
    fun interruptionsAreClampedWhenParsed() {
        val json = JSONObject(queueFile("a.jpg").readText())
        val max = UploadWorker.MAX_UPLOAD_RUN_ATTEMPTS

        fun parsed(value: Any) = parseUploadQueue(JSONObject(json.toString()).put("interruptions", value).toString())

        assertEquals(max, parsed(Int.MAX_VALUE)?.interruptions)
        assertEquals(max, parsed(Int.MAX_VALUE.toLong() + 10L)?.interruptions)
        assertEquals(max, parsed(Long.MAX_VALUE)?.interruptions)
        assertEquals(2, parsed(2.0)?.interruptions)
        assertEquals(0, parsed(-3)?.interruptions)
        assertEquals(0, parsed(Long.MIN_VALUE)?.interruptions)
    }

    @Test
    fun queueWithoutInterruptionsParsesAsZeroAndTheCounterRoundTrips() {
        val queue = queueFile("a.jpg")
        val parsed = parseUploadQueue(queue.readText())
        assertEquals(0, parsed?.interruptions)

        val counted = parsed!!.withInterruption().withInterruption()
        assertEquals(2, parseUploadQueue(counted.toProgressJson().toString())?.interruptions)
        // Retry files start with a fresh budget; a zero counter is not written.
        assertFalse(counted.toRetryJson(listOf(0)).has("interruptions"))
        assertFalse(parsed.toProgressJson().has("interruptions"))
        assertEquals(
            0,
            parseUploadQueue(JSONObject(queue.readText()).put("interruptions", -3).toString())?.interruptions,
        )
        assertEquals(
            0,
            parseUploadQueue(JSONObject(queue.readText()).put("interruptions", "x").toString())?.interruptions,
        )
    }
}
