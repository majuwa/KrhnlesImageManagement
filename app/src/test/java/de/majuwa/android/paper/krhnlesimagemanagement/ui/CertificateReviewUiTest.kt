package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateReviewException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.net.ssl.SSLHandshakeException

/** Review card -> confirmation dialog -> the ViewModel stores the pin; trusted row -> stop trusting. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class CertificateReviewUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun certificate(notAfter: Long = Long.MAX_VALUE / 2) =
        ServerCertificate(
            ServerOrigin("cloud.example.com", 443),
            "ab".repeat(32).decodeHex(),
            "CN=a",
            "CN=b",
            0L,
            notAfter,
        )

    private fun viewModel(
        trusted: FakeTrustedCertificateRepository,
        certificate: ServerCertificate?,
    ) = SettingsViewModel(
        application = RuntimeEnvironment.getApplication() as Application,
        credentialStore =
            FakeCredentialRepository(WebDavConfig("https://cloud.example.com/dav/", "testuser", "pass", "Photos")),
        trustedCertificates = trusted,
        connectionTester = { _, pin ->
            if (certificate != null && pin == null) {
                Result.failure(CertificateReviewException(certificate, false, null, SSLHandshakeException("x")))
            } else {
                Result.success(Unit)
            }
        },
    )

    @Test
    fun `review card to confirmation dialog stores the certificate`() {
        val trusted = FakeTrustedCertificateRepository()
        val vm = viewModel(trusted, certificate())
        composeTestRule.setContent { SettingsScreen(viewModel = vm, onNavigateBack = {}) }
        composeTestRule.onNodeWithText("Test Connection").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("certificate_review").assertExists()
        composeTestRule.onNodeWithTag("trust_certificate").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("confirm_trust").performClick()
        composeTestRule.waitForIdle()
        assertEquals(certificate(), trusted.current!!.certificate)
    }

    @Test
    fun `an expired certificate offers no trust action`() {
        val vm = viewModel(FakeTrustedCertificateRepository(), certificate(notAfter = 1L))
        composeTestRule.setContent { SettingsScreen(viewModel = vm, onNavigateBack = {}) }
        composeTestRule.onNodeWithText("Test Connection").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("certificate_review").assertExists()
        composeTestRule.onNodeWithTag("trust_certificate").assertDoesNotExist()
    }

    @Test
    fun `the trusted certificate row stops trusting`() {
        val trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
        val vm = viewModel(trusted, null)
        composeTestRule.setContent { SettingsScreen(viewModel = vm, onNavigateBack = {}) }
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithTag("stop_trusting")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        assertNull(trusted.current)
    }
}
