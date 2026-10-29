package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeLocalMediaTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaPermissionRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider.Companion.row
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeRemovalChecker
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.verifiedRecord
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.RemovalState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.UploadedRemovalHost
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.UploadedRemovalViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The "Remove uploaded photos from device" action of the real [PhotoGridScreen]: overflow menu, check
 * progress, in-app confirmation, the system trash request (a fake [ActivityResultRegistry] records
 * each `StartIntentSenderForResult` launch and answers with [systemResult]) and the result snackbar.
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridRemovalTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private val serverUrl = "https://cloud.example.com/remote.php/dav/files/u/"
    private val verified = FakeVerifiedUploadsRepository()
    private val checker = FakeRemovalChecker()
    private val trasher = FakeLocalMediaTrasher()
    private val launches = mutableListOf<IntentSenderRequest>()

    /** What the system trash dialog answers. */
    private var systemResult = Activity.RESULT_OK

    /** Runs when the system dialog is answered (e.g. the media store no longer lists the trashed photo). */
    private var onSystemDialog: () -> Unit = {}

    /** False = the system dialog stays open (no result is dispatched). */
    private var answerSystemDialog = true

    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launches += input as IntentSenderRequest
                if (!answerSystemDialog) return
                onSystemDialog()
                dispatchResult(requestCode, systemResult, null)
            }
        }

    private val day = Instant.parse("2026-05-14T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        FakeMediaStoreProvider.reset()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.imageRows =
            listOf(row(1, "a.jpg", day, "image/jpeg"), row(2, "b.jpg", day + 1, "image/jpeg"))
        runBlocking { listOf(1L, 2L).forEach { verified.recordVerified(verifiedRecord(it, serverUrl)) } }
    }

    @After
    fun tearDown() = FakeMediaStoreProvider.reset()

    private fun removalViewModel() =
        UploadedRemovalViewModel(
            app,
            verified,
            FakeCredentialRepository(WebDavConfig(serverUrl, "u", "p", "")),
            checker,
            trasher,
            computeDispatcher = Dispatchers.Unconfined,
        )

    private fun setScreen() {
        val gridViewModel =
            PhotoGridViewModel(
                app,
                MediaRepository(app.contentResolver),
                FakeCredentialRepository(),
                FakeUploadedPhotosRepository(),
                WorkManager.getInstance(app),
                FakeMediaPermissionRepository(),
            )
        val removalViewModel = removalViewModel()
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
                    viewModel = gridViewModel,
                    onNavigateToSettings = {},
                    onNavigateToUploadHistory = {},
                    onStartUpload = {},
                    removalViewModel = removalViewModel,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun string(id: Int) = app.getString(id)

    private fun openMenu() {
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_options)).performClick()
        composeTestRule.waitForIdle()
    }

    private fun clickRemoveItem() {
        openMenu()
        composeTestRule.onNodeWithTag("removeUploadedItem").performClick()
        composeTestRule.waitForIdle()
    }

    private fun photoTiles() = composeTestRule.onAllNodes(hasContentDescription("Photo", substring = true))

    @Test
    fun `the overflow menu offers the removal action`() {
        setScreen()

        openMenu()

        composeTestRule.onNodeWithText(string(R.string.action_remove_uploaded)).assertExists()
        composeTestRule.onNodeWithTag("removeUploadedItem").assertIsEnabled()
    }

    @Test
    fun `without a candidate an explanation is shown and no system dialog`() {
        setScreen()

        clickRemoveItem()

        composeTestRule.onNodeWithText(string(R.string.removal_nothing_title)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.removal_nothing_message)).assertExists()
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `confirming shows the count, launches the system request and reports the confirmed count`() {
        checker.passing = setOf(1, 2)
        setScreen()

        clickRemoveItem()

        composeTestRule.onNodeWithText("Move 2 photos and videos to trash?").assertExists()
        assertTrue("nothing is requested before the user confirms", launches.isEmpty())

        // The system dialog moves both to the trash (before it, they are not in the trash).
        onSystemDialog =
            { trasher.trashed += listOf(1, 2).map { Uri.parse("content://media/external/images/media/$it") } }
        composeTestRule.onNodeWithTag("removalConfirm").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, launches.size)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("2 photos and videos moved to the trash.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `cancelling the confirmation launches nothing`() {
        checker.passing = setOf(1)
        setScreen()

        clickRemoveItem()
        composeTestRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(launches.isEmpty())
        composeTestRule.onAllNodesWithText(string(R.string.removal_confirm_message)).assertCountEquals(0)
    }

    @Test
    fun `declining the system dialog reports that nothing was moved`() {
        checker.passing = setOf(1)
        systemResult = Activity.RESULT_CANCELED
        setScreen()

        clickRemoveItem()
        composeTestRule.onNodeWithTag("removalConfirm").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, launches.size)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(string(R.string.removal_result_cancelled))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `trashed photos are reloaded into the grid`() {
        checker.passing = setOf(1)
        setScreen()
        photoTiles().assertCountEquals(2)
        onSystemDialog = {
            trasher.trashed += Uri.parse("content://media/external/images/media/1")
            FakeMediaStoreProvider.imageRows = listOf(row(2, "b.jpg", day + 1, "image/jpeg"))
        }

        clickRemoveItem()
        composeTestRule.onNodeWithTag("removalConfirm").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { photoTiles().fetchSemanticsNodes().size == 1 }

        photoTiles().assertCountEquals(1)
    }

    @Test
    fun `the check shows its progress with a cancel action and the menu item is disabled while it runs`() {
        checker.passing = setOf(1)
        checker.gate = CompletableDeferred()
        setScreen()

        clickRemoveItem()

        composeTestRule.onNodeWithTag("removalCancelCheck").assertExists()
        openMenu()
        composeTestRule.onNodeWithTag("removeUploadedItem").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("removalCancelCheck").performClick()
        composeTestRule.waitForIdle()
        assertTrue(launches.isEmpty())
        assertEquals(1, checker.checkCalls)
    }

    @Test
    fun `an unreachable server is explained and nothing is requested`() {
        checker.passing = setOf(1)
        checker.serverUnreachable = true
        setScreen()

        clickRemoveItem()

        composeTestRule.onNodeWithText(string(R.string.removal_server_unreachable_title)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.removal_server_unreachable_message)).assertExists()
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `a recreated screen does not launch the pending system request a second time`() {
        checker.passing = setOf(1)
        answerSystemDialog = false
        val viewModel = removalViewModel()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                UploadedRemovalHost(
                    viewModel = viewModel,
                    snackbarHostState = remember { SnackbarHostState() },
                    onNavigateToSettings = {},
                    onTrashed = {},
                )
            }
        }
        viewModel.start()
        composeTestRule.waitForIdle()
        viewModel.confirm()
        composeTestRule.waitForIdle()
        assertEquals(1, launches.size)

        // The system dialog is still open while the screen is recreated (e.g. rotation).
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(1, launches.size)
        assertEquals(RemovalState.Trashing, viewModel.state.value)
    }
}
