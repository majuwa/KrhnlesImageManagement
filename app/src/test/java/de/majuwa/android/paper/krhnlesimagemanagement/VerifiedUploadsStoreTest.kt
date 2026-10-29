package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.majuwa.android.paper.krhnlesimagemanagement.data.MAX_VERIFIED_UPLOADS
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadTarget
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUploadsStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.verifiedUploadsCorruptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** [VerifiedUploadsStore] against real test DataStores (never the app's process-wide delegate). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VerifiedUploadsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val recordsKey = stringSetPreferencesKey("records")

    private fun file() = File(tmp.root, "verified_uploads.preferences_pb")

    private fun dataStore(
        scope: CoroutineScope,
        file: File = file(),
        withHandler: Boolean = false,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = if (withHandler) verifiedUploadsCorruptionHandler else null,
            scope = scope,
            produceFile = { file },
        )

    private fun record(
        id: Long,
        verifiedAt: Long = id,
        fileName: String = "img_$id.jpg",
    ) = VerifiedUpload(
        id = id,
        uri = "content://media/external/images/media/$id",
        size = 1234L,
        dateModified = 1_700_000_000L,
        serverUrl = "https://cloud.example.com/remote.php/dav/files/u/",
        folder = "Photos/2026/05/14",
        fileName = fileName,
        verifiedAt = verifiedAt,
    )

    @Test
    fun `empty by default`() =
        runTest(UnconfinedTestDispatcher()) {
            assertEquals(emptyList<VerifiedUpload>(), VerifiedUploadsStore(dataStore(backgroundScope)).records.first())
        }

    @Test
    fun `record round trips with every field`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(7L))
            assertEquals(listOf(record(7L)), store.records.first())
        }

    @Test
    fun `video flagged ids round trip`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            val videoId = (1L shl 62) or 42L
            store.recordVerified(record(videoId))
            assertEquals(
                videoId,
                store.records
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun `a newer record of the same id replaces the older one`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L, verifiedAt = 10L, fileName = "old.jpg"))
            store.recordVerified(record(1L, verifiedAt = 20L, fileName = "new.jpg"))
            assertEquals(listOf("new.jpg"), store.records.first().map { it.fileName })
        }

    @Test
    fun `duplicate ids in the stored file are deduped on read, newest wins`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            ds.edit {
                it[recordsKey] =
                    setOf(
                        json(record(5L, verifiedAt = 1L, fileName = "a.jpg")),
                        json(record(5L, verifiedAt = 9L, fileName = "b.jpg")),
                    )
            }
            assertEquals(listOf("b.jpg"), VerifiedUploadsStore(ds).records.first().map { it.fileName })
        }

    @Test
    fun `records are listed newest first`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L, verifiedAt = 10L))
            store.recordVerified(record(2L, verifiedAt = 30L))
            store.recordVerified(record(3L, verifiedAt = 20L))
            assertEquals(listOf(2L, 3L, 1L), store.records.first().map { it.id })
        }

    @Test
    fun `negative ids are never recorded`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(-1L))
            assertEquals(emptyList<VerifiedUpload>(), store.records.first())
        }

    @Test
    fun `malformed elements are skipped`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            val good = record(3L)
            val missingField = JSONObject(json(good)).apply { remove("folder") }.toString()
            val stringId = JSONObject(json(good)).put("id", "3").toString()
            val doubleSize = JSONObject(json(good)).put("size", 1.5).toString()
            val negativeId = JSONObject(json(good)).put("id", -4L).toString()
            val numericUri = JSONObject(json(good)).put("uri", 5).toString()
            ds.edit {
                it[recordsKey] =
                    setOf("not json", "{}", missingField, stringId, doubleSize, negativeId, numericUri, json(good))
            }
            assertEquals(listOf(good), VerifiedUploadsStore(ds).records.first())
        }

    @Test
    fun `forget removes only the given ids`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L))
            store.recordVerified(record(2L))
            store.recordVerified(record(3L))
            store.forget(setOf(1L, 3L, 99L))
            assertEquals(listOf(2L), store.records.first().map { it.id })
        }

    @Test
    fun `clear removes everything`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L))
            store.clear()
            assertEquals(emptyList<VerifiedUpload>(), store.records.first())
        }

    @Test
    fun `the cap drops the oldest records`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            // Seeded in one edit: recording 20,000 photos one by one would re-parse the set each time.
            ds.edit { prefs -> prefs[recordsKey] = (1L..MAX_VERIFIED_UPLOADS).map { json(record(it)) }.toSet() }
            val store = VerifiedUploadsStore(ds)

            store.recordVerified(record(MAX_VERIFIED_UPLOADS + 1L))

            val ids = store.records.first().map { it.id }
            assertEquals(MAX_VERIFIED_UPLOADS, ids.size)
            assertEquals(MAX_VERIFIED_UPLOADS + 1L, ids.first())
            assertFalse("the oldest record must be dropped", 1L in ids)
            assertTrue(2L in ids)
        }

    @Test
    fun `stored json holds no credential fields`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            VerifiedUploadsStore(ds).recordVerified(record(1L))
            val keys = JSONObject(ds.data.first()[recordsKey]!!.single()).keys().asSequence().toSet()
            assertEquals(
                setOf("id", "uri", "size", "dateModified", "serverUrl", "folder", "fileName", "verifiedAt"),
                keys,
            )
        }

    @Test
    fun `corrupt file yields no records and stays writable`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = file()
            f.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, -1, -2, -3))
            val store = VerifiedUploadsStore(dataStore(backgroundScope, f, withHandler = true))
            assertEquals(emptyList<VerifiedUpload>(), store.records.first())
            store.recordVerified(record(4L))
            assertEquals(listOf(4L), store.records.first().map { it.id })
        }

    private fun json(r: VerifiedUpload): String =
        JSONObject()
            .put("id", r.id)
            .put("uri", r.uri)
            .put("size", r.size)
            .put("dateModified", r.dateModified)
            .put("serverUrl", r.serverUrl)
            .put("folder", r.folder)
            .put("fileName", r.fileName)
            .put("verifiedAt", r.verifiedAt)
            .toString()

    // ── path binding (review finding 3) and parsing off the main thread (finding 6) ──

    private fun target(
        folder: String = "Photos/2026/05/14",
        fileName: String,
        serverUrl: String = "https://cloud.example.com/remote.php/dav/files/u/",
    ) = UploadTarget(serverUrl, folder, fileName)

    @Test
    fun `applyUpload drops every record at the target, case-insensitively, and stores the new one`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L, fileName = "a.jpg"))
            store.recordVerified(record(2L, fileName = "b.jpg"))

            store.applyUpload(target(fileName = "A.JPG"), record(3L, verifiedAt = 9L, fileName = "A.JPG"))

            assertEquals(
                setOf(2L, 3L),
                store.records
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `applyUpload without a record only forgets the target and the given ids`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L, fileName = "a.jpg"))
            store.recordVerified(record(2L, fileName = "b.jpg"))
            store.recordVerified(record(3L, fileName = "c.jpg"))

            store.applyUpload(target(fileName = "a.jpg"), forgetIds = setOf(3L))

            assertEquals(listOf(2L), store.records.first().map { it.id })
        }

    @Test
    fun `applyUpload keeps records of another server or folder`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = VerifiedUploadsStore(dataStore(backgroundScope))
            store.recordVerified(record(1L, fileName = "a.jpg"))

            store.applyUpload(target(folder = "Photos/2026/05/15", fileName = "a.jpg"))
            store.applyUpload(target(fileName = "a.jpg", serverUrl = "https://other.example.com/dav/"))

            assertEquals(listOf(1L), store.records.first().map { it.id })
        }

    @Test
    fun `records are parsed on the injected background dispatcher`() =
        runTest(UnconfinedTestDispatcher()) {
            val dispatches = AtomicInteger()
            val counting =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatches.incrementAndGet()
                        Dispatchers.Default.dispatch(context, block)
                    }
                }
            val store = VerifiedUploadsStore(dataStore(backgroundScope), parseDispatcher = counting)
            store.recordVerified(record(1L))

            assertEquals(listOf(1L), store.records.first().map { it.id })
            assertTrue(dispatches.get() > 0)
        }
}
