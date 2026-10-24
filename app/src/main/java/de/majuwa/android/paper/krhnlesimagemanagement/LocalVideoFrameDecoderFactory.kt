package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.ContentResolver
import coil3.ImageLoader
import coil3.decode.ContentMetadata
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.video.VideoFrameDecoder

/**
 * Video frame decoding for local `content://` media only (the grid's own videos). A source from
 * the network or the disk cache carries no [ContentMetadata] and is refused, so a server answering
 * a thumbnail request with `video/mp4` is never handed to `MediaMetadataRetriever` (a large, complex
 * native parser). The app's image loader disables Coil's service loader, so this wrapper is the only
 * registration of [VideoFrameDecoder].
 */
internal class LocalVideoFrameDecoderFactory(
    private val delegate: Decoder.Factory = VideoFrameDecoder.Factory(),
) : Decoder.Factory {
    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val metadata = result.source.metadata as? ContentMetadata ?: return null
        if (metadata.uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return delegate.create(result, options, imageLoader)
    }
}
