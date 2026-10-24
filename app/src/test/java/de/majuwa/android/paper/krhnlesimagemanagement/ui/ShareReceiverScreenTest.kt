package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeShareProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ProviderGate
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ShareTestHarness
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ShareTestHarness.Companion.share
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareFinish
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareRoute
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.util.MAX_SHARED_PHOTOS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareReceiverScreenTest {
    @get:Rule(order = 0)
    val temp = TemporaryFolder()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var harness: ShareTestHarness
    private val finished = mutableListOf<ShareFinish>()
    private var openedApp = 0
    private val created = AtomicInteger()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeShareProvider::class.java, FakeShareProvider.AUTHORITY)
        harness = ShareTestHarness(app, temp.newFolder("share_staging"), temp.newFolder("queues"))
    }

    @After
    fun tearDown() {
        FakeShareProvider.items.clear()
    }

    @Composable
    private fun Host(
        intent: Intent,
        repo: FakeCredentialRepository,
    ) {
        val vm: ShareViewModel =
            viewModel(
                factory =
                    viewModelFactory {
                        initializer {
                            created.incrementAndGet()
                            harness.viewModel(repo)
                        }
                    },
            )
        LaunchedEffect(vm) { vm.start(intent) }
        KrhnlesImageManagementTheme {
            ShareRoute(viewModel = vm, onFinished = { finished += it }, onOpenApp = { openedApp++ })
        }
    }

    private fun show(
        vararg uris: Uri,
        repo: FakeCredentialRepository = harness.configured(),
    ) {
        val intent = share(*uris)
        rule.setContent { Host(intent, repo) }
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private fun plural(
        id: Int,
        count: Int,
        vararg args: Any,
    ) = rule.activity.resources.getQuantityString(id, count, *args)

    /** Waits for [text], draining the main looper with `waitForIdle()` (not `waitUntil`). */
    private fun awaitText(text: String) = awaitCondition { rule.onAllNodesWithTextCount(text) > 0 }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met in time")
    }

    private fun typeAndUpload(name: String = "Trip") {
        rule.onNode(hasSetTextAction()).performTextInput(name)
        rule.onNodeWithText(string(R.string.action_upload)).performClick()
    }

    @Test
    fun dialog_showsTheNumberOfAcceptedPhotos() {
        show(harness.image("1"), harness.image("2"), harness.image("3"))

        awaitText(plural(R.plurals.share_upload_message, 3, 3))
    }

    @Test
    fun dialog_reportsSkippedAndNotIncludedItems() {
        show(*Array(MAX_SHARED_PHOTOS + 2) { harness.image("m$it") } + harness.image("pdf", type = "application/pdf"))

        awaitText(plural(R.plurals.share_upload_message, MAX_SHARED_PHOTOS, MAX_SHARED_PHOTOS), substring = true)
        rule
            .onNodeWithText(plural(R.plurals.share_too_many_photos, 3, 3, MAX_SHARED_PHOTOS), substring = true)
            .assertExists()
    }

    @Test
    fun confirm_enqueuesExactlyOnce_andFinishes() {
        show(harness.image("1"), harness.image("2"))
        awaitText(plural(R.plurals.share_upload_message, 2, 2))

        rule.onNode(hasSetTextAction()).performTextInput("Trip")
        rule.onNodeWithText(string(R.string.action_upload)).performClick()
        rule.waitForIdle()
        // The dialog is gone after the first click, so a second confirm is impossible.
        rule.onNodeWithText(string(R.string.action_upload)).assertDoesNotExist()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        awaitCondition { finished.isNotEmpty() }

        assertEquals(1, harness.enqueued.size)
        assertEquals(
            2,
            harness.enqueued
                .single()
                .photos.size,
        )
        assertEquals(listOf(ShareFinish()), finished)
    }

    @Test
    fun autoDateFoldersOn_confirmStillUploadsIntoTheOccasion() {
        show(harness.image("1"), repo = harness.configured(autoDate = true))
        awaitText(plural(R.plurals.share_upload_message, 1, 1))

        typeAndUpload("Birthday")
        awaitCondition { finished.isNotEmpty() }

        assertEquals("Birthday", (harness.enqueued.single() as UploadBatch.Occasion).folderName)
    }

    @Test
    fun recreateDuringCopy_keepsTheCopy_andEnqueuesOnce() {
        val (gated, gate) = harness.gatedImage("2")
        val intent = share(harness.image("1"), gated)
        val repo = harness.configured()
        rule.setContent { Host(intent, repo) }
        awaitText(plural(R.plurals.share_upload_message, 2, 2))
        typeAndUpload()
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        awaitText(rule.activity.getString(R.string.share_preparing, 2, 2))

        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { it.setContent { Host(intent, repo) } }
        awaitText(app.getString(R.string.share_preparing, 2, 2))
        gate.release()
        awaitCondition { finished.isNotEmpty() }

        assertEquals(1, created.get())
        assertEquals(1, harness.enqueued.size)
        assertEquals(
            2,
            harness.enqueued
                .single()
                .photos.size,
        )
    }

    @Test
    fun cancelDuringCopy_enqueuesNothing_andLeavesNoFiles() {
        val (gated, gate) = harness.gatedImage("2")
        show(harness.image("1"), gated)
        awaitText(plural(R.plurals.share_upload_message, 2, 2))
        typeAndUpload()
        assertTrue(gate.started.await(5, TimeUnit.SECONDS))
        awaitText(string(R.string.share_preparing_title))

        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        awaitCondition { finished.isNotEmpty() }

        assertEquals(listOf(ShareFinish()), finished)
        assertTrue(harness.enqueued.isEmpty())
        assertTrue(harness.stagedFiles().isEmpty())
    }

    @Test
    fun preparing_showsAProgressIndicatorWithCancel() {
        val gate = ProviderGate(honoursCancel = false)
        FakeShareProvider.items["slow"] = FakeShareProvider.Item(name = "slow.jpg", queryGate = gate)
        try {
            show(FakeShareProvider.uri("slow"))
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            awaitText(string(R.string.share_reading_title))
            rule
                .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .assertExists()

            rule.onNodeWithText(string(R.string.action_cancel)).performClick()
            awaitCondition { finished.isNotEmpty() }

            assertEquals(listOf(ShareFinish()), finished)
            assertTrue(harness.enqueued.isEmpty())
        } finally {
            gate.release()
        }
    }

    @Test
    fun notConfigured_showsTheSetupPrompt_andOpenAppFinishes() {
        show(harness.image("1"), repo = FakeCredentialRepository())
        awaitText(string(R.string.share_not_configured_message))
        rule.onNodeWithText(string(R.string.action_upload)).assertDoesNotExist()

        rule.onNodeWithText(string(R.string.share_not_configured_open_app)).performClick()
        awaitCondition { finished.isNotEmpty() }

        assertEquals(1, openedApp)
        assertTrue(harness.stagedFiles().isEmpty())
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun unreadablePhoto_isReportedAsSkippedInTheFinish() {
        show(harness.image("1"), harness.image("2", content = null))
        awaitText(plural(R.plurals.share_upload_message, 2, 2))

        typeAndUpload()
        awaitCondition { finished.isNotEmpty() }

        assertEquals(listOf(ShareFinish(skipped = 1)), finished)
        assertEquals(
            1,
            harness.enqueued
                .single()
                .photos.size,
        )
    }

    private fun awaitText(
        text: String,
        substring: Boolean,
    ) = awaitCondition { rule.onAllNodesWithTextCount(text, substring) > 0 }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(
    text: String,
    substring: Boolean = false,
): Int =
    onAllNodes(
        androidx.compose.ui.test
            .hasText(text, substring = substring),
    ).fetchSemanticsNodes()
        .size
