package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.DefaultRemovalCandidateChecker
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidate
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavClient
import de.majuwa.android.paper.krhnlesimagemanagement.data.validatedMediaUri
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class RemovalCandidateCheckerTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var config: WebDavConfig
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    /** Server size by request path; absent = 404. */
    private val serverSizes = mutableMapOf<String, Long?>()
    private var hangPaths = mutableSetOf<String>()

    private val ownPackage get() = context.packageName

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when {
                        path in hangPaths -> {
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }

                        !serverSizes.containsKey(path) -> {
                            MockResponse().setResponseCode(404)
                        }

                        serverSizes[path] == null -> {
                            MockResponse().setResponseCode(200).clearHeaders()
                        }

                        else -> {
                            MockResponse()
                                .setResponseCode(
                                    200,
                                ).setHeader("Content-Length", serverSizes[path].toString())
                        }
                    }
                }
            }
        server.start()
        config = WebDavConfig(server.url("/remote.php/dav/files/u/").toString(), "u", "p", "")
    }

    @After
    fun tearDown() {
        server.shutdown()
        FakeMediaStoreProvider.reset()
    }

    private fun record(
        id: Long,
        uri: String = "content://media/external/images/media/$id",
        size: Long = 100L,
        dateModified: Long = 1_700_000_000L,
        serverUrl: String = config.url,
        folder: String = "Trip",
        fileName: String = "img_$id.jpg",
    ) = VerifiedUpload(id, uri, size, dateModified, serverUrl, folder, fileName, verifiedAt = 1L)

    /** MediaStore row and server file consistent with [record]. */
    private fun consistent(record: VerifiedUpload): VerifiedUpload {
        FakeMediaStoreProvider.itemRows[record.uri] = FakeMediaStoreProvider.item(record.size, record.dateModified)
        serverSizes["/remote.php/dav/files/u/${record.folder}/${record.fileName}"] = record.size
        return record
    }

    private fun checker(timeoutMs: Long = 30_000L) =
        DefaultRemovalCandidateChecker(context.contentResolver, ownPackage, ::WebDavClient, timeoutMs)

    private fun check(
        vararg records: VerifiedUpload,
        timeoutMs: Long = 30_000L,
    ): List<RemovalCandidate> =
        runBlocking { checker(timeoutMs).check(records.toList(), config) { _, _ -> } }.candidates

    // ── validatedMediaUri ───────────────────────────────────────────────────

    @Test
    fun `a consistent image and video record validate`() {
        val image = record(7)
        assertEquals(image.uri, validatedMediaUri(image, ownPackage).toString())
        val videoId = 9L or Photo.VIDEO_ID_FLAG
        val video = record(videoId, uri = "content://media/external/video/media/9")
        assertEquals(video.uri, validatedMediaUri(video, ownPackage).toString())
    }

    @Test
    fun `inconsistent records never validate`() {
        val videoId = 9L or Photo.VIDEO_ID_FLAG
        val invalid =
            listOf(
                "id differs from the uri" to record(8, uri = "content://media/external/images/media/9"),
                "video uri with an image id" to record(9, uri = "content://media/external/video/media/9"),
                "image uri with the video flag" to record(videoId, uri = "content://media/external/images/media/9"),
                "flagged id above the flag" to record(videoId, uri = "content://media/external/video/media/$videoId"),
                "audio collection" to record(9, uri = "content://media/external/audio/media/9"),
                "other authority" to record(9, uri = "content://com.example.photos/external/images/media/9"),
                "own authority" to record(9, uri = "content://$ownPackage/external/images/media/9"),
                "file scheme" to record(9, uri = "file:///sdcard/Pictures/a.jpg"),
                "extra segment" to record(9, uri = "content://media/external/images/media/9/extra"),
                "missing segment" to record(9, uri = "content://media/images/media/9"),
                "wrong third segment" to record(9, uri = "content://media/external/images/thumbnails/9"),
                "non numeric id" to record(9, uri = "content://media/external/images/media/abc"),
                "query" to record(9, uri = "content://media/external/images/media/9?x=1"),
                "fragment" to record(9, uri = "content://media/external/images/media/9#x"),
                "user info authority" to record(9, uri = "content://0@media/external/images/media/9"),
                "empty" to record(9, uri = ""),
            )
        invalid.forEach { (label, record) -> assertNull(label, validatedMediaUri(record, ownPackage)) }
    }

    // ── check ───────────────────────────────────────────────────────────────

    @Test
    fun `a verified unchanged photo that is on the server is a candidate`() {
        val photo = consistent(record(1))

        val candidates = check(photo)

        assertEquals(listOf(photo), candidates.map { it.record })
        assertEquals(photo.uri, candidates.single().uri.toString())
        val head = requests.single()
        assertEquals("HEAD", head.method)
        assertEquals("/remote.php/dav/files/u/Trip/img_1.jpg", head.path)
    }

    @Test
    fun `a photo edited after the upload is kept`() {
        val photo = record(1)
        consistent(photo)
        FakeMediaStoreProvider.itemRows[photo.uri] = FakeMediaStoreProvider.item(photo.size, photo.dateModified + 5)
        assertTrue(check(photo).isEmpty())

        FakeMediaStoreProvider.itemRows[photo.uri] = FakeMediaStoreProvider.item(photo.size + 1, photo.dateModified)
        assertTrue(check(photo).isEmpty())
    }

    @Test
    fun `a missing or already trashed photo is kept`() {
        val missing = record(1)
        serverSizes["/remote.php/dav/files/u/Trip/img_1.jpg"] = missing.size
        assertTrue(check(missing).isEmpty())

        val trashed = record(2)
        consistent(trashed)
        FakeMediaStoreProvider.itemRows[trashed.uri] =
            FakeMediaStoreProvider.item(trashed.size, trashed.dateModified, trashed = true)
        assertTrue(check(trashed).isEmpty())
    }

    @Test
    fun `a trashed or pending photo is kept although the item query returns its row`() {
        // The platform returns trashed and pending rows for an item uri whatever the query args say.
        val trashed = consistent(record(1))
        FakeMediaStoreProvider.itemRows[trashed.uri] =
            FakeMediaStoreProvider.item(trashed.size, trashed.dateModified, trashed = true)
        val pending = consistent(record(2))
        FakeMediaStoreProvider.itemRows[pending.uri] =
            FakeMediaStoreProvider.item(pending.size, pending.dateModified, pending = true)

        assertTrue(check(trashed, pending).isEmpty())
        assertTrue("no server call for a photo that fails locally", requests.isEmpty())
    }

    @Test
    fun `a failing MediaStore query of any kind keeps the photo`() {
        val photo = consistent(record(1))
        FakeMediaStoreProvider.itemFailures[photo.uri] = IllegalStateException("provider crashed")

        assertTrue(check(photo).isEmpty())
    }

    @Test
    fun `five server failures in a row stop the check and keep every photo`() {
        val records = (1L..20L).map { consistent(record(it)) }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return MockResponse().setResponseCode(503)
                }
            }

        val result = runBlocking { checker().check(records, config) { _, _ -> } }

        assertTrue(result.serverUnreachable)
        assertTrue(result.candidates.isEmpty())
        // 5 failures plus at most the calls already in flight (parallelism 4).
        assertTrue("requests: ${requests.size}", requests.size <= 5 + 3)
    }

    @Test
    fun `five timeouts in a row stop the check`() {
        val records = (1L..20L).map { consistent(record(it)) }
        records.forEach { hangPaths += "/remote.php/dav/files/u/Trip/${it.fileName}" }

        val result = runBlocking { checker(timeoutMs = 200L).check(records, config) { _, _ -> } }

        assertTrue(result.serverUnreachable)
        assertTrue(result.candidates.isEmpty())
        assertTrue("requests: ${requests.size}", requests.size < records.size)
    }

    @Test
    fun `fewer than five server failures do not stop the check`() {
        val failing = (1L..4L).map { consistent(record(it)) }
        val good = consistent(record(5))
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return if (request.path!!.endsWith("img_5.jpg")) {
                        MockResponse().setResponseCode(200).setHeader("Content-Length", good.size.toString())
                    } else {
                        MockResponse().setResponseCode(500)
                    }
                }
            }

        val result = runBlocking { checker().check(failing + good, config) { _, _ -> } }

        assertEquals(false, result.serverUnreachable)
        assertEquals(listOf(5L), result.candidates.map { it.record.id })
    }

    @Test
    fun `answers that are not failures never stop the check`() {
        // 404 / other sizes are answers: the server is reachable, the photos are just kept.
        val gone =
            (1L..10L).map {
                record(it).also { r ->
                    FakeMediaStoreProvider.itemRows[r.uri] =
                        FakeMediaStoreProvider.item(r.size, r.dateModified)
                }
            }

        val result = runBlocking { checker().check(gone, config) { _, _ -> } }

        assertEquals(false, result.serverUnreachable)
        assertEquals(10, requests.size)
    }

    @Test
    fun `a photo gone from the server or of another server size is kept`() {
        val gone = record(1)
        FakeMediaStoreProvider.itemRows[gone.uri] = FakeMediaStoreProvider.item(gone.size, gone.dateModified)
        assertTrue(check(gone).isEmpty())

        val other = consistent(record(2))
        serverSizes["/remote.php/dav/files/u/Trip/img_2.jpg"] = other.size + 1
        assertTrue(check(other).isEmpty())

        val unknownSize = consistent(record(3))
        serverSizes["/remote.php/dav/files/u/Trip/img_3.jpg"] = null
        assertTrue(check(unknownSize).isEmpty())
    }

    @Test
    fun `a server that does not answer in time keeps the photo`() {
        val photo = consistent(record(1))
        hangPaths += "/remote.php/dav/files/u/Trip/img_1.jpg"

        assertTrue(check(photo, timeoutMs = 300L).isEmpty())
    }

    @Test
    fun `a server error keeps the photo`() {
        val photo = consistent(record(1))
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
            }
        assertTrue(check(photo).isEmpty())
    }

    @Test
    fun `a photo verified on another server is kept without a request`() {
        val photo = consistent(record(1, serverUrl = "https://other.example.com/remote.php/dav/files/u/"))

        assertTrue(check(photo).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an invalid record is kept without a request`() {
        val bad = record(1, uri = "content://media/external/images/media/2")
        serverSizes["/remote.php/dav/files/u/Trip/img_1.jpg"] = bad.size

        assertTrue(check(bad).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an invalid folder name is kept without a request`() {
        val bad = consistent(record(1, folder = "../etc"))

        assertTrue(check(bad).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `several records are decided one by one and progress reaches the total`() {
        val good1 = consistent(record(1))
        val bad = record(2)
        val good2 = consistent(record(3))
        val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        val candidates =
            runBlocking {
                checker().check(
                    listOf(good1, bad, good2),
                    config,
                ) { done, total -> progress += done to total }
            }.candidates

        assertEquals(listOf(1L, 3L), candidates.map { it.record.id })
        assertEquals(3, progress.size)
        assertEquals(setOf(3 to 3), progress.filter { it.first == 3 }.toSet())
        assertTrue(progress.all { it.second == 3 })
    }

    @Test
    fun `cancellation propagates and cancels the server calls`() =
        runBlocking {
            val photo = consistent(record(1))
            hangPaths += "/remote.php/dav/files/u/Trip/img_1.jpg"
            var thrown: Throwable? = null
            val job =
                launch(Dispatchers.IO) {
                    try {
                        checker().check(listOf(photo), config) { _, _ -> }
                    } catch (e: CancellationException) {
                        thrown = e
                        throw e
                    }
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
            assertNotNull(thrown)
        }

    @Test
    fun `recheckLocal drops photos that changed meanwhile`() =
        runTest {
            val keep = consistent(record(1))
            val changed = consistent(record(2))
            val candidates = check(keep, changed)
            assertEquals(2, candidates.size)

            FakeMediaStoreProvider.itemRows[changed.uri] =
                FakeMediaStoreProvider.item(changed.size, changed.dateModified + 1)

            assertEquals(listOf(1L), checker().recheckLocal(candidates).map { it.record.id })
        }

    @Test
    fun `an empty record list makes no request`() =
        runTest {
            assertTrue(checker().check(emptyList(), config) { _, _ -> }.candidates.isEmpty())
            assertTrue(requests.isEmpty())
        }
}
