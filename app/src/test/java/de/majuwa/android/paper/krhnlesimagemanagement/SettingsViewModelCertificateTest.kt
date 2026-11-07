package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.CertificateReviewException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.ConnectionTestResult
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString.Companion.decodeHex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelCertificateTest {
    private val dispatcher = StandardTestDispatcher()
    private val url = "https://cloud.example/remote.php/dav/files/user/"
    private val origin = ServerOrigin("cloud.example", 443)
    private val farFuture = Long.MAX_VALUE / 2
    private lateinit var credentials: FakeCredentialRepository
    private lateinit var trusted: FakeTrustedCertificateRepository
    private val seenPins = mutableListOf<PinnedCertificate?>()
    private var testerFailure: Throwable? = null
    private var testerGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private fun certificate(
        hex: String = "ab",
        origin: ServerOrigin = this.origin,
        notAfter: Long = farFuture,
        keyAlgorithm: String = "RSA",
        keyBits: Int = 2048,
    ) = ServerCertificate(origin, hex.repeat(32).decodeHex(), "CN=a", "CN=b", 0L, notAfter, keyAlgorithm, keyBits)

    private fun SettingsViewModel.confirm() = certificateTrust.confirm(uiState.value.certificateReview!!)

    private fun viewModel() =
        SettingsViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            credentialStore = credentials,
            uploadedPhotosRepository = FakeUploadedPhotosRepository(),
            verifiedUploadsRepository = FakeVerifiedUploadsRepository(),
            trustedCertificates = trusted,
            connectionTester = { _, pin ->
                seenPins += pin
                testerGate?.await()
                testerFailure?.let { Result.failure(it) } ?: Result.success(Unit)
            },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        credentials = FakeCredentialRepository(WebDavConfig(url, "user", "pw", "Photos"))
        trusted = FakeTrustedCertificateRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun untrusted(cert: ServerCertificate = certificate()) =
        CertificateReviewException(cert, false, null, SSLHandshakeException("x"))

    @Test
    fun `an untrusted test opens a review`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.testResult is ConnectionTestResult.UntrustedCertificate)
            assertEquals(
                certificate(),
                vm.uiState.value.certificateReview!!
                    .certificate,
            )
            assertFalse(
                vm.uiState.value.certificateReview!!
                    .changed,
            )
        }

    @Test
    fun `a changed review carries the previous fingerprint`() =
        runTest(dispatcher) {
            val previous = "cd".repeat(32).decodeHex()
            testerFailure = CertificateReviewException(certificate(), true, previous, SSLHandshakeException("x"))
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            val review = vm.uiState.value.certificateReview!!
            assertTrue(review.changed)
            assertEquals(previous, review.previousSha256)
        }

    @Test
    fun `confirming stores the pin and runs the test again with it`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            testerFailure = null
            vm.confirm()
            advanceUntilIdle()
            assertEquals(certificate(), trusted.current!!.certificate)
            assertNull(vm.uiState.value.certificateReview)
            assertEquals(ConnectionTestResult.Success, vm.uiState.value.testResult)
            assertEquals(trusted.current!!.pin, seenPins.last())
            assertNotNull(vm.uiState.value.trustedCertificate)
        }

    @Test
    fun `an expired certificate cannot be confirmed`() =
        runTest(dispatcher) {
            testerFailure = untrusted(certificate(notAfter = 1L))
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            assertNull(trusted.current)
        }

    @Test
    fun `a configuration change before confirming stores nothing`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            val review = vm.uiState.value.certificateReview!!
            credentials.save("https://other.example/dav/", "user", "pw")
            advanceUntilIdle()
            assertNull(vm.uiState.value.certificateReview)
            vm.certificateTrust.confirm(review)
            advanceUntilIdle()
            assertNull(trusted.current)
        }

    @Test
    fun `a review for another origin than the configured one is not stored`() =
        runTest(dispatcher) {
            testerFailure = untrusted(certificate(origin = ServerOrigin("evil.example", 443)))
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            assertNull(trusted.current)
            assertTrue(vm.uiState.value.trustSaveFailed)
        }

    @Test
    fun `a write failure shows an error and the user can retry`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            trusted.failure = java.io.IOException("disk")
            vm.confirm()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.trustSaveFailed)
            assertNotNull(vm.uiState.value.certificateReview)
            assertFalse(vm.uiState.value.trustInProgress)
            trusted.failure = null
            testerFailure = null
            vm.confirm()
            advanceUntilIdle()
            assertNotNull(trusted.current)
        }

    @Test
    fun `a write that never finishes times out and releases the guard`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            trusted.hang = true
            vm.confirm()
            advanceTimeBy(6_000)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.trustSaveFailed)
            assertFalse(vm.uiState.value.trustInProgress)
        }

    @Test
    fun `stop trusting clears the pin`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            val vm = viewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.trustedCertificate)
            vm.certificateTrust.stop()
            advanceUntilIdle()
            assertNull(trusted.current)
            assertNull(vm.uiState.value.trustedCertificate)
        }

    @Test
    fun `a pin for another origin is not shown as trusted`() =
        runTest(dispatcher) {
            trusted =
                FakeTrustedCertificateRepository(
                    TrustedCertificateInfo(certificate(origin = ServerOrigin("other.example", 443)), 1L),
                )
            val vm = viewModel()
            advanceUntilIdle()
            assertNull(vm.uiState.value.trustedCertificate)
        }

    @Test
    fun `logout clears the pin even when another clear fails`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            credentials.clearFailure = java.io.IOException("x")
            val vm = viewModel()
            advanceUntilIdle()
            vm.logout()
            advanceUntilIdle()
            assertNull(trusted.current)
        }

    @Test
    fun `saving another server clears the pin but the same origin keeps it`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            val vm = viewModel()
            advanceUntilIdle()
            vm.onManualUrlChange("https://cloud.example/other/path/")
            vm.onManualUsernameChange("someone")
            vm.onManualPasswordChange("new")
            vm.saveManualConfig()
            advanceUntilIdle()
            assertNotNull(trusted.current)
            assertEquals(0, trusted.clearCalls)
            vm.onManualUrlChange("https://other.example/dav/")
            vm.saveManualConfig()
            advanceUntilIdle()
            assertNull(trusted.current)
        }

    @Test
    fun `a failing pin clear does not block saving credentials`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            trusted.failure = java.io.IOException("x")
            val vm = viewModel()
            advanceUntilIdle()
            vm.onManualUrlChange("https://other.example/dav/")
            vm.onManualUsernameChange("u")
            vm.onManualPasswordChange("p")
            vm.saveManualConfig()
            advanceUntilIdle()
            assertEquals("https://other.example/dav/", credentials.webDavConfig.first().url)
        }

    @Test
    fun `an http failure produces no review`() =
        runTest(dispatcher) {
            testerFailure = WebDavHttpException(401, "x")
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            assertNull(vm.uiState.value.certificateReview)
        }

    @Test
    fun `a weak key cannot be confirmed`() =
        runTest(dispatcher) {
            for (weak in listOf(
                certificate(keyBits = 1024),
                certificate(keyAlgorithm = "EC", keyBits = 192),
                certificate(keyAlgorithm = "DSA", keyBits = 3072),
            )) {
                testerFailure = untrusted(weak)
                val vm = viewModel()
                advanceUntilIdle()
                vm.testConnection()
                advanceUntilIdle()
                vm.confirm()
                advanceUntilIdle()
                assertNull(weak.keyAlgorithm + weak.keyBits, trusted.current)
                assertEquals(0, trusted.trustCalls)
            }
        }

    @Test
    fun `an EC 256 key and an RSA 2048 key can be confirmed`() =
        runTest(dispatcher) {
            testerFailure = untrusted(certificate(keyAlgorithm = "EC", keyBits = 256))
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            testerFailure = null
            vm.confirm()
            advanceUntilIdle()
            assertNotNull(trusted.current)
        }

    @Test
    fun `starting a new test clears the review, and a replaced review cannot be confirmed`() =
        runTest(dispatcher) {
            testerFailure = untrusted(certificate("ab"))
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            val shown = vm.uiState.value.certificateReview!!

            testerFailure = untrusted(certificate("cd"))
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            testerGate = gate
            vm.testConnection()
            advanceUntilIdle()
            // The review the user is looking at disappears as soon as the next test starts.
            assertNull(vm.uiState.value.certificateReview)
            // The dialog of the first review confirms nothing while the next test still runs.
            vm.certificateTrust.confirm(shown)
            advanceUntilIdle()
            assertEquals(0, trusted.trustCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "cd".repeat(32),
                vm.uiState.value.certificateReview!!
                    .certificate.sha256
                    .hex(),
            )

            // The dialog of the first review confirms nothing now.
            vm.certificateTrust.confirm(shown)
            advanceUntilIdle()
            assertNull(trusted.current)
            assertEquals(0, trusted.trustCalls)
        }

    @Test
    fun `a second connection test while one runs is ignored`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            vm.testConnection()
            advanceUntilIdle()
            assertEquals(1, seenPins.size)
        }

    @Test
    fun `confirming twice while the write runs stores once`() =
        runTest(dispatcher) {
            testerFailure = untrusted()
            val vm = viewModel()
            advanceUntilIdle()
            vm.testConnection()
            advanceUntilIdle()
            val review = vm.uiState.value.certificateReview!!
            testerFailure = null
            vm.certificateTrust.confirm(review)
            vm.certificateTrust.confirm(review)
            advanceUntilIdle()
            assertEquals(1, trusted.trustCalls)
        }

    @Test
    fun `a pin that cannot be removed at logout is reported`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            trusted.failure = java.io.IOException("x")
            val vm = viewModel()
            advanceUntilIdle()
            vm.logout()
            advanceUntilIdle()
            val application = RuntimeEnvironment.getApplication()
            assertEquals(
                application.getString(R.string.error_trusted_certificate_clear_failed),
                vm.uiState.value.error,
            )
            // The next sign-in has no previous configuration and clears the leftover pin first.
            trusted.failure = null
            vm.onManualUrlChange(url)
            vm.onManualUsernameChange("user")
            vm.onManualPasswordChange("pw")
            vm.saveManualConfig()
            advanceUntilIdle()
            assertNull(trusted.current)
        }

    @Test
    fun `a pin that cannot be removed when the server changes is reported but the save succeeds`() =
        runTest(dispatcher) {
            trusted = FakeTrustedCertificateRepository(TrustedCertificateInfo(certificate(), 1L))
            trusted.failure = java.io.IOException("x")
            val vm = viewModel()
            advanceUntilIdle()
            vm.onManualUrlChange("https://other.example/dav/")
            vm.onManualUsernameChange("u")
            vm.onManualPasswordChange("p")
            vm.saveManualConfig()
            advanceUntilIdle()
            assertEquals(
                RuntimeEnvironment.getApplication().getString(R.string.error_trusted_certificate_clear_failed),
                vm.uiState.value.error,
            )
            assertEquals("https://other.example/dav/", credentials.webDavConfig.first().url)
        }
}
