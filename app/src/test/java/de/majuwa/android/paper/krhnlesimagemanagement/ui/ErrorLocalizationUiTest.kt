package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import androidx.work.Configuration as WorkConfiguration

/**
 * Typed UI errors are resolved in Compose (task 5.2, automated part): the error text follows the
 * resources of the composition, also for an error that was already visible when the language changed,
 * and the photo grid error state offers an explicit Retry (a pull-to-refresh over a non-scrollable
 * box never reacts).
 */
@RunWith(RobolectricTestRunner::class)
class ErrorLocalizationUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private val media = FakeMediaRepository()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(app, WorkConfiguration.Builder().build())
    }

    private fun resources(locale: Locale): Resources {
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocale(locale)
        return app.createConfigurationContext(configuration).resources
    }

    private fun photoGridViewModel() =
        PhotoGridViewModel(
            application = app,
            mediaRepository = media,
            credentialStore = FakeCredentialRepository(),
            uploadedPhotosRepository = FakeUploadedPhotosRepository(),
            mediaPermissions = FakeMediaPermissionRepository(),
        )

    @androidx.compose.runtime.Composable
    private fun ShowGrid(viewModel: PhotoGridViewModel) =
        PhotoGridScreen(viewModel, onNavigateToSettings = {}, onNavigateToUploadHistory = {}, onStartUpload = {})

    private fun settingsViewModel() =
        SettingsViewModel(
            application = app,
            credentialStore = FakeCredentialRepository(),
            trustedCertificates = FakeTrustedCertificateRepository(),
        )

    @Test
    fun `photo grid error is shown in english and german without pull-down wording`() {
        media.shouldThrow = RuntimeException("disk error")
        val viewModel = photoGridViewModel()
        var locale by mutableStateOf(Locale.ENGLISH)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalResources provides resources(locale)) {
                ShowGrid(viewModel)
            }
        }
        viewModel.loadPhotos()
        composeTestRule.waitForIdle()

        val english = resources(Locale.ENGLISH).getString(R.string.error_photos_load_failed)
        val german = resources(Locale.GERMAN).getString(R.string.error_photos_load_failed)
        composeTestRule.onNodeWithText(english).assertIsDisplayed()

        // The error stays in the state; only the resources change.
        locale = Locale.GERMAN
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(german).assertIsDisplayed()
        assertEquals(false, german.contains("nach unten"))
        assertEquals(false, english.contains("Pull down"))
    }

    @Test
    fun `photo grid retry button loads the photos again`() {
        media.shouldThrow = RuntimeException("disk error")
        val viewModel = photoGridViewModel()
        composeTestRule.setContent {
            ShowGrid(viewModel)
        }
        viewModel.loadPhotos()
        composeTestRule.waitForIdle()
        val failed = app.getString(R.string.error_photos_load_failed)
        composeTestRule.onNodeWithText(failed).assertIsDisplayed()
        val loadsBefore = media.loadCount

        media.shouldThrow = null
        composeTestRule.onNodeWithText(app.getString(R.string.action_retry)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(loadsBefore + 1, media.loadCount)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `a failed refresh leaves a retry that recovers`() {
        val viewModel = photoGridViewModel()
        composeTestRule.setContent {
            ShowGrid(viewModel)
        }
        viewModel.loadPhotos()
        composeTestRule.waitForIdle()
        media.shouldThrow = RuntimeException("disk error")
        viewModel.refreshPhotos()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(app.getString(R.string.action_retry)).assertIsDisplayed()

        media.shouldThrow = null
        composeTestRule.onNodeWithText(app.getString(R.string.action_retry)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `settings error follows the language also when shown before the switch`() {
        val viewModel = settingsViewModel()
        var locale by mutableStateOf(Locale.ENGLISH)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalResources provides resources(locale)) {
                SettingsScreen(viewModel = viewModel, onNavigateBack = {})
            }
        }
        viewModel.startLoginFlow() // blank URL -> ServerUrlRequired
        composeTestRule.waitForIdle()

        val english = resources(Locale.ENGLISH).getString(R.string.error_server_url_required)
        val german = resources(Locale.GERMAN).getString(R.string.error_server_url_required)
        composeTestRule.onNodeWithText(english).assertIsDisplayed()
        locale = Locale.GERMAN
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(german).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `settings error renders in german under the de qualifier`() {
        val viewModel = settingsViewModel()
        composeTestRule.setContent { SettingsScreen(viewModel = viewModel, onNavigateBack = {}) }
        viewModel.startLoginFlow()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(app.getString(R.string.error_server_url_required)).assertIsDisplayed()
        assertEquals(
            "de",
            app.resources.configuration.locales[0]
                .language,
        )
    }
}
