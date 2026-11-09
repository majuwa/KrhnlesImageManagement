package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialCipher
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.SoftwareKeyProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.NEW_PHOTOS_REMINDER_WORK_NAME
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.ACTION_SHOW_NEW_MEDIA
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.AppLaunchRequest
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.LaunchRequestViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The launch request and the reminder reconcile of [MainActivity]'s non-share path. The activities are only
 * created, never attached to a window: composing a real activity caches Compose's process-wide UI dispatcher
 * on the first test's Choreographer, which would leave every later real-activity test (the share tests) without
 * recomposition. `onCreate` is all these tests need.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityLaunchRequestTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = CredentialStore(app, CredentialCipher(SoftwareKeyProvider()))

    private val reminderIntent = Intent(ACTION_SHOW_NEW_MEDIA)

    private val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/a.jpg"))
        }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        runBlocking { store.clear() }
    }

    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()

    private fun launch(intent: Intent) =
        Robolectric.buildActivity(MainActivity::class.java, intent).also {
            controllers +=
                it
        }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun pendingOf(activity: MainActivity) =
        ViewModelProvider(activity)[LaunchRequestViewModel::class.java].pending.value

    private fun reminderWork(): List<WorkInfo> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME).get()

    private fun waitForWork(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (reminderWork().any { it.state == WorkInfo.State.ENQUEUED }) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `launching with the reminder action offers ShowNewMedia`() {
        val controller = launch(reminderIntent).create()
        assertEquals(AppLaunchRequest.ShowNewMedia, pendingOf(controller.get()))
    }

    @Test
    fun `a normal launch offers nothing`() {
        val controller = launch(Intent(Intent.ACTION_MAIN)).create()
        assertNull(pendingOf(controller.get()))
    }

    @Test
    fun `onNewIntent with the action offers the request to the running instance`() {
        val controller = launch(Intent(Intent.ACTION_MAIN)).create()
        assertNull(pendingOf(controller.get()))

        controller.newIntent(reminderIntent)

        assertEquals(AppLaunchRequest.ShowNewMedia, pendingOf(controller.get()))
    }

    @Test
    fun `a recreated activity does not offer the original intent again`() {
        // `create(Bundle)` is what a recreated activity runs (rotation, process death): saved state present.
        // (`ActivityController.recreate()` is avoided: it attaches the window and composes, which poisons
        // Compose's cached UI dispatcher for every later real-activity test.)
        val controller = launch(reminderIntent).create(Bundle())

        assertNull(pendingOf(controller.get()))
    }

    @Test
    fun `a share-mode instance keeps the share and offers nothing`() {
        val controller = launch(shareIntent).create()
        val activity = controller.get()
        controller.newIntent(reminderIntent)

        assertNull(pendingOf(activity))
    }

    @Test
    fun `a stored-on reminder without work is scheduled after launch`() {
        runBlocking { store.saveNewPhotosReminder(true, ReminderInterval.Monthly) }
        assertTrue(reminderWork().isEmpty())

        launch(Intent(Intent.ACTION_MAIN)).create()

        assertTrue("reconcile did not enqueue the periodic work", waitForWork())
        assertEquals(WorkInfo.State.ENQUEUED, reminderWork().single().state)
    }

    @Test
    fun `a share launch does not touch the scheduler`() {
        runBlocking { store.saveNewPhotosReminder(true, ReminderInterval.Weekly) }

        launch(shareIntent).create()

        assertTrue(!waitForWork())
    }

    @Test
    fun `a recreated activity does not reconcile again`() {
        runBlocking { store.saveNewPhotosReminder(true, ReminderInterval.Weekly) }

        launch(Intent(Intent.ACTION_MAIN)).create(Bundle())

        assertTrue(!waitForWork())
    }

    private companion object {
        const val WAIT_MS = 2_000L
    }
}
