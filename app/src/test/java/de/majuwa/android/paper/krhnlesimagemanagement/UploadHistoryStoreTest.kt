package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.uploadHistoryCorruptionHandler
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [UploadHistoryStore] against real test DataStores on temporary files (never the app's process-wide
 * delegate). Robolectric supplies the real org.json and android.util.Log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UploadHistoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = stringPreferencesKey("upload_history_entries")

    private fun file() = File(tmp.root, "upload_history.preferences_pb")

    private fun dataStore(
        scope: CoroutineScope,
        withHandler: Boolean = false,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = if (withHandler) uploadHistoryCorruptionHandler else null,
            scope = scope,
            produceFile = { file() },
        )

    private fun entry(
        id: Long,
        time: Long = id,
        name: String = "Trip",
        photos: Int = 2,
        failed: Int = 0,
    ) = UploadHistoryEntry(id, name, time, photos, failed)

    @Test
    fun `entries are newest first`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadHistoryStore(dataStore(backgroundScope))
            store.addEntry(entry(1, 1000, "Older"))
            store.addEntry(entry(2, 3000, "Newest"))
            store.addEntry(entry(3, 2000, "Middle"))

            assertEquals(listOf("Newest", "Middle", "Older"), store.entries.first().map { it.occasionName })
        }

    @Test
    fun `same timestamp ids stay distinct and delete removes only one`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadHistoryStore(dataStore(backgroundScope))
            store.addEntry(entry(5, 5, "A"))
            store.addEntry(entry(5, 5, "B"))
            store.addEntry(entry(5, 5, "C"))

            val ids = store.entries.first().map { it.id }
            assertEquals(3, ids.size)
            assertEquals(3, ids.toSet().size)

            store.removeEntry(ids.first())
            assertEquals(2, store.entries.first().size)
            assertTrue(ids.first() !in store.entries.first().map { it.id })
        }

    @Test
    fun `clearAll empties the history`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadHistoryStore(dataStore(backgroundScope))
            store.addEntry(entry(1))
            store.clearAll()
            assertTrue(store.entries.first().isEmpty())
        }

    @Test
    fun `501st entry drops the oldest`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadHistoryStore(dataStore(backgroundScope))
            for (i in 1..501) store.addEntry(entry(i.toLong(), time = i * 10L))

            val entries = store.entries.first()
            assertEquals(500, entries.size)
            assertEquals(5010L, entries.first().timestampMillis)
            assertEquals(20L, entries.last().timestampMillis)
        }

    @Test
    fun `existing oversized history is trimmed on the next add`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            val json =
                (1..600).joinToString(prefix = "[", postfix = "]") {
                    """{"id":$it,"occasionName":"x","timestampMillis":$it,"photoCount":1,"failedCount":0}"""
                }
            ds.edit { it[key] = json }
            val store = UploadHistoryStore(ds)
            assertEquals(600, store.entries.first().size)

            store.addEntry(entry(1000, time = 1000))

            val entries = store.entries.first()
            assertEquals(500, entries.size)
            assertEquals(1000L, entries.first().timestampMillis)
        }

    @Test
    fun `corrupt file with the handler gives an empty list and stays writable`() =
        runTest(UnconfinedTestDispatcher()) {
            file().writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, -1, -2, -3))
            val store = UploadHistoryStore(dataStore(backgroundScope, withHandler = true))

            assertTrue(store.entries.first().isEmpty())
            store.addEntry(entry(1))
            assertEquals(1, store.entries.first().size)
        }

    @Test
    fun `corrupt json gives an empty list`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            ds.edit { it[key] = "{not json" }
            assertTrue(UploadHistoryStore(ds).entries.first().isEmpty())
        }

    @Test
    fun `invalid persisted entries are filtered out`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            ds.edit {
                it[key] =
                    """
                    [
                      {"id":1,"occasionName":"valid","timestampMillis":1000,"photoCount":4,"failedCount":1},
                      {"id":2,"occasionName":"invalid-negative","timestampMillis":2000,"photoCount":-1,"failedCount":0},
                      {"id":3,"occasionName":"invalid-overflow","timestampMillis":3000,"photoCount":2,"failedCount":3}
                    ]
                    """.trimIndent()
            }

            assertEquals(listOf(1L), UploadHistoryStore(ds).entries.first().map { it.id })
        }

    @Test
    fun `persisted duplicate ids keep the newest entry and removeEntry removes only one`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            ds.edit {
                it[key] =
                    """
                    [
                      {"id":1,"occasionName":"first","timestampMillis":1000,"photoCount":1,"failedCount":0},
                      {"id":1,"occasionName":"dup","timestampMillis":2000,"photoCount":1,"failedCount":0},
                      {"id":2,"occasionName":"other","timestampMillis":3000,"photoCount":1,"failedCount":0}
                    ]
                    """.trimIndent()
            }
            val store = UploadHistoryStore(ds)

            assertEquals(listOf("other", "dup"), store.entries.first().map { it.occasionName })
            store.removeEntry(1L)
            assertEquals(listOf("other"), store.entries.first().map { it.occasionName })
        }

    @Test
    fun `entries persist across instances`() =
        runTest(UnconfinedTestDispatcher()) {
            val job = Job()
            val first =
                UploadHistoryStore(dataStore(CoroutineScope(job + UnconfinedTestDispatcher(testScheduler))))
            first.addEntry(entry(1, name = "Trip", photos = 5, failed = 1))
            job.cancel()
            job.join()

            val reloaded = UploadHistoryStore(dataStore(backgroundScope)).entries.first()

            assertEquals(1, reloaded.size)
            assertEquals("Trip", reloaded.single().occasionName)
            assertEquals(5, reloaded.single().photoCount)
            assertEquals(1, reloaded.single().failedCount)
        }
}
