package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import app.cash.turbine.test
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeAlbumsRepository
    private lateinit var fakeCredentials: FakeCredentialRepository
    private lateinit var viewModel: AlbumsViewModel

    private val validConfig =
        WebDavConfig(
            url = "https://cloud.example.com/remote.php/dav/files/user/",
            username = "user",
            password = "pass",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeAlbumsRepository()
        fakeCredentials = FakeCredentialRepository(validConfig)
        val app = RuntimeEnvironment.getApplication() as Application
        viewModel =
            AlbumsViewModel(
                application = app,
                credentialRepo = fakeCredentials,
                repoFactory = { fakeRepo },
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── loadAlbums ──────────────────────────────────────────────────────────

    @Test
    fun `loadAlbums updates state with albums on success`() =
        runTest {
            val albums =
                listOf(
                    RemoteAlbum("Summer", "/Summer/"),
                    RemoteAlbum("Winter", "/Winter/"),
                )
            fakeRepo.albums = Result.success(albums)

            viewModel.loadAlbums()
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertFalse(state.isLoading)
            assertEquals(2, state.albums.size)
            assertEquals("Summer", state.albums[0].displayName)
        }

    @Test
    fun `loadAlbums sets loading state`() =
        runTest {
            fakeRepo.albums = Result.success(emptyList())

            viewModel.albumsState.test {
                assertEquals(AlbumsState(), awaitItem()) // initial

                viewModel.loadAlbums()

                val loading = awaitItem()
                assertTrue(loading.isLoading)

                val done = awaitItem()
                assertFalse(done.isLoading)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `loadAlbums sets error on failure`() =
        runTest {
            fakeRepo.albums = Result.failure(java.net.UnknownHostException("cloud.example.com"))

            viewModel.loadAlbums()
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertFalse(state.isLoading)
            assertEquals("typed error instead of raw exception text", AlbumsError.UnknownHost, state.error)
        }

    @Test
    fun `loadAlbums with a repository factory throwing IllegalArgumentException shows an error instead of crashing`() =
        runTest {
            viewModel =
                AlbumsViewModel(
                    application = RuntimeEnvironment.getApplication() as Application,
                    credentialRepo = fakeCredentials,
                    repoFactory = { throw IllegalArgumentException("Invalid WebDAV URL") },
                )

            viewModel.loadAlbums()
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertFalse(state.isLoading)
            assertEquals(AlbumsError.InvalidServerUrl, state.error)
        }

    @Test
    fun `loadAlbums without stored credentials shows not configured`() =
        runTest {
            fakeCredentials.clear()

            viewModel.loadAlbums()
            advanceUntilIdle()

            assertEquals(AlbumsError.NotConfigured, viewModel.albumsState.value.error)
        }

    // ── deleteAlbum ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAlbum removes album from albums state on success`() =
        runTest {
            val albums =
                listOf(
                    RemoteAlbum("Summer", "/Summer/"),
                    RemoteAlbum("Winter", "/Winter/"),
                )
            fakeRepo.albums = Result.success(albums)

            viewModel.loadAlbums()
            advanceUntilIdle()

            var success = false
            viewModel.deleteAlbum(albums[0]) { success = it }
            advanceUntilIdle()

            assertTrue(success)
            assertEquals(1, viewModel.albumsState.value.albums.size)
            assertEquals(
                "Winter",
                viewModel.albumsState.value.albums[0]
                    .displayName,
            )
        }

    @Test
    fun `deleteAlbum reports failure`() =
        runTest {
            fakeRepo.deleteAlbumResult = Result.failure(RuntimeException("Forbidden"))
            fakeRepo.albums = Result.success(listOf(RemoteAlbum("Summer", "/Summer/")))

            viewModel.loadAlbums()
            advanceUntilIdle()

            var success = true
            viewModel.deleteAlbum(RemoteAlbum("Summer", "/Summer/")) { success = it }
            advanceUntilIdle()

            assertFalse(success)
        }

    @Test
    fun `renameAlbum updates album in albums state on success`() =
        runTest {
            val albums =
                listOf(
                    RemoteAlbum("Summer", "/Summer/"),
                    RemoteAlbum("Winter", "/Winter/"),
                )
            fakeRepo.albums = Result.success(albums)
            fakeRepo.renameAlbumResult = Result.success(RemoteAlbum("Autumn", "/Autumn/"))

            viewModel.loadAlbums()
            advanceUntilIdle()

            var success = false
            viewModel.renameAlbum(albums[0], "Autumn") { success = it }
            advanceUntilIdle()

            assertTrue(success)
            assertEquals(
                "Autumn",
                viewModel.albumsState.value.albums[0]
                    .displayName,
            )
            assertEquals(
                "/Autumn/",
                viewModel.albumsState.value.albums[0]
                    .href,
            )
            assertEquals(
                "Winter",
                viewModel.albumsState.value.albums[1]
                    .displayName,
            )
        }

    @Test
    fun `loadAlbums recreates the repository so configuration changes are picked up`() =
        runTest {
            val created = mutableListOf<WebDavConfig>()
            viewModel =
                AlbumsViewModel(
                    application = RuntimeEnvironment.getApplication() as Application,
                    credentialRepo = fakeCredentials,
                    repoFactory = { config ->
                        created += config
                        fakeRepo
                    },
                )

            viewModel.loadAlbums()
            advanceUntilIdle()
            fakeCredentials.save("https://other.example.com/dav/", "user2", "pass2")
            viewModel.loadAlbums()
            advanceUntilIdle()

            assertEquals(listOf(validConfig.url, "https://other.example.com/dav/"), created.map { it.url })
        }
}
