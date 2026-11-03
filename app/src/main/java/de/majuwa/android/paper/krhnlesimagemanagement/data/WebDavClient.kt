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

    /**
     * [uploadClient] with a [CHUNK_ASSEMBLY_READ_TIMEOUT_MINUTES] read timeout, for the final `MOVE`
     * of a chunked upload: Nextcloud assembles a multi-gigabyte file before it answers. Derived from
     * [uploadClient], so it keeps its pool, dispatcher, no-redirect rule, origin-restricted auth
     * interceptor (and whatever the upload client is later built with); no call timeout.
     */
    @VisibleForTesting
    internal val assemblyClient: OkHttpClient by lazy {
        uploadClient
            .newBuilder()
            .readTimeout(CHUNK_ASSEMBLY_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
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
            }.onFailure { if (it is CancellationException) throw it }
        }

    /** Cancellable with the coroutine ([executeCancellable]): a user cancel aborts a running MKCOL at once. */
    private suspend fun mkcol(cumulativeSegments: List<String>) {
        val request =
            Request
                .Builder()
                .url(buildPathUrl(cumulativeSegments))
                .method("MKCOL", null)
                .build()
        executeCancellable(client.newCall(request)) { response, _ ->
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

    /**
     * Creates the Nextcloud upload folder of [transferId] (`MKCOL` below the uploads collection)
     * for the file [folderName]/[fileName] of [totalLength] bytes: 201 is [ChunkedUploadCreation.Created],
     * 405 [ChunkedUploadCreation.AlreadyExists] (an earlier attempt of the same transfer); every other
     * status, including any redirect, fails with [WebDavHttpException]. Invalid ids / segments, or a
     * server URL without a uploads collection, fail without a request. Cancellable, no redirects.
     */
    suspend fun createChunkedUpload(
        transferId: String,
        folderName: String,
        fileName: String,
        totalLength: Long,
    ): Result<ChunkedUploadCreation> =
        runCatching {
            val request =
                chunkedRequest(transferId, folderName, fileName, totalLength) { it.method("MKCOL", null) }
            executeCancellable(uploadClient.newCall(request)) { response, _ ->
                when (response.code) {
                    HTTP_CREATED -> ChunkedUploadCreation.Created
                    HTTP_METHOD_NOT_ALLOWED -> ChunkedUploadCreation.AlreadyExists
                    else -> throw WebDavHttpException(response.code, "Upload folder failed: HTTP ${response.code}")
                }
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Sends chunk [chunkNumber] of the transfer: exactly [chunkLength] bytes read from [stream],
     * which stays open (the caller reads the next chunk from it). A stream that ends early fails
     * with an [IOException]. Never asks for a server hash. Any non-2xx status fails with
     * [WebDavHttpException]. Cancellable, no redirects, no call timeout.
     */
    suspend fun uploadChunk(
        transferId: String,
        chunkNumber: Int,
        folderName: String,
        fileName: String,
        totalLength: Long,
        stream: InputStream,
        chunkLength: Long,
    ): Result<Unit> =
        runCatching {
            val request =
                chunkedRequest(transferId, folderName, fileName, totalLength, chunkName(chunkNumber)) {
                    it.put(BoundedInputStreamRequestBody(stream, chunkLength))
                }
            executeCancellable(uploadClient.newCall(request)) { response, _ ->
                if (!response.isSuccessful) {
                    throw WebDavHttpException(response.code, "Chunk upload failed: HTTP ${response.code}")
                }
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Assembles the transfer into its destination with `MOVE <transfer>/.file`. Only 201 / 204 are
     * success. With [requestServerHash] the request carries `X-Hash: sha256` (Nextcloud hashes the
     * assembled file on regular storage) and the answer's `X-Hash-SHA256` is returned if it is a
     * well-formed SHA-256. Runs on [assemblyClient].
     */
    suspend fun finishChunkedUpload(
        transferId: String,
        folderName: String,
        fileName: String,
        totalLength: Long,
        requestServerHash: Boolean = false,
    ): Result<UploadResponse> =
        runCatching {
            val request =
                chunkedRequest(transferId, folderName, fileName, totalLength, CHUNKED_ASSEMBLED_NAME) {
                    it.method("MOVE", null)
                    if (requestServerHash) it.header(HEADER_REQUEST_HASH, HASH_ALGORITHM_SHA256)
                }
            executeCancellable(assemblyClient.newCall(request)) { response, _ ->
                if (response.code != HTTP_CREATED && response.code != HTTP_NO_CONTENT) {
                    throw WebDavHttpException(response.code, "Assembling failed: HTTP ${response.code}")
                }
                UploadResponse(serverSha256 = response.header(HEADER_RESPONSE_SHA256)?.takeIf(::isSha256Hex))
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * Removes the upload folder of [transferId] (`DELETE`). 2xx and 404 (already gone) are success.
     * Best-effort cleanup: the caller bounds it in time and ignores its failure.
     */
    suspend fun abortChunkedUpload(transferId: String): Result<Unit> =
        runCatching {
            val url = chunkedTransferUrl(transferId)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .delete()
                    .build()
            executeCancellable(uploadClient.newCall(request)) { response, _ ->
                if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) {
                    throw WebDavHttpException(response.code, "Aborting the upload failed: HTTP ${response.code}")
                }
            }
        }.onFailure { if (it is CancellationException) throw it }

    /**
     * The uploads collection and the destination URL of `folderName/fileName` for a chunked upload,
     * or `null` when the server URL is not a Nextcloud files URL or a segment is unsafe (the file
     * then uses one `PUT`).
     */
    internal fun chunkedLocations(
        folderName: String,
        fileName: String,
    ): ChunkedLocations? =
        runCatching {
            val collection = requireNotNull(nextcloudUploadsCollection(config.url))
            ChunkedLocations(collection.toString(), buildPathUrl(remoteFileSegments(folderName, fileName)).toString())
        }.getOrNull()

    /** `<uploads collection>/<transferId>`; invalid ids or no uploads collection fail before any request. */
    private fun chunkedTransferUrl(
        transferId: String,
        vararg more: String,
    ): HttpUrl {
        require(isValidTransferId(transferId)) { "Invalid transfer id" }
        val collection = requireNotNull(nextcloudUploadsCollection(config.url)) { "No Nextcloud uploads collection" }
        return collection
            .newBuilder()
            .addPathSegment(transferId)
            .apply { more.forEach(::addPathSegment) }
            .build()
    }

    /**
     * The request of one chunked step to `<collection>/<transferId>[/<name>]`: `Destination` (the
     * absolute target URL, built from validated segments) and `OC-Total-Length`, then the method and
     * body set by [configure].
     */
    private fun chunkedRequest(
        transferId: String,
        folderName: String,
        fileName: String,
        totalLength: Long,
        name: String? = null,
        configure: (Request.Builder) -> Unit,
    ): Request {
        val segments = remoteFileSegments(folderName, fileName)
        val url = if (name == null) chunkedTransferUrl(transferId) else chunkedTransferUrl(transferId, name)
        val builder =
            Request
                .Builder()
                .url(url)
                .header(HEADER_DESTINATION, buildPathUrl(segments).toString())
                .header(HEADER_TOTAL_LENGTH, totalLength.toString())
        configure(builder)
        return builder.build()
    }

    private fun remoteFileSegments(
        folderName: String,
        fileName: String,
    ): List<String> {
        val folderSegments = folderName.split("/").filter { it.isNotBlank() }
        folderSegments.forEach(::validatePathSegment)
        validatePathSegment(fileName)
        return folderSegments + fileName
    }

    private fun buildPathUrl(segments: List<String>): HttpUrl =
        requireBaseHttpUrl()
            .newBuilder()
            .apply {
                segments.forEach(::addPathSegment)
            }.build()

    private fun requireBaseHttpUrl(): HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid WebDAV URL" }

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

    /**
     * One-shot body of a chunk: copies exactly [chunkLength] bytes from the shared [input] and never
     * closes it (the next chunk continues there). A stream that ends early fails with an [IOException].
     */
    @VisibleForTesting
    internal class BoundedInputStreamRequestBody(
        private val input: InputStream,
        private val chunkLength: Long,
    ) : RequestBody() {
        override fun contentType(): MediaType = CHUNK_MEDIA_TYPE

        override fun contentLength(): Long = chunkLength

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = chunkLength
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) throw IOException("Stream ended before the chunk was complete")
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    companion object {
        /** Read timeout of [assemblyClient]: the server assembles the whole file before it answers. */
        const val CHUNK_ASSEMBLY_READ_TIMEOUT_MINUTES = 30L

        private val CHUNK_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private const val CHUNKED_ASSEMBLED_NAME = ".file"
        private const val HEADER_DESTINATION = "Destination"
        private const val HEADER_TOTAL_LENGTH = "OC-Total-Length"
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NOT_FOUND = 404

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

/** Where a chunked upload collects its chunks and where the finished file lands (absolute URLs). */
internal data class ChunkedLocations(
    val collectionUrl: String,
    val destinationUrl: String,
)

/** Outcome of creating the upload folder of a chunked transfer. */
enum class ChunkedUploadCreation {
    /** 201: a new upload folder. */
    Created,

    /** 405: the folder exists already (a resumed transfer). */
    AlreadyExists,
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
