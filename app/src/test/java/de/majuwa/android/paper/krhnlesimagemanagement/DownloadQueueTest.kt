package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueue
import de.majuwa.android.paper.krhnlesimagemanagement.worker.DownloadQueueEntry
import de.majuwa.android.paper.krhnlesimagemanagement.worker.MAX_DOWNLOAD_RUN_ATTEMPTS
import de.majuwa.android.paper.krhnlesimagemanagement.worker.accountFingerprint
import de.majuwa.android.paper.krhnlesimagemanagement.worker.downloadQueueFilesDir
import de.majuwa.android.paper.krhnlesimagemanagement.worker.isDownloadQueueFile
import de.majuwa.android.paper.krhnlesimagemanagement.worker.parseDownloadQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val queue =
        DownloadQueue(
            serverUrl = "https://cloud.example/remote.php/dav/files/u",
            albumHref = "/remote.php/dav/files/u/Photos/A/",
            entries = listOf(DownloadQueueEntry("/a.jpg", downloaded = true), DownloadQueueEntry("/b.jpg")),
            pendingItem = "content://media/external_primary/images/media/7",
            interruptions = 2,
            account = "0123abcd",
        )

    @Test
    fun `a queue survives a round trip`() {
        assertEquals(queue, parseDownloadQueue(queue.toJson().toString()))
        assertEquals(listOf(1), queue.pendingIndices)
    }

    @Test
    fun `optional fields may be absent`() {
        val parsed = parseDownloadQueue("""{"serverUrl":"u","albumHref":"a","entries":[{"href":"x"}]}""")

        assertEquals(DownloadQueue("u", "a", listOf(DownloadQueueEntry("x"))), parsed)
    }

    @Test
    fun `a queue file without an account is unbound and an account survives the round trip`() {
        assertNull(parseDownloadQueue("""{"serverUrl":"u","albumHref":"a","entries":[]}""")?.account)
        assertEquals("0123abcd", parseDownloadQueue(queue.toJson().toString())?.account)
    }

    @Test
    fun `the account fingerprint is a hash of url and user that contains neither`() {
        val config = WebDavConfig("https://cloud.example/dav", "alice", "pw-secret")

        val fingerprint = config.accountFingerprint()

        assertTrue(Regex("[0-9a-f]{64}").matches(fingerprint))
        assertFalse(fingerprint.contains("alice"))
        assertEquals(fingerprint, config.copy(password = "other").accountFingerprint())
        assertFalse(fingerprint == config.copy(username = "bob").accountFingerprint())
        assertFalse(fingerprint == config.copy(url = "https://cloud.example/dav2").accountFingerprint())
        // "ab"+"c" and "a"+"bc" must not collide.
        assertFalse(
            WebDavConfig("ab", "c", "p").accountFingerprint() == WebDavConfig("a", "bc", "p").accountFingerprint(),
        )
    }

    @Test
    fun `a missing or non string field is malformed`() {
        listOf(
            """{"albumHref":"a","entries":[]}""",
            """{"serverUrl":"u","entries":[]}""",
            """{"serverUrl":"u","albumHref":"a"}""",
            """{"serverUrl":5,"albumHref":"a","entries":[]}""",
            """{"serverUrl":null,"albumHref":"a","entries":[]}""",
            """{"serverUrl":"u","albumHref":"a","entries":{}}""",
            """{"serverUrl":"u","albumHref":"a","entries":[{"href":1}]}""",
            """{"serverUrl":"u","albumHref":"a","entries":[{}]}""",
            """{"serverUrl":"u","albumHref":"a","entries":["x"]}""",
            """{"serverUrl":"u","albumHref":"a","entries":[],"pendingItem":3}""",
            """{"serverUrl":"u","albumHref":"a","entries":[],"account":3}""",
            """{"serverUrl":"u","albumHref":"a","entries":[],"account":null}""",
            """{"serverUrl":"u","albumHref":"a","entries":[],"pendingItem":null}""",
            "not json",
            "",
        ).forEach { assertNull(it, parseDownloadQueue(it)) }
    }

    @Test
    fun `downloaded counts only as a json true`() {
        val parsed =
            parseDownloadQueue(
                """{"serverUrl":"u","albumHref":"a","entries":""" +
                    """[{"href":"x","downloaded":"true"},{"href":"y","downloaded":true}]}""",
            )

        assertEquals(listOf(false, true), parsed?.entries?.map { it.downloaded })
    }

    @Test
    fun `interruptions are clamped`() {
        fun parsed(value: String) =
            parseDownloadQueue(
                """{"serverUrl":"u","albumHref":"a","entries":[],"interruptions":$value}""",
            )?.interruptions

        assertEquals(0, parsed("-3"))
        assertEquals(0, parsed("\"x\""))
        assertEquals(1, parsed("1.9"))
        assertEquals(MAX_DOWNLOAD_RUN_ATTEMPTS, parsed("999999999999"))
        assertEquals(MAX_DOWNLOAD_RUN_ATTEMPTS, parsed("1e30"))
        assertEquals(3, parsed("3"))
    }

    @Test
    fun `only app named files directly in the no backup dir are queue files`() {
        val dir = downloadQueueFilesDir(context)
        assertEquals(context.noBackupFilesDir, dir)
        val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"

        assertTrue(isDownloadQueueFile(context, File(dir, "download_queue_1700000000000_$uuid.json")))
        listOf(
            File(dir, "upload_queue_1700000000000_$uuid.json"),
            File(dir, "retry_queue_$uuid.json"),
            File(dir, "download_queue_x_$uuid.json"),
            File(dir, "download_queue_1_$uuid.json.tmp"),
            File(dir, "sub/download_queue_1_$uuid.json"),
            File(dir, "../download_queue_1_$uuid.json"),
            File(context.filesDir, "download_queue_1_$uuid.json"),
            File("/data/local/tmp/download_queue_1_$uuid.json"),
        ).forEach { assertFalse(it.path, isDownloadQueueFile(context, it)) }
    }

    @Test
    fun `the written json has no credential like key`() {
        val keys = mutableSetOf<String>()

        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.keys().forEach { key ->
                        keys += key
                        collect(value.get(key))
                    }
                }

                is org.json.JSONArray -> {
                    (0 until value.length()).forEach { collect(value.get(it)) }
                }
            }
        }
        collect(queue.toJson())

        assertNotNull(keys.find { it == "serverUrl" })
        assertTrue(
            keys.toString(),
            keys.none { key ->
                listOf("user", "password", "token", "secret").any { key.lowercase().contains(it) }
            },
        )
    }

    @Test
    fun `progress helpers update a copy`() {
        val next = queue.withDownloaded(1).withPendingItem(null).withInterruption()

        assertEquals(emptyList<Int>(), next.pendingIndices)
        assertNull(next.pendingItem)
        assertEquals(3, next.interruptions)
        assertEquals(listOf(1), queue.pendingIndices)
    }
}
