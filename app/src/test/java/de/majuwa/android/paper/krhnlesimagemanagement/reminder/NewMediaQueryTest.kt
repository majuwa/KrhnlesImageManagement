package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.download.DOWNLOAD_RELATIVE_ROOT
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider.Companion.newMediaRow
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class NewMediaQueryTest {
    private lateinit var context: Context
    private val images = setOf(MediaKind.Image)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakeMediaStoreProvider.reset()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
    }

    @After
    fun tearDown() = FakeMediaStoreProvider.reset()

    private fun query(
        kinds: Set<MediaKind> = images,
        baselineMillis: Long = 100_000L,
        timeoutMs: Long = 5_000L,
        cap: Int = MAX_REMINDER_SCAN_ROWS,
    ): NewMediaQueryResult =
        runBlocking {
            NewMediaQuery(context.contentResolver, OWN, Dispatchers.IO, timeoutMs, cap).query(kinds, baselineMillis)
        }

    private fun rowsOf(result: NewMediaQueryResult): List<NewMediaRow> = (result as NewMediaQueryResult.Rows).rows

    @Test
    fun `only rows after the baseline are returned`() {
        FakeMediaStoreProvider.newMediaImageRows =
            listOf(newMediaRow(1, 99), newMediaRow(2, 100), newMediaRow(3, 101), newMediaRow(4, 500))
        assertEquals(listOf(4L, 3L), rowsOf(query()).map { it.id })
    }

    @Test
    fun `own, saved, pending and trashed rows are excluded and null owner and path rows are counted`() {
        FakeMediaStoreProvider.newMediaImageRows =
            listOf(
                newMediaRow(1, 500, owner = OWN),
                newMediaRow(2, 500, owner = null, relativePath = "${DOWNLOAD_RELATIVE_ROOT}Trip/"),
                newMediaRow(3, 500, pending = true),
                newMediaRow(4, 500, trashed = true),
                newMediaRow(5, 500, owner = null, relativePath = null),
                newMediaRow(6, 500, owner = "other.app", relativePath = "DCIM/Camera/"),
                newMediaRow(7, 500, owner = null, relativePath = "pictures/kroehnsync/Other/"),
            )
        assertEquals(setOf(5L, 6L), rowsOf(query()).map { it.id }.toSet())
    }

    @Test
    fun `projection reads no name size or data column and sets no match flag`() {
        FakeMediaStoreProvider.newMediaImageRows = listOf(newMediaRow(1, 500))
        query()
        val recorded = FakeMediaStoreProvider.collectionQueries.single()
        assertEquals(listOf("_id", "date_added"), recorded.projection)
        assertFalse(recorded.args.keySet().any { it.startsWith("android:query-arg-match") })
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, recorded.uri)
    }

    @Test
    fun `row cap is respected and reported`() {
        FakeMediaStoreProvider.newMediaImageRows = (1L..5L).map { newMediaRow(it, 500 + it) }
        val capped = query(cap = 3) as NewMediaQueryResult.Rows
        assertEquals(3, capped.rows.size)
        assertTrue(capped.capReached)
        assertEquals(
            4,
            FakeMediaStoreProvider.collectionQueries
                .single()
                .args
                .getInt(ContentResolver.QUERY_ARG_LIMIT),
        )

        val exact = query(cap = 5) as NewMediaQueryResult.Rows
        assertEquals(5, exact.rows.size)
        assertFalse(exact.capReached)
    }

    @Test
    fun `a refused video query yields only images`() {
        FakeMediaStoreProvider.newMediaImageRows = listOf(newMediaRow(1, 500))
        FakeMediaStoreProvider.newMediaVideoRows = listOf(newMediaRow(2, 500))
        FakeMediaStoreProvider.videoFailure = SecurityException("no video")
        val result = query(setOf(MediaKind.Image, MediaKind.Video))
        assertEquals(listOf(1L), rowsOf(result).map { it.id })
    }

    @Test
    fun `video ids carry the video flag`() {
        FakeMediaStoreProvider.newMediaVideoRows = listOf(newMediaRow(42, 500))
        val id = rowsOf(query(setOf(MediaKind.Video))).single().id
        assertEquals(42L or Photo.VIDEO_ID_FLAG, id)
    }

    @Test
    fun `a blocked provider returns within the bound as a timeout and the signal is cancelled`() {
        val latch = CountDownLatch(1)
        FakeMediaStoreProvider.blockQueries = latch
        val started = System.currentTimeMillis()
        val result = query(timeoutMs = 300L)
        assertTrue(System.currentTimeMillis() - started < 4_000L)
        assertEquals(NewMediaQueryResult.TimedOut, result)
        val signal = FakeMediaStoreProvider.collectionQueries.single().signal
        assertTrue(signal != null && signal.isCanceled)
        latch.countDown()
    }

    private companion object {
        const val OWN = "de.majuwa.android.paper.krhnlesimagemanagement"
    }
}
