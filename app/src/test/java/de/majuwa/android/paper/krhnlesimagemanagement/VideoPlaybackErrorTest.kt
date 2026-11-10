package de.majuwa.android.paper.krhnlesimagemanagement

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import de.majuwa.android.paper.krhnlesimagemanagement.data.RepositoryClosedException
import de.majuwa.android.paper.krhnlesimagemanagement.data.UnsafeVideoUrlException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateChangedException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.VideoPlaybackError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.toVideoPlaybackError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.videoPlaybackErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
class VideoPlaybackErrorTest {
    private val spec = DataSpec(Uri.parse("https://cloud.example.com/remote.php/dav/files/user/secret-name.mp4"))

    private fun status(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, "m", null, emptyMap(), spec, ByteArray(0))

    private fun wrapped(
        cause: Throwable,
        code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    ) = PlaybackException("Source error", cause, code)

    private fun httpFailure(cause: java.io.IOException) =
        wrapped(
            HttpDataSource.HttpDataSourceException(
                cause,
                spec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            ),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

    private fun mapped(e: PlaybackException) = e.toVideoPlaybackError()

    @Test
    fun `redirect statuses are Redirected`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertEquals(VideoPlaybackError.Redirected, mapped(wrapped(status(code))))
        }
    }

    @Test
    fun `authorization and missing file statuses map to the album errors`() {
        assertEquals(VideoPlaybackError.Server(AlbumsError.AuthFailed), mapped(wrapped(status(401))))
        assertEquals(VideoPlaybackError.Server(AlbumsError.AuthFailed), mapped(wrapped(status(403))))
        assertEquals(VideoPlaybackError.Server(AlbumsError.NotFound), mapped(wrapped(status(404))))
        assertEquals(VideoPlaybackError.Server(AlbumsError.NotFound), mapped(wrapped(status(410))))
        assertEquals(VideoPlaybackError.Server(AlbumsError.HttpError(503)), mapped(wrapped(status(503))))
        assertEquals(VideoPlaybackError.Server(AlbumsError.HttpError(500)), mapped(wrapped(status(500))))
    }

    @Test
    fun `timeouts and connection failures map to the network errors`() {
        assertEquals(
            VideoPlaybackError.Server(AlbumsError.Timeout),
            mapped(httpFailure(SocketTimeoutException("t"))),
        )
        assertEquals(
            VideoPlaybackError.Server(AlbumsError.ServerUnreachable),
            mapped(httpFailure(ConnectException("c"))),
        )
        assertEquals(
            VideoPlaybackError.Server(AlbumsError.UnknownHost),
            mapped(httpFailure(UnknownHostException("h"))),
        )
    }

    @Test
    fun `a pinned certificate failure reads as a changed or untrusted certificate`() {
        val changed = SSLHandshakeException("x").apply { initCause(CertificateChangedException(RuntimeException())) }
        assertEquals(VideoPlaybackError.Server(AlbumsError.CertificateChanged), mapped(httpFailure(changed)))
        assertEquals(
            VideoPlaybackError.Server(AlbumsError.CertificateError),
            mapped(httpFailure(SSLHandshakeException("x"))),
        )
    }

    @Test
    fun `decoder and container problems are an unsupported format`() {
        val codes =
            listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        for (code in codes) {
            assertEquals(
                code.toString(),
                VideoPlaybackError.UnsupportedFormat,
                mapped(PlaybackException("m", null, code)),
            )
        }
    }

    @Test
    fun `transient decoder problems are a generic retryable error not an unsupported format`() {
        for (
        code in
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        )
        ) {
            assertEquals(
                code.toString(),
                VideoPlaybackError.Server(AlbumsError.Unknown),
                mapped(PlaybackException("m", null, code)),
            )
        }
    }

    @Test
    fun `the app's own guard exceptions anywhere in the chain are generic`() {
        assertEquals(VideoPlaybackError.Server(AlbumsError.Unknown), mapped(httpFailure(UnsafeVideoUrlException())))
        assertEquals(VideoPlaybackError.Server(AlbumsError.Unknown), mapped(httpFailure(RepositoryClosedException())))
        val deep = java.io.IOException("outer", java.io.IOException("middle", UnsafeVideoUrlException()))
        assertEquals(VideoPlaybackError.Server(AlbumsError.Unknown), mapped(httpFailure(deep)))
    }

    @Test
    fun `an unclassified failure is unknown`() {
        assertEquals(
            VideoPlaybackError.Server(AlbumsError.Unknown),
            mapped(PlaybackException("m", null, PlaybackException.ERROR_CODE_UNSPECIFIED)),
        )
    }

    @Test
    fun `no user message contains exception text, urls or file names`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val errors =
            listOf(
                mapped(wrapped(status(404))),
                mapped(wrapped(status(302))),
                mapped(httpFailure(SocketTimeoutException("secret-name.mp4"))),
                mapped(
                    PlaybackException(
                        "secret-name.mp4 cloud.example.com",
                        null,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                    ),
                ),
                mapped(httpFailure(UnsafeVideoUrlException())),
            )
        for (error in errors) {
            val text = videoPlaybackErrorMessage(resources, error)
            assertTrue(text.isNotBlank())
            assertFalse(text, "secret-name" in text || "cloud.example.com" in text || "Exception" in text)
        }
        assertEquals(
            videoPlaybackErrorMessage(resources, VideoPlaybackError.UnsupportedFormat),
            "This video format is not supported on this device.",
        )
    }
}
