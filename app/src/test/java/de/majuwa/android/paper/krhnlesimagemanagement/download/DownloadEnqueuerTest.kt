package de.majuwa.android.paper.krhnlesimagemanagement.download

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DownloadEnqueuerTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var manager: WorkManager
    private val config = WebDavConfig("https://cloud.example/remote.php/dav/files/u/", "u", "secret-pw", "Photos")
    private val album = "/remote.php/dav/files/u/Photos/Summer/"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        manager = WorkManager.getInstance(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        queueFiles().forEach { it.delete() }
    }

    private fun queueFiles() =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("download_queue_") }

    private fun enqueuer(
        repo: FakeCredentialRepository = FakeCredentialRepository(initialConfig = config, initialWifiOnly = true),
        workManager: () -> WorkManager = { manager },
        sweepLookupTimeoutMs: Long = 5_000L,
    ) = DownloadEnqueuer(
        context,
        repo,
        scope,
        workManager,
        { LocalMediaWriter(context) },
        Dispatchers.IO,
        Dispatchers.Main,
        sweepLookupTimeoutMs,
    )

    private fun request(
        e: DownloadEnqueuer,
        vararg names: String,
    ) {
        val job = e.requestDownload(album, names.map { "$album$it" })
        runBlocking {
            // The toast is posted on the main looper: let it run while waiting.
            while (job?.isActive == true) {
                shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(10)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun works(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(DOWNLOAD_UNIQUE_WORK_NAME).get()

    @Test
    fun `the queue file is valid in the no backup dir and holds no credentials`() {
        request(enqueuer(), "a.jpg", "b.jpg", "a.jpg")

        val file = queueFiles().single()
        assertEquals(context.noBackupFilesDir, file.parentFile)
        assertTrue(Regex("download_queue_[0-9]+_[0-9a-f-]+\\.json").matches(file.name))
        val text = file.readText()
        listOf("secret-pw", "\"u\"", "password", "username", "token").forEach { assertFalse(it, text.contains(it)) }
        val queue = checkNotNull(parseDownloadQueue(text))
        assertEquals(config.url, queue.serverUrl)
        assertEquals(album, queue.albumHref)
        assertEquals(listOf("${album}a.jpg", "${album}b.jpg"), queue.entries.map { it.href })
        assertEquals("Saving 2 photos in the background.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `the work input has only the queue path`() {
        val file = File(context.noBackupFilesDir, "download_queue_1_${UUID.randomUUID()}.json")

        val request: OneTimeWorkRequest = downloadWorkRequest(file)

        assertEquals(setOf(DownloadWorker.KEY_QUEUE_FILE), request.workSpec.input.keyValueMap.keys)
        assertEquals(file.absolutePath, request.workSpec.input.getString(DownloadWorker.KEY_QUEUE_FILE))
    }

    @Test
    fun `unique work name policy tags and constraint`() {
        request(enqueuer(), "a.jpg")

        val info = works().single()
        val file = queueFiles().single()
        assertTrue(info.tags.containsAll(listOf("photo_download", "download_queue:${file.name}")))
        assertFalse(info.tags.contains("photo_upload"))
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        // The Wi-Fi-only upload setting is on in this test; the download still needs only a connection.
        val request = downloadWorkRequest(file)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.tags.containsAll(listOf("photo_download", "download_queue:${file.name}")))
        assertFalse(request.tags.contains("photo_upload"))
    }

    @Test
    fun `a new batch after a user cancelled one still runs`() {
        val e = enqueuer()
        request(e, "a.jpg")
        val first = works().single()
        manager.cancelWorkById(first.id).result.get()
        assertEquals(listOf(WorkInfo.State.CANCELLED), works().map { it.state })

        request(e, "b.jpg")

        // APPEND_OR_REPLACE drops the cancelled prerequisite: the new batch is not cancelled with it.
        val infos = works()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), infos.map { it.state })
        assertTrue(first.id !in infos.map { it.id })
    }

    @Test
    fun `a batch queued after a running one waits and is not cancelled`() {
        val e = enqueuer()
        request(e, "a.jpg")
        request(e, "b.jpg")

        val infos = works()
        assertEquals(2, infos.size)
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), infos.map { it.state }.toSet())
    }

    @Test
    fun `without a valid configuration nothing is written or queued`() {
        request(enqueuer(FakeCredentialRepository(initialConfig = WebDavConfig())), "a.jpg")

        assertTrue(queueFiles().isEmpty())
        assertTrue(works().isEmpty())
        assertEquals(
            "No server is configured. Open Settings and connect your server first.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `an empty selection does nothing`() {
        val job = enqueuer().requestDownload(album, emptyList())

        assertNull(job)
        assertTrue(queueFiles().isEmpty())
    }

    @Test
    fun `an enqueue failure deletes the file and tells the user`() {
        val failing = mockk<WorkManager>()
        every { failing.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } throws
            IllegalStateException("WorkManager down")

        request(enqueuer(workManager = { failing }), "a.jpg")

        assertTrue(queueFiles().isEmpty())
        assertEquals("Saving could not be started. Please try again.", ShadowToast.getTextOfLatestToast())
    }

    private fun orphan(pendingItem: String? = null): File {
        val queue =
            """{"serverUrl":"${config.url}","albumHref":"$album","entries":[{"href":"${album}x.jpg"}]""" +
                (pendingItem?.let { ""","pendingItem":"$it"""" } ?: "") + "}"
        return File(context.noBackupFilesDir, "download_queue_1_${UUID.randomUUID()}.json").also { it.writeText(queue) }
    }

    @Test
    fun `the sweep deletes finished and unknown queues and keeps those with live work`() {
        val e = enqueuer()
        val unknown = orphan()
        val cancelled = orphan()
        val live = orphan()
        manager
            .enqueueUniqueWork(
                "photo_download",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                downloadWorkRequest(cancelled),
            ).result
            .get()
        manager.cancelAllWorkByTag(downloadQueueTag(cancelled.name)).result.get()
        manager
            .enqueueUniqueWork(
                "photo_download",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                downloadWorkRequest(live),
            ).result
            .get()

        runBlocking { e.sweepOrphans() }

        assertFalse(unknown.exists())
        assertFalse(cancelled.exists())
        assertTrue(live.exists())
    }

    @Test
    fun `the sweep discards the pending item recorded by an orphan before deleting it`() {
        val item = LocalMediaWriter(context).insertPending("Pictures/KroehnSync/Summer/", "x.jpg", "image/jpeg")
        val file = orphan(pendingItem = item.uriString)

        runBlocking { enqueuer().sweepOrphans() }

        assertFalse(file.exists())
        assertNull(FakeMediaStoreProvider.inserted[item.uriString])
        assertEquals(listOf(item.uriString), FakeMediaStoreProvider.deletes)
    }

    @Test
    fun `the sweep deletes nothing when the work lookup does not answer`() {
        val hanging = mockk<WorkManager>()
        val never: Flow<List<WorkInfo>> = flow { kotlinx.coroutines.awaitCancellation() }
        every { hanging.getWorkInfosByTagFlow(any<String>()) } returns never
        val file = orphan()

        runBlocking { enqueuer(workManager = { hanging }, sweepLookupTimeoutMs = 200L).sweepOrphans() }

        assertTrue(file.exists())
    }

    @Test
    fun `the sweep deletes nothing when the work lookup fails`() {
        val failing = mockk<WorkManager>()
        every { failing.getWorkInfosByTagFlow(any<String>()) } throws IllegalStateException("db closed")
        val file = orphan()

        runBlocking { enqueuer(workManager = { failing }).sweepOrphans() }

        assertTrue(file.exists())
        assertNotNull(file)
    }
}
