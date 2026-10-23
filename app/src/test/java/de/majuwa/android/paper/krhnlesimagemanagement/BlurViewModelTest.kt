package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.RecordingPhotoDeletionLauncher
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurScorer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

/** Blur review state of [BlurViewModel] (split from `AlbumsViewModel`, issues #29 / #48). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BlurViewModelTest {
    /** Returns the scores of [scoresInOrder] one per call (`null` = throws); counts how often it was closed. */
    private class FakeScorer(
        private val scoresInOrder: List<Double?>,
    ) : BlurScorer {
        private var next = 0
        var closed = 0

        override suspend fun computeBlurScore(bitmap: Bitmap): Double = scoresInOrder[next++] ?: error("scoring failed")

        override fun close() {
            closed++
        }
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeAlbumsRepository
    private val scorers = mutableListOf<FakeScorer>()
    private var scores: List<Double?> = listOf(SHARP, BLURRY)
    private var scorerFailure: RuntimeException? = null

    /** The album detail's repository, as handed to the review. */
    private val repositories = AlbumsRepositorySource { Result.success(fakeRepo) }

    /** Every bitmap handed out by the fake repository, to check that each one is recycled. */
    private val bitmaps = mutableListOf<Bitmap>()
    private lateinit var viewModel: BlurViewModel

    private val photoA = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
    private val photoB = RemotePhoto("b.jpg", "/Album/b.jpg", "2", "image/jpeg")
    private val photos = listOf(photoA, photoB)

    private fun createViewModel(): BlurViewModel =
        BlurViewModel(
            blurScorerFactory = {
                scorerFailure?.let { throw it }
                FakeScorer(scores).also { scorers += it }
            },
            analysisDispatcher = testDispatcher,
        )

    private fun BlurViewModel.scan(
        input: List<RemotePhoto> = photos,
        thumbnailUrls: Map<String, String> = emptyMap(),
        source: AlbumsRepositorySource = repositories,
    ) = findBlurryPhotos(input, thumbnailUrls, source)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo =
            FakeAlbumsRepository().apply {
                downloadBitmapFactory = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmaps += it } }
            }
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `photos scoring below the threshold are reported as blurry with their scores`() =
        runTest(testDispatcher) {
            viewModel.scan()
            advanceUntilIdle()

            assertEquals(BlurState.Found(listOf(photoB), mapOf(photoB.href to BLURRY)), viewModel.blurState.value)
            assertEquals("the scorer is closed after the scan", 1, scorers.single().closed)
            assertTrue("every downloaded bitmap is recycled", bitmaps.size == 2 && bitmaps.all { it.isRecycled })
        }

    @Test
    fun `a photo whose scoring fails is skipped and its bitmap is still recycled`() =
        runTest(testDispatcher) {
            scores = listOf(null, BLURRY)

            viewModel.scan()
            advanceUntilIdle()

            assertEquals(BlurState.Found(listOf(photoB), mapOf(photoB.href to BLURRY)), viewModel.blurState.value)
            assertTrue("bitmap of the failed photo is recycled too", bitmaps.size == 2 && bitmaps.all { it.isRecycled })
        }

    @Test
    fun `a failing scorer factory is a typed error, not a crash or a stuck scan`() =
        runTest(testDispatcher) {
            scorerFailure = IllegalStateException("ML Kit unavailable")

            viewModel.scan()
            advanceUntilIdle()

            assertEquals(BlurState.Error(AlbumsError.Unknown), viewModel.blurState.value)
            assertTrue("no scorer to close", scorers.isEmpty())
            assertTrue(fakeRepo.downloadedUrls.isEmpty())
        }

    @Test
    fun `only sharp photos means none found`() =
        runTest(testDispatcher) {
            scores = listOf(SHARP, SHARP)

            viewModel.scan()
            advanceUntilIdle()

            assertEquals(BlurState.NoneFound, viewModel.blurState.value)
        }

    @Test
    fun `scan reports progress per photo`() =
        runTest(testDispatcher) {
            viewModel.blurState.test {
                assertEquals(BlurState.Idle, awaitItem())
                viewModel.scan()

                assertEquals(BlurState.Scanning(0, 2), awaitItem())
                assertEquals(BlurState.Scanning(1, 2), awaitItem())
                assertEquals(BlurState.Scanning(2, 2), awaitItem())
                assertTrue(awaitItem() is BlurState.Found)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `no photos completes immediately without a scorer`() {
        viewModel.scan(emptyList())

        assertEquals(BlurState.NoneFound, viewModel.blurState.value)
        assertTrue(scorers.isEmpty())
    }

    @Test
    fun `uses the supplied thumbnail URLs and falls back to the repository`() =
        runTest(testDispatcher) {
            viewModel.scan(thumbnailUrls = mapOf(photoA.href to "https://cached/a"))
            advanceUntilIdle()

            assertEquals(
                listOf("https://cached/a", "https://fake.server/thumb/${photoB.href}"),
                fakeRepo.downloadedUrls,
            )
        }

    @Test
    fun `a second scan request while scanning is ignored`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onDownloadBitmap = { gate.await() }
            viewModel.scan()
            runCurrent()
            viewModel.scan()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, scorers.size)
            assertEquals(2, fakeRepo.downloadedUrls.size)
        }

    @Test
    fun `a repository factory throwing IllegalArgumentException is a typed error instead of a crash`() =
        runTest(testDispatcher) {
            val invalidUrl =
                AlbumsRepositoryProvider(
                    FakeCredentialRepository(WebDavConfig(url = "not a url", username = "u", password = "p")),
                ) { throw IllegalArgumentException("Invalid WebDAV URL") }

            viewModel.scan(source = invalidUrl)
            advanceUntilIdle()

            assertEquals(BlurState.Error(AlbumsError.InvalidServerUrl), viewModel.blurState.value)
            assertTrue("the scorer is only created once the repository is ready", scorers.isEmpty())
        }

    @Test
    fun `a new scan request is ignored while results are shown`() =
        runTest(testDispatcher) {
            viewModel.scan()
            advanceUntilIdle()
            val found = viewModel.blurState.value

            viewModel.scan() // screen recreated, e.g. after rotation

            assertEquals(found, viewModel.blurState.value)
            assertEquals("no second scan", 1, scorers.size)
        }

    @Test
    fun `a new scan request is ignored while deleting`() =
        runTest(testDispatcher) {
            val launcher = RecordingPhotoDeletionLauncher()
            viewModel.deleteBlurryPhotos(listOf(photoB), launcher)

            viewModel.scan() // screen recreated, e.g. after rotation
            advanceUntilIdle()

            assertEquals(BlurState.Deleting, viewModel.blurState.value)
            assertTrue(scorers.isEmpty())
            launcher.pendingFinish!!.invoke()
            assertEquals("compare-and-set still finds Deleting", BlurState.Idle, viewModel.blurState.value)
        }

    @Test
    fun `a new scan request is ignored after an error but Retry starts one`() =
        runTest(testDispatcher) {
            val loggedOut = AlbumsRepositoryProvider(FakeCredentialRepository(WebDavConfig())) { fakeRepo }
            viewModel.scan(source = loggedOut)
            advanceUntilIdle()
            assertEquals(BlurState.Error(AlbumsError.NotConfigured), viewModel.blurState.value)

            viewModel.scan()
            assertEquals(BlurState.Error(AlbumsError.NotConfigured), viewModel.blurState.value)

            viewModel.retryFindBlurryPhotos(photos, emptyMap(), repositories)
            advanceUntilIdle()
            assertTrue(viewModel.blurState.value is BlurState.Found)
        }

    @Test
    fun `Retry is ignored unless the scan failed`() =
        runTest(testDispatcher) {
            viewModel.retryFindBlurryPhotos(photos, emptyMap(), repositories)
            advanceUntilIdle()

            assertEquals(BlurState.Idle, viewModel.blurState.value)
            assertTrue(scorers.isEmpty())
        }

    @Test
    fun `resetBlurState sets state to Idle`() =
        runTest(testDispatcher) {
            viewModel.scan()
            advanceUntilIdle()

            viewModel.resetBlurState()

            assertEquals(BlurState.Idle, viewModel.blurState.value)
        }

    @Test
    fun `deletion enters Deleting and returns to Idle when the launcher finishes`() =
        runTest(testDispatcher) {
            val launcher = RecordingPhotoDeletionLauncher()

            assertTrue(viewModel.deleteBlurryPhotos(listOf(photoB), launcher))
            assertEquals(BlurState.Deleting, viewModel.blurState.value)
            assertEquals(listOf(listOf(photoB)), launcher.requests)

            launcher.pendingFinish!!.invoke()
            assertEquals(BlurState.Idle, viewModel.blurState.value)
        }

    @Test
    fun `a finishing deletion never overwrites a newer scan`() =
        runTest(testDispatcher) {
            val launcher = RecordingPhotoDeletionLauncher()
            viewModel.deleteBlurryPhotos(listOf(photoB), launcher)

            viewModel.resetBlurState()
            viewModel.scan()
            advanceUntilIdle()
            launcher.pendingFinish!!.invoke()

            assertTrue(viewModel.blurState.value is BlurState.Found)
        }

    @Test
    fun `a rejected deletion leaves the review state unchanged`() =
        runTest(testDispatcher) {
            viewModel.scan()
            advanceUntilIdle()
            val before = viewModel.blurState.value

            assertFalse(viewModel.deleteBlurryPhotos(listOf(photoB), RecordingPhotoDeletionLauncher(accept = false)))

            assertEquals(before, viewModel.blurState.value)
        }

    @Test
    fun `clearing the ViewModel stops a running scan and closes the scorer`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onDownloadBitmap = { gate.await() }
            val store = ViewModelStore()
            val scoped =
                ViewModelProvider(store, viewModelFactory { initializer { createViewModel() } })[
                    BlurViewModel::class.java,
                ]

            scoped.scan()
            runCurrent()
            store.clear() // the review entry is popped
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("no photo after the first is downloaded", 1, fakeRepo.downloadedUrls.size)
            assertEquals(1, scorers.single().closed)
            assertEquals("a cancelled scan is not an error", BlurState.Scanning(0, 2), scoped.blurState.value)
        }

    @Test
    fun `a configuration change mid-scan stops requests to the old repository`() =
        runTest(testDispatcher) {
            val newRepo = FakeAlbumsRepository().apply { downloadBitmapFactory = fakeRepo.downloadBitmapFactory }
            var changed = false
            fakeRepo.onDownloadBitmap = { changed = true } // the config changes while photo 1 downloads
            val source =
                AlbumsRepositorySource { Result.success(if (changed) newRepo else fakeRepo) }

            viewModel.scan(source = source)
            advanceUntilIdle()

            assertEquals("no request after the change", 1, fakeRepo.downloadedUrls.size)
            assertTrue(newRepo.downloadedUrls.isEmpty())
            assertEquals(BlurState.Error(AlbumsError.ConfigurationChanged), viewModel.blurState.value)
        }

    @Test
    fun `a logout mid-scan is a typed error without further requests`() =
        runTest(testDispatcher) {
            var loggedOut = false
            fakeRepo.onDownloadBitmap = { loggedOut = true }
            val source =
                AlbumsRepositorySource {
                    if (loggedOut) {
                        Result.failure(
                            AlbumsRepositoryUnavailableException(
                                AlbumsRepositoryUnavailableException.Reason.NOT_CONFIGURED,
                            ),
                        )
                    } else {
                        Result.success(fakeRepo)
                    }
                }

            viewModel.scan(source = source)
            advanceUntilIdle()

            assertEquals(1, fakeRepo.downloadedUrls.size)
            assertEquals(BlurState.Error(AlbumsError.NotConfigured), viewModel.blurState.value)
        }

    @Test
    fun `cancelScan stops a running scan and returns to Idle`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onDownloadBitmap = { gate.await() }
            viewModel.scan()
            runCurrent()

            viewModel.cancelScan()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(BlurState.Idle, viewModel.blurState.value)
            assertEquals("no photo after the first is requested", 1, fakeRepo.downloadedUrls.size)
        }

    @Test
    fun `cancelScan discards shown results`() =
        runTest(testDispatcher) {
            viewModel.scan()
            advanceUntilIdle()
            assertTrue(viewModel.blurState.value is BlurState.Found)

            viewModel.cancelScan()

            assertEquals(BlurState.Idle, viewModel.blurState.value)
        }

    @Test
    fun `reset during a scan then a new scan - only the new scan publishes`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            fakeRepo.onDownloadBitmap = { gate.await() }
            viewModel.scan(listOf(photoA))
            runCurrent()
            viewModel.resetBlurState()
            viewModel.scan() // two photos
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("old scan stopped after its first download, new scan did two", 3, fakeRepo.downloadedUrls.size)
            assertTrue(viewModel.blurState.value is BlurState.Found)
        }

    @Test
    fun `Retry with an empty album keeps the error instead of reporting none found`() =
        runTest(testDispatcher) {
            viewModel.scan(source = AlbumsRepositorySource { Result.failure(IllegalStateException("x")) })
            advanceUntilIdle()

            viewModel.retryFindBlurryPhotos(emptyList(), emptyMap(), repositories)
            advanceUntilIdle()

            assertEquals(BlurState.Error(AlbumsError.Unknown), viewModel.blurState.value)
        }

    private companion object {
        const val SHARP = BlurDetector.DEFAULT_THRESHOLD * 2
        const val BLURRY = BlurDetector.DEFAULT_THRESHOLD / 2
    }
}
