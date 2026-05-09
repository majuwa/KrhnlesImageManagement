package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeCredentials: FakeCredentialRepository
    private lateinit var fakeUploadedPhotos: FakeUploadedPhotosRepository
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
        val app = RuntimeEnvironment.getApplication() as Application
        viewModel =
            SettingsViewModel(
                application = app,
                credentialStore = fakeCredentials,
                uploadedPhotosRepository = fakeUploadedPhotos,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads existing config into UI state`() =
        runTest {
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("https://cloud.example.com/remote.php/dav/files/user/", state.webDavUrl)
            assertEquals("testuser", state.username)
            assertEquals("Photos", state.baseFolder)
            assertTrue(state.isLoggedIn)
        }

    @Test
    fun `onServerUrlChange updates server URL and clears error`() {
        viewModel.onServerUrlChange("https://new.server.com")
        val state = viewModel.uiState.value
        assertEquals("https://new.server.com", state.serverUrl)
        assertEquals(null, state.error)
    }

    @Test
    fun `startLoginFlow with blank URL sets error`() =
        runTest {
            viewModel.onServerUrlChange("")
            viewModel.startLoginFlow()
            advanceUntilIdle()

            assertEquals("Server URL is required", viewModel.uiState.value.error)
        }

    @Test
    fun `toggleManualConfig toggles flag`() {
        assertFalse(viewModel.uiState.value.useManualConfig)
        viewModel.toggleManualConfig()
        assertTrue(viewModel.uiState.value.useManualConfig)
        viewModel.toggleManualConfig()
        assertFalse(viewModel.uiState.value.useManualConfig)
    }

    @Test
    fun `onManualUrlChange updates URL`() {
        viewModel.onManualUrlChange("https://manual.com")
        assertEquals("https://manual.com", viewModel.uiState.value.manualUrl)
    }

    @Test
    fun `onManualUsernameChange updates username`() {
        viewModel.onManualUsernameChange("admin")
        assertEquals("admin", viewModel.uiState.value.manualUsername)
    }

    @Test
    fun `onManualPasswordChange updates password`() {
        viewModel.onManualPasswordChange("secret")
        assertEquals("secret", viewModel.uiState.value.manualPassword)
    }

    @Test
    fun `saveManualConfig with empty fields sets error`() =
        runTest {
            viewModel.onManualUrlChange("")
            viewModel.onManualUsernameChange("")
            viewModel.onManualPasswordChange("")
            viewModel.saveManualConfig()
            advanceUntilIdle()

            assertEquals("Please fill in all fields.", viewModel.uiState.value.error)
        }

    @Test
    fun `saveManualConfig saves valid config`() =
        runTest {
            viewModel.onManualUrlChange("cloud.example.com/dav")
            viewModel.onManualUsernameChange("admin")
            viewModel.onManualPasswordChange("secret")
            viewModel.saveManualConfig()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isLoggedIn)
            assertEquals("https://cloud.example.com/dav", viewModel.uiState.value.manualUrl)
        }

    @Test
    fun `saveBaseFolder persists via credential store`() =
        runTest {
            viewModel.onBaseFolderChange("NewFolder")
            viewModel.saveBaseFolder()
            advanceUntilIdle()

            assertEquals("NewFolder", viewModel.uiState.value.baseFolder)
        }

    @Test
    fun `setAutoDateFoldersEnabled persists via credential store`() =
        runTest {
            viewModel.setAutoDateFoldersEnabled(true)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.autoDateFoldersEnabled)
        }

    @Test
    fun `testConnection cancels config flow after first emission`() =
        runTest {
            val trackingCredentials = TrackingCredentialRepository()
            val app = RuntimeEnvironment.getApplication() as Application
            val trackingViewModel =
                SettingsViewModel(
                    application = app,
                    credentialStore = trackingCredentials,
                )

            advanceUntilIdle()
            val collectorsBeforeTest = trackingCredentials.activeCollectors.get()
            trackingViewModel.testConnection()
            advanceUntilIdle()

            assertTrue(collectorsBeforeTest > 0)
            assertEquals(collectorsBeforeTest, trackingCredentials.activeCollectors.get())
            assertFalse(trackingViewModel.uiState.value.isTesting)
            assertEquals("Not configured.", trackingViewModel.uiState.value.testResult)
        }

    @Test
    fun `logout clears credentials and resets UI state`() =
        runTest {
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoggedIn)

            viewModel.logout()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoggedIn)
            assertEquals("", viewModel.uiState.value.webDavUrl)
            assertFalse(viewModel.uiState.value.autoDateFoldersEnabled)
        }

    @Test
    fun `logout clears uploaded photo tracking`() =
        runTest {
            fakeUploadedPhotos.markAsUploaded(setOf(1L, 2L, 3L))
            advanceUntilIdle()

            viewModel.logout()
            advanceUntilIdle()

            assertTrue(fakeUploadedPhotos.uploadedPhotoIds.first().isEmpty())
        }

    @Test
    fun `onServerUrlChange sets httpWarning for explicit http scheme`() {
        viewModel.onServerUrlChange("http://mycloud.local")
        assertTrue(viewModel.uiState.value.httpWarning)
    }

    @Test
    fun `onServerUrlChange clears httpWarning for https`() {
        viewModel.onServerUrlChange("http://mycloud.local")
        viewModel.onServerUrlChange("https://mycloud.local")
        assertFalse(viewModel.uiState.value.httpWarning)
    }

    @Test
    fun `onServerUrlChange no warning when no scheme provided`() {
        viewModel.onServerUrlChange("mycloud.local")
        assertFalse(viewModel.uiState.value.httpWarning)
    }

    @Test
    fun `onManualUrlChange sets httpWarning for explicit http scheme`() {
        viewModel.onManualUrlChange("http://192.168.1.1/dav")
        assertTrue(viewModel.uiState.value.httpWarning)
    }

    @Test
    fun `onManualUrlChange clears httpWarning for https`() {
        viewModel.onManualUrlChange("http://192.168.1.1/dav")
        viewModel.onManualUrlChange("https://192.168.1.1/dav")
        assertFalse(viewModel.uiState.value.httpWarning)
    }

    @Test
    fun `wifiOnly defaults to false`() =
        runTest {
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.wifiOnly)
        }

    @Test
    fun `setWifiOnly true persists and reflects in UI state`() =
        runTest {
            advanceUntilIdle()
            viewModel.setWifiOnly(true)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.wifiOnly)
            assertTrue(fakeCredentials.wifiOnlyValue())
        }

    @Test
    fun `setWifiOnly false after true resets to false`() =
        runTest {
            advanceUntilIdle()
            viewModel.setWifiOnly(true)
            advanceUntilIdle()
            viewModel.setWifiOnly(false)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.wifiOnly)
            assertFalse(fakeCredentials.wifiOnlyValue())
        }

    private class TrackingCredentialRepository : CredentialRepository {
        val activeCollectors = AtomicInteger(0)

        override val autoDateFoldersEnabled: Flow<Boolean> =
            flow {
                emit(false)
            }

        override val webDavConfig: Flow<WebDavConfig> =
            flow {
                activeCollectors.incrementAndGet()
                try {
                    emit(WebDavConfig())
                    awaitCancellation()
                } finally {
                    activeCollectors.decrementAndGet()
                }
            }

        override val wifiOnly: Flow<Boolean> =
            flow {
                emit(false)
            }

        override val isConfigured: Flow<Boolean> =
            flow {
                emit(false)
            }

        override fun password(): String? = null

        override fun isLoggedIn(): Boolean = false

        override suspend fun save(
            serverUrl: String,
            username: String,
            password: String,
        ) = Unit

        override suspend fun saveBaseFolder(folder: String) = Unit

        override suspend fun saveWifiOnly(wifiOnly: Boolean) = Unit

        override suspend fun saveAutoDateFolders(enabled: Boolean) = Unit

        override suspend fun clear() = Unit
    }
}
