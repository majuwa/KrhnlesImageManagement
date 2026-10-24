package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.RenameOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val twoAlbums =
        listOf(
            RemoteAlbum("Summer", "/Summer/"),
            RemoteAlbum("Winter", "/Winter/"),
        )

    private fun TestScope.loadTwoAlbums() {
        fakeRepo.albums = Result.success(twoAlbums)
        viewModel.loadAlbums()
        advanceUntilIdle()
    }

    @Test
    fun `renameAlbum replaces the entry in place without listing albums again`() =
        runTest {
            fakeRepo.renameAlbumResult = Result.success(RemoteAlbum("Autumn", "/Autumn/"))
            loadTwoAlbums()
            val listCallsBefore = fakeRepo.listAlbumsCalls

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            advanceUntilIdle()

            val state = viewModel.albumsState.value
            assertEquals(listOf("Autumn", "Winter"), state.albums.map { it.displayName })
            assertEquals("/Autumn/", state.albums[0].href)
            assertFalse(state.isRenamingAlbum)
            assertEquals(listCallsBefore, fakeRepo.listAlbumsCalls)
            assertEquals(RenameOutcome.Success("Autumn"), viewModel.renameOutcome.value)
            viewModel.consumeRenameOutcome()
            assertNull(viewModel.renameOutcome.value)
        }

    @Test
    fun `renameAlbum with an existing name keeps the list and reports AlreadyExists`() =
        runTest {
            fakeRepo.renameAlbumResult = Result.failure(WebDavHttpException(412, "exists"))
            loadTwoAlbums()

            viewModel.renameAlbum(twoAlbums[0], "Winter")
            advanceUntilIdle()

            assertEquals(twoAlbums, viewModel.albumsState.value.albums)
            assertFalse(viewModel.albumsState.value.isRenamingAlbum)
            assertEquals(RenameOutcome.Failure(AlbumsError.AlreadyExists), viewModel.renameOutcome.value)
        }

    @Test
    fun `renameAlbum called twice sends one MOVE`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onRenameAlbum = { gate.await() }
            loadTwoAlbums()

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            viewModel.renameAlbum(twoAlbums[0], "Spring")
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fakeRepo.renamedAlbums.size)
            assertEquals("Autumn", fakeRepo.renamedAlbums[0].second)
        }

    @Test
    fun `renameAlbum timeout releases the guard reports timeout and reloads`() =
        runTest {
            loadTwoAlbums()
            val listCallsBefore = fakeRepo.listAlbumsCalls
            fakeRepo.onRenameAlbum = { awaitCancellation() }

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            runCurrent()
            assertTrue(viewModel.albumsState.value.isRenamingAlbum)

            advanceTimeBy(AlbumsViewModel.RENAME_TIMEOUT_MILLIS + 1)
            advanceUntilIdle()

            assertFalse(viewModel.albumsState.value.isRenamingAlbum)
            assertEquals(RenameOutcome.Failure(AlbumsError.RenameTimedOut), viewModel.renameOutcome.value)
            assertEquals(listCallsBefore + 1, fakeRepo.listAlbumsCalls)

            // The guard is released: a new rename is accepted.
            fakeRepo.onRenameAlbum = {}
            viewModel.consumeRenameOutcome()
            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            advanceUntilIdle()
            assertEquals(2, fakeRepo.renamedAlbums.size)
        }

    @Test
    fun `refresh is ignored while a rename runs`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onRenameAlbum = { gate.await() }
            loadTwoAlbums()
            val listCallsBefore = fakeRepo.listAlbumsCalls

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            runCurrent()
            viewModel.loadAlbums()
            runCurrent()
            assertEquals(listCallsBefore, fakeRepo.listAlbumsCalls)

            gate.complete(Unit)
            advanceUntilIdle()
            viewModel.loadAlbums()
            advanceUntilIdle()
            assertEquals(listCallsBefore + 1, fakeRepo.listAlbumsCalls)
        }

    @Test
    fun `renameAlbum drops another entry that already has the new href`() =
        runTest {
            fakeRepo.renameAlbumResult = Result.success(RemoteAlbum("Winter", "/Winter/"))
            loadTwoAlbums()

            viewModel.renameAlbum(twoAlbums[0], "Winter")
            advanceUntilIdle()

            assertEquals(
                listOf("/Winter/"),
                viewModel.albumsState.value.albums
                    .map { it.href },
            )
        }

    @Test
    fun `renameAlbum with an unexpected 2xx reports RenamePartial and reloads`() =
        runTest {
            fakeRepo.renameAlbumResult = Result.failure(WebDavHttpException(207, "multi"))
            loadTwoAlbums()
            val listCallsBefore = fakeRepo.listAlbumsCalls

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            advanceUntilIdle()

            assertEquals(RenameOutcome.Failure(AlbumsError.RenamePartial), viewModel.renameOutcome.value)
            assertEquals(listCallsBefore + 1, fakeRepo.listAlbumsCalls)
        }

    @Test
    fun `a cancelled rename publishes no outcome and does not reload`() =
        runTest {
            loadTwoAlbums()
            val listCallsBefore = fakeRepo.listAlbumsCalls
            fakeRepo.onRenameAlbum = { awaitCancellation() }

            viewModel.renameAlbum(twoAlbums[0], "Autumn")
            runCurrent()
            assertTrue(viewModel.albumsState.value.isRenamingAlbum)

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()

            assertNull(viewModel.renameOutcome.value)
            assertFalse(viewModel.albumsState.value.isRenamingAlbum)
            assertEquals(listCallsBefore, fakeRepo.listAlbumsCalls)
        }

    @Test
    fun `renameAlbum with an invalid name is rejected without a request`() =
        runTest {
            loadTwoAlbums()

            listOf("", "   ", "a/b", "a\\b", "..").forEach { name ->
                viewModel.renameAlbum(twoAlbums[0], name)
                advanceUntilIdle()
                assertEquals(RenameOutcome.Failure(AlbumsError.InvalidName), viewModel.renameOutcome.value)
                viewModel.consumeRenameOutcome()
            }
            assertTrue(fakeRepo.renamedAlbums.isEmpty())
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
