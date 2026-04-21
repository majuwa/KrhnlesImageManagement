package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.os.Build
import de.majuwa.android.paper.krhnlesimagemanagement.model.LoginFlowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val POLL_INTERVAL_MS = 2_000L
private const val MAX_POLL_DURATION_MS = 120_000L
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 30L

class NextcloudAuthRepository(
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val maxPollDurationMs: Long = MAX_POLL_DURATION_MS,
) {
    private val userAgent = "Kroehnles-Image-Management/1.0 (Android; ${Build.MANUFACTURER} ${Build.MODEL})"

    private val httpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", userAgent)
                        .build(),
                )
            }.build()

    fun loginFlow(serverUrl: String): Flow<LoginFlowState> =
        flow {
            val normalized = serverUrl.trim()
            val baseUrl = (if ("://" !in normalized) "https://$normalized" else normalized).trimEnd('/')

            val initBody =
                httpClient.newCall(buildInitRequest(baseUrl)).execute().use { initResponse ->
                    if (!initResponse.isSuccessful) {
                        emit(LoginFlowState.Failed("Server responded ${initResponse.code}"))
                        return@flow
                    }
                    initResponse.body.string()
                }

            val json = JSONObject(initBody)
            val pollToken = json.getJSONObject("poll").getString("token")
            val pollEndpoint = json.getJSONObject("poll").getString("endpoint")
            val loginUrl = json.getString("login")

            val originError = validateSameOrigin(baseUrl, pollEndpoint, loginUrl)
            if (originError != null) {
                emit(LoginFlowState.Failed(originError))
                return@flow
            }

            emit(LoginFlowState.WaitingForBrowser(loginUrl))

            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < maxPollDurationMs) {
                delay(pollIntervalMs)
                httpClient.newCall(buildPollRequest(pollEndpoint, pollToken)).execute().use { pollResponse ->
                    if (pollResponse.isSuccessful) {
                        val creds = JSONObject(pollResponse.body.string())
                        emit(
                            LoginFlowState.Authenticated(
                                server = creds.getString("server").trimEnd('/'),
                                loginName = creds.getString("loginName"),
                                appPassword = creds.getString("appPassword"),
                            ),
                        )
                        return@flow
                    }
                }
            }
            emit(LoginFlowState.Failed("Login timed out. Please try again."))
        }.catch { e ->
            emit(LoginFlowState.Failed(e.message ?: "Unexpected error"))
        }.flowOn(Dispatchers.IO)

    private fun buildInitRequest(baseUrl: String): Request =
        Request
            .Builder()
            .url("$baseUrl/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("OCS-APIREQUEST", "true")
            .build()

    private fun buildPollRequest(
        pollEndpoint: String,
        pollToken: String,
    ): Request =
        Request
            .Builder()
            .url(pollEndpoint)
            .post(FormBody.Builder().add("token", pollToken).build())
            .build()

    /**
     * Returns an error message if [pollEndpoint] or [loginUrl] do not share the same
     * origin as [baseUrl], or null when all origins match.
     * Prevents token theft and phishing via a compromised server response.
     */
    private fun validateSameOrigin(
        baseUrl: String,
        pollEndpoint: String,
        loginUrl: String,
    ): String? {
        val serverOrigin = buildOrigin(baseUrl)
        if (serverOrigin.isBlank() || buildOrigin(pollEndpoint) != serverOrigin) {
            return "Poll endpoint origin does not match server"
        }
        if (buildOrigin(loginUrl) != serverOrigin) {
            return "Login URL origin does not match server"
        }
        return null
    }
}
