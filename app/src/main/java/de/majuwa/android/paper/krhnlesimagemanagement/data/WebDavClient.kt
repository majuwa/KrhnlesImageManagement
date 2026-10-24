package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.os.Build
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
                // Credentials never leave the configured origin (e.g. after a redirect).
                if (original.url.hasSameOriginAs(config.url)) {
                    builder.header("Authorization", Credentials.basic(config.username, config.password))
                }
                chain.proceed(builder.build())
            }.build()

    /** Shares pool and interceptors with [client]; used where a redirect must count as failure. */
    private val noRedirectClient: OkHttpClient by lazy {
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
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

    suspend fun uploadFile(
        folderName: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val folderSegments = folderName.split("/").filter { it.isNotBlank() }
                folderSegments.forEach(::validatePathSegment)
                validatePathSegment(fileName)
                val body = InputStreamRequestBody(mimeType.toMediaType(), inputStream)
                val request =
                    Request
                        .Builder()
                        .url(buildPathUrl(folderSegments + fileName))
                        .put(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Upload failed: HTTP ${response.code} ${response.message}")
                    }
                }
            }
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

    private class InputStreamRequestBody(
        private val mediaType: MediaType,
        private val input: InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

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

        /** 2xx, including 207 Multi-Status, which is the normal PROPFIND answer. */
        private val HTTP_SUCCESS_CODES = 200..299
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
    }
}

/** A WebDAV request completed but the server answered with the non-success HTTP status [code]. */
class WebDavHttpException(
    val code: Int,
    message: String,
) : IOException(message)
