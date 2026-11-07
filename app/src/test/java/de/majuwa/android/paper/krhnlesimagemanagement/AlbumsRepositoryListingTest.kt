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

/** The album-folder listing's `getlastmodified` handling (album-search-and-sort). */
@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryListingTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: AlbumsRepository

    private fun propfindResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(body)

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
            null,
        )
    }

    @Test
    fun `album listing requests getlastmodified and maps dates`() =
        runTest {
            repo = createRepo()
            val base = "/remote.php/dav/files/user"

            fun folder(
                name: String,
                modified: String?,
            ) = "<d:response><d:href>$base/$name/</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname>" +
                "<d:resourcetype><d:collection/></d:resourcetype>" +
                (modified?.let { "<d:getlastmodified>$it</d:getlastmodified>" } ?: "") +
                "</d:prop></d:propstat></d:response>"
            val xml =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">" +
                    folder("", null).replace("//", "/") +
                    folder("Dated", "Tue, 15 Nov 1994 08:12:31 GMT") +
                    folder("Undated", null) +
                    folder("Broken", "yesterday") +
                    "<d:response><d:href>$base/../evil/</d:href><d:propstat><d:prop>" +
                    "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>" +
                    "</d:multistatus>"
            server.enqueue(propfindResponse(xml))

            val albums = repo.listAlbums().getOrThrow()

            assertTrue(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains("getlastmodified"),
            )
            assertEquals(listOf("Dated", "Undated", "Broken"), albums.map { it.displayName })
            assertEquals(784887151000L, albums[0].lastModified)
            assertNull(albums[1].lastModified)
            assertNull(albums[2].lastModified)
        }

    @Test
    fun `photo listing request does not ask for getlastmodified`() =
        runTest {
            repo = createRepo()
            servePhotos(propfindResponse(multistatus("/remote.php/dav/files/user/Album/" to null)))

            repo.listPhotos("/remote.php/dav/files/user/Album/").getOrThrow()

            val propfind =
                generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
                    .first { it.method == "PROPFIND" }
            assertTrue(!propfind.body.readUtf8().contains("getlastmodified"))
        }
}
