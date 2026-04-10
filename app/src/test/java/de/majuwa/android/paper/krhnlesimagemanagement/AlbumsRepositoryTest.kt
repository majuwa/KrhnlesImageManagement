package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: AlbumsRepository

    private fun propfindResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(body)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createRepo(baseFolder: String = ""): AlbumsRepository {
        val url = server.url("/remote.php/dav/files/user/").toString()
        return AlbumsRepository(
            WebDavConfig(
                url = url,
                username = "testuser",
                password = "testpass",
                baseFolder = baseFolder,
            ),
        )
    }

    // ── listAlbums ──────────────────────────────────────────────────────────

    @Test
    fun `listAlbums returns album directories, skipping root and files`() =
        runTest {
            repo = createRepo()
            val xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response>
                    <d:href>/remote.php/dav/files/user/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>user</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Summer25/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Summer25</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Winter26/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Winter26</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/readme.txt</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>readme.txt</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>text/plain</d:getcontenttype>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            server.enqueue(propfindResponse(xml))

            val result = repo.listAlbums()
            assertTrue(result.isSuccess)
            val albums = result.getOrThrow()
            assertEquals(2, albums.size)
            assertEquals("Summer25", albums[0].displayName)
            assertEquals("Winter26", albums[1].displayName)
        }

    @Test
    fun `listAlbums with baseFolder includes folder in PROPFIND path`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            val xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Photos/Backup/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Backup</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            server.enqueue(propfindResponse(xml))

            repo.listAlbums()
            val request = server.takeRequest()
            assertTrue(
                "Path should include base folder: ${request.path}",
                request.path!!.contains("Photos/Backup"),
            )
        }

    @Test
    fun `listAlbums returns failure on server error`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val result = repo.listAlbums()
            assertTrue(result.isFailure)
        }

    // ── listPhotos ──────────────────────────────────────────────────────────

    @Test
    fun `listPhotos returns only image files`() =
        runTest {
            repo = createRepo()
            val xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Summer25/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Summer25</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Summer25/beach.jpg</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>beach.jpg</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>image/jpeg</d:getcontenttype>
                      <oc:fileid>100</oc:fileid>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Summer25/notes.txt</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>notes.txt</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>text/plain</d:getcontenttype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Summer25/sunset.png</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>sunset.png</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>image/png</d:getcontenttype>
                      <oc:fileid>101</oc:fileid>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            server.enqueue(propfindResponse(xml))

            val result = repo.listPhotos("/remote.php/dav/files/user/Summer25/")
            assertTrue(result.isSuccess)
            val photos = result.getOrThrow()
            assertEquals(2, photos.size)
            assertEquals("beach.jpg", photos[0].displayName)
            assertEquals("100", photos[0].fileId)
            assertEquals("sunset.png", photos[1].displayName)
            assertEquals("image/png", photos[1].contentType)
        }

    // ── deletePhoto ─────────────────────────────────────────────────────────

    @Test
    fun `deletePhoto sends DELETE request`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(204))

            val photo =
                RemotePhoto(
                    displayName = "beach.jpg",
                    href = "/remote.php/dav/files/user/Summer25/beach.jpg",
                    fileId = "100",
                    contentType = "image/jpeg",
                )
            val result = repo.deletePhoto(photo)
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.endsWith("/Summer25/beach.jpg"))
        }

    @Test
    fun `deletePhoto fails on 403`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(403))

            val photo = RemotePhoto("x.jpg", "/x.jpg", null, "image/jpeg")
            val result = repo.deletePhoto(photo)
            assertTrue(result.isFailure)
        }

    // ── deleteAlbum ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAlbum sends DELETE request`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(204))

            val album = RemoteAlbum("Summer25", "/remote.php/dav/files/user/Summer25/")
            val result = repo.deleteAlbum(album)
            assertTrue(result.isSuccess)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
        }

    // ── fullImageUrl ────────────────────────────────────────────────────────

    @Test
    fun `fullImageUrl returns direct WebDAV URL when Memories not checked`() =
        runTest {
            repo = createRepo()
            val photo = RemotePhoto("img.jpg", "/dav/files/user/img.jpg", "42", "image/jpeg")
            val url = repo.fullImageUrl(photo)
            assertTrue("Should use direct href path", url.endsWith("/dav/files/user/img.jpg"))
        }

    // Note: Memories API integration tests (thumbnailUrl / fullImageUrl with Memories)
    // are skipped in unit tests because ensureMemoriesChecked() uses withTimeoutOrNull
    // which interacts poorly with runTest's virtual time dispatcher.
    // These should be tested as instrumented tests on a real device.
}
