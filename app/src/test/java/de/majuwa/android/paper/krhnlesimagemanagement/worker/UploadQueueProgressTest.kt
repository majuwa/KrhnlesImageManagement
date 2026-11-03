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

    // --- chunked-upload progress (issue #69) ---

    private val hash = "ab".repeat(32)
    private val transferId = "krhnles-0123456789abcdef0123456789abcdef"

    private fun chunkedJson(vararg overrides: Pair<String, Any?>): String {
        val obj =
            JSONObject()
                .put("transferId", transferId)
                .put("chunkBytes", 10)
                .put("totalBytes", 25)
                .put("confirmedChunks", 2)
                .put("prefixSha256", hash)
                .put("original", true)
                .put("binding", hash)
        overrides.forEach { (key, value) -> if (value == REMOVE) obj.remove(key) else obj.put(key, value) }
        return ",\"chunked\":$obj"
    }

    private fun chunkedOf(vararg overrides: Pair<String, Any?>): ChunkedTransferState? =
        parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1, chunkedJson(*overrides))}]}""")!!
            .entries
            .single()
            .chunked

    @Test
    fun chunkedProgress_roundTripsThroughProgressJson() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1, chunkedJson())},${entry(2)}]}""")!!

        val state = queue.entries[0].chunked!!
        assertEquals(ChunkedTransferState(transferId, 10L, 25L, 2, hash, true, hash), state)
        assertEquals(null, queue.entries[1].chunked)

        val again = parseUploadQueue(queue.toProgressJson().toString())!!
        assertEquals(state, again.entries[0].chunked)
        assertEquals(null, again.entries[1].chunked)
    }

    @Test
    fun filesWithoutTheChunkedKey_parseUnchanged() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1)}]}""")!!

        assertEquals(null, queue.entries.single().chunked)
        assertFalse(queue.toProgressJson().toString().contains("chunked"))
    }

    @Test
    fun withChunked_updatesOneEntryAndNull_clearsIt() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1)},${entry(2)}]}""")!!
        val state = ChunkedTransferState(transferId, 10L, 25L, 1, hash, false, hash)

        val updated = queue.withChunked(1, state)

        assertEquals(null, updated.entries[0].chunked)
        assertEquals(state, updated.entries[1].chunked)
        assertEquals(state, parseUploadQueue(updated.toProgressJson().toString())!!.entries[1].chunked)
        assertEquals(null, updated.withChunked(1, null).entries[1].chunked)
    }

    @Test
    fun withUploaded_clearsTheChunkedProgress() {
        val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1, chunkedJson())}]}""")!!

        val done = queue.withUploaded(0)

        assertTrue(done.entries.single().uploaded)
        assertEquals(null, done.entries.single().chunked)
        assertFalse(done.toProgressJson().toString().contains("chunked"))
    }

    @Test
    fun retryJson_neverCarriesChunkedProgress_butProgressJsonKeepsIt() {
        val queue =
            parseUploadQueue(
                """{"folderName":"Trip","photos":[${entry(1, chunkedJson())},${entry(2, chunkedJson())}]}""",
            )!!

        val retry = queue.toRetryJson(listOf(0, 1)).toString()

        assertFalse(retry.contains("chunked"))
        assertEquals(null, parseUploadQueue(retry)!!.entries[0].chunked)
        assertTrue(queue.toProgressJson().toString().contains("chunked"))
    }

    @Test
    fun invalidChunkedProgress_isDroppedAndTheQueueStaysValid() {
        val invalid =
            listOf(
                listOf<Pair<String, Any?>>("transferId" to REMOVE),
                listOf<Pair<String, Any?>>("transferId" to "bad"),
                listOf<Pair<String, Any?>>("transferId" to 5),
                listOf<Pair<String, Any?>>("chunkBytes" to 0),
                listOf<Pair<String, Any?>>("chunkBytes" to -1),
                listOf<Pair<String, Any?>>("chunkBytes" to "10"),
                listOf<Pair<String, Any?>>("chunkBytes" to 10.5),
                listOf<Pair<String, Any?>>("totalBytes" to 0),
                listOf<Pair<String, Any?>>("totalBytes" to REMOVE),
                listOf<Pair<String, Any?>>("confirmedChunks" to -1),
                listOf<Pair<String, Any?>>("confirmedChunks" to 4), // more than the 3 chunks of 25 bytes
                listOf<Pair<String, Any?>>("confirmedChunks" to "2"),
                listOf<Pair<String, Any?>>("confirmedChunks" to 1.5),
                listOf<Pair<String, Any?>>("prefixSha256" to "zz"),
                listOf<Pair<String, Any?>>("prefixSha256" to "ab".repeat(31)),
                listOf<Pair<String, Any?>>("prefixSha256" to null),
                listOf<Pair<String, Any?>>("original" to "true"),
                listOf<Pair<String, Any?>>("original" to REMOVE),
                listOf<Pair<String, Any?>>("binding" to hash.dropLast(1)),
                listOf<Pair<String, Any?>>("binding" to REMOVE),
                listOf<Pair<String, Any?>>("chunkBytes" to 1, "totalBytes" to 10_001), // over 10,000 chunks
            )

        invalid.forEach { overrides -> assertEquals(overrides.toString(), null, chunkedOf(*overrides.toTypedArray())) }
    }

    @Test
    fun nonObjectChunkedValues_areDropped() {
        listOf("\"x\"", "5", "true", "[]", "null").forEach { value ->
            val queue = parseUploadQueue("""{"folderName":"Trip","photos":[${entry(1, ",\"chunked\":$value")}]}""")
            assertEquals(value, null, queue!!.entries.single().chunked)
        }
    }

    @Test
    fun invalidProgressOfOneEntry_doesNotAffectItsNeighbours() {
        val json =
            """{"folderName":"Trip","photos":[${entry(1, chunkedJson())},""" +
                """${entry(2, chunkedJson("transferId" to "bad"))},${entry(3, chunkedJson())}]}"""

        val queue = parseUploadQueue(json)!!

        assertNotNull(queue.entries[0].chunked)
        assertEquals(null, queue.entries[1].chunked)
        assertNotNull(queue.entries[2].chunked)
    }

    @Test
    fun chunkedProgress_doesNotChangeMarkersOrInterruptions() {
        val json =
            """{"interruptions":2,"folderName":"Trip","photos":[${entry(1, ",\"uploaded\":true")},""" +
                """${entry(2, chunkedJson())}]}"""

        val queue = parseUploadQueue(json)!!

        assertEquals(listOf(1), queue.pendingIndices)
        assertEquals(2, queue.interruptions)
        assertEquals(2, parseUploadQueue(queue.toProgressJson().toString())!!.interruptions)
    }

    private companion object {
        val REMOVE = Any()
    }
}
