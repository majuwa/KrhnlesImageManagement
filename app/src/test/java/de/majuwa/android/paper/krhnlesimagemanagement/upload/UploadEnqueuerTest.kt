package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class UploadEnqueuerTest {
    private lateinit var context: Context
    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        context.filesDir
            .listFiles()
            ?.filter { it.name.startsWith("upload_queue_") }
            ?.forEach { it.delete() }
    }

    private fun photo(id: Long) =
        Photo(
            id = id,
            uri = Uri.parse("content://media/external/images/media/$id"),
            displayName = "$id.jpg",
            dateTaken = LocalDate.of(2026, 5, 1),
            size = 1,
            mimeType = "image/jpeg",
        )

    /** Wi-Fi-only (unmetered) keeps the enqueued work pending, so no real worker runs. */
    private fun repo(readDelayMs: Long = 0): CredentialRepository {
        val fake = FakeCredentialRepository(initialWifiOnly = true)
        return object : CredentialRepository by fake {
            override val wifiOnly: Flow<Boolean> =
                flow {
                    delay(readDelayMs)
                    emit(true)
                }
        }
    }

    private fun enqueuer(readDelayMs: Long = 0) = UploadEnqueuer(context, repo(readDelayMs), appScope)

    /** Waits for [job], pumping the main looper the final toast is posted to. */
    private fun await(job: Job?) {
        val deadline = System.currentTimeMillis() + 10_000
        while (job != null && !job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("enqueue did not finish", job == null || job.isCompleted)
    }

    private fun works() = WorkManager.getInstance(context).getWorkInfosByTag("photo_upload").get()

    @Test
    fun enqueue_writesQueueFileEnqueuesWorkWithConstraintAndShowsToast() {
        val job = enqueuer().enqueue(listOf(UploadBatch(" Trip ", listOf(photo(1), photo(2)))))

        await(job)

        val work = works().single()
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        val queueFile =
            File(
                context.filesDir
                    .listFiles()!!
                    .single { it.name.startsWith("upload_queue_") }
                    .path,
            )
        val json = JSONObject(queueFile.readText())
        assertEquals("Trip", json.getString("folderName"))
        assertEquals(2, json.getJSONArray("photos").length())
        assertEquals(
            context.resources.getQuantityString(R.plurals.toast_upload_started, 1, 1, 2),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun enqueue_ignoresEmptyBatches() {
        assertNull(enqueuer().enqueue(listOf(UploadBatch("Trip", emptyList()))))

        assertTrue(works().isEmpty())
    }

    @Test
    fun cancellingTheCallerScopeDoesNotCancelTheEnqueue() {
        val caller = CoroutineScope(Job() + Dispatchers.Default)
        var job: Job? = null
        runBlocking {
            caller
                .launch { job = enqueuer(readDelayMs = 300).enqueue(listOf(UploadBatch("Trip", listOf(photo(1))))) }
                .join()
            // The setting read is still in flight; the "activity" (caller) is destroyed now.
            caller.cancel()
        }

        await(job)

        assertEquals(1, works().size)
        assertEquals(1, context.filesDir.listFiles()!!.count { it.name.startsWith("upload_queue_") })
    }

    @Test
    fun oneWorkPerBatch() {
        val job =
            enqueuer().enqueue(
                listOf(UploadBatch("2026/05-May", listOf(photo(1))), UploadBatch("2026/06-Jun", listOf(photo(2)))),
            )

        await(job)

        assertEquals(2, works().size)
    }
}
