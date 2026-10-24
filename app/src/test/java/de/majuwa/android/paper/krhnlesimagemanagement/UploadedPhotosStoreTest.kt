package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadedPhotosStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.uploadedPhotosCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [UploadedPhotosStore] against real test DataStores (never the app's process-wide delegate). */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadedPhotosStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val idsKey = stringSetPreferencesKey("uploaded_ids")

    private fun file(name: String = "uploaded_photos.preferences_pb") = File(tmp.root, name)

    private fun dataStore(
        scope: CoroutineScope,
        file: File,
        withHandler: Boolean = false,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = if (withHandler) uploadedPhotosCorruptionHandler else null,
            scope = scope,
            produceFile = { file },
        )

    @Test
    fun `empty by default`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadedPhotosStore(dataStore(backgroundScope, file()))
            assertEquals(emptySet<Long>(), store.uploadedPhotoIds.first())
        }

    @Test
    fun `markAsUploaded accumulates`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadedPhotosStore(dataStore(backgroundScope, file()))
            store.markAsUploaded(setOf(1L, 2L))
            store.markAsUploaded(setOf(2L, 3L))
            assertEquals(setOf(1L, 2L, 3L), store.uploadedPhotoIds.first())
        }

    @Test
    fun `empty set is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadedPhotosStore(dataStore(backgroundScope, file()))
            store.markAsUploaded(emptySet())
            assertEquals(emptySet<Long>(), store.uploadedPhotoIds.first())
            assertEquals(false, file().exists())
        }

    @Test
    fun `markers survive a restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val job = Job()
            val first =
                UploadedPhotosStore(dataStore(CoroutineScope(job + UnconfinedTestDispatcher(testScheduler)), file()))
            first.markAsUploaded(setOf(7L, 9L))
            job.cancel()
            job.join()

            val second = UploadedPhotosStore(dataStore(backgroundScope, file()))
            assertEquals(setOf(7L, 9L), second.uploadedPhotoIds.first())
        }

    @Test
    fun `clear empties the set`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadedPhotosStore(dataStore(backgroundScope, file()))
            store.markAsUploaded(setOf(1L, 2L))
            store.clear()
            assertEquals(emptySet<Long>(), store.uploadedPhotoIds.first())
        }

    @Test
    fun `non numeric entries are skipped`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope, file())
            ds.edit { it[idsKey] = setOf("x", "5") }
            assertEquals(setOf(5L), UploadedPhotosStore(ds).uploadedPhotoIds.first())
        }

    @Test
    fun `video flagged ids round trip`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = UploadedPhotosStore(dataStore(backgroundScope, file()))
            val videoId = 1L shl 62 or 42L
            store.markAsUploaded(setOf(videoId, 42L))
            assertEquals(setOf(videoId, 42L), store.uploadedPhotoIds.first())
        }

    @Test
    fun `corrupt file yields an empty set and stays writable`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = file()
            f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, -1, -2, -3))
            val store = UploadedPhotosStore(dataStore(backgroundScope, f, withHandler = true))
            assertEquals(emptySet<Long>(), store.uploadedPhotoIds.first())
            store.markAsUploaded(setOf(4L))
            assertEquals(setOf(4L), store.uploadedPhotoIds.first())
        }
}
