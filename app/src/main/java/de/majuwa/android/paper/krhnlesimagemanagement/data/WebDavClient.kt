package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.os.Build
import androidx.annotation.VisibleForTesting
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebDavClient(
    private val config: WebDavConfig,
) {
    private val userAgent = "Kroehnles-Image-Management/1.0 (Android; ${Build.MANUFACTURER} ${Build.MODEL})"

    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder().header("User-Agent", userAgent)
                // Application interceptor: runs once per call, for the original request only, so
                // this check alone does not cover redirects. On a followed redirect OkHttp drops
                // `Authorization` only when host or port change; a same-host, same-port https ->
                // http redirect would keep it. Uploads and renames therefore never follow redirects
                // ([noRedirectClient]); the remaining callers (PROPFIND test, MKCOL) send no body.
                if (original.url.hasSameOriginAs(config.url)) {
                    builder.header("Authorization", Credentials.basic(config.username, config.password))
                }
                chain.proceed(builder.build())
            }.build()

    /** The shared client; never gets a call timeout (large uploads). */
    @VisibleForTesting
    internal val httpClient: OkHttpClient get() = client

    /** Shares pool and interceptors with [client]; used where a redirect must count as failure. */
    private val noRedirectClient: OkHttpClient by lazy {
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * [noRedirectClient] with a longer read timeout for uploads: the server may take long to answer
     * after the last byte of a large file. It never follows a redirect: OkHttp turns a 301 / 302 /
     * 303 `PUT` into a body-less `GET`, whose 2xx would look like a successful upload, so every 3xx
     * fails the upload instead. Shares pool, dispatcher and the origin-restricted auth interceptor;
     * no call timeout.
     */
    @VisibleForTesting
    internal val uploadClient: OkHttpClient by lazy {
        noRedirectClient
            .newBuilder()
            .readTimeout(UPLOAD_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    private val baseUrl: String
        get() = config.url.trimEnd('/')

    /**
     * Sends a `PROPFIND` (Depth 0) to the base URL to verify the server and credentials.
     *
     * Unlike uploads, the whole call is bounded by [timeoutMillis] (a per-call OkHttp call timeout,
     * so the shared client used for large uploads keeps no overall limit) and is cancelled when the
     * calling coroutine is cancelled. A non-success status fails with [WebDavHttpException]; a call
     * timeout fails with an [java.io.InterruptedIOException]. Coroutine cancellation is rethrown,
     * never wrapped in the result.
     */
    suspend fun testConnection(timeoutMillis: Long = CONNECTION_TEST_TIMEOUT_MILLIS): Result<Unit> =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(baseUrl)
                    .method("PROPFIND", null)
                    .header("Depth", "0")
                    .build()
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            val (code, message) = call.awaitStatus()
            if (code !in HTTP_SUCCESS_CODES) {
                throw WebDavHttpException(code, "Connection failed: HTTP $code $message")
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Creates all path segments leading to [folderPath].
     * E.g. "Photos/KrohnSync/Summer 2026" creates each segment in order,
     * ignoring 405 (already exists) at each step.
     */
    suspend fun createDirectory(folderPath: String): Result<Unit> = createDirectories(listOf(folderPath))

    /**
     * Creates all path segments leading to each of [folderPaths], in order. Every cumulative path
     * is requested at most once per call, so levels shared by several paths (base folder, year,
     * month) are not repeated; 405 (already exists) is not an error. All segments of all paths are
     * validated before the first request; the first failing MKCOL stops the call.
     */
    suspend fun createDirectories(folderPaths: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val segmentLists = folderPaths.map { path -> path.split("/").filter { it.isNotBlank() } }
                segmentLists.flatten().forEach(::validatePathSegment)
                val created = mutableSetOf<List<String>>()
                for (segments in segmentLists) {
                    for (depth in 1..segments.size) {
                        val cumulativeSegments = segments.subList(0, depth).toList()
                        if (created.add(cumulativeSegments)) mkcol(cumulativeSegments)
                    }
                }
            }
        }

    private fun mkcol(cumulativeSegments: List<String>) {
        val request =
            Request
                .Builder()
                .url(buildPathUrl(cumulativeSegments))
                .method("MKCOL", null)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 405) {
                throw WebDavHttpException(
                    response.code,
                    "Failed to create directory '${cumulativeSegments.joinToString("/")}': HTTP ${response.code}",
                )
            }
        }
    }

    /**
     * Renames the collection at the path [oldSegments] to [newSegments] (each element one decoded
     * path segment below the base URL, none blank) with one `MOVE` (`Overwrite: F`, so an existing
     * target answers 412). Only 201 / 204 count as success; any other status, including a redirect
     * (not followed: a followed 301 would turn the MOVE into a GET), fails with
     * [WebDavHttpException] (a 2xx such as 207 means the outcome is unknown). The source URL carries a trailing slash
     * (collection URL). The call is
     * cancelled with the coroutine; there is deliberately no call timeout on the shared client, the
     * caller bounds the rename.
     */
    suspend fun renameDirectory(
        oldSegments: List<String>,
        newSegments: List<String>,
    ): Result<Unit> =
        runCatching {
            require(oldSegments.isNotEmpty()) { "Invalid path: empty" }
            require(newSegments.isNotEmpty()) { "Invalid path: empty" }
            // Blank segments are rejected, never filtered: filtering would address another folder.
            oldSegments.forEach(::validatePathSegment)
            newSegments.forEach(::validatePathSegment)

            val sourceUrl = buildPathUrl(oldSegments + "")
            val destinationUrl = buildPathUrl(newSegments)
            val request =
                Request
                    .Builder()
                    .url(sourceUrl)
                    .method("MOVE", null)
                    .header("Destination", destinationUrl.toString())
                    .header("Overwrite", "F")
                    .build()
            val (code, message) = noRedirectClient.newCall(request).awaitStatus()
            if (code != HTTP_CREATED && code != HTTP_NO_CONTENT) {
                throw WebDavHttpException(code, "Rename failed: HTTP $code $message")
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Streams [inputStream] to `folderName/fileName` with one `PUT` (never buffered in memory).
     * [contentLength] is sent as `Content-Length` when known; `-1` (unknown) uses chunked transfer.
     * The body is one-shot: the stream is read once and OkHttp does not replay it for a retry or an
     * authentication challenge. The call runs on [uploadClient] (no redirects, longer read timeout
     * for servers that finalise a large file after the last byte, no call timeout) and is cancelled
     * with the coroutine ([executeCancellable]). Any non-2xx status, including every 3xx redirect,
     * fails with [WebDavHttpException] (see [toUploadFailure] for 413 / 507); cancellation is
     * rethrown. With [requestServerHash] the `PUT` carries `X-Hash: sha256` (Nextcloud hashes the
     * stored content) and the answer's `X-Hash-SHA256` is returned in [UploadResponse] if it is a
     * well-formed SHA-256.
     */
    suspend fun uploadFile(
        folderName: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
        contentLength: Long = UNKNOWN_CONTENT_LENGTH,
        requestServerHash: Boolean = false,
    ): Result<UploadResponse> =
        runCatching {
            val folderSegments = folderName.split("/").filter { it.isNotBlank() }
            folderSegments.forEach(::validatePathSegment)
            validatePathSegment(fileName)
            val body = InputStreamRequestBody(mimeType.toMediaType(), inputStream, contentLength)
            val request =
                Request
                    .Builder()
                    .url(buildPathUrl(folderSegments + fileName))
                    .put(body)
                    .apply { if (requestServerHash) header(HEADER_REQUEST_HASH, HASH_ALGORITHM_SHA256) }
                    .build()
            executeCancellable(uploadClient.newCall(request)) { response, _ ->
                if (!response.isSuccessful) {
                    throw WebDavHttpException(response.code, "Upload failed: HTTP ${response.code}")
                }
                UploadResponse(serverSha256 = response.header(HEADER_RESPONSE_SHA256)?.takeIf(::isSha256Hex))
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Downloads `folderName/fileName` and returns the SHA-256 and byte count of the body (never
     * buffered). The caller passes the number of bytes it uploaded as [maxBytes]: a larger
     * `Content-Length`, or a body that outgrows it while streaming, fails with
     * [RemoteFileTooLargeException] (the server holds more than was sent) as soon as it is known,
     * distinct from the [IOException]s of a network failure. Runs on [uploadClient] (no redirects:
     * any 3xx fails with [WebDavHttpException], no call timeout, read timeout per read) with
     * `Accept-Encoding: identity` so the stored bytes are compared, and is cancelled with the
     * coroutine; cancellation is rethrown.
     */
    suspend fun downloadSha256(
        folderName: String,
        fileName: String,
        maxBytes: Long,
    ): Result<RemoteDigest> =
        runCatching {
            val segments = remoteFileSegments(folderName, fileName)
            val request =
                Request
                    .Builder()
                    .url(buildPathUrl(segments))
                    .get()
                    .header("Accept-Encoding", "identity")
                    .build()
            executeCancellable(uploadClient.newCall(request)) { response, _ ->
                if (!response.isSuccessful) {
                    throw WebDavHttpException(response.code, "Download failed: HTTP ${response.code}")
                }
                val body = response.body
                if (body.contentLength() > maxBytes) throw RemoteFileTooLargeException()
                digestOf(LimitedInputStream(body.byteStream(), maxBytes) { RemoteFileTooLargeException() })
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Size of `folderName/fileName` on the server from one `HEAD` (`Content-Length`; `null` when the
     * server sends none). `Accept-Encoding: identity` keeps it the stored size. Runs on
     * [noRedirectClient] (any 3xx fails), is cancelled with the coroutine; a non-2xx status fails
     * with [WebDavHttpException]; invalid segments fail without a request.
     */
    suspend fun remoteFileSize(
        folderName: String,
        fileName: String,
    ): Result<Long?> =
        runCatching {
            val segments = remoteFileSegments(folderName, fileName)
            val request =
                Request
                    .Builder()
                    .url(buildPathUrl(segments))
                    .head()
                    .header("Accept-Encoding", "identity")
                    .build()
            executeCancellable(noRedirectClient.newCall(request)) { response, _ ->
                if (!response.isSuccessful) {
                    throw WebDavHttpException(response.code, "Size check failed: HTTP ${response.code}")
                }
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
            }
        }.onFailure { if (it is CancellationException) throw it }

    private fun remoteFileSegments(
        folderName: String,
        fileName: String,
    ): List<String> {
        val folderSegments = folderName.split("/").filter { it.isNotBlank() }
        folderSegments.forEach(::validatePathSegment)
        validatePathSegment(fileName)
        return folderSegments + fileName
    }

    private fun digestOf(stream: InputStream): RemoteDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
                count += read
            }
        }
        return RemoteDigest(sha256 = digest.digest().toHexString(), sizeBytes = count)
    }

    /** Enqueues the call and suspends until its status line arrives; cancelling the coroutine cancels the call. */
    private suspend fun Call.awaitStatus(): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val status = response.use { it.code to it.message }
                        continuation.resume(status)
                    }
                },
            )
        }

    private fun buildPathUrl(segments: List<String>): HttpUrl =
        requireBaseHttpUrl()
            .newBuilder()
            .apply {
                segments.forEach(::addPathSegment)
            }.build()

    private fun requireBaseHttpUrl(): HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid WebDAV URL" }

    private fun validatePathSegment(segment: String) {
        require(segment.isNotBlank()) { "Invalid path segment: blank" }
        require('/' !in segment) { "Invalid path segment: '$segment'" }
        require(segment != "." && segment != "..") { "Invalid path segment: '$segment'" }
        require('\\' !in segment) { "Invalid path segment: backslash" }
        require(segment.none { it.isISOControl() }) { "Invalid path segment contains a control character" }
    }

    /** Streams [input] once; [length] `-1` = unknown (chunked transfer). */
    @VisibleForTesting
    internal class InputStreamRequestBody(
        private val mediaType: MediaType,
        private val input: InputStream,
        private val length: Long = UNKNOWN_CONTENT_LENGTH,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = if (length >= 0L) length else UNKNOWN_CONTENT_LENGTH

        /** The stream cannot be replayed: OkHttp must not resend this body (retry, auth challenge). */
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                }
            } finally {
                input.close()
            }
        }
    }

    companion object {
        /** Overall limit for [testConnection] (connect + TLS + request + response headers). */
        const val CONNECTION_TEST_TIMEOUT_MILLIS = 30_000L

        /** Unknown upload size: no `Content-Length`, chunked transfer. */
        const val UNKNOWN_CONTENT_LENGTH = -1L

        /** Read timeout of [uploadClient] (time to the response after the last byte, per read). */
        const val UPLOAD_READ_TIMEOUT_MINUTES = 5L

        /** 2xx, including 207 Multi-Status, which is the normal PROPFIND answer. */
        private val HTTP_SUCCESS_CODES = 200..299
        private const val HEADER_REQUEST_HASH = "X-Hash"
        private const val HEADER_RESPONSE_SHA256 = "X-Hash-SHA256"
        private const val HASH_ALGORITHM_SHA256 = "sha256"
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
    }
}

/** What a successful `PUT` answered: the server-computed SHA-256 (64 hex chars), null when absent or malformed. */
data class UploadResponse(
    val serverSha256: String?,
)

/** SHA-256 (lower-case hex) and byte count of a downloaded body. */
data class RemoteDigest(
    val sha256: String,
    val sizeBytes: Long,
)

/** The downloaded file is larger than the local file that was uploaded; never a network failure. */
class RemoteFileTooLargeException : IOException("Remote file is larger than the uploaded file")

private const val SHA256_HEX_LENGTH = 64

/** True for exactly 64 hex digits (any case): the only shape accepted as a server SHA-256. */
internal fun isSha256Hex(value: String): Boolean =
    value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** A WebDAV request completed but the server answered with the non-success HTTP status [code]. */
class WebDavHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/** Why one upload failed, for user-facing messages (never raw exception text). */
enum class UploadFailure {
    /** HTTP 413: the file is larger than the server (or a proxy) accepts. */
    TooLarge,

    /** HTTP 507: the server has no storage left. */
    StorageFull,

    /** The server copy differs from the local file, or the check could not finish. */
    Verification,

    /** Any other failure (network, other status, unreadable file). */
    Other,
}

private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_INSUFFICIENT_STORAGE = 507

/** Branches on [WebDavHttpException.code] only, never on the message. */
fun Throwable.toUploadFailure(): UploadFailure =
    when ((this as? WebDavHttpException)?.code) {
        HTTP_PAYLOAD_TOO_LARGE -> UploadFailure.TooLarge
        HTTP_INSUFFICIENT_STORAGE -> UploadFailure.StorageFull
        else -> UploadFailure.Other
    }
