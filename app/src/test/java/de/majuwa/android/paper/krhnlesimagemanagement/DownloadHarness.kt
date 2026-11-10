package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DOWNLOAD_RESULT_NOTIFICATION_ID
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueueEntry
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadWorkerSeams
import de.majuwa.android.paper.krhnlesimagemanagement.worker.accountFingerprint
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared set-up of the download worker tests: a MockWebServer that serves `/…/Photos/<album>/<name>`
 * from [responses], the fake MediaStore provider, a queue file writer and the real [DownloadWorker]
 * with fakes and its seams (writer, free space, user cancel, foreground).
 */
class DownloadHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val other = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val authHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Response by request path; absent = 404. A function so a test can stall or count. */
    val responses = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    var config = WebDavConfig("", "u", "p", "Photos")
    lateinit var credentials: FakeCredentialRepository
    var trusted = FakeTrustedCertificateRepository()
    var writer: LocalMediaWriter? = null
    var freeBytes: (() -> Long)? = null
    var userCancelled: (() -> Boolean)? = null
    var maxBytes: Long? = null
    val seenPins: MutableList<PinnedCertificate?> = Collections.synchronizedList(mutableListOf())
    var clientFactory: ((WebDavConfig, PinnedCertificate?) -> WebDavClient)? = null

    /** Index of the first refused `setForeground` call; null = never refused. */
    var foregroundFailsFrom: Int? = null
    private var foregroundCalls = 0

    var runAttemptCount = 0

    /** Test seam for `isStopped`; null = WorkManager's own flag. */
    var stopped: (() -> Boolean)? = null

    /** Whether a validated network is active; the default keeps a connection failure a failure. */
    var networkAvailable: () -> Boolean = { true }

    val albumHref get() = "/remote.php/dav/files/u/Photos/Summer/"

    fun hrefOf(name: String) = "${albumHref}$name"

    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    authHeaders += request.getHeader("Authorization")
                    val handler = responses[request.path.orEmpty()] ?: return MockResponse().setResponseCode(404)
                    return handler(request)
                }
            }
        server.start()
        other.start()
        config = WebDavConfig(server.url("/remote.php/dav/files/u/").toString(), "u", "p", "Photos")
        credentials = FakeCredentialRepository(initialConfig = config)
    }

    fun tearDown() {
        server.shutdown()
        other.shutdown()
        context.noBackupFilesDir
            .listFiles()
            ?.filter {
                it.name.endsWith(".json") || it.name.endsWith(".tmp")
            }?.forEach { it.delete() }
    }

    /**
     * Serves an image for [name] of the default album: the valid start of that file type
     * ([imageHeader]) followed by [body].
     */
    fun serve(
        name: String,
        body: ByteArray = name.toByteArray(),
        type: String = "image/jpeg",
    ) {
        responses["/remote.php/dav/files/u/Photos/Summer/${encode(name)}"] = {
            MockResponse().setBody(Buffer().write(imageHeader(name) + body)).setHeader("Content-Type", type)
        }
    }

    fun serveStatus(
        name: String,
        code: Int,
    ) {
        responses["/remote.php/dav/files/u/Photos/Summer/${encode(name)}"] = { MockResponse().setResponseCode(code) }
    }

    private fun encode(name: String) = name.replace(" ", "%20")

    /** A queue file for the default album with the given photo [hrefs] (or names below the album). */
    fun queueFile(
        vararg names: String,
        hrefs: List<String> = names.map { hrefOf(it) },
        pendingItem: String? = null,
        interruptions: Int = 0,
        downloaded: Set<Int> = emptySet(),
        account: String? = config.accountFingerprint(),
    ): File {
        val queue =
            DownloadQueue(
                serverUrl = config.url,
                albumHref = albumHref,
                entries = hrefs.mapIndexed { i, href -> DownloadQueueEntry(href, i in downloaded) },
                pendingItem = pendingItem,
                interruptions = interruptions,
                account = account,
            )
        return File(context.noBackupFilesDir, "download_queue_1_${UUID.randomUUID()}.json")
            .also { it.writeText(queue.toJson().toString()) }
    }

    fun workerFactory(): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker {
                val mediaWriter = writer ?: LocalMediaWriter(appContext)
                return DownloadWorker(
                    appContext,
                    workerParameters,
                    credentials,
                    trusted,
                    clientFactory ?: { c, pin ->
                        seenPins += pin
                        WebDavClient(c, pin)
                    },
                    mediaWriter,
                    freeBytes ?: { Long.MAX_VALUE / 2 },
                    { FIXED_NOW },
                    DownloadWorkerSeams(userCancelled, stopped, networkAvailable),
                ).also { worker -> maxBytes?.let { worker.maxDownloadBytes = it } }
            }
        }

    fun buildWorker(queueFile: File): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setRunAttemptCount(runAttemptCount)
            .setForegroundUpdater { _, _, _ ->
                val call = foregroundCalls++
                val failsFrom = foregroundFailsFrom
                if (failsFrom != null && call >= failsFrom) {
                    Futures.immediateFailedFuture(
                        android.app.ForegroundServiceStartNotAllowedException("budget used up"),
                    )
                } else {
                    Futures.immediateFuture(null)
                }
            }.setWorkerFactory(workerFactory())
            .build()

    fun run(queueFile: File): ListenableWorker.Result = runBlocking { buildWorker(queueFile).doWork() }

    /** Items other apps can see, i.e. published (not pending). */
    fun published() = FakeMediaStoreProvider.inserted.values.filter { !it.pending }

    fun resultNotifications() =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == DOWNLOAD_RESULT_NOTIFICATION_ID }

    fun title(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    fun requestsTo(method: String = "GET") = requests.count { it.startsWith(method) }

    companion object {
        const val FIXED_NOW = 1_700_000_000_000L

        /** The valid start of a file called [name] (by extension), as `matchesSignature` expects it. */
        fun imageHeader(name: String): ByteArray =
            when (name.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
                "png" -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                "gif" -> "GIF89a".toByteArray()
                "webp" -> "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)
                "heic", "heif" -> byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()
                "avif" -> byteArrayOf(0, 0, 0, 0x18) + "ftypavif".toByteArray()
                "bmp" -> "BM".toByteArray()
                "dng" -> byteArrayOf(0x49, 0x49, 0x2A, 0x00)
                else -> ByteArray(0)
            }
    }
}
