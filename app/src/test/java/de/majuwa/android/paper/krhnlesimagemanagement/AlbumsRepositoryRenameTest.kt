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
class AlbumsRepositoryRenameTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: AlbumsRepository

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

    @Test
    fun `renameAlbum sends MOVE and returns updated album`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            server.enqueue(MockResponse().setResponseCode(201))

            val album = RemoteAlbum("Summer 2025", "/remote.php/dav/files/user/Photos/Backup/Summer%202025/")
            val result = repo.renameAlbum(album, "Autumn 2025")

            assertTrue(result.isSuccess)
            val renamedAlbum = result.getOrThrow()
            assertEquals("Autumn 2025", renamedAlbum.displayName)
            assertEquals(
                "/remote.php/dav/files/user/Photos/Backup/Autumn%202025/",
                renamedAlbum.href,
            )

            val request = server.takeRequest()
            assertEquals("MOVE", request.method)
            assertTrue(request.path!!.endsWith("/Photos/Backup/Summer%202025/"))
            assertEquals("F", request.getHeader("Overwrite"))
            assertEquals(1, server.requestCount)
            assertTrue(request.getHeader("Destination")!!.endsWith("/Photos/Backup/Autumn%202025"))
        }

    @Test
    fun `renameAlbum trims the new name`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            server.enqueue(MockResponse().setResponseCode(201))

            val album = RemoteAlbum("Summer", "/remote.php/dav/files/user/Photos/Backup/Summer/")
            val renamed = repo.renameAlbum(album, "  Autumn  ").getOrThrow()

            assertEquals("Autumn", renamed.displayName)
            assertTrue(server.takeRequest().getHeader("Destination")!!.endsWith("/Photos/Backup/Autumn"))
        }

    @Test
    fun `renameAlbum rejects invalid names without a request`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            val album = RemoteAlbum("Summer", "/remote.php/dav/files/user/Photos/Backup/Summer/")

            listOf("", "  ", "a/b", "a\\b", "..", ".", "a\u0000b").forEach { name ->
                val failure = repo.renameAlbum(album, name).exceptionOrNull()
                assertTrue(name, failure is InvalidAlbumNameException)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `renameAlbum with an unsafe href sends nothing`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")

            listOf(
                "/remote.php/dav/files/user/Other/Summer/",
                "/remote.php/dav/files/user/Photos/Backup/../Summer/",
                "/remote.php/dav/files/user/Photos/Backup/",
                "/remote.php/dav/files/user/Photos/Backup/%20/Summer/",
                "/remote.php/dav/files/user/Photos/Backup/%20/",
                "/remote.php/dav/files/user/Photos/Backup//Summer/",
                "/remote.php/dav/files/user/Photos/Backup/Summer/Sub/",
            ).forEach { href ->
                assertTrue(href, repo.renameAlbum(RemoteAlbum("x", href), "New").isFailure)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `renameAlbum rejects a name over 255 UTF-8 bytes but accepts exactly 255`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            val album = RemoteAlbum("Summer", "/remote.php/dav/files/user/Photos/Backup/Summer/")

            // 128 two-byte characters are 256 bytes although only 128 chars long.
            listOf("a".repeat(256), "\u00e4".repeat(128)).forEach { name ->
                assertTrue(
                    "${name.length}",
                    repo.renameAlbum(album, name).exceptionOrNull() is AlbumNameTooLongException,
                )
            }
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(201))
            assertTrue(repo.renameAlbum(album, "a".repeat(255)).isSuccess)
        }

    @Test
    fun `renameAlbum reports an unexpected 2xx as WebDavHttpException`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            server.enqueue(MockResponse().setResponseCode(207))
            val album = RemoteAlbum("Summer", "/remote.php/dav/files/user/Photos/Backup/Summer/")
            assertEquals(207, (repo.renameAlbum(album, "New").exceptionOrNull() as WebDavHttpException).code)
        }

    @Test
    fun `renameAlbum reports 412 and redirects as WebDavHttpException with one request each`() =
        runTest {
            repo = createRepo(baseFolder = "Photos/Backup")
            val album = RemoteAlbum("Summer", "/remote.php/dav/files/user/Photos/Backup/Summer/")
            listOf(412, 404, 301).forEach { code ->
                val before = server.requestCount
                server.enqueue(MockResponse().setResponseCode(code))
                val failure = repo.renameAlbum(album, "New").exceptionOrNull()
                assertEquals(code, (failure as WebDavHttpException).code)
                assertEquals(before + 1, server.requestCount)
                assertEquals("MOVE", server.takeRequest().method)
            }
        }
}
