package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.os.Build
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
                var cumulative = ""
                for (segment in segments) {
                    cumulative = if (cumulative.isEmpty()) segment else "$cumulative/$segment"
                    val url = "$baseUrl/$cumulative"
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .method("MKCOL", null)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful && response.code != 405) {
                            error("Failed to create directory '$cumulative': HTTP ${response.code} ${response.message}")
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
                val bytes = inputStream.readBytes()
                val url = "$baseUrl/$folderName/$fileName"
                val body = bytes.toRequestBody(mimeType.toMediaType())
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .put(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Upload failed: HTTP ${response.code} ${response.message}")
                    }
                }
            }
        }
}
