package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeDownloadRequester
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** [AlbumDetailViewModel.saveToDevice]: only the loaded album's photos reach the download requester. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumDetailViewModelSaveTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repo = FakeAlbumsRepository()
    private val requester = FakeDownloadRequester()
    private lateinit var credentials: FakeCredentialRepository
    private lateinit var viewModel: AlbumDetailViewModel

    private val beach = RemotePhoto("beach.jpg", "/Summer/beach.jpg", "1", "image/jpeg")
    private val sunset = RemotePhoto("sunset.jpg", "/Summer/sunset.jpg", "2", "image/jpeg")
    private val clip = RemotePhoto("clip.mp4", "/Summer/clip.mp4", "3", "video/mp4", MediaKind.Video)
    private val stale = RemotePhoto("old.jpg", "/Summer/old.jpg", "4", "image/jpeg")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        credentials =
            FakeCredentialRepository(
                WebDavConfig("https://cloud.example.com/remote.php/dav/files/user/", "user", "pass"),
            )
        viewModel =
            AlbumDetailViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialRepo = credentials,
                trustedCertificates = FakeTrustedCertificateRepository(),
                repoFactory = { _, _ -> repo },
                backgroundDispatcher = testDispatcher,
                downloadRequester = requester,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.loadAlbum() {
        repo.photos = Result.success(listOf(beach, sunset, clip))
        viewModel.loadAlbum("/Summer/")
        advanceUntilIdle()
    }

    @Test
    fun `the album href and the selected hrefs are passed on`() =
        runTest {
            loadAlbum()

            viewModel.saveToDevice(listOf(sunset, beach))

            assertEquals(listOf("/Summer/" to listOf("/Summer/sunset.jpg", "/Summer/beach.jpg")), requester.requests)
        }

    @Test
    fun `stale photos are dropped and duplicates collapsed`() =
        runTest {
            loadAlbum()

            viewModel.saveToDevice(listOf(beach, stale, beach))

            assertEquals(listOf("/Summer/" to listOf("/Summer/beach.jpg")), requester.requests)
        }

    @Test
    fun `only stale photos request nothing`() =
        runTest {
            loadAlbum()

            viewModel.saveToDevice(listOf(stale))
            viewModel.saveToDevice(emptyList())

            assertTrue(requester.requests.isEmpty())
        }

    @Test
    fun `videos are passed through for the worker to report`() =
        runTest {
            loadAlbum()

            viewModel.saveToDevice(listOf(clip, beach))

            assertEquals(listOf("/Summer/clip.mp4", "/Summer/beach.jpg"), requester.requests.single().second)
        }

    @Test
    fun `nothing is requested before the album is loaded`() =
        runTest {
            viewModel.saveToDevice(listOf(beach))
            repo.photos = Result.success(listOf(beach))
            viewModel.loadAlbum("/Summer/")
            viewModel.saveToDevice(listOf(beach))

            assertTrue(requester.requests.isEmpty())
        }

    @Test
    fun `nothing is requested after a failed load`() =
        runTest {
            repo.photos = Result.failure(java.io.IOException("down"))
            viewModel.loadAlbum("/Summer/")
            advanceUntilIdle()

            viewModel.saveToDevice(listOf(beach))

            assertTrue(requester.requests.isEmpty())
        }

    @Test
    fun `nothing is requested during or after a configuration change`() =
        runTest {
            loadAlbum()
            credentials.setConfig(WebDavConfig("https://other.example/dav/", "user", "pass"))
            advanceUntilIdle()

            viewModel.saveToDevice(listOf(beach))

            assertTrue(requester.requests.isEmpty())
        }

    @Test
    fun `nothing is requested after a logout`() =
        runTest {
            loadAlbum()
            credentials.setConfig(WebDavConfig())
            advanceUntilIdle()

            viewModel.saveToDevice(listOf(beach))

            assertTrue(requester.requests.isEmpty())
        }
}
