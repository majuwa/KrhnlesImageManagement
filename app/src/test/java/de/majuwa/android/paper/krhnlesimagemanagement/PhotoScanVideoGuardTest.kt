package de.majuwa.android.paper.krhnlesimagemanagement

import android.graphics.Bitmap
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoAnalyzer
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoScanOutcome
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.scanAlbumPhotos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [scanAlbumPhotos] never requests a video, even when a caller forgets to filter (issue #74). */
@RunWith(RobolectricTestRunner::class)
class PhotoScanVideoGuardTest {
    @Test
    fun `a video in the list records no request and no result`() {
        val repo =
            FakeAlbumsRepository().apply {
                downloadBitmapFactory = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
            }
        val image = RemotePhoto("a.jpg", "/Album/a.jpg", "1", "image/jpeg")
        val video = RemotePhoto("v.mp4", "/Album/v.mp4", "2", "video/mp4", MediaKind.Video)
        val analyzer =
            object : PhotoAnalyzer<Int> {
                override suspend fun analyze(bitmap: Bitmap) = 1
            }

        val outcome =
            runBlocking {
                scanAlbumPhotos(
                    photos = listOf(video, image),
                    thumbnailUrls =
                        mapOf(
                            video.href to "https://fake.server/thumb/video",
                            image.href to "https://fake.server/a",
                        ),
                    repositories = AlbumsRepositorySource { Result.success(repo) },
                    analyzerFactory = { analyzer },
                    onProgress = {},
                )
            }

        assertEquals(listOf("https://fake.server/a"), repo.downloadedUrls)
        assertTrue(repo.thumbnailRequests.none { it.isVideo })
        assertEquals(mapOf(image.href to 1), (outcome as PhotoScanOutcome.Completed).results)
    }
}
