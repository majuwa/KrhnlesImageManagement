package de.majuwa.android.paper.krhnlesimagemanagement.data

import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The repository that owns a video stream client was closed; the stream request was not sent. */
class RepositoryClosedException : IOException("Repository closed")

/**
 * A video stream request was refused: not a body-less `GET`, or a URL that is not strictly below
 * the configured base folder on the configured origin. Nothing was sent.
 *
 * Both guard exceptions MUST extend [IOException]: the Media3 `OkHttpDataSource` runs calls with
 * `Call.enqueue()`, and OkHttp rethrows any other exception thrown by an interceptor on its
 * dispatcher thread, which would crash the app.
 */
class UnsafeVideoUrlException : IOException("Refusing video request outside the configured WebDAV folder")

/**
 * The OkHttp client that streams videos for playback, derived with `newBuilder()` from the album
 * client of [AlbumsRepository] (same pin-aware TLS setup, origin-restricted `Authorization`
 * interceptor, timeouts and connection pool, so no client is built outside `data/tls/`).
 *
 * It differs from the album client by: no redirects (any 3xx is a failed response, never followed),
 * its own [Dispatcher] (so [cancelAll] never touches album calls), no cache and no overall
 * `callTimeout` (a video can play for hours), and a guard interceptor, added after the
 * authentication interceptor and therefore run before any network I/O, that
 *
 * 1. fails with [RepositoryClosedException] once [close] was called,
 * 2. fails with [UnsafeVideoUrlException] unless the request is a body-less `GET` for a URL
 *    strictly below [baseFolderUrl] on the configured origin,
 * 3. sets `Accept-Encoding: identity` so byte ranges refer to the stored file,
 * 4. replaces the body of every non-2xx response with an empty one: the Media3 data source reads a
 *    whole error body into memory, so an endless 4xx / 3xx body could otherwise exhaust memory
 *    (and the body is never shown anyway).
 */
internal class VideoStreamClient(
    albumClient: OkHttpClient,
    private val baseFolderUrl: HttpUrl,
) {
    private val closed = AtomicBoolean(false)

    val client: OkHttpClient =
        albumClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dispatcher(Dispatcher())
            .cache(null)
            .addInterceptor(::guard)
            .build()

    val callFactory: Call.Factory get() = client

    /** Refuses new requests and cancels every running one (and nothing else). */
    fun close() {
        closed.set(true)
        client.dispatcher.cancelAll()
    }

    /** Only a body-less `GET` strictly below the base folder on the configured origin may be sent. */
    private fun requireSafeStreamRequest(request: Request) {
        if (request.method != "GET" || request.body != null) throw UnsafeVideoUrlException()
        if (resolveWebDavHref(baseFolderUrl, request.url.toString()) == null) throw UnsafeVideoUrlException()
    }

    private fun guard(chain: Interceptor.Chain): Response {
        if (closed.get()) throw RepositoryClosedException()
        val request = chain.request()
        requireSafeStreamRequest(request)
        val response =
            chain.proceed(
                request
                    .newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build(),
            )
        if (response.isSuccessful) return response
        val contentType = response.body.contentType()
        response.close()
        return response.newBuilder().body("".toResponseBody(contentType)).build()
    }
}
