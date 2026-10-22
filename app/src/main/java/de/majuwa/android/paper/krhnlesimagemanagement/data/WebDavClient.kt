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
                val authenticated =
                    original
                        .newBuilder()
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", userAgent)
                        .build()
                chain.proceed(authenticated)
            }.build()

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
    suspend fun createDirectory(folderPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val segments = folderPath.split("/").filter { it.isNotBlank() }
                segments.forEach(::validatePathSegment)
                val cumulativeSegments = mutableListOf<String>()
                for (segment in segments) {
                    cumulativeSegments += segment
                    val request =
                        Request
                            .Builder()
                            .url(buildPathUrl(cumulativeSegments))
                            .method("MKCOL", null)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful && response.code != 405) {
                            error(
                                "Failed to create directory '${cumulativeSegments.joinToString(
                                    "/",
                                )}': HTTP ${response.code} ${response.message}",
                            )
                        }
                    }
                }
            }
        }

    suspend fun renameDirectory(
        oldPath: String,
        newPath: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val oldSegments = oldPath.split("/").filter { it.isNotBlank() }
                val newSegments = newPath.split("/").filter { it.isNotBlank() }
                require(oldSegments.isNotEmpty()) { "Invalid path segment: blank" }
                require(newSegments.isNotEmpty()) { "Invalid path segment: blank" }
                oldSegments.forEach(::validatePathSegment)
                newSegments.forEach(::validatePathSegment)

                val sourceUrl = buildPathUrl(oldSegments)
                val destinationUrl = buildPathUrl(newSegments)
                val request =
                    Request
                        .Builder()
                        .url(sourceUrl)
                        .method("MOVE", null)
                        .header("Destination", destinationUrl.toString())
                        .header("Overwrite", "F")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Rename failed: HTTP ${response.code} ${response.message}")
                    }
                }
            }
        }

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
        require('\u0000' !in segment) { "Invalid path segment contains NUL byte" }
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
    }
}

/** A WebDAV request completed but the server answered with the non-success HTTP status [code]. */
class WebDavHttpException(
    val code: Int,
    message: String,
) : IOException(message)
