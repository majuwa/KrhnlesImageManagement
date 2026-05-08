package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadHistoryStoreTest {
    private lateinit var context: Context
    private lateinit var store: UploadHistoryStore

    @Before
    fun setup() =
        runTest {
            context = ApplicationProvider.getApplicationContext()
            store = UploadHistoryStore(context)
            store.clearAll()
        }

    @Test
    fun `entries persist across new store instances`() =
        runTest {
            store.addEntry(
                UploadHistoryEntry(
                    id = 1L,
                    occasionName = "Trip",
                    timestampMillis = 1000L,
                    photoCount = 5,
                    failedCount = 1,
                ),
            )

            val reloadedStore = UploadHistoryStore(context)
            val entries = reloadedStore.entries.first()

            assertEquals(1, entries.size)
            assertEquals("Trip", entries.first().occasionName)
            assertEquals(5, entries.first().photoCount)
            assertEquals(1, entries.first().failedCount)
        }

    @Test
    fun `entries are newest first`() =
        runTest {
            store.addEntry(
                UploadHistoryEntry(
                    id = 1L,
                    occasionName = "Older",
                    timestampMillis = 1000L,
                    photoCount = 2,
                    failedCount = 0,
                ),
            )
            store.addEntry(
                UploadHistoryEntry(
                    id = 2L,
                    occasionName = "Newer",
                    timestampMillis = 2000L,
                    photoCount = 3,
                    failedCount = 1,
                ),
            )

            val entries = store.entries.first()

            assertEquals(2, entries.size)
            assertEquals("Newer", entries[0].occasionName)
            assertEquals("Older", entries[1].occasionName)
        }

    @Test
    fun `removeEntry and clearAll update history`() =
        runTest {
            store.addEntry(
                UploadHistoryEntry(
                    id = 1L,
                    occasionName = "A",
                    timestampMillis = 1000L,
                    photoCount = 1,
                    failedCount = 0,
                ),
            )
            store.addEntry(
                UploadHistoryEntry(
                    id = 2L,
                    occasionName = "B",
                    timestampMillis = 2000L,
                    photoCount = 2,
                    failedCount = 1,
                ),
            )

            store.removeEntry(1L)
            val afterSingleDelete = store.entries.first()
            assertEquals(1, afterSingleDelete.size)
            assertEquals(2L, afterSingleDelete.first().id)

            store.clearAll()
            assertTrue(store.entries.first().isEmpty())
        }
}
