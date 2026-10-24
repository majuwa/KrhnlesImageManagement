package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** Per-entry upload progress in queue files: `uploaded` markers, backward compatibility, atomic rewrite. */
@RunWith(RobolectricTestRunner::class)
class UploadQueueProgressTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val staged = UUID.randomUUID().toString()

    private fun entry(
        id: Long,
        extra: String = "",
    ) = """{"id":$id,"uri":"content://media/external/images/media/$id","mimeType":"image/jpeg",""" +
        """"displayName":"$id.jpg"$extra}"""

    private val stagedEntry =
        """{"uri":"content://share/x","mimeType":"image/jpeg","displayName":"s.jpg","staged":"$staged"}"""

    @After
    fun tearDown() {
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun queueWithoutMarkers_hasEveryEntryPending() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1)},${entry(2)}]}""")!!

        assertEquals(listOf(0, 1), queue.pendingIndices)
        assertTrue(queue.entries.none { it.uploaded })
    }

    @Test
    fun onlyJsonTrueMarksAnEntryUploaded() {
        val json =
            """{"folderName":"Trip","photos":[${entry(1, ",\"uploaded\":true")},""" +
                """${entry(2, ",\"uploaded\":\"true\"")},${entry(3, ",\"uploaded\":false")}]}"""

        val queue = parseUploadQueue(json)!!

        assertEquals(listOf(1, 2), queue.pendingIndices)
    }

    @Test
    fun progressJson_roundTripsMarkersInBothFormatsAndKeepsStagedNames() {
        val single = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1)},$stagedEntry]}""")!!
        val perPhoto =
            parseUploadQueue(
                """{"label":"L","photos":[${entry(1, ",\"folderName\":\"2026/05/01\"")},""" +
                    """${entry(2, ",\"folderName\":\"2026/05/02\"")}]}""",
            )!!

        val singleBack = parseUploadQueue(single.withUploaded(1).toProgressJson().toString())!!
        val perPhotoBack = parseUploadQueue(perPhoto.withUploaded(0).toProgressJson().toString())!!

        assertEquals(single.withUploaded(1), singleBack)
        assertEquals(listOf(0), singleBack.pendingIndices)
        assertEquals(setOf(staged), stagedNamesOf(single.withUploaded(1).toProgressJson().toString()))
        assertEquals(perPhoto.withUploaded(0), perPhotoBack)
        assertEquals(listOf(1), perPhotoBack.pendingIndices)
    }

    @Test
    fun retryJson_neverCarriesTheMarker() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1)},${entry(2)}]}""")!!.withUploaded(0)

        val retry = queue.toRetryJson(listOf(0, 1))

        val photos = retry.getJSONArray("photos")
        assertFalse(photos.getJSONObject(0).has("uploaded"))
        assertEquals(listOf(0, 1), parseUploadQueue(retry.toString())!!.pendingIndices)
    }

    @Test
    fun rewriteQueueFile_replacesTheContentAndLeavesNoTempFile() {
        val file = File(queueFilesDir(context), "upload_queue_1_${UUID.randomUUID()}.json").apply { writeText("{}") }

        assertTrue(rewriteQueueFile(file, """{"folderName":"Trip","photos":[]}"""))

        assertEquals("Trip", JSONObject(file.readText()).getString("folderName"))
        assertFalse(queueTempFile(file).exists())
        assertFalse(isWorkerQueueFile(context, queueTempFile(file)))
    }

    @Test
    fun rewriteQueueFile_failureKeepsTheOldContent() {
        val dir = File(queueFilesDir(context), "sub").apply { mkdirs() }
        val file = File(dir, "upload_queue_1_${UUID.randomUUID()}.json").apply { writeText("old") }
        // The temp file path is a directory: writing it fails.
        assertTrue(queueTempFile(file).mkdirs())

        assertFalse(rewriteQueueFile(file, "new"))

        assertEquals("old", file.readText())
        queueTempFile(file).delete()
        assertNotNull(dir.listFiles())
        dir.deleteRecursively()
    }

    @Test
    fun deleteQueueFile_alsoRemovesALeftoverTempFile() {
        val file = File(queueFilesDir(context), "upload_queue_1_${UUID.randomUUID()}.json").apply { writeText("{}") }
        queueTempFile(file).writeText("partial")

        deleteQueueFile(context, file)

        assertFalse(file.exists())
        assertFalse(queueTempFile(file).exists())
    }
}
