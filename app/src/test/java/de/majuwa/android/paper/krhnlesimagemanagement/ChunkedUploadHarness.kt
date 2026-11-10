package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.withFakeTrustedCertificates
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.ChunkedUploadPolicy
import de.majuwa.android.paper.krhnlesimagemanagement.worker.ChunkedUploadSettings
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared set-up of the chunked-upload worker tests: a MockWebServer that behaves like Nextcloud's
 * chunked upload (upload folders, numbered chunks, `MOVE .file` assembling them and checking
 * `OC-Total-Length`, `X-Hash-SHA256` on request), MediaStore videos with a known length served by
 * [FakeMediaStoreProvider], and the real [UploadWorker] with fakes and a tiny [ChunkedUploadPolicy]
 * (threshold 20 bytes, chunks of 10 bytes).
 */
class ChunkedUploadHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()

    /** One received request; [queue] is the queue file content when it arrived (null = no file). */
    class Rec(
        val method: String,
        val path: String,
        val headers: Headers,
        val body: ByteArray,
        val queue: String?,
    ) {
        val name: String get() = path.substringAfterLast('/')
        val transferId: String get() = path.removePrefix(UPLOADS_PREFIX).split('/').getOrElse(1) { "" }

        override fun toString() = "$method $path"
    }

    val requests = CopyOnWriteArrayList<Rec>()

    /** The server's upload folders (`<user>/<transfer id>` → chunk name → bytes). */
    val uploadFolders = ConcurrentHashMap<String, ConcurrentSkipListMap<String, ByteArray>>()

    /** Files on the server by encoded path. */
    val serverFiles = ConcurrentHashMap<String, ByteArray>()

    /** The server answers a requested `X-Hash` with `X-Hash-SHA256` (regular Nextcloud storage). */
    var serverHash = true

    /** The `X-Hash-SHA256` value the server reports for stored content (default: its real hash). */
    var hashOf: (ByteArray) -> String = { sha256(it) }

    /** Answers a request instead of the fake Nextcloud (after it was recorded); null = default. */
    var override: (Rec) -> MockResponse? = { null }

    /** Never answers a request for which this is true (the client's read is aborted by a cancel). */
    var hold: (Rec) -> Boolean = { false }
    private val held = CountDownLatch(1)

    var currentQueueFile: File? = null
    val uploaded = FakeUploadedPhotosRepository()
    val verified = FakeVerifiedUploadsRepository()
    val history = CancelTestHistory()
    var uploadedOverride: UploadedPhotosRepositoryContract? = null
    var verifiedOverride: VerifiedUploadsRepositoryContract? = null
    var userCancelled: (() -> Boolean)? = null

    /** Wraps the worker's credential repository (e.g. to cancel while a setting is read). */
    var credentials: (CredentialRepository) -> CredentialRepository = { it }
    var verifyByDownload = false
    var serverPath = "/remote.php/dav/files/u/"

    /** The configuration the worker reads (default: the mock server); lets a test use an `https` origin to pin. */
    var credentialConfig: WebDavConfig? = null

    /** The worker's client factory; default builds the client for the given configuration and pin. */
    var clientFactory: (WebDavConfig, PinnedCertificate?) -> WebDavClient = { config, pin -> WebDavClient(config, pin) }

    /** The worker's pin store (in memory, never the process DataStore). */
    var trusted: TrustedCertificateRepository = FakeTrustedCertificateRepository()

    /** Foreground calls from this index on are refused (0 = the call at the start of the run). */
    var foregroundFailsFrom: Int? = null
    private val foregroundCalls = AtomicInteger()
    val foregroundTexts = CopyOnWriteArrayList<String>()

    val sleeps = CopyOnWriteArrayList<Long>()
    internal var settings =
        ChunkedUploadSettings(
            policy = ChunkedUploadPolicy(thresholdBytes = 20L, chunkBytes = 10L, maxChunks = 10_000),
            sleep = { sleeps += it },
            abortTimeoutMs = 5_000L,
            cancelAbortTimeoutMs = 5_000L,
        )

    fun setUp(grantLocation: Boolean = false) {
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        val permissions =
            listOfNotNull(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_MEDIA_LOCATION.takeIf { grantLocation },
            )
        shadowOf(context as android.app.Application).grantPermissions(*permissions.toTypedArray())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
        server.start()
    }

    fun tearDown() {
        server.shutdown()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") || it.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        shareStagingDir(context).deleteRecursively()
        FakeMediaStoreProvider.reset()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        val rec =
            Rec(
                request.method.orEmpty(),
                request.requestUrl?.encodedPath.orEmpty(),
                request.headers,
                request.body.readByteArray(),
                currentQueueFile?.takeIf { it.exists() }?.readText(),
            )
        requests += rec
        if (hold(rec)) {
            held.countDown()
            return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        return override(rec) ?: nextcloud(rec)
    }

    private fun nextcloud(rec: Rec): MockResponse =
        if (rec.path.startsWith(UPLOADS_PREFIX)) uploads(rec) else files(rec)

    private fun uploads(rec: Rec): MockResponse {
        val parts = rec.path.removePrefix(UPLOADS_PREFIX).split('/')
        val folderKey = "${parts[0]}/${parts.getOrElse(1) { "" }}"
        val name = parts.getOrElse(2) { "" }
        return when (rec.method) {
            "MKCOL" -> {
                code(if (uploadFolders.putIfAbsent(folderKey, ConcurrentSkipListMap()) == null) 201 else 405)
            }

            "PUT" -> {
                val folder = uploadFolders[folderKey] ?: return code(404)
                folder[name] = rec.body
                code(201)
            }

            "MOVE" -> {
                move(rec, folderKey)
            }

            "DELETE" -> {
                code(if (uploadFolders.remove(folderKey) != null) 204 else 404)
            }

            else -> {
                code(405)
            }
        }
    }

    private fun move(
        rec: Rec,
        folderKey: String,
    ): MockResponse {
        val folder = uploadFolders[folderKey] ?: return code(404)
        val assembled = folder.values.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        if (rec.headers["OC-Total-Length"]?.toLongOrNull() != assembled.size.toLong()) return code(400)
        uploadFolders.remove(folderKey)
        val destination =
            rec.headers["Destination"]
                .orEmpty()
                .toHttpUrl()
                .encodedPath
        serverFiles[destination] = assembled
        return withHash(code(201), rec, assembled)
    }

    private fun files(rec: Rec): MockResponse =
        when (rec.method) {
            "MKCOL" -> {
                code(201)
            }

            "PUT" -> {
                serverFiles[rec.path] = rec.body
                withHash(code(201), rec, rec.body)
            }

            "GET" -> {
                serverFiles[rec.path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: code(404)
            }

            else -> {
                code(200)
            }
        }

    private fun withHash(
        response: MockResponse,
        rec: Rec,
        content: ByteArray,
    ): MockResponse =
        if (serverHash && rec.headers["X-Hash"] == "sha256") {
            response.setHeader("X-Hash-SHA256", hashOf(content))
        } else {
            response
        }

    /** Registers a MediaStore video [id] with [bytes] (known length, item row for the verification). */
    fun video(
        id: Long,
        bytes: ByteArray,
        name: String = "clip$id.mp4",
    ): JSONObject {
        setContent(id, bytes)
        return JSONObject()
            .put("id", id)
            .put("uri", videoUri(id))
            .put("mimeType", "video/mp4")
            .put("displayName", name)
    }

    /** Changes the local content of video [id] (and its MediaStore size / modification time). */
    fun setContent(
        id: Long,
        bytes: ByteArray,
        dateModified: Long = 1_700_000_000L + id,
    ) {
        FakeMediaStoreProvider.files[videoUri(id)] = bytes
        FakeMediaStoreProvider.itemRows[videoUri(id)] = FakeMediaStoreProvider.item(bytes.size.toLong(), dateModified)
    }

    /** A single-folder ("Trip") queue file of [entries]; becomes [currentQueueFile]. */
    fun queueFile(
        vararg entries: JSONObject,
        interruptions: Int = 0,
    ): File {
        val json = JSONObject().put("folderName", "Trip").put("photos", JSONArray(entries.toList()))
        if (interruptions > 0) json.put("interruptions", interruptions)
        return File(context.noBackupFilesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
            it.writeText(json.toString())
            currentQueueFile = it
        }
    }

    /** A queue entry of a shared file staged with [bytes]. */
    fun stagedEntry(
        name: String,
        bytes: ByteArray,
    ): JSONObject {
        shareStagingDir(context).mkdirs()
        val staged = UUID.randomUUID().toString()
        File(shareStagingDir(context), staged).writeBytes(bytes)
        return JSONObject()
            .put("uri", "content://shared.gone/$name")
            .put("mimeType", "video/mp4")
            .put("displayName", name)
            .put("staged", staged)
    }

    fun config(): WebDavConfig = WebDavConfig(server.url(serverPath).toString(), "u", "p", "")

    fun buildWorker(queueFile: File): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setForegroundUpdater { _, _, info ->
                val call = foregroundCalls.getAndIncrement()
                foregroundTexts +=
                    info.notification.extras
                        .getCharSequence(Notification.EXTRA_TEXT)
                        .toString()
                val failsFrom = foregroundFailsFrom
                if (failsFrom != null && call >= failsFrom) {
                    Futures.immediateFailedFuture(ForegroundServiceStartNotAllowedException("budget used up"))
                } else {
                    Futures.immediateFuture(null)
                }
            }.setWorkerFactory(workerFactory())
            .build()
            .also { it.chunking = settings }

    private fun workerFactory(): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                UploadWorker(
                    appContext,
                    workerParameters,
                    credentials(
                        FakeCredentialRepository(
                            initialConfig = credentialConfig ?: config(),
                            initialVerifyByDownload = verifyByDownload,
                        ),
                    ),
                    { config, pin -> clientFactory(config, pin) },
                    uploadedOverride ?: uploaded,
                    history,
                    { false },
                    verifiedOverride ?: verified,
                    userCancelled,
                ).withFakeTrustedCertificates(trusted)
        }

    /** Runs the worker to completion (no cancel); the foreground counter restarts per run. */
    fun run(queueFile: File): ListenableWorker.Result {
        foregroundCalls.set(0)
        return runBlocking { buildWorker(queueFile).doWork() }
    }

    /** Starts the worker, waits until a request is held, then cancels its job; returns the job. */
    fun runAndCancelWhenHeld(queueFile: File): Job {
        foregroundCalls.set(0)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { buildWorker(queueFile).doWork() }
        assertTrue("the request was never held", held.await(30, TimeUnit.SECONDS))
        job.cancel()
        runBlocking { job.join() }
        scope.cancel()
        return job
    }

    /** Starts the worker in [scope]; the caller cancels it (e.g. from a fake). */
    fun start(
        queueFile: File,
        scope: CoroutineScope,
    ): Job {
        foregroundCalls.set(0)
        return scope.launch { buildWorker(queueFile).doWork() }
    }

    fun retryFiles(): List<File> =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") && it.name.endsWith(".json") }

    fun uploadedIds(): Set<Long> = runBlocking { uploaded.uploadedPhotoIds.first() }

    fun of(method: String): List<Rec> = requests.filter { it.method == method }

    fun uploadRequests(): List<Rec> = requests.filter { it.path.startsWith(UPLOADS_PREFIX) }

    fun chunkPuts(): List<Rec> = uploadRequests().filter { it.method == "PUT" }

    fun filePuts(): List<Rec> = requests.filter { it.method == "PUT" && !it.path.startsWith(UPLOADS_PREFIX) }

    fun resultNotifications(): List<Notification> =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }
            .map { it.notification }

    fun title(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    companion object {
        const val UPLOADS_PREFIX = "/remote.php/dav/uploads/"

        fun videoUri(id: Long) = "content://media/external/video/media/$id"

        fun code(code: Int): MockResponse = MockResponse().setResponseCode(code)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** [size] distinct bytes starting at [seed]. */
        fun bytes(
            size: Int,
            seed: Int = 0,
        ): ByteArray = ByteArray(size) { (it + seed).toByte() }

        /** The `chunked` object of entry [index] in queue JSON [json]; null = none. */
        fun chunkedOf(
            json: String?,
            index: Int = 0,
        ): JSONObject? =
            json?.let {
                JSONObject(it)
                    .getJSONArray("photos")
                    .getJSONObject(index)
                    .optJSONObject("chunked")
            }
    }
}
