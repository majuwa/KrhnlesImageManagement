package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import coil3.video.VideoFrameDecoder
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Video thumbnails need `coil-video`'s [VideoFrameDecoder], but only for local `content://` media:
 * the app's image loader disables Coil's service loader and registers it once, wrapped in
 * [LocalVideoFrameDecoderFactory], so a remote `video/mp4` answer is never decoded as a video.
 */
@RunWith(RobolectricTestRunner::class)
class VideoDecoderRegistrationTest {
    private val app = ApplicationProvider.getApplicationContext<KrhnlesApplication>()
    private val loader = app.newImageLoader(app)
    private val factory = LocalVideoFrameDecoderFactory()

    private fun result(
        metadata: ImageSource.Metadata?,
        dataSource: DataSource,
        mimeType: String = "video/mp4",
    ) = SourceFetchResult(
        source = ImageSource(Buffer().write(ByteArray(16)), FileSystem.SYSTEM, metadata),
        mimeType = mimeType,
        dataSource = dataSource,
    )

    private fun contentMetadata(uri: String): ContentMetadata {
        val file = File.createTempFile("video", ".mp4", app.cacheDir).apply { writeBytes(ByteArray(16)) }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return ContentMetadata(uri.toUri(), AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH))
    }

    @Test
    fun `app image loader registers only the local video decoder and no service-loaded one`() {
        val factories = loader.components.decoderFactories

        assertEquals(1, factories.count { it is LocalVideoFrameDecoderFactory })
        assertEquals(0, factories.count { it is VideoFrameDecoder.Factory })
    }

    @Test
    fun `a remote video answer is not handled by the video decoder`() {
        val network = factory.create(result(null, DataSource.NETWORK), Options(app), loader)
        val diskCached = factory.create(result(null, DataSource.DISK), Options(app), loader)

        assertNull(network)
        assertNull(diskCached)
    }

    @Test
    fun `a local content video is decoded by the video frame decoder`() {
        val metadata = contentMetadata("content://media/external/video/media/1")

        val decoder = factory.create(result(metadata, DataSource.DISK), Options(app), loader)

        assertNotNull(decoder)
        assertTrue(decoder is VideoFrameDecoder)
    }

    @Test
    fun `a local content image is left to the image decoders`() {
        val metadata = contentMetadata("content://media/external/images/media/1")

        assertNull(factory.create(result(metadata, DataSource.DISK, "image/jpeg"), Options(app), loader))
    }
}
