package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.os.Build
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

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

    suspend fun testConnection(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(baseUrl)
                        .method("PROPFIND", null)
                        .header("Depth", "0")
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        error("Connection failed: HTTP ${response.code} ${response.message}")
                    }
                }
            }
        }

    /**
     * Creates all path segments leading to [folderPath].
     * E.g. "Photos/KrohnSync/Summer 2026" creates each segment in order,
     * ignoring 405 (already exists) at each step.
     */
    suspend fun createDirectory(folderPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val segments = folderPath.split("/").filter { it.isNotBlank() }
                val cumulativeSegments = mutableListOf<String>()
                for (segment in segments) {
                    validatePathSegment(segment)
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
                                "Failed to create directory '${cumulativeSegments.joinToString("/")}': HTTP ${response.code} ${response.message}",
                            )
                        }
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

    private fun buildPathUrl(segments: List<String>): HttpUrl =
        requireBaseHttpUrl()
            .newBuilder()
            .apply {
                segments.forEach(::addPathSegment)
            }.build()

    private fun requireBaseHttpUrl(): HttpUrl =
        requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid WebDAV URL" }

    private fun validatePathSegment(segment: String) {
        require(segment.isNotBlank()) { "Invalid path segment: blank" }
        require(segment != "." && segment != "..") { "Invalid path segment: '$segment'" }
        require('\u0000' !in segment) { "Invalid path segment contains NUL byte" }
    }

    private class InputStreamRequestBody(
        private val mediaType: MediaType,
        private val input: InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
            }
        }
    }
}
