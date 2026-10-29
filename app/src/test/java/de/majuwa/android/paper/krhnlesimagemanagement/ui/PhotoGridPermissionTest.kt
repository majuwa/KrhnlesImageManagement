package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider.Companion.row
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/**
 * Media permission handling of the real [PhotoGridScreen] (issues #15 / #34): permission requests
 * go through a fake [ActivityResultRegistry] that records each launch and answers with [grantOnRequest].
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridPermissionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var workManager: WorkManager
    private val launches = mutableListOf<List<String>>()

    /** Permissions granted when a request is answered; the others are denied. */
    private var grantOnRequest: Set<String> = emptySet()

    /** Runs when a request is answered, before the result (e.g. what the media store now returns). */
    private var onRequest: () -> Unit = {}

    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                val requested = (input as Array<*>).map { it as String }
                launches += requested
                onRequest()
                // The fake media store refuses the video query exactly while video access is missing.
                if (Manifest.permission.READ_MEDIA_VIDEO in grantOnRequest) FakeMediaStoreProvider.videoFailure = null
                val granted = requested.filter { it in grantOnRequest }
                shadowOf(app).grantPermissions(*granted.toTypedArray())
                dispatchResult(requestCode, requested.associateWith { it in grantOnRequest })
            }
        }

    private val day = Instant.parse("2026-05-14T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app).denyPermissions(*MEDIA)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        workManager = WorkManager.getInstance(app)
        FakeMediaStoreProvider.reset()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.imageRows = listOf(row(1, "a.jpg", day, "image/jpeg"))
        FakeMediaStoreProvider.videoRows = listOf(row(1, "b.mp4", day + 1, "video/mp4", durationMs = 5_000))
    }

    @After
    fun tearDown() = FakeMediaStoreProvider.reset()

    private fun string(id: Int) = app.getString(id)

    private fun setScreen(
        permissions: FakeMediaPermissionRepository = FakeMediaPermissionRepository(),
        uploaded: FakeUploadedPhotosRepository = FakeUploadedPhotosRepository(),
    ) {
        val viewModel =
            PhotoGridViewModel(
                app,
                MediaRepository(app.contentResolver),
                FakeCredentialRepository(),
                uploaded,
                workManager,
                permissions,
            )
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
            ) {
                PhotoGridScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    onNavigateToUploadHistory = {},
                    onStartUpload = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun videoTiles() = composeTestRule.onAllNodes(hasContentDescription("Video", substring = true))

    private fun photoTiles() = composeTestRule.onAllNodes(hasContentDescription("Photo", substring = true))

    @Test
    fun upgrade_imagesOnly_asksForVideoOnce_thenShowsBannerAndOnlyPhotos() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        // Video access missing: the video query is not permitted.
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO, true)
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertEquals(listOf(MEDIA.toList()), launches)
        assertTrue(permissions.isRequested())
        // The one combined request also asked for the media location: it is not asked separately.
        assertTrue(permissions.isLocationRequested())
        composeTestRule.onNodeWithText(string(R.string.banner_video_access_missing)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.action_grant_video_access)).assertExists()
        photoTiles().assertCountEquals(1)
        videoTiles().assertCountEquals(0)
    }

    @Test
    fun upgrade_alreadyAsked_doesNotAskAgainButShowsBanner() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")

        setScreen(FakeMediaPermissionRepository(initialRequested = true, initialLocationRequested = true))

        assertTrue(launches.isEmpty())
        composeTestRule.onNodeWithText(string(R.string.banner_video_access_missing)).assertExists()
        // The banner action asks again on request.
        composeTestRule.onNodeWithText(string(R.string.action_grant_video_access)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, launches.size)
    }

    @Test
    fun partialAccess_isNeverAskedAutomatically_andBannerReopensTheRequest() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        grantOnRequest = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        setScreen()

        assertTrue(launches.isEmpty())
        composeTestRule.onNodeWithText(string(R.string.action_select_more_media)).assertExists()
        photoTiles().assertCountEquals(1)
        videoTiles().assertCountEquals(1)
        composeTestRule.onNodeWithText(string(R.string.action_select_more_media)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(MEDIA.toList()), launches)
        // Still partial: the banner stays and nothing opens the settings.
        composeTestRule.onNodeWithText(string(R.string.action_select_more_media)).assertExists()
        assertNull(shadowOf(composeTestRule.activity).nextStartedActivity)
    }

    private fun setVideoRationale(show: Boolean) =
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO, show)

    @Test
    fun videoDialogDismissed_withoutEarlierRationale_bannerKeepsAskingInsteadOfOpeningSettings() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")
        // A dismissed first dialog: denied, and the system offers no rationale (it never did).
        setVideoRationale(false)
        grantOnRequest = setOf(Manifest.permission.READ_MEDIA_IMAGES)
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertEquals(1, launches.size)
        assertFalse(permissions.isRationaleShown())
        composeTestRule.onAllNodesWithText(string(R.string.action_open_app_settings)).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.action_grant_video_access)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, launches.size)
        assertNull(shadowOf(composeTestRule.activity).nextStartedActivity)
    }

    @Test
    fun videoDeniedAgainAfterARationale_bannerOpensAppSettings() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")
        grantOnRequest = setOf(Manifest.permission.READ_MEDIA_IMAGES)
        // First denial: the system will show the dialog again with a rationale.
        setVideoRationale(true)
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertEquals(1, launches.size)
        assertTrue(permissions.isRationaleShown())
        composeTestRule.onAllNodesWithText(string(R.string.action_open_app_settings)).assertCountEquals(0)

        // Second denial: no rationale any more = permanently denied.
        setVideoRationale(false)
        composeTestRule.onNodeWithText(string(R.string.action_grant_video_access)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, launches.size)
        composeTestRule.onNodeWithText(string(R.string.action_open_app_settings)).performClick()
        composeTestRule.waitForIdle()
        val started: Intent = shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.fromParts("package", app.packageName, null), started.data)
        assertEquals(2, launches.size)
    }

    @Test
    fun rationaleOfAnEarlierSession_makesTheFirstDenialWithoutRationaleOpenSettings() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")
        setVideoRationale(false)

        setScreen(
            FakeMediaPermissionRepository(
                initialRequested = true,
                initialRationaleShown = true,
                initialLocationRequested = true,
            ),
        )
        composeTestRule.onNodeWithText(string(R.string.action_grant_video_access)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.action_open_app_settings)).assertExists()
    }

    @Test
    fun upgradeUserGrantsVideo_reloadsAndShowsTheVideos() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FakeMediaStoreProvider.videoFailure = SecurityException("no video access")
        grantOnRequest = setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

        setScreen()

        assertEquals(1, launches.size)
        photoTiles().assertCountEquals(1)
        videoTiles().assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.banner_video_access_missing)).assertCountEquals(0)
    }

    @Test
    fun partialUserGrantsFullAccess_reloadsTheGrid() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        grantOnRequest = MEDIA.toSet()
        setScreen()
        photoTiles().assertCountEquals(1)
        // With full access the media store returns every photo.
        onRequest = {
            FakeMediaStoreProvider.imageRows =
                FakeMediaStoreProvider.imageRows + row(2, "c.jpg", day + 2, "image/jpeg")
        }

        composeTestRule.onNodeWithText(string(R.string.action_select_more_media)).performClick()
        composeTestRule.waitForIdle()

        photoTiles().assertCountEquals(2)
        composeTestRule.onAllNodesWithText(string(R.string.action_select_more_media)).assertCountEquals(0)
    }

    @Test
    fun uploadedVideoWithTheSameRawIdAsAPhoto_onlyTheVideoIsMarkedAndHiddenUnderNewOnly() {
        shadowOf(app).grantPermissions(*FULL_ACCESS)
        FakeMediaStoreProvider.imageRows = listOf(row(42, "a.jpg", day, "image/jpeg"))
        FakeMediaStoreProvider.videoRows = listOf(row(42, "b.mp4", day + 1, "video/mp4", durationMs = 5_000))
        val uploaded = FakeUploadedPhotosRepository()
        runBlocking { uploaded.markAsUploaded(setOf(42L or Photo.VIDEO_ID_FLAG)) }

        setScreen(uploaded = uploaded)

        val notSelected = string(R.string.cd_not_selected)
        val uploadedState = app.getString(R.string.cd_photo_state_uploaded, notSelected)
        videoTiles().assertCountEquals(1)
        photoTiles().assertCountEquals(1)
        videoTiles()[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, uploadedState))
        photoTiles()[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, notSelected),
        )

        composeTestRule.onNodeWithContentDescription(string(R.string.action_show_only_new)).performClick()
        composeTestRule.waitForIdle()

        videoTiles().assertCountEquals(0)
        photoTiles().assertCountEquals(1)
    }

    @Test
    fun allDenied_showsPermissionScreenWithGrantButton() {
        setScreen()

        assertEquals(listOf(MEDIA.toList()), launches)
        composeTestRule.onNodeWithText(string(R.string.permission_rationale)).assertExists()
        composeTestRule.onAllNodesWithText(string(R.string.banner_video_access_missing)).assertCountEquals(0)
        photoTiles().assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.action_grant_permission)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, launches.size)
    }

    @Test
    fun fullAccess_showsPhotosAndVideosWithoutBannerOrRequest() {
        shadowOf(app).grantPermissions(*FULL_ACCESS)

        setScreen()

        assertTrue(launches.isEmpty())
        photoTiles().assertCountEquals(1)
        videoTiles().assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.banner_video_access_missing)).assertCountEquals(0)
        assertFalse(
            composeTestRule.onAllNodesWithText(string(R.string.action_select_more_media)).fetchSemanticsNodes().any(),
        )
    }

    // ── media location (ACCESS_MEDIA_LOCATION) ──────────────────────────────

    @Test
    fun upgrade_fullAccessWithoutLocation_isAskedOnce_andADenialShowsNoBanner() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        // Denied: nothing is granted by the answer.
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertEquals(listOf(MEDIA.toList()), launches)
        assertTrue(permissions.isLocationRequested())
        assertFalse("video was not missing, so it is not marked", permissions.isRequested())
        photoTiles().assertCountEquals(1)
        videoTiles().assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.banner_video_access_missing)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.action_select_more_media)).assertCountEquals(0)
    }

    @Test
    fun location_alreadyAsked_isNotAskedAgain() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

        setScreen(FakeMediaPermissionRepository(initialLocationRequested = true))

        assertTrue(launches.isEmpty())
        photoTiles().assertCountEquals(1)
    }

    @Test
    fun location_isNeverAskedBeforeTheStoredFlagIsRead() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val permissions = FakeMediaPermissionRepository()
        permissions.locationState.value = null

        setScreen(permissions)
        assertTrue(launches.isEmpty())

        permissions.locationState.value = false
        composeTestRule.waitForIdle()
        assertEquals(1, launches.size)
    }

    @Test
    fun partialAccessWithoutLocation_isNeverAskedForIt() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertTrue(launches.isEmpty())
        assertFalse(permissions.isLocationRequested())
    }

    @Test
    fun theFirstRequestAlsoAsksForTheLocation_andItIsNotAskedAgainAfterwards() {
        grantOnRequest = setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val permissions = FakeMediaPermissionRepository()

        setScreen(permissions)

        assertEquals(listOf(MEDIA.toList()), launches)
        assertTrue(permissions.isLocationRequested())
        photoTiles().assertCountEquals(1)
    }

    private companion object {
        val FULL_ACCESS =
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )

        val MEDIA =
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
    }
}
