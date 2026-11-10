package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NewPhotosReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private var notificationCancels = 0

    private val notifications =
        object : ReminderNotifications {
            override fun canPost() = true

            override fun post(
                count: NewMediaCount,
                sinceLastUpload: Boolean,
            ) = Unit

            override fun cancel() {
                notificationCancels++
            }
        }

    private lateinit var scheduler: NewPhotosReminderScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
        scheduler = NewPhotosReminderScheduler({ workManager }, notifications)
    }

    private fun works(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME).get()

    private fun active(): List<WorkInfo> = works().filter { it.state == WorkInfo.State.ENQUEUED }

    private fun days(info: WorkInfo) = TimeUnit.MILLISECONDS.toDays(info.periodicityInfo!!.repeatIntervalMillis)

    @Test
    fun `enable schedules one periodic work with the interval as period and initial delay`() {
        listOf(ReminderInterval.Weekly to 7L, ReminderInterval.EveryTwoWeeks to 14L, ReminderInterval.Monthly to 30L)
            .forEach { (interval, expected) ->
                runBlocking { scheduler.cancel() }
                runBlocking { scheduler.schedule(interval) }
                val info = active().single()
                assertEquals(expected, days(info))
                assertEquals(TimeUnit.DAYS.toMillis(expected), info.initialDelayMillis)
            }
    }

    @Test
    fun `interval change keeps one work with the same id and the new period`() {
        runBlocking { scheduler.schedule(ReminderInterval.Weekly) }
        val id = active().single().id
        runBlocking { scheduler.schedule(ReminderInterval.Monthly) }
        val info = active().single()
        assertEquals(id, info.id)
        assertEquals(30L, days(info))
    }

    @Test
    fun `cancel removes the work and the notification, enabling again enqueues a new one`() {
        runBlocking { scheduler.schedule(ReminderInterval.Weekly) }
        val old = active().single().id
        runBlocking { scheduler.cancel() }
        assertTrue(active().isEmpty())
        assertEquals(1, notificationCancels)
        runBlocking { scheduler.schedule(ReminderInterval.Weekly) }
        val info = active().single()
        assertNotEquals(old, info.id)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
    }

    @Test
    fun `reconcile enabled keeps an existing work and repairs a different period`() {
        runBlocking { scheduler.schedule(ReminderInterval.Weekly) }
        val id = active().single().id
        runBlocking { scheduler.reconcile(NewPhotosReminderSettings(true, ReminderInterval.Monthly, 1L)) }
        val info = active().single()
        assertEquals(id, info.id)
        assertEquals(30L, days(info))
        runBlocking { scheduler.reconcile(NewPhotosReminderSettings(true, ReminderInterval.Monthly, 1L)) }
        assertEquals(id, active().single().id)
    }

    @Test
    fun `reconcile enabled without work enqueues, disabled cancels`() {
        runBlocking { scheduler.reconcile(NewPhotosReminderSettings(true, ReminderInterval.EveryTwoWeeks, 1L)) }
        assertEquals(14L, days(active().single()))
        runBlocking { scheduler.reconcile(NewPhotosReminderSettings(false)) }
        assertTrue(active().isEmpty())
    }

    @Test
    fun `cancel leaves an enqueued upload work untouched`() {
        val upload =
            OneTimeWorkRequestBuilder<UploadWorker>().addTag("photo_upload").setInitialDelay(1, TimeUnit.DAYS).build()
        workManager.enqueue(upload).result.get()
        runBlocking { scheduler.schedule(ReminderInterval.Weekly) }

        runBlocking { scheduler.cancel() }

        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(upload.id).get()!!.state)
    }

    @Test
    fun `an uninitialised WorkManager does not crash`() {
        val broken =
            NewPhotosReminderScheduler({ throw IllegalStateException("WorkManager is not initialized") }, notifications)
        runBlocking {
            broken.schedule(ReminderInterval.Weekly)
            broken.cancel()
            broken.reconcile(NewPhotosReminderSettings(true))
        }
    }
}
