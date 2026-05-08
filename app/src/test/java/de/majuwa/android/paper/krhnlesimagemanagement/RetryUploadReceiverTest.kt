package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.worker.RetryUploadReceiver
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import junit.framework.TestCase.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RetryUploadReceiverTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun retryUploadReceiver_enqueuesUploadWorkerWithQueueFile() {
        val retryFile = File(context.filesDir, "retry_queue_test.json")
        retryFile.writeText("""{"folderName":"TestFolder","photos":[]}""")

        val intent =
            Intent(context, RetryUploadReceiver::class.java).apply {
                putExtra(RetryUploadReceiver.EXTRA_QUEUE_FILE, retryFile.absolutePath)
                putExtra(RetryUploadReceiver.EXTRA_NOTIFICATION_ID, UploadWorker.NOTIFICATION_ID)
            }

        val receiver = RetryUploadReceiver()
        receiver.onReceive(context, intent)

        val workInfos =
            WorkManager.getInstance(context)
                .getWorkInfosByTag("photo_upload")
                .get()
        assertTrue(workInfos.isNotEmpty())

        retryFile.delete()
    }

    @Test
    fun retryUploadReceiver_doesNothingWhenQueueFileExtrasIsMissing() {
        val intent = Intent(context, RetryUploadReceiver::class.java)

        val receiver = RetryUploadReceiver()
        receiver.onReceive(context, intent)

        val workInfos =
            WorkManager.getInstance(context)
                .getWorkInfosByTag("photo_upload")
                .get()
        assertTrue(workInfos.isEmpty())
    }

    @Test
    fun retryQueueFile_hasCorrectJsonStructure() {
        val retryFile = File(context.filesDir, "retry_queue_structure_test.json")
        val json =
            JSONObject().apply {
                put("folderName", "VacationPhotos")
                put(
                    "photos",
                    org.json.JSONArray().also { arr ->
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

        val parsed = JSONObject(retryFile.readText())
        val photos = parsed.getJSONArray("photos")

        assertTrue(retryFile.exists())
        assertTrue(parsed.getString("folderName") == "VacationPhotos")
        assertTrue(photos.length() == 2)
        assertTrue(photos.getJSONObject(0).getString("uri") == "content://media/external/images/media/1")
        assertTrue(photos.getJSONObject(1).getString("uri") == "content://media/external/images/media/3")

        retryFile.delete()
    }
}
