package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.decodeHex
import org.json.JSONArray
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** The upload worker reads the pin per run and per entry and hands it to its client factory. */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerPinTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val config = WebDavConfig("https://cloud.example:8443/", "u", "p")
    private val pin =
        PinnedCertificate(ServerOrigin("cloud.example", 8443), "ab".repeat(32).decodeHex())
    private val info =
        TrustedCertificateInfo(
            ServerCertificate(pin.origin, pin.sha256, "CN=a", "CN=b", 0L, Long.MAX_VALUE / 2),
            1L,
        )
    private val seenPins = java.util.Collections.synchronizedList(mutableListOf<PinnedCertificate?>())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(201)
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
        context.filesDir
            .listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.forEach { it.delete() }
    }

    private object NoHistory : UploadHistoryRepository {
        override val entries = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())

        override suspend fun addEntry(entry: UploadHistoryEntry) = Unit

        override suspend fun removeEntry(entryId: Long) = Unit

        override suspend fun clearAll() = Unit
    }

    private fun queueFile(count: Int): File {
        val photos = JSONArray()
        repeat(count) { i ->
            val uri = "content://media/external/images/media/${i + 1}"
            photos.put(
                JSONObject()
                    .put("id", i + 1L)
                    .put("uri", uri)
                    .put("mimeType", "image/jpeg")
                    .put("displayName", "p$i.jpg"),
            )
            val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            shadowOf(context.contentResolver).registerInputStream(uri.toUri(), stream)
        }
        return File(context.filesDir, "upload_queue_1_${UUID.randomUUID()}.json")
            .also { it.writeText(JSONObject().put("folderName", "Trip").put("photos", photos).toString()) }
    }

    private fun run(
        queue: File,
        trusted: FakeTrustedCertificateRepository,
        uploaded: UploadedPhotosRepositoryContract = FakeUploadedPhotosRepository(),
    ) = runBlocking {
        val mockConfig = WebDavConfig(server.url("/").toString(), "u", "p")
        val worker =
            TestListenableWorkerBuilder<UploadWorker>(context)
                .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queue.absolutePath))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = UploadWorker(
                            appContext,
                            workerParameters,
                            FakeCredentialRepository(initialConfig = config),
                            { _, pin ->
                                seenPins += pin
                                // The server under test speaks plain HTTP; only the pin handed over is observed.
                                WebDavClient(mockConfig, pin)
                            },
                            uploaded,
                            NoHistory,
                            { false },
                        ).also { it.trustedCertificates = trusted }
                    },
                ).build()
        worker.doWork()
    }

    @Test
    fun `the factory receives the stored pin`() {
        run(queueFile(1), FakeTrustedCertificateRepository(info))
        assertEquals(listOf<PinnedCertificate?>(pin), seenPins.toList())
    }

    @Test
    fun `a pin read that times out falls back to system trust`() {
        run(queueFile(1), FakeTrustedCertificateRepository(info).apply { hangRead = true })
        assertTrue(seenPins.isNotEmpty())
        assertTrue(seenPins.all { it == null })
    }

    @Test
    fun `a pin removed after the first entry rebuilds the client without it`() {
        val trusted = FakeTrustedCertificateRepository(info)
        val uploaded =
            object : UploadedPhotosRepositoryContract by FakeUploadedPhotosRepository() {
                override suspend fun markAsUploaded(photoIds: Set<Long>) {
                    trusted.clear()
                }
            }
        run(queueFile(2), trusted, uploaded)
        assertEquals(pin, seenPins.first())
        assertNull(seenPins.last())
        assertEquals(2, seenPins.size)
    }
}
