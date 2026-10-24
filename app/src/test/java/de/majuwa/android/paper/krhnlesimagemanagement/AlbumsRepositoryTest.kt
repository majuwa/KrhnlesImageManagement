package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumNameTooLongException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.data.InvalidAlbumNameException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeHrefException
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.memoriesAwareDispatcher
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun photoAt(href: String) = RemotePhoto("photo.jpg", href, null, "image/jpeg")

    /** Minimal PROPFIND multistatus; a null content type marks a collection. */
    private fun multistatus(vararg entries: Pair<String, String?>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
            entries.forEach { (href, contentType) ->
                append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop>")
                append("<d:displayname>").append(href.trimEnd('/').substringAfterLast('/')).append("</d:displayname>")
                if (contentType == null) {
                    append("<d:resourcetype><d:collection/></d:resourcetype>")
                } else {
                    append("<d:resourcetype/><d:getcontenttype>").append(contentType).append("</d:getcontenttype>")
                }
                append("</d:prop></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }

    /**
     * Serves [propfind] for every request except the Nextcloud Memories check (answered 404),
     * which `listPhotos` runs concurrently with the PROPFIND.
     */
    private fun servePhotos(propfind: MockResponse) {
        server.dispatcher = memoriesAwareDispatcher(propfind)
    }

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
            servePhotos(propfindResponse(xml))

            val result = repo.listPhotos("/remote.php/dav/files/user/Summer25/")
            assertTrue(result.isSuccess)
            val photos = result.getOrThrow().photos
            assertEquals(2, photos.size)
            assertEquals("beach.jpg", photos[0].displayName)
            assertEquals("100", photos[0].fileId)
            assertEquals("sunset.png", photos[1].displayName)
            assertEquals("image/png", photos[1].contentType)
        }

    @Test
    fun `listPhotos includes images without content type via extension fallback`() =
        runTest {
            repo = createRepo()
            val xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response>
                    <d:href>/remote.php/dav/files/user/NewAlbum/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>NewAlbum</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/NewAlbum/photo.jpg</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>photo.jpg</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype/>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/NewAlbum/shot.HEIC</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>shot.HEIC</d:displayname>
                      <d:resourcetype/>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/NewAlbum/doc.pdf</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>doc.pdf</d:displayname>
                      <d:resourcetype/>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            servePhotos(propfindResponse(xml))

            val result = repo.listPhotos("/remote.php/dav/files/user/NewAlbum/")
            assertTrue(result.isSuccess)
            val photos = result.getOrThrow().photos
            assertEquals(2, photos.size)
            assertEquals("photo.jpg", photos[0].displayName)
            assertEquals("shot.HEIC", photos[1].displayName)
        }

    @Test
    fun `listPhotos skips video files so reviews never scan them`() =
        runTest {
            repo = createRepo()
            val xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Trip/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Trip</d:displayname>
                      <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Trip/clip.mp4</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>clip.mp4</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>video/mp4</d:getcontenttype>
                      <oc:fileid>200</oc:fileid>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Trip/untyped.mov</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>untyped.mov</d:displayname>
                      <d:resourcetype/>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/files/user/Trip/beach.jpg</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>beach.jpg</d:displayname>
                      <d:resourcetype/>
                      <d:getcontenttype>image/jpeg</d:getcontenttype>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            servePhotos(propfindResponse(xml))

            val photos = repo.listPhotos("/remote.php/dav/files/user/Trip/").getOrThrow().photos

            assertEquals(listOf("beach.jpg"), photos.map { it.displayName })
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

            val result = repo.deletePhoto(photoAt("/remote.php/dav/files/user/Summer25/x.jpg"))
            assertEquals(403, (result.exceptionOrNull() as WebDavHttpException).code)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `deletePhoto treats 404 as already deleted`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(404))

            val result = repo.deletePhoto(photoAt("/remote.php/dav/files/user/Summer25/gone.jpg"))

            assertTrue("404 means the photo is already gone: $result", result.isSuccess)
        }

    @Test
    fun `deletePhoto treats 410 as already deleted`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(410))

            val result = repo.deletePhoto(photoAt("/remote.php/dav/files/user/Summer25/gone.jpg"))

            assertTrue(result.isSuccess)
        }

    @Test
    fun `deletePhoto fails with typed status on server error`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(500))

            val result = repo.deletePhoto(photoAt("/remote.php/dav/files/user/Summer25/a.jpg"))

            assertEquals(500, (result.exceptionOrNull() as WebDavHttpException).code)
        }

    @Test
    fun `deleteAlbum still fails on 404`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(404))

            val result = repo.deleteAlbum(RemoteAlbum("Summer25", "/remote.php/dav/files/user/Summer25/"))

            assertEquals(404, (result.exceptionOrNull() as WebDavHttpException).code)
        }

    @Test
    fun `deletePhoto accepts percent-encoded Nextcloud hrefs`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            server.enqueue(MockResponse().setResponseCode(204))

            val result =
                repo.deletePhoto(photoAt("/remote.php/dav/files/user/Photos/Backup/Summer%202025/IMG%20%C3%A4.jpg"))

            assertTrue(result.isSuccess)
            assertEquals(
                "/remote.php/dav/files/user/Photos/Backup/Summer%202025/IMG%20%C3%A4.jpg",
                server.takeRequest().path,
            )
        }

    @Test
    fun `deletePhoto accepts an absolute same-origin href`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repo.deletePhoto(photoAt(server.url("/remote.php/dav/files/user/Summer25/a.jpg").toString()))

            assertTrue(result.isSuccess)
            assertEquals("/remote.php/dav/files/user/Summer25/a.jpg", server.takeRequest().path)
        }

    // ── href hardening (no request for unsafe hrefs) ────────────────────────

    private fun assertDeleteRefused(
        href: String,
        baseFolder: String = "",
    ) = runTest {
        repo = createRepo(baseFolder = baseFolder)
        server.enqueue(MockResponse().setResponseCode(204))

        val photoResult = repo.deletePhoto(photoAt(href))
        val albumResult = repo.deleteAlbum(RemoteAlbum("x", href))

        assertTrue("photo href must be refused: $href", photoResult.exceptionOrNull() is UnsafeHrefException)
        assertTrue("album href must be refused: $href", albumResult.exceptionOrNull() is UnsafeHrefException)
        assertEquals("no request may be sent for $href", 0, server.requestCount)
    }

    @Test
    fun `href with userinfo-like prefix is refused`() = assertDeleteRefused("@attacker.example/x.jpg")

    @Test
    fun `scheme-relative href to another host is refused`() =
        assertDeleteRefused("//attacker.example/remote.php/dav/files/user/x.jpg")

    @Test
    fun `absolute cross-origin href is refused`() =
        assertDeleteRefused("https://attacker.example/remote.php/dav/files/user/x.jpg")

    @Test
    fun `same host on another port is refused`() {
        val other = MockWebServer().apply { start() }
        try {
            assertDeleteRefused(other.url("/remote.php/dav/files/user/Summer25/x.jpg").toString())
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `dot-dot traversal is refused`() = assertDeleteRefused("/remote.php/dav/files/user/Summer25/../../other/x.jpg")

    @Test
    fun `traversal that stays inside the base folder is still refused`() =
        assertDeleteRefused("/remote.php/dav/files/user/Summer25/../Summer25/x.jpg")

    @Test
    fun `percent-encoded traversal is refused`() =
        assertDeleteRefused("/remote.php/dav/files/user/Summer25/%2e%2e/%2E%2E/other/x.jpg")

    @Test
    fun `encoded slash inside a segment is refused`() =
        assertDeleteRefused("/remote.php/dav/files/user/Summer25%2F..%2F..%2Fx.jpg")

    @Test
    fun `backslash traversal is refused`() = assertDeleteRefused("/remote.php/dav/files/user/Summer25\\..\\..\\x.jpg")

    @Test
    fun `query in href is refused`() = assertDeleteRefused("/remote.php/dav/files/user/Summer25/x.jpg?x=1")

    @Test
    fun `href outside the base folder is refused`() =
        assertDeleteRefused("/remote.php/dav/files/user/Other/x.jpg", baseFolder = "Photos/Backup")

    @Test
    fun `href of another user is refused`() = assertDeleteRefused("/remote.php/dav/files/admin/x.jpg")

    @Test
    fun `the base folder itself is refused`() =
        assertDeleteRefused("/remote.php/dav/files/user/Photos/Backup/", baseFolder = "Photos/Backup")

    @Test
    fun `listPhotos refuses an album href outside the base folder without a request`() =
        runTest {
            repo = createRepo(baseFolder = "Photos")

            val result = repo.listPhotos("/remote.php/dav/files/user/Other/")

            assertTrue(result.exceptionOrNull() is UnsafeHrefException)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `listPhotos skips entries with unsafe hrefs`() =
        runTest {
            repo = createRepo()
            servePhotos(
                propfindResponse(
                    multistatus(
                        "/remote.php/dav/files/user/Summer25/" to null,
                        "/remote.php/dav/files/user/Summer25/ok%20%C3%A4.jpg" to "image/jpeg",
                        "@attacker.example/steal.jpg" to "image/jpeg",
                        "https://attacker.example/remote.php/dav/files/user/Summer25/x.jpg" to "image/jpeg",
                        "/remote.php/dav/files/user/Summer25/../Other/y.jpg" to "image/jpeg",
                        "/remote.php/dav/files/user/Other/z.jpg" to "image/jpeg",
                    ),
                ),
            )

            val photos = repo.listPhotos("/remote.php/dav/files/user/Summer25/").getOrThrow().photos

            assertEquals(listOf("/remote.php/dav/files/user/Summer25/ok%20%C3%A4.jpg"), photos.map { it.href })
        }

    @Test
    fun `listAlbums skips directories outside the base folder`() =
        runTest {
            repo = createRepo(baseFolder = "Photos")
            server.enqueue(
                propfindResponse(
                    multistatus(
                        "/remote.php/dav/files/user/Photos/" to null,
                        "/remote.php/dav/files/user/Photos/Summer%202025/" to null,
                        "/remote.php/dav/files/user/Other/" to null,
                        "//attacker.example/remote.php/dav/files/user/Photos/Evil/" to null,
                    ),
                ),
            )

            val albums = repo.listAlbums().getOrThrow()

            assertEquals(listOf("/remote.php/dav/files/user/Photos/Summer%202025/"), albums.map { it.href })
        }

    // ── Authorization only for the configured origin ────────────────────────

    @Test
    fun `credentials are sent to the configured origin`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(204))

            repo.deletePhoto(photoAt("/remote.php/dav/files/user/Summer25/a.jpg"))

            assertEquals(Credentials.basic("testuser", "testpass"), server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `credentials are not sent to another origin after a redirect`() =
        runTest {
            repo = createRepo()
            val other = MockWebServer().apply { start() }
            try {
                server.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", other.url("/x.jpg").toString()),
                )
                other.enqueue(MockResponse().setResponseCode(404))

                runCatching { repo.downloadBitmapForHash(server.url("/remote.php/dav/files/user/A/x.jpg").toString()) }

                val request = other.takeRequest()
                assertNull("Authorization leaked to another origin", request.getHeader("Authorization"))
            } finally {
                other.shutdown()
            }
        }

    // ── downloadBitmapForHash URL validation ────────────────────────────────

    private fun assertDownloadRefused(url: String) =
        runTest {
            repo = createRepo(baseFolder = "Photos")

            val failure = runCatching { repo.downloadBitmapForHash(url) }.exceptionOrNull()

            assertTrue("expected UnsafeHrefException for $url, got $failure", failure is UnsafeHrefException)
            assertEquals("no request may be sent", 0, server.requestCount)
        }

    @Test
    fun `thumbnail download from another origin is refused without a request`() {
        val other = MockWebServer().apply { start() }
        try {
            assertDownloadRefused(other.url("/remote.php/dav/files/user/Photos/A/x.jpg").toString())
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `thumbnail download outside the base folder is refused`() =
        assertDownloadRefused(server.url("/remote.php/dav/files/user/Other/x.jpg").toString())

    @Test
    fun `thumbnail download of another user is refused`() =
        assertDownloadRefused(server.url("/remote.php/dav/files/admin/Photos/x.jpg").toString())

    @Test
    fun `thumbnail download with traversal is refused`() =
        assertDownloadRefused(server.url("/").toString() + "remote.php/dav/files/user/Photos/A/%2e%2e/%2e%2e/x.jpg")

    @Test
    fun `non-preview Nextcloud endpoint is refused`() =
        assertDownloadRefused(server.url("/index.php/apps/memories/api/describe").toString())

    @Test
    fun `Memories preview with extra path segments is refused`() =
        assertDownloadRefused(server.url("/index.php/apps/memories/api/image/preview/42/x").toString())

    @Test
    fun `malformed thumbnail URL is refused`() = assertDownloadRefused("not a url")

    @Test
    fun `thumbnail below the base folder is downloaded`() =
        runTest {
            repo = createRepo(baseFolder = "Photos")
            server.enqueue(MockResponse().setResponseCode(404))

            val failure =
                runCatching {
                    repo.downloadBitmapForHash(server.url("/remote.php/dav/files/user/Photos/A/x.jpg").toString())
                }.exceptionOrNull()

            assertEquals("/remote.php/dav/files/user/Photos/A/x.jpg", server.takeRequest().path)
            assertEquals("typed HTTP status", 404, (failure as WebDavHttpException).code)
        }

    @Test
    fun `Memories preview of a file id is downloaded`() =
        runTest {
            repo = createRepo(baseFolder = "Photos")
            server.enqueue(MockResponse().setResponseCode(404))

            runCatching {
                repo.downloadBitmapForHash(
                    server.url("/index.php/apps/memories/api/image/preview/42?x=512&y=512&a=1").toString(),
                )
            }

            assertEquals("/index.php/apps/memories/api/image/preview/42?x=512&y=512&a=1", server.takeRequest().path)
        }

    @Test
    fun `listAlbums failure carries the HTTP status`() =
        runTest {
            repo = createRepo()
            server.enqueue(MockResponse().setResponseCode(401))

            val failure = repo.listAlbums().exceptionOrNull()

            assertEquals(401, (failure as WebDavHttpException).code)
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
            val photo = RemotePhoto("img.jpg", "/remote.php/dav/files/user/Album/img.jpg", "42", "image/jpeg")
            val url = repo.fullImageUrl(photo)
            assertEquals(server.url("/remote.php/dav/files/user/Album/img.jpg").toString(), url)
        }

    @Test
    fun `fullImageUrl is empty for an unsafe href`() =
        runTest {
            repo = createRepo()
            val photo = RemotePhoto("img.jpg", "@attacker.example/img.jpg", "42", "image/jpeg")
            assertEquals("", repo.fullImageUrl(photo))
        }
}
