package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
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
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class VerificationTestHistory : UploadHistoryRepository {
    val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/**
 * The real [UploadWorker] verifying uploads (design D1 - D5): Nextcloud server hash, download
 * strategy, unverified outcomes, failed outcomes and the verified-upload record.
 */
@RunWith(RobolectricTestRunner::class)
class UploadWorkerVerificationTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val stored = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val uploaded = FakeUploadedPhotosRepository()
    private val verified = FakeVerifiedUploadsRepository()
    private val history = VerificationTestHistory()

    /** `null` = answer the PUT without `X-Hash-SHA256`; otherwise the header value for every PUT. */
    private var serverHash: ((ByteArray) -> String?)? = { sha256(it) }

    /** Replaces what a GET returns for a path (default: the stored body). */
    private var downloadBody: ((String) -> ByteArray?)? = null
    private var downloadCode = 200
    private var nextcloud = true
    private var verifyByDownload = false
    private var putCode = 201

    /** Runs on the server thread while a PUT is answered (e.g. the account changes meanwhile). */
    private var onPut: (String) -> Unit = {}

    /** Runs on the server thread before a GET is answered (e.g. blocks a verification download). */
    private var onGet: () -> Unit = {}
    private lateinit var credentials: FakeCredentialRepository

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when (request.method) {
                        "MKCOL" -> {
                            MockResponse().setResponseCode(201)
                        }

                        "PUT" -> {
                            val body = request.body.readByteArray()
                            stored[path] = body
                            onPut(path)
                            if (putCode != 201) return MockResponse().setResponseCode(putCode)
                            MockResponse().setResponseCode(201).also { response ->
                                serverHash?.invoke(body)?.let { response.setHeader("X-Hash-SHA256", it) }
                            }
                        }

                        "GET" -> {
                            onGet()
                            val body = downloadBody?.invoke(path) ?: stored[path]
                            if (downloadCode != 200 || body == null) {
                                MockResponse().setResponseCode(if (downloadCode != 200) downloadCode else 404)
                            } else {
                                MockResponse().setResponseCode(200).setBody(okio.Buffer().write(body))
                            }
                        }

                        else -> {
                            MockResponse().setResponseCode(405)
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        FakeMediaStoreProvider.reset()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { it.delete() }
        }
    }

    private class Photo(
        val id: Long,
        val name: String,
        val content: ByteArray = "photo-$id".toByteArray(),
        /** MediaStore size / modification time; null = no MediaStore row. */
        val mediaSize: Long? = content.size.toLong(),
        val dateModified: Long = 1_700_000_000L + id,
        /** How the original (`setRequireOriginal`) opens: registered, missing (FileNotFound) or throwing. */
        val original: Original = Original.Registered,
        val uri: String = "content://media/external/images/media/$id",
    ) {
        /** What the MediaProvider hands out without the original: the location zeroed, same size. */
        val redacted: ByteArray get() = ByteArray(content.size)
    }

    private sealed interface Original {
        data object Registered : Original

        data object Missing : Original

        data class Throws(
            val failure: RuntimeException,
        ) : Original
    }

    private fun registerStreams(photo: Photo) {
        val resolver = shadowOf(context.contentResolver)
        val uri = Uri.parse(photo.uri)
        resolver.registerInputStream(uri, ByteArrayInputStream(photo.redacted))
        val original = MediaStore.setRequireOriginal(uri)
        when (val mode = photo.original) {
            Original.Registered -> resolver.registerInputStream(original, ByteArrayInputStream(photo.content))
            Original.Missing -> Unit
            is Original.Throws -> resolver.registerInputStreamSupplier(original) { throw mode.failure }
        }
    }

    private fun queueFile(vararg photos: Photo): File {
        val arr = JSONArray()
        photos.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("uri", it.uri)
                    .put("mimeType", "image/jpeg")
                    .put("displayName", it.name),
            )
            registerStreams(it)
            it.mediaSize?.let { size ->
                FakeMediaStoreProvider.itemRows[it.uri] = FakeMediaStoreProvider.item(size, it.dateModified)
            }
        }
        return File(context.filesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
            it.writeText(JSONObject().put("folderName", "Trip").put("photos", arr).toString())
        }
    }

    private fun serverUrl() = if (nextcloud) server.url("/remote.php/dav/files/u/") else server.url("/files/")

    private fun worker(queueFile: File): UploadWorker {
        if (!::credentials.isInitialized) {
            credentials =
                FakeCredentialRepository(
                    initialConfig = WebDavConfig(serverUrl().toString(), "u", "p", ""),
                    initialVerifyByDownload = verifyByDownload,
                )
        }
        val repo = credentials
        return TestListenableWorkerBuilder<UploadWorker>(context)
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
                        repo,
                        ::WebDavClient,
                        uploaded,
                        history,
                        { false },
                        verified,
                    )
                },
            ).build()
    }

    private fun run(queueFile: File): ListenableWorker.Result = runBlocking { worker(queueFile).doWork() }

    private fun methods(method: String) = requests.filter { it.method == method }

    private fun uploadedIds() = runBlocking { uploaded.uploadedPhotoIds.first() }

    private fun retryNames(): List<String> =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") }
            .flatMap { file ->
                val photos = JSONObject(file.readText()).getJSONArray("photos")
                (0 until photos.length()).map { photos.getJSONObject(it).getString("displayName") }
            }

    private fun counts(result: ListenableWorker.Result): Pair<Int, Int> {
        val data = (result as ListenableWorker.Result.Success).outputData
        return data.getInt(UploadWorker.KEY_UPLOADED_COUNT, -1) to data.getInt(UploadWorker.KEY_FAILED_COUNT, -1)
    }

    private fun resultText(): String =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .single { it.id == UploadWorker.RESULT_NOTIFICATION_ID }
            .notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString()

    // ── Nextcloud server hash ───────────────────────────────────────────────

    @Test
    fun `nextcloud upload sends X-Hash and a matching server hash is recorded without another request`() {
        val photo = Photo(1, "a.jpg")

        val result = run(queueFile(photo))

        assertEquals(1 to 0, counts(result))
        assertEquals("sha256", methods("PUT").single().getHeader("X-Hash"))
        assertTrue(methods("GET").isEmpty())
        val record = verified.current.single()
        assertEquals(1L, record.id)
        assertEquals(photo.uri, record.uri)
        assertEquals(photo.content.size.toLong(), record.size)
        assertEquals(photo.dateModified, record.dateModified)
        assertEquals(server.url("/remote.php/dav/files/u/").toString(), record.serverUrl)
        assertEquals("Trip", record.folder)
        assertEquals("a.jpg", record.fileName)
        assertEquals(setOf(1L), uploadedIds())
    }

    @Test
    fun `nextcloud server hash is compared case-insensitively`() {
        serverHash = { sha256(it).uppercase() }
        run(queueFile(Photo(1, "a.jpg")))
        assertEquals(1, verified.current.size)
    }

    @Test
    fun `a server hash that differs is a failed photo, not marked, not recorded and offered for retry`() {
        serverHash = { "00".repeat(32) }
        val result = run(queueFile(Photo(1, "a.jpg"), Photo(2, "b.jpg")))

        assertEquals(0 to 2, counts(result))
        assertTrue(uploadedIds().isEmpty())
        assertTrue(verified.current.isEmpty())
        assertEquals(listOf("a.jpg", "b.jpg"), retryNames())
        assertTrue(resultText(), resultText().contains(context.getString(R.string.upload_failure_verification)))
    }

    @Test
    fun `one mismatch among several leaves the others uploaded and verified`() {
        serverHash = { body -> if (String(body) == "photo-2") "00".repeat(32) else sha256(body) }

        val result = run(queueFile(Photo(1, "a.jpg"), Photo(2, "b.jpg"), Photo(3, "c.jpg")))

        assertEquals(2 to 1, counts(result))
        assertEquals(setOf(1L, 3L), uploadedIds())
        assertEquals(setOf(1L, 3L), verified.current.map { it.id }.toSet())
        assertEquals(listOf("b.jpg"), retryNames())
    }

    @Test
    fun `a failed verification removes the older record of the same photo`() {
        runBlocking {
            verified.recordVerified(
                VerifiedUpload(1L, "content://media/external/images/media/1", 1, 1, "u", "Trip", "a.jpg", 1L),
            )
        }
        serverHash = { "00".repeat(32) }

        run(queueFile(Photo(1, "a.jpg")))

        assertTrue(verified.current.isEmpty())
    }

    @Test
    fun `nextcloud without a server hash and without download verification is unverified but uploaded`() {
        serverHash = null
        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(1 to 0, counts(result))
        assertEquals(setOf(1L), uploadedIds())
        assertTrue(verified.current.isEmpty())
        assertTrue(methods("GET").isEmpty())
        assertTrue(retryNames().isEmpty())
    }

    @Test
    fun `nextcloud without a server hash falls back to the download when it is enabled`() {
        serverHash = null
        verifyByDownload = true

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(1 to 0, counts(result))
        assertEquals(1, methods("GET").size)
        assertEquals(1, verified.current.size)
    }

    // ── plain WebDAV ────────────────────────────────────────────────────────

    @Test
    fun `plain webdav without the download setting sends no hash header and downloads nothing`() {
        nextcloud = false

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(1 to 0, counts(result))
        assertNull(methods("PUT").single().getHeader("X-Hash"))
        assertTrue(methods("GET").isEmpty())
        assertTrue(verified.current.isEmpty())
        assertEquals(setOf(1L), uploadedIds())
    }

    @Test
    fun `plain webdav with the download setting verifies by downloading and sends no hash header`() {
        nextcloud = false
        verifyByDownload = true

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(1 to 0, counts(result))
        assertNull(methods("PUT").single().getHeader("X-Hash"))
        assertEquals(1, methods("GET").size)
        assertEquals(1, verified.current.size)
    }

    @Test
    fun `a downloaded body that differs is a failed photo`() {
        nextcloud = false
        verifyByDownload = true
        downloadBody = { "corrupt!".toByteArray() }

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(0 to 1, counts(result))
        assertTrue(uploadedIds().isEmpty())
        assertTrue(verified.current.isEmpty())
        assertEquals(listOf("a.jpg"), retryNames())
    }

    @Test
    fun `a longer downloaded body is a failed photo`() {
        nextcloud = false
        verifyByDownload = true
        downloadBody = { "photo-1-and-much-more-data".toByteArray() }

        assertEquals(0 to 1, counts(run(queueFile(Photo(1, "a.jpg")))))
    }

    @Test
    fun `a failing verification download is an inconclusive failed photo`() {
        nextcloud = false
        verifyByDownload = true
        downloadCode = 500

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(0 to 1, counts(result))
        assertTrue(uploadedIds().isEmpty())
        assertTrue(verified.current.isEmpty())
        assertEquals(listOf("a.jpg"), retryNames())
        assertTrue(resultText().contains(context.getString(R.string.upload_failure_verification)))
    }

    // ── unverified outcomes ─────────────────────────────────────────────────

    @Test
    fun `a MediaStore size that differs from the streamed bytes is uploaded but unverified and not downloaded`() {
        verifyByDownload = true
        serverHash = null

        val result = run(queueFile(Photo(1, "a.jpg", mediaSize = 999L)))

        assertEquals(1 to 0, counts(result))
        assertEquals(setOf(1L), uploadedIds())
        assertTrue(verified.current.isEmpty())
        assertTrue(methods("GET").isEmpty())
        assertTrue(retryNames().isEmpty())
    }

    @Test
    fun `a photo without MediaStore metadata is uploaded but never verified, even with a matching server hash`() {
        val result = run(queueFile(Photo(1, "a.jpg", mediaSize = null)))

        assertEquals(1 to 0, counts(result))
        assertEquals(setOf(1L), uploadedIds())
        assertTrue(verified.current.isEmpty())
    }

    @Test
    fun `an unverified re-upload forgets the older record`() {
        runBlocking {
            verified.recordVerified(
                VerifiedUpload(1L, "content://media/external/images/media/1", 1, 1, "u", "Trip", "a.jpg", 1L),
            )
        }
        serverHash = null

        run(queueFile(Photo(1, "a.jpg")))

        assertTrue(verified.current.isEmpty())
    }

    @Test
    fun `a failing record write never fails the upload`() {
        verified.recordFailure = java.io.IOException("disk full")

        val result = run(queueFile(Photo(1, "a.jpg")))

        assertEquals(1 to 0, counts(result))
        assertEquals(setOf(1L), uploadedIds())
        assertFalse(verified.current.any())
    }

    @Test
    fun `the verified record of a retried photo is written by the retry run`() {
        serverHash = { "00".repeat(32) }
        run(queueFile(Photo(1, "a.jpg")))
        assertTrue(verified.current.isEmpty())

        serverHash = { sha256(it) }
        // The first run consumed the registered stream.
        registerStreams(Photo(1, "a.jpg"))
        val retry =
            context.noBackupFilesDir
                .listFiles()
                .orEmpty()
                .single { it.name.startsWith("retry_queue_") }
        val result = run(retry)

        assertEquals(1 to 0, counts(result))
        assertEquals(listOf(1L), verified.current.map { it.id })
    }

    @Test
    fun `a staged shared photo is never verified, recorded or hashed and keeps its Content-Length`() {
        verifyByDownload = true
        shareStagingDir(context).mkdirs()
        val stagedName = UUID.randomUUID().toString()
        File(shareStagingDir(context), stagedName).writeText("shared-bytes")
        val queue =
            File(context.noBackupFilesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
                it.writeText(
                    JSONObject()
                        .put("folderName", "Trip")
                        .put(
                            "photos",
                            JSONArray().put(
                                JSONObject()
                                    .put("uri", "content://revoked.example/p/1")
                                    .put("mimeType", "image/jpeg")
                                    .put("displayName", "s.jpg")
                                    .put("staged", stagedName),
                            ),
                        ).toString(),
                )
            }

        val result = run(queue)

        assertEquals(1 to 0, counts(result))
        val put = methods("PUT").single()
        assertNull(put.getHeader("X-Hash"))
        assertEquals("12", put.getHeader("Content-Length"))
        assertNull(put.getHeader("Transfer-Encoding"))
        assertTrue(methods("GET").isEmpty())
        assertTrue(verified.current.isEmpty())
        shareStagingDir(context).deleteRecursively()
    }

    // ── originals and the media location (review finding 1) ────────────────

    private fun putBody(name: String): ByteArray = stored.entries.single { it.key.endsWith("/$name") }.value

    @Test
    fun `with media location access the original is uploaded and recorded`() {
        val photo = Photo(1, "a.jpg")

        run(queueFile(photo))

        assertTrue(putBody("a.jpg").contentEquals(photo.content))
        assertEquals(listOf(1L), verified.current.map { it.id })
    }

    @Test
    fun `without media location access the photo is uploaded but never recorded`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        verifyByDownload = true
        val photo = Photo(1, "a.jpg")

        val result = run(queueFile(photo))

        assertEquals(1 to 0, counts(result))
        assertEquals(setOf(1L), uploadedIds())
        // The (possibly redacted) copy verifies byte for byte, so it must never be recorded.
        assertTrue(putBody("a.jpg").contentEquals(photo.redacted))
        assertTrue(verified.current.isEmpty())
        assertNull(methods("PUT").single().getHeader("X-Hash"))
        assertTrue(methods("GET").isEmpty())
    }

    @Test
    fun `an original that cannot be opened falls back to an unverified upload`() {
        val failures =
            listOf(
                Original.Missing,
                Original.Throws(UnsupportedOperationException("no original")),
                Original.Throws(SecurityException("no location access")),
            )
        failures.forEachIndexed { i, mode ->
            val id = i + 1L
            val photo = Photo(id, "p$id.jpg", original = mode)

            val result = run(queueFile(photo))

            assertEquals("$mode", 1 to 0, counts(result))
            assertTrue("$mode", putBody("p$id.jpg").contentEquals(photo.redacted))
            assertTrue("$mode", verified.current.isEmpty())
            assertTrue("$mode", id in uploadedIds())
        }
    }

    // ── records are bound to the remote path (review finding 3) ────────────

    private fun recordedIds() = verified.current.map { it.id }.toSet()

    @Test
    fun `a second photo with the same name in the same batch removes the first record`() {
        run(queueFile(Photo(1, "a.jpg"), Photo(2, "a.jpg")))

        assertEquals(setOf(2L), recordedIds())
    }

    @Test
    fun `a later batch writing the same path removes the older record, also case-insensitively`() {
        run(queueFile(Photo(1, "a.jpg")))
        assertEquals(setOf(1L), recordedIds())

        run(queueFile(Photo(2, "A.JPG")))

        assertEquals(setOf(2L), recordedIds())
    }

    @Test
    fun `an unverified upload to the same path removes the older record of another photo`() {
        run(queueFile(Photo(1, "a.jpg")))
        serverHash = null

        run(queueFile(Photo(2, "a.jpg")))

        assertTrue(verified.current.isEmpty())
    }

    @Test
    fun `a failed upload to the same path removes the older record of another photo`() {
        run(queueFile(Photo(1, "a.jpg")))
        putCode = 500

        run(queueFile(Photo(2, "a.jpg")))

        assertTrue(verified.current.isEmpty())
    }

    @Test
    fun `a staged photo written to the same path removes the older record`() {
        run(queueFile(Photo(1, "a.jpg")))
        shareStagingDir(context).mkdirs()
        val stagedName = UUID.randomUUID().toString()
        File(shareStagingDir(context), stagedName).writeText("photo-9")
        val queue =
            File(context.noBackupFilesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
                it.writeText(
                    JSONObject()
                        .put("folderName", "Trip")
                        .put(
                            "photos",
                            JSONArray().put(
                                JSONObject()
                                    .put("uri", "content://revoked.example/p/1")
                                    .put("mimeType", "image/jpeg")
                                    .put("displayName", "a.jpg")
                                    .put("staged", stagedName),
                            ),
                        ).toString(),
                )
            }

        run(queue)

        assertTrue(verified.current.isEmpty())
        shareStagingDir(context).deleteRecursively()
    }

    @Test
    fun `another folder or another file name keeps the record`() {
        run(queueFile(Photo(1, "a.jpg")))

        run(queueFile(Photo(2, "b.jpg")))

        assertEquals(setOf(1L, 2L), recordedIds())
    }

    // ── account binding (review finding 5c) ─────────────────────────────────

    @Test
    fun `no record when the server or the account changes or the user logs out during the run`() {
        val changes =
            listOf<(WebDavConfig) -> WebDavConfig?>(
                { it.copy(url = "https://other.example.com/remote.php/dav/files/u/") },
                { it.copy(username = "someone-else") },
                { null },
            )
        changes.forEachIndexed { i, change ->
            val id = i + 1L
            credentials =
                FakeCredentialRepository(initialConfig = WebDavConfig(serverUrl().toString(), "u", "p", ""))
            val runConfig = runBlocking { credentials.webDavConfig.first() }
            onPut = { _ ->
                val changed = change(runConfig)
                runBlocking { if (changed == null) credentials.clear() else credentials.setConfig(changed) }
            }

            val result = run(queueFile(Photo(id, "p$id.jpg")))

            assertEquals("change $i", 1 to 0, counts(result))
            assertTrue("change $i", id in uploadedIds())
            assertTrue("change $i", verified.current.isEmpty())
        }
    }

    // ── cancellation and uri validation (review findings 11, 7e) ────────────

    @Test
    fun `cancelling the work during the verification download records nothing and marks nothing`() =
        runBlocking {
            nextcloud = false
            verifyByDownload = true
            val getStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            onGet = {
                getStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            val worker = worker(queueFile(Photo(1, "a.jpg")))

            val job = launch(Dispatchers.IO) { worker.doWork() }
            assertTrue(getStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            release.countDown()

            assertTrue(job.isCancelled)
            assertTrue(verified.current.isEmpty())
            assertTrue(uploadedIds().isEmpty())
        }

    @Test
    fun `a uri of this app is rejected before any MediaStore query`() {
        val ownAuthority = "${context.packageName}.media"
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, ownAuthority)
        val photo = Photo(1, "a.jpg", uri = "content://$ownAuthority/external/images/media/1")

        val result = run(queueFile(photo))

        assertEquals(0 to 1, counts(result))
        assertNull(FakeMediaStoreProvider.itemQueries[photo.uri])
        assertTrue(methods("PUT").isEmpty())
    }
}
