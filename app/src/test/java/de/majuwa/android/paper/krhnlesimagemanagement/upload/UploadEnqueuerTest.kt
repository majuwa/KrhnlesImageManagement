package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.junit.Assert.assertFalse
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
        context.noBackupFilesDir
            .listFiles()
            ?.filter { it.name.startsWith("upload_queue_") }
            ?.forEach { it.delete() }
    }

    private fun photo(
        id: Long,
        dateTaken: LocalDate = LocalDate.of(2026, 5, 1),
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "$id.jpg",
        dateTaken = dateTaken,
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
        val job = enqueuer().enqueue(listOf(UploadBatch.Occasion(" Trip ", listOf(photo(1), photo(2)))))

        await(job)

        val work = works().single()
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        val queueFile =
            File(
                context.noBackupFilesDir
                    .listFiles()!!
                    .single { it.name.startsWith("upload_queue_") }
                    .path,
            )
        val json = JSONObject(queueFile.readText())
        assertEquals("Trip", json.getString("folderName"))
        assertEquals(2, json.getJSONArray("photos").length())
        assertEquals(
            "Queued 1 upload for 2 items.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun enqueue_mixedPhotosAndVideo_queuesOneUploadWithEveryItem() {
        val video =
            Photo(
                id = 3L or Photo.VIDEO_ID_FLAG,
                uri = Uri.parse("content://media/external/video/media/3"),
                displayName = "3.mp4",
                dateTaken = LocalDate.of(2026, 5, 1),
                size = 1,
                mimeType = "video/mp4",
                mediaKind = MediaKind.Video,
            )
        val job = enqueuer().enqueue(listOf(UploadBatch.Occasion("Trip", listOf(photo(1), photo(2), video))))

        await(job)

        works().single()
        val queueFile = context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("upload_queue_") }
        val entries = JSONObject(queueFile.readText()).getJSONArray("photos")
        assertEquals(3, entries.length())
        assertEquals("video/mp4", entries.getJSONObject(2).getString("mimeType"))
        assertEquals(3L or Photo.VIDEO_ID_FLAG, entries.getJSONObject(2).getLong("id"))
        assertEquals(
            "Queued 1 upload for 3 items.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun enqueue_singleItem_toastUsesTheSingularItemCount() {
        await(enqueuer().enqueue(listOf(UploadBatch.Occasion("Trip", listOf(photo(1))))))

        assertEquals("Queued 1 upload for 1 item.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun enqueue_ignoresEmptyBatches() {
        assertNull(enqueuer().enqueue(listOf(UploadBatch.Occasion("Trip", emptyList()))))

        assertTrue(works().isEmpty())
    }

    @Test
    fun cancellingTheCallerScopeDoesNotCancelTheEnqueue() {
        val caller = CoroutineScope(Job() + Dispatchers.Default)
        var job: Job? = null
        runBlocking {
            caller
                .launch {
                    job =
                        enqueuer(readDelayMs = 300).enqueue(listOf(UploadBatch.Occasion("Trip", listOf(photo(1)))))
                }.join()
            // The setting read is still in flight; the "activity" (caller) is destroyed now.
            caller.cancel()
        }

        await(job)

        assertEquals(1, works().size)
        assertEquals(1, context.noBackupFilesDir.listFiles()!!.count { it.name.startsWith("upload_queue_") })
    }

    @Test
    fun oneWorkPerBatch() {
        val job =
            enqueuer().enqueue(
                listOf(
                    UploadBatch.Occasion("2026/05-May", listOf(photo(1))),
                    UploadBatch.Occasion("2026/06-Jun", listOf(photo(2))),
                ),
            )

        await(job)

        assertEquals(2, works().size)
    }

    private fun queueFiles() = context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("upload_queue_") }

    @Test
    fun multiDayAutoBatch_isOneWorkWithPerEntryFoldersAndLabel() {
        val batch =
            resolveAutoDateUploadBatch(
                listOf(photo(2, LocalDate.of(2026, 5, 1)), photo(1, LocalDate.of(2026, 4, 30))),
            )

        await(enqueuer().enqueue(listOf(batch)))

        assertEquals(1, works().size)
        val json = JSONObject(queueFiles().single().readText())
        assertFalse(json.has("folderName"))
        assertEquals("2026/04/30 – 2026/05/01", json.getString("label"))
        val photos = json.getJSONArray("photos")
        assertEquals(listOf(1L, 2L), (0 until photos.length()).map { photos.getJSONObject(it).getLong("id") })
        assertEquals(
            listOf("2026/04/30", "2026/05/01"),
            (0 until photos.length()).map { photos.getJSONObject(it).getString("folderName") },
        )
        assertEquals(
            "Queued 1 upload for 2 items.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun singleDayAutoBatch_isOneWorkWithDayLabel() {
        val batch =
            resolveAutoDateUploadBatch(listOf(photo(1, LocalDate.of(2026, 5, 14)), photo(2, LocalDate.of(2026, 5, 14))))

        await(enqueuer().enqueue(listOf(batch)))

        assertEquals(1, works().size)
        assertEquals("2026/05/14", JSONObject(queueFiles().single().readText()).getString("label"))
        assertTrue(
            context.filesDir
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith("upload_queue_") },
        )
    }

    @Test
    fun occasionBatch_writesTheSingleFolderFormatUnchanged() {
        val file = runBlocking { enqueuer().writeQueueFile(UploadBatch.Occasion(" Trip ", listOf(photo(1)))) }

        assertEquals(
            """{"folderName":"Trip","photos":[{"id":1,"uri":"content:\/\/media\/external\/images\/media\/1",""" +
                """"mimeType":"image\/jpeg","displayName":"1.jpg"}]}""",
            file.readText(),
        )
    }

    @Test
    fun unexpectedRuntimeFailure_runsOnFailure_doesNotCrash_andRemovesTheQueueFile() {
        var failures = 0
        val crashes = mutableListOf<Throwable>()
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, e -> crashes += e },
            )
        val enqueuer =
            UploadEnqueuer(
                context,
                repo(),
                scope,
                workManager = {
                    mockk {
                        every { enqueue(any<WorkRequest>()) } throws UnsupportedOperationException("broken")
                    }
                },
            )

        val job = enqueuer.enqueue(listOf(UploadBatch.Occasion("Trip", listOf(photo(1))))) { failures++ }
        await(job)
        scope.cancel()

        assertEquals(1, failures)
        assertTrue(crashes.isEmpty())
        assertTrue(queueFiles().isEmpty())
        assertTrue(job!!.isCompleted && !job.isCancelled)
    }
}
