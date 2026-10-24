package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.worker.RetryUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.handleRetry
import de.majuwa.android.paper.krhnlesimagemanagement.worker.hasUnfinishedRetryWork
import de.majuwa.android.paper.krhnlesimagemanagement.worker.retryWorkName
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class RetryUploadReceiverTest {
    private lateinit var context: Context
    private val testFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        testFiles.forEach { it.delete() }
        testFiles.clear()
    }

    private fun retryFile(name: String = "retry_queue_${UUID.randomUUID()}.json"): File {
        val file = File(context.filesDir, name)
        file.writeText("""{"folderName":"TestFolder","photos":[]}""")
        testFiles.add(file)
        return file
    }

    private fun retryIntent(
        path: String?,
        tag: String? = null,
        notificationId: Int = UploadWorker.RESULT_NOTIFICATION_ID,
    ) = Intent(context, RetryUploadReceiver::class.java).apply {
        if (path != null) putExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE, path)
        putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        if (tag != null) putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_TAG, tag)
    }

    private fun workInfos() = WorkManager.getInstance(context).getWorkInfosByTag("photo_upload").get()

    private fun postNotification(
        tag: String,
        id: Int = UploadWorker.RESULT_NOTIFICATION_ID,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, "test")
                .setSmallIcon(R.drawable.ic_notification_upload)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(tag, id, notification)
    }

    private fun activeTags() =
        context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.tag }

    @Test
    fun handleRetry_enqueuesUploadWorkerWithQueueFile() =
        runBlocking {
            val file = retryFile()

            handleRetry(context, retryIntent(file.absolutePath), flowOf(false))

            val infos = workInfos()
            assertEquals(1, infos.size)
            assertEquals(NetworkType.NOT_REQUIRED, infos.single().constraints.requiredNetworkType)
        }

    @Test
    fun handleRetry_wifiOnlyRequiresUnmetered() =
        runBlocking {
            handleRetry(context, retryIntent(retryFile().absolutePath), flowOf(true))

            assertEquals(NetworkType.UNMETERED, workInfos().single().constraints.requiredNetworkType)
        }

    @Test
    fun handleRetry_slowSettingFallsBackToUnmetered() =
        runBlocking {
            val slow =
                flow {
                    delay(10_000)
                    emit(false)
                }

            handleRetry(context, retryIntent(retryFile().absolutePath), slow, wifiOnlyTimeoutMs = 50)

            assertEquals(NetworkType.UNMETERED, workInfos().single().constraints.requiredNetworkType)
        }

    @Test
    fun handleRetry_failingSettingFallsBackToUnmetered() =
        runBlocking {
            val failing = flow<Boolean> { throw IllegalStateException("boom") }

            handleRetry(context, retryIntent(retryFile().absolutePath), failing)

            assertEquals(NetworkType.UNMETERED, workInfos().single().constraints.requiredNetworkType)
        }

    @Test
    fun handleRetry_cancelsExactlyTheNotificationFromExtras() =
        runBlocking {
            postNotification("upload_queue_1_a.json")
            postNotification("upload_queue_2_b.json")

            handleRetry(
                context,
                retryIntent(retryFile().absolutePath, tag = "upload_queue_1_a.json"),
                flowOf(false),
            )

            assertEquals(listOf("upload_queue_2_b.json"), activeTags())
        }

    @Test
    fun handleRetry_missingFileCancelsNotificationButEnqueuesNothing() =
        runBlocking {
            postNotification("upload_queue_1_a.json")
            val missing = File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json")

            handleRetry(context, retryIntent(missing.absolutePath, tag = "upload_queue_1_a.json"), flowOf(false))

            assertTrue(activeTags().isEmpty())
            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun handleRetry_isPersistedWhenItReturns() =
        runBlocking {
            val file = retryFile()

            handleRetry(context, retryIntent(file.absolutePath), flowOf(true))

            // enqueue(...).await() ran inside handleRetry, so the unique work is already stored.
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(file.name).get()
            assertEquals(1, infos.size)
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        }

    @Test
    fun handleRetry_twiceBeforeTheWorkerFinishesEnqueuesOnlyOneWork() =
        runBlocking {
            val file = retryFile()
            val intent = retryIntent(file.absolutePath)

            // Wi-Fi only: the unmetered constraint keeps the first work pending, as a running one would.
            handleRetry(context, intent, flowOf(true))
            handleRetry(context, intent, flowOf(true))

            assertEquals(1, workInfos().size)
            assertEquals(
                1,
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(file.name)
                    .get()
                    .size,
            )
        }

    @Test
    fun handleRetry_refreshesTheRetryFileTime_andItsPendingWorkCountsAsUnfinished() =
        runBlocking {
            val file = retryFile().apply { setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)) }
            assertEquals(false, hasUnfinishedRetryWork(context, retryWorkName(file)))

            handleRetry(context, retryIntent(file.absolutePath), flowOf(true))

            assertTrue(System.currentTimeMillis() - file.lastModified() < TimeUnit.MINUTES.toMillis(1))
            // Wi-Fi only keeps the retry pending: the staging sweep must keep its staged copies.
            assertEquals(true, hasUnfinishedRetryWork(context, retryWorkName(file)))
        }

    @Test
    fun handleRetry_doesNothingWhenQueueFileExtraIsMissing() =
        runBlocking {
            handleRetry(context, retryIntent(null), flowOf(false))

            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun handleRetry_rejectsPathOutsideFilesDir() =
        runBlocking {
            handleRetry(context, retryIntent("/etc/passwd"), flowOf(false))

            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun handleRetry_rejectsRelativePathTraversal() =
        runBlocking {
            val traversalPath = context.filesDir.absolutePath + "/../../../etc/passwd"

            handleRetry(context, retryIntent(traversalPath), flowOf(false))

            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun handleRetry_rejectsFileInSubdirectory() =
        runBlocking {
            val subDir = File(context.filesDir, "sub").also { it.mkdirs() }
            val file = File(subDir, "retry_queue_${UUID.randomUUID()}.json")
            file.writeText("""{"folderName":"x","photos":[]}""")
            testFiles.add(file)
            testFiles.add(subDir)

            handleRetry(context, retryIntent(file.absolutePath), flowOf(false))

            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun handleRetry_rejectsUnexpectedFileName() =
        runBlocking {
            val other = retryFile(name = "other_data.json")
            val nonHex = retryFile(name = "retry_queue_test.json")

            handleRetry(context, retryIntent(other.absolutePath), flowOf(false))
            handleRetry(context, retryIntent(nonHex.absolutePath), flowOf(false))

            assertTrue(workInfos().isEmpty())
        }

    @Test
    fun retryQueueFile_hasCorrectJsonStructure() {
        val retryFile = File(context.filesDir, "retry_queue_structure_test.json")
        val json =
            JSONObject().apply {
                put("folderName", "VacationPhotos")
                put(
                    "photos",
                    JSONArray().also { arr ->
                        arr.put(
                            JSONObject().apply {
                                put("uri", "content://media/external/images/media/1")
                                put("mimeType", "image/jpeg")
                                put("displayName", "photo_1.jpg")
                            },
                        )
                        arr.put(
                            JSONObject().apply {
                                put("uri", "content://media/external/images/media/3")
                                put("mimeType", "image/png")
                                put("displayName", "photo_3.png")
                            },
                        )
                    },
                )
            }
        retryFile.writeText(json.toString())
        testFiles.add(retryFile)

        val parsed = JSONObject(retryFile.readText())
        val photos = parsed.getJSONArray("photos")

        assertTrue(retryFile.exists())
        assertEquals("VacationPhotos", parsed.getString("folderName"))
        assertEquals(2, photos.length())
        assertEquals(
            "content://media/external/images/media/1",
            photos.getJSONObject(0).getString("uri"),
        )
        assertEquals(
            "content://media/external/images/media/3",
            photos.getJSONObject(1).getString("uri"),
        )
    }
}
