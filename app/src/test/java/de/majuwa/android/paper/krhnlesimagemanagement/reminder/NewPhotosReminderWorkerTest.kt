package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import de.majuwa.android.paper.krhnlesimagemanagement.data.NewPhotosReminderRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadedPhotosRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.MediaAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewPhotosReminderWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Only the reminder setting: the worker is given no way to read credentials at all. */
    private class Settings(
        initial: NewPhotosReminderSettings,
    ) : NewPhotosReminderRepository {
        val state = MutableStateFlow(initial)
        var shownWrites = mutableListOf<Long>()
        override val newPhotosReminder: Flow<NewPhotosReminderSettings> = state

        override suspend fun saveNewPhotosReminder(
            enabled: Boolean,
            interval: ReminderInterval,
        ) = error("the worker never saves the setting")

        // Like the real store: one conditional edit (still on, still the same reminder).
        override suspend fun markNewPhotosReminderShown(
            atMillis: Long,
            expectedSinceMillis: Long,
        ) {
            val current = state.value
            if (!current.enabled || current.sinceMillis != expectedSinceMillis) return
            shownWrites += atMillis
            state.value = current.copy(lastShownMillis = atMillis)
        }
    }

    private class Notifier(
        var allowed: Boolean = true,
        var onPost: () -> Unit = {},
    ) : ReminderNotifications {
        val posted = mutableListOf<Pair<NewMediaCount, Boolean>>()

        override fun canPost() = allowed

        override fun post(
            count: NewMediaCount,
            sinceLastUpload: Boolean,
        ) {
            onPost()
            posted += count to sinceLastUpload
        }

        override fun cancel() = Unit
    }

    private class Scheduler : NewPhotosReminderScheduling {
        var cancels = 0
        var schedules = 0

        override suspend fun schedule(interval: ReminderInterval) {
            schedules++
        }

        override suspend fun cancel() {
            cancels++
        }

        override suspend fun reconcile(settings: NewPhotosReminderSettings) = Unit
    }

    private class Query(
        var result: NewMediaQueryResult,
    ) : NewMediaSource {
        var calls = 0
        var lastBaseline = -1L
        var lastKinds: Set<MediaKind> = emptySet()
        var onQuery: () -> Unit = {}

        override suspend fun query(
            kinds: Set<MediaKind>,
            baselineMillis: Long,
        ): NewMediaQueryResult {
            calls++
            onQuery()
            lastKinds = kinds
            lastBaseline = baselineMillis
            return result
        }
    }

    private val enabled = NewPhotosReminderSettings(true, ReminderInterval.Weekly, sinceMillis = 1_000L)
    private val settings = Settings(enabled)
    private val notifier = Notifier()
    private val scheduler = Scheduler()
    private val markers = FakeUploadedPhotosRepository()
    private val history = FakeUploadHistoryRepository()
    private var access = MediaAccess(images = true, video = true, userSelected = false)
    private val query =
        Query(NewMediaQueryResult.Rows((1L..37L).map { NewMediaRow(it, 5_000L) }, capReached = false))

    private var clock: () -> Long = { NOW }

    private fun run(): ListenableWorker.Result {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    NewPhotosReminderWorker(
                        appContext,
                        workerParameters,
                        settings,
                        markers,
                        history,
                        query,
                        notifier,
                        scheduler,
                        ReminderWorkerSeams({ access }, { clock() }),
                    )
            }
        val worker = TestListenableWorkerBuilder<NewPhotosReminderWorker>(context).setWorkerFactory(factory).build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun `new items post one notification and store the reminder time`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, notifier.posted.size)
        assertEquals(
            37,
            notifier.posted
                .single()
                .first.total,
        )
        assertEquals(listOf(NOW), settings.shownWrites)
        assertEquals(1_000L, query.lastBaseline)
    }

    @Test
    fun `the reminder time is taken before the query`() {
        val events = mutableListOf<String>()
        clock = {
            events += "clock"
            NOW
        }
        query.onQuery = { events += "query" }
        run()
        assertEquals("clock", events.first())
        assertTrue(events.indexOf("clock") < events.indexOf("query"))
        assertEquals(listOf(NOW), settings.shownWrites)
    }

    @Test
    fun `logout while posting writes no reminder time`() {
        notifier.onPost = { settings.state.value = NewPhotosReminderSettings() }
        run()
        assertEquals(1, notifier.posted.size)
        assertTrue(settings.shownWrites.isEmpty())
        assertEquals(NewPhotosReminderSettings(), settings.state.value)
    }

    @Test
    fun `zero new items post nothing`() {
        query.result = NewMediaQueryResult.Rows(emptyList(), false)
        run()
        assertTrue(notifier.posted.isEmpty())
        assertTrue(settings.shownWrites.isEmpty())
    }

    @Test
    fun `nothing new since the last reminder posts nothing`() {
        settings.state.value = enabled.copy(lastShownMillis = 6_000_000L)
        run()
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `an upload after enabling moves the baseline and changes the wording flag`() {
        history.setEntries(
            listOf(
                de.majuwa.android.paper.krhnlesimagemanagement.model
                    .UploadHistoryEntry(1, "x", 3_000L, 2, 0),
            ),
        )
        run()
        assertEquals(3_000L, query.lastBaseline)
        assertTrue(notifier.posted.single().second)
        // Without an upload the wording flag is off.
        notifier.posted.clear()
        history.setEntries(emptyList())
        run()
        assertEquals(false, notifier.posted.single().second)
    }

    @Test
    fun `no media permission means no query and nothing posted`() {
        access = MediaAccess(images = false, video = false, userSelected = false, location = true)
        run()
        assertEquals(0, query.calls)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `selected-only access queries both kinds`() {
        access = MediaAccess(images = false, video = false, userSelected = true)
        run()
        assertEquals(setOf(MediaKind.Image, MediaKind.Video), query.lastKinds)
    }

    @Test
    fun `notifications not allowed means no query, no post and the time is unchanged`() {
        notifier.allowed = false
        run()
        assertEquals(0, query.calls)
        assertTrue(notifier.posted.isEmpty())
        assertTrue(settings.shownWrites.isEmpty())
    }

    @Test
    fun `unreadable markers post nothing`() {
        val failing =
            object : de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract {
                override val uploadedPhotoIds: Flow<Set<Long>> =
                    kotlinx.coroutines.flow.flow {
                        throw java.io
                            .IOException()
                    }

                override suspend fun markAsUploaded(photoIds: Set<Long>) = Unit

                override suspend fun clear() = Unit
            }
        val factoryWorker = buildWith(failing)
        assertEquals(ListenableWorker.Result.success(), runBlocking { factoryWorker.doWork() })
        assertTrue(notifier.posted.isEmpty())
    }

    private fun buildWith(
        failingMarkers: de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosRepositoryContract,
    ): NewPhotosReminderWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    NewPhotosReminderWorker(
                        appContext,
                        workerParameters,
                        settings,
                        failingMarkers,
                        history,
                        query,
                        notifier,
                        scheduler,
                        ReminderWorkerSeams({ access }, { clock() }),
                    )
            }
        return TestListenableWorkerBuilder<NewPhotosReminderWorker>(context).setWorkerFactory(factory).build()
    }

    @Test
    fun `a query timeout posts nothing and keeps the reminder time`() {
        query.result = NewMediaQueryResult.TimedOut
        run()
        assertTrue(notifier.posted.isEmpty())
        assertTrue(settings.shownWrites.isEmpty())
    }

    @Test
    fun `a disabled setting cancels through the scheduler and posts nothing`() {
        settings.state.value = enabled.copy(enabled = false)
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, scheduler.cancels)
        assertEquals(0, query.calls)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `disabled between count and post posts nothing`() {
        query.result = NewMediaQueryResult.Rows(listOf(NewMediaRow(1, 5_000L)), false)
        val racing =
            object : NewMediaSource {
                override suspend fun query(
                    kinds: Set<MediaKind>,
                    baselineMillis: Long,
                ): NewMediaQueryResult {
                    settings.state.value = enabled.copy(enabled = false)
                    return query.result
                }
            }
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    NewPhotosReminderWorker(
                        appContext,
                        workerParameters,
                        settings,
                        markers,
                        history,
                        racing,
                        notifier,
                        scheduler,
                        ReminderWorkerSeams({ access }, { clock() }),
                    )
            }
        val worker = TestListenableWorkerBuilder<NewPhotosReminderWorker>(context).setWorkerFactory(factory).build()
        runBlocking { worker.doWork() }
        assertTrue(notifier.posted.isEmpty())
        assertTrue(settings.shownWrites.isEmpty())
    }

    @Test
    fun `an enabled run never schedules or cancels and never touches upload work`() {
        run()
        assertEquals(0, scheduler.cancels)
        assertEquals(0, scheduler.schedules)
    }

    private companion object {
        const val NOW = 9_999L
    }
}
