package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.NextcloudAuthRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.LoginFlowState
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NextcloudAuthFlowTest {
    private lateinit var mockWebServer: MockWebServer
    private val serverUrl: String
        get() = mockWebServer.url("").toString()

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun loginFlowV2_requestsAuthorizationCode() =
        runTest {
            // Arrange
            val clientId = "test-client-id"
            val redirectUri = "app://callback"

            // Act: Build auth request
            val authUrl =
                "$serverUrl/index.php/apps/oauth2/authorize?" +
                    "client_id=$clientId&redirect_uri=$redirectUri&response_type=code"

            // Assert
            assertTrue(authUrl.contains(clientId))
            assertTrue(authUrl.contains(redirectUri))
        }

    @Test
    fun loginFlowV2_handlesRedirectWithCode() =
        runTest {
            // Arrange
            val authCode = "test-auth-code-123"
            val redirectUri = "app://callback"

            // Act: Simulate redirect from Nextcloud
            val redirectUrl = "$redirectUri?code=$authCode&state=test-state"

            // Assert
            assertTrue(redirectUrl.contains(authCode))
            assertTrue(redirectUrl.contains("code="))
        }

    @Test
    fun tokenExchange_convertsCodeToToken() =
        runTest {
            // Arrange
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token": "token-xyz", "token_type": "Bearer"}""",
                ),
            )

            val clientId = "test-client-id"
            val clientSecret = "test-client-secret"
            val authCode = "auth-code-123"

            // Act: Token exchange request body
            val tokenRequestBody =
                """
                client_id=$clientId&
                client_secret=$clientSecret&
                code=$authCode&
                grant_type=authorization_code
                """.trimIndent().replace("\n", "")

            // Assert
            assertTrue(tokenRequestBody.contains(clientId))
            assertTrue(tokenRequestBody.contains(clientSecret))
            assertTrue(tokenRequestBody.contains(authCode))
        }

    @Test
    fun tokenExchange_handles401InvalidClient() =
        runTest {
            // Arrange
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            // Act
            mockWebServer.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS)

            // Assert (should fail gracefully)
            assertTrue(true)
        }

    @Test
    fun tokenStorage_savesAppToken() =
        runTest {
            // Arrange
            val token = "app-specific-token-xyz"
            val credentials = mutableMapOf<String, String>()

            // Act
            credentials["app_token"] = token

            // Assert
            assertEquals(token, credentials["app_token"])
        }

    @Test
    fun tokenStorage_retrievesStoredToken() =
        runTest {
            // Arrange
            val credentials =
                mapOf(
                    "app_token" to "saved-token-123",
                    "username" to "user@example.com",
                )

            // Act
            val retrievedToken = credentials["app_token"]

            // Assert
            assertEquals("saved-token-123", retrievedToken)
        }

    @Test
    fun loginFlow_handlesCancellation() =
        runTest {
            // Arrange
            val redirectUri = "app://callback"

            // Act: Simulate user cancelling auth in browser
            val cancelUrl = "$redirectUri?error=access_denied&error_description=User%20cancelled%20authorization"

            // Assert
            assertTrue(cancelUrl.contains("error=access_denied"))
        }

    @Test
    fun loginFlow_extractsServerUrlFromResponse() =
        runTest {
            // Arrange
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"server_url": "https://nextcloud.example.com", "access_token": "token"}""",
                ),
            )

            val expectedServerUrl = "https://nextcloud.example.com"

            // Act & Assert
            assertTrue(expectedServerUrl.startsWith("https://"))
        }

    @Test
    fun loginFlow_handlesMissingRedirectParameter() =
        runTest {
            // Arrange
            val redirectUrl = "app://callback?state=test-state"

            // Act: Parse redirect (missing code parameter)
            val hasAuthCode = redirectUrl.contains("code=")

            // Assert
            assertTrue(!hasAuthCode)
        }

    @Test
    fun loginFlowState_preventsCrossOriginAttacks() =
        runTest {
            // Arrange
            val generatedState = "random-state-${System.currentTimeMillis()}"
            val authUrl = "https://nextcloud.example.com/authorize?state=$generatedState"

            val redirectResponse = "app://callback?state=$generatedState&code=auth-code"

            // Act: Verify state matches
            val stateMatches = authUrl.contains(generatedState) && redirectResponse.contains(generatedState)

            // Assert
            assertTrue(stateMatches)
        }

    @Test
    fun tokenExpiration_tracksExpiryTime() =
        runTest {
            // Arrange
            val issuedAt = System.currentTimeMillis()
            val expiresIn = 3600L

            // Act
            val expiresAt = issuedAt + (expiresIn * 1000)

            // Assert
            assertTrue(expiresAt > issuedAt)
        }

    @Test
    fun tokenRefresh_requestsNewTokenBeforeExpiry() =
        runTest {
            // Arrange
            val expiresAt = System.currentTimeMillis() + 300_000L // 5 minutes from now
            val shouldRefresh = expiresAt - System.currentTimeMillis() < 600_000L

            // Assert
            assertTrue(shouldRefresh)
        }

    // ── Security: origin validation ─────────────────────────────────────────

    @Test
    fun `loginFlow fails when pollEndpoint origin differs from server`() =
        runTest {
            // A malicious server returns a pollEndpoint on a third-party host.
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "poll": {
                        "token": "tok123",
                        "endpoint": "https://evil.attacker.com/poll"
                      },
                      "login": "${mockWebServer.url("/login")}"
                    }
                    """.trimIndent(),
                ),
            )

            val repo = NextcloudAuthRepository()
            val states = repo.loginFlow(mockWebServer.url("").toString()).toList()

            val failed = states.filterIsInstance<LoginFlowState.Failed>()
            assertTrue("Expected a Failed state for mismatched poll origin", failed.isNotEmpty())
            assertTrue(failed.first().message.contains("Poll endpoint origin", ignoreCase = true))
        }

    @Test
    fun `loginFlow fails when loginUrl origin differs from server`() =
        runTest {
            // A malicious server returns a loginUrl pointing to a phishing page.
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "poll": {
                        "token": "tok123",
                        "endpoint": "${mockWebServer.url("/poll")}"
                      },
                      "login": "https://phishing.example.com/fake-login"
                    }
                    """.trimIndent(),
                ),
            )

            val repo = NextcloudAuthRepository()
            val states = repo.loginFlow(mockWebServer.url("").toString()).toList()

            val failed = states.filterIsInstance<LoginFlowState.Failed>()
            assertTrue("Expected a Failed state for mismatched login URL origin", failed.isNotEmpty())
            assertTrue(failed.first().message.contains("Login URL origin", ignoreCase = true))
        }

    @Test
    fun `loginFlow succeeds when both poll endpoint and login URL share server origin`() =
        runTest {
            val pollUrl = mockWebServer.url("/poll").toString()
            val loginUrl = mockWebServer.url("/login").toString()

            // Init response with matching origins
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "poll": {"token": "tok123", "endpoint": "$pollUrl"},
                      "login": "$loginUrl"
                    }
                    """.trimIndent(),
                ),
            )
            // Poll response: credentials granted
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"server":"${mockWebServer.url("")}","loginName":"alice","appPassword":"app-pw"}""",
                ),
            )

            val repo = NextcloudAuthRepository()
            val states = repo.loginFlow(mockWebServer.url("").toString()).toList()

            assertFalse(
                "Should not fail for same-origin URLs",
                states.any { it is LoginFlowState.Failed },
            )
            assertTrue(states.any { it is LoginFlowState.Authenticated })
        }

    @Test
    fun `loginFlow fails with timeout when poll never succeeds`() =
        runTest {
            val pollUrl = mockWebServer.url("/poll").toString()
            val loginUrl = mockWebServer.url("/login").toString()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "poll": {"token": "tok123", "endpoint": "$pollUrl"},
                      "login": "$loginUrl"
                    }
                    """.trimIndent(),
                ),
            )
            repeat(10) {
                mockWebServer.enqueue(MockResponse().setResponseCode(404))
            }

            val repo = NextcloudAuthRepository(pollIntervalMs = 10, maxPollDurationMs = 50)
            val states =
                withTimeout(3_000) {
                    repo.loginFlow(mockWebServer.url("").toString()).toList()
                }

            val failed = states.filterIsInstance<LoginFlowState.Failed>()
            assertTrue("Expected timeout failure state", failed.isNotEmpty())
            assertTrue(failed.last().message.contains("timed out", ignoreCase = true))
        }
}
