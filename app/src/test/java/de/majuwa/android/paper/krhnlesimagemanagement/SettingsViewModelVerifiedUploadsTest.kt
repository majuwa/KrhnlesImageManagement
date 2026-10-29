package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * Verified upload records are bound to the account (review finding 5): a server URL or username
 * change on save clears them, logout clears them first and one failing clear never skips another;
 * a failing "verify by download" write never crashes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelVerifiedUploadsTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeCredentials: FakeCredentialRepository
    private lateinit var fakeUploadedPhotos: FakeUploadedPhotosRepository
    private lateinit var fakeVerifiedUploads: FakeVerifiedUploadsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeCredentials =
            FakeCredentialRepository(
                WebDavConfig(
                    url = "https://cloud.example.com/remote.php/dav/files/user/",
                    username = "testuser",
                    password = "testpass",
                    baseFolder = "Photos",
                ),
            )
        fakeUploadedPhotos = FakeUploadedPhotosRepository()
        fakeVerifiedUploads = FakeVerifiedUploadsRepository()
        viewModel =
            SettingsViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialStore = fakeCredentials,
                uploadedPhotosRepository = fakeUploadedPhotos,
                verifiedUploadsRepository = fakeVerifiedUploads,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedVerified() =
        fakeVerifiedUploads.recordVerified(
            VerifiedUpload(1L, "content://media/external/images/media/1", 1L, 1L, "u", "f", "a.jpg", 1L),
        )

    @Test
    fun `logout clears the verified records first and a failing clear never skips the others`() =
        runTest {
            seedVerified()
            fakeUploadedPhotos.markAsUploaded(setOf(1L))
            fakeCredentials.clearFailure = IOException("disk full")
            advanceUntilIdle()

            viewModel.logout()
            advanceUntilIdle()

            assertTrue(fakeVerifiedUploads.current.isEmpty())
            assertTrue(fakeUploadedPhotos.uploadedPhotoIds.first().isEmpty())
        }

    @Test
    fun `a failing verified clear on logout still clears the credentials and markers`() =
        runTest {
            fakeVerifiedUploads.clearFailure = IOException("disk full")
            fakeUploadedPhotos.markAsUploaded(setOf(1L))
            advanceUntilIdle()

            viewModel.logout()
            advanceUntilIdle()

            assertEquals(1, fakeVerifiedUploads.clearCalls)
            assertFalse(fakeCredentials.webDavConfig.first().isValid)
            assertTrue(fakeUploadedPhotos.uploadedPhotoIds.first().isEmpty())
        }

    @Test
    fun `saving another server URL or username clears the verified records`() =
        runTest {
            val cases =
                listOf(
                    "https://other.example.com/remote.php/dav/files/user/" to "testuser",
                    "https://cloud.example.com/remote.php/dav/files/user/" to "otheruser",
                )
            cases.forEach { (url, user) ->
                fakeCredentials.setConfig(
                    WebDavConfig("https://cloud.example.com/remote.php/dav/files/user/", "testuser", "testpass"),
                )
                seedVerified()
                viewModel.toggleManualConfig()
                viewModel.onManualUrlChange(url)
                viewModel.onManualUsernameChange(user)
                viewModel.onManualPasswordChange("pw")
                viewModel.saveManualConfig()
                advanceUntilIdle()

                assertTrue("$url / $user", fakeVerifiedUploads.current.isEmpty())
                assertEquals(url, fakeCredentials.webDavConfig.first().url)
                viewModel.toggleManualConfig()
            }
        }

    @Test
    fun `saving the same server and username with a new password keeps the verified records`() =
        runTest {
            seedVerified()
            advanceUntilIdle()
            viewModel.toggleManualConfig()
            viewModel.onManualUrlChange("https://cloud.example.com/remote.php/dav/files/user/")
            viewModel.onManualUsernameChange("testuser")
            viewModel.onManualPasswordChange("new-password")
            viewModel.saveManualConfig()
            advanceUntilIdle()

            assertEquals(0, fakeVerifiedUploads.clearCalls)
            assertEquals(1, fakeVerifiedUploads.current.size)
            assertEquals("new-password", fakeCredentials.webDavConfig.first().password)
        }

    @Test
    fun `a failing verify by download write does not crash and keeps the stored value`() =
        runTest {
            fakeCredentials.verifyByDownloadSaveFailure = IOException("disk full")
            advanceUntilIdle()

            viewModel.setVerifyByDownload(true)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.verifyByDownload)
            assertFalse(fakeCredentials.verifyByDownload.first())
        }
}
