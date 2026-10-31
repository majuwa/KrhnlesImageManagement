package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Notification
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.shareStagingDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** In-memory history that remembers every entry. */
class CancelTestHistory : UploadHistoryRepository {
    val state = MutableStateFlow<List<UploadHistoryEntry>>(emptyList())
    override val entries = state

    /** Runs before an entry is stored (e.g. to cancel the worker at that moment). */
    var onAdd: () -> Unit = {}

    override suspend fun addEntry(entry: UploadHistoryEntry) {
        onAdd()
        state.value = state.value + entry
    }

    override suspend fun removeEntry(entryId: Long) = Unit

    override suspend fun clearAll() = Unit
}

/** Marks photos like the fake, but runs [onFirstMark] before the first marker is stored. */
class CancellingUploadedPhotos(
    private val delegate: FakeUploadedPhotosRepository,
    private val onFirstMark: () -> Unit,
) : UploadedPhotosRepositoryContract by delegate {
    private var marks = 0

    override suspend fun markAsUploaded(photoIds: Set<Long>) {
        if (marks++ == 0) onFirstMark()
        delegate.markAsUploaded(photoIds)
    }
}

/**
 * Shared set-up of the cancel tests: a MockWebServer that can hold one request forever (the
 * client's blocked read is aborted by the cancel), queue files over the fake MediaStore, the real
 * [UploadWorker] with fakes, and a way to cancel it while a request is held.
 */
class UploadCancelHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val putBodies = java.util.concurrent.ConcurrentHashMap<String, String>()
    val uploaded = FakeUploadedPhotosRepository()
    val verified = FakeVerifiedUploadsRepository()
    val history = CancelTestHistory()
    private val putCount = AtomicInteger()

    /** Nextcloud URL + a server hash that matches (verified uploads). */
    var nextcloud = true
    var verifyByDownload = false
    private var holdPut: Int? = null
    var holdMethod: String? = null
    var holdForeground = false
    var uploadedOverride: UploadedPhotosRepositoryContract? = null
    var verifiedOverride: VerifiedUploadsRepositoryContract? = null
    private var held = CountDownLatch(1)
    var userCancelled: (() -> Boolean)? = null

    /** Holds the [number]th PUT (counted from now) forever; null = hold none. Re-arms the latch. */
    fun holdPutNumber(number: Int?) {
        holdPut = number
        putCount.set(0)
        held = CountDownLatch(1)
    }

    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
        server.start()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        requests += "${request.method} ${request.path}"
        val isPut = request.method == "PUT"
        val n = if (isPut) putCount.incrementAndGet() else 0
        if ((isPut && n == holdPut) || request.method == holdMethod) {
            held.countDown()
            return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val response = MockResponse().setResponseCode(if (request.method == "MKCOL") 201 else 200)
        if (isPut) {
            val body = request.body.readByteArray()
            putBodies[request.path.orEmpty()] = String(body)
            response.setResponseCode(201)
            if (nextcloud) {
                val hash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(body)
                        .joinToString("") { "%02x".format(it) }
                response.setHeader("X-Hash-SHA256", hash)
            }
        } else if (request.method == "GET") {
            response.setBody(putBodies[request.path.orEmpty()].orEmpty())
        }
        return response
    }

    fun tearDown() {
        server.shutdown()
        listOf(context.filesDir, context.noBackupFilesDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".json") || it.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        shareStagingDir(context).deleteRecursively()
    }

    private fun registerMedia(
        index: Int,
        name: String,
    ): JSONObject {
        val uri = Uri.parse("content://media/external/images/media/${index + 1}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        shadowOf(context.contentResolver)
            .registerInputStream(MediaStore.setRequireOriginal(uri), ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        FakeMediaStoreProvider.itemRows[uri.toString()] = FakeMediaStoreProvider.item(3L, 1_700_000_000L + index)
        return JSONObject()
            .put("id", index + 1L)
            .put("uri", uri.toString())
            .put("mimeType", "image/jpeg")
            .put("displayName", name)
    }

    /** A single-folder upload queue of MediaStore photos with ids 1..n. */
    fun queueFile(
        vararg names: String,
        folder: String = "Trip",
        dir: File = context.noBackupFilesDir,
        interruptions: Int = 0,
    ): File {
        val photos = JSONArray()
        names.forEachIndexed { i, name -> photos.put(registerMedia(i, name)) }
        val json = JSONObject().put("folderName", folder).put("photos", photos)
        if (interruptions > 0) json.put("interruptions", interruptions)
        return File(dir, "upload_queue_1_${UUID.randomUUID()}.json").also { it.writeText(json.toString()) }
    }

    /** An auto-date style queue: every photo has its own folder, no top-level folder. */
    fun perPhotoQueueFile(vararg namesAndFolders: Pair<String, String>): File {
        val photos = JSONArray()
        namesAndFolders.forEachIndexed { i, (name, folder) ->
            photos.put(registerMedia(i, name).put("folderName", folder))
        }
        val json = JSONObject().put("label", "Auto").put("photos", photos)
        return File(context.noBackupFilesDir, "upload_queue_1_${UUID.randomUUID()}.json").also {
            it.writeText(json.toString())
        }
    }

    /** A queue of shared photos whose staged copies exist in the staging folder. */
    fun stagedQueueFile(vararg names: String): Pair<File, List<String>> {
        shareStagingDir(context).mkdirs()
        val staged = names.map { UUID.randomUUID().toString() }
        val photos = JSONArray()
        names.forEachIndexed { i, name ->
            File(shareStagingDir(context), staged[i]).writeText("data-$name")
            photos.put(
                JSONObject()
                    .put("uri", "content://shared.gone/$name")
                    .put("mimeType", "image/jpeg")
                    .put("displayName", name)
                    .put("staged", staged[i]),
            )
        }
        val json = JSONObject().put("folderName", "Trip").put("photos", photos)
        val file = File(context.noBackupFilesDir, "upload_queue_1_${UUID.randomUUID()}.json")
        file.writeText(json.toString())
        return file to staged
    }

    fun buildWorker(queueFile: File): UploadWorker {
        val foreground: (Context, UUID, androidx.work.ForegroundInfo) -> ListenableFuture<Void?> = { _, _, _ ->
            if (holdForeground) {
                held.countDown()
                SettableFuture.create()
            } else {
                Futures.immediateFuture(null)
            }
        }
        return TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
            .setForegroundUpdater { c, id, info -> foreground(c, id, info) }
            .setWorkerFactory(workerFactory())
            .build()
    }

    /** Creates [UploadWorker]s with this harness's fakes (and its `userCancelled` seam). */
    fun workerFactory(): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                if (workerClassName == UploadWorker::class.java.name) {
                    UploadWorker(
                        appContext,
                        workerParameters,
                        FakeCredentialRepository(
                            initialConfig =
                                WebDavConfig(
                                    server.url(if (nextcloud) "/remote.php/dav/files/u/" else "/").toString(),
                                    "u",
                                    "p",
                                    "",
                                ),
                            initialVerifyByDownload = verifyByDownload,
                        ),
                        ::WebDavClient,
                        uploadedOverride ?: uploaded,
                        history,
                        { false },
                        verifiedOverride ?: verified,
                        userCancelled,
                    )
                } else {
                    null
                }
        }

    /** Runs the worker to completion (no cancel). */
    fun run(queueFile: File): ListenableWorker.Result = runBlocking { buildWorker(queueFile).doWork() }

    fun awaitHeld(seconds: Long): Boolean = held.await(seconds, TimeUnit.SECONDS)

    /** Starts the worker, waits until a request (or the foreground call) is held, then cancels its job. */
    fun runAndCancelWhenHeld(queueFile: File) {
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { buildWorker(queueFile).doWork() }
        assertTrue("the request was never held", held.await(30, TimeUnit.SECONDS))
        job.cancel()
        runBlocking { job.join() }
        scope.cancel()
    }

    /** Starts the worker; the caller cancels the returned job (e.g. from a fake). */
    fun start(
        queueFile: File,
        scope: CoroutineScope,
    ): Job = scope.launch { buildWorker(queueFile).doWork() }

    fun retryFiles(): List<File> =
        context.noBackupFilesDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("retry_queue_") && it.name.endsWith(".json") }

    fun uploadedIds(): Set<Long> = runBlocking { uploaded.uploadedPhotoIds.first() }

    fun putNames(): List<String> = requests.filter { it.startsWith("PUT") }.map { it.substringAfterLast('/') }

    fun resultNotifications() =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == UploadWorker.RESULT_NOTIFICATION_ID }

    fun title(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    companion object {
        fun names(file: File): List<String> {
            val photos = JSONObject(file.readText()).getJSONArray("photos")
            return (0 until photos.length()).map { photos.getJSONObject(it).getString("displayName") }
        }

        fun markers(file: File): List<Boolean> {
            val photos = JSONObject(file.readText()).getJSONArray("photos")
            return List(photos.length()) { photos.getJSONObject(it).optBoolean("uploaded", false) }
        }
    }
}
