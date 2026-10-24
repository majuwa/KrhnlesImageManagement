package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeShareProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ProviderGate
import de.majuwa.android.paper.krhnlesimagemanagement.worker.resolveStagedFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A stream that never ends. */
private class EndlessStream : InputStream() {
    var bytesRead = 0L

    override fun read(): Int {
        bytesRead++
        return 7
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        bytesRead += len
        b.fill(7, off, off + len)
        return len
    }
}

/** A stream whose read blocks until it is closed (a stuck provider). */
private class BlockingStream : InputStream() {
    val readStarted = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    val closed = AtomicBoolean(false)

    override fun read(): Int {
        readStarted.countDown()
        closedLatch.await(10, TimeUnit.SECONDS)
        throw IOException("closed")
    }

    override fun close() {
        closed.set(true)
        closedLatch.countDown()
    }
}

@RunWith(RobolectricTestRunner::class)
class ShareStagingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val resolver = context.contentResolver
    private lateinit var stagingDir: File
    private lateinit var queueDir: File
    private var now = 1_000_000_000_000L
    private var free = Long.MAX_VALUE / 4

    private val limits =
        StagingLimits(
            maxFileBytes = 1_000,
            maxShareBytes = 2_500,
            maxFolderBytes = 10_000,
            safetyMarginBytes = 100,
            retentionMillis = TimeUnit.DAYS.toMillis(7),
        )

    /** Unfinished retry work by work name; absent = finished, null value = unknown. */
    private val retryWork = mutableMapOf<String, Boolean?>()

    private fun staging(
        limits: StagingLimits = this.limits,
        opener: SharedSourceOpener? = null,
    ) = ShareStaging(
        stagingDir,
        listOf(queueDir),
        freeBytes = { free },
        limits = limits,
        clock = { now },
        hasUnfinishedRetryWork = { name -> if (name in retryWork) retryWork[name] else false },
        sourceOpener = opener ?: ProviderSourceOpener,
    )

    /** A staging whose every open returns [stream] (streams that block or never end). */
    private fun streaming(stream: InputStream) = staging(opener = { _, _, _ -> stream })

    @Before
    fun setUp() {
        stagingDir = File(temp.root, "share_staging")
        queueDir = temp.newFolder("queues")
        Robolectric.setupContentProvider(FakeShareProvider::class.java, FakeShareProvider.AUTHORITY)
    }

    private val gates = mutableListOf<ProviderGate>()

    @After
    fun tearDown() {
        gates.forEach { it.release() }
        FakeShareProvider.items.clear()
    }

    /** Item [id] served by the provider through `openTypedAssetFile(…, signal)`. */
    private fun register(
        id: String,
        content: ByteArray,
    ): Uri {
        FakeShareProvider.items[id] = FakeShareProvider.Item(content = content)
        return FakeShareProvider.uri(id)
    }

    private fun gated(
        id: String,
        honoursCancel: Boolean,
    ): Pair<Uri, ProviderGate> {
        val gate = ProviderGate(honoursCancel).also { gates += it }
        FakeShareProvider.items[id] = FakeShareProvider.Item(content = bytes(10), openGate = gate)
        return FakeShareProvider.uri(id) to gate
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("condition not met in time", condition())
    }

    private fun bytes(n: Int) = ByteArray(n) { it.toByte() }

    private fun stagedFiles() = stagingDir.listFiles().orEmpty().toList()

    private fun stage(
        staging: ShareStaging,
        uri: Uri,
        budget: ShareBudget,
        reportedSize: Long = 0,
    ) = runBlocking { staging.stage(resolver, uri, reportedSize, budget) }

    private fun budget(staging: ShareStaging) = requireNotNull(runBlocking { staging.newBudget() })

    @Test
    fun stage_copiesTheBytesIntoAUuidNamedFile() {
        val content = bytes(600)
        val uri = register("1", content)
        val staging = staging()
        val budget = budget(staging)

        val result = stage(staging, uri, budget) as StageResult.Staged

        assertNotNull(UUID.fromString(result.name))
        assertEquals(600L, result.bytes)
        assertArrayEquals(content, File(stagingDir, result.name).readBytes())
        assertEquals(1_900L, budget.shareRemaining)
    }

    @Test
    fun reportedSizeOverTheCaps_isSkippedWithoutOpening() {
        var opened = 0
        val staging =
            staging(
                opener = { _, _, _ ->
                    opened++
                    ByteArrayInputStream(bytes(10))
                },
            )
        val budget = budget(staging)
        val uri = FakeShareProvider.uri("1")

        assertEquals(StageResult.Skipped(SkipReason.TooLarge), stage(staging, uri, budget, reportedSize = 1_001))
        budget.consume(1_600) // 900 of the 2,500 share budget left
        assertEquals(StageResult.Skipped(SkipReason.ShareLimit), stage(staging, uri, budget, reportedSize = 950))
        assertEquals(0, opened)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun endlessStream_stopsAtThePerFileCap_andLeavesNoPartialFile() {
        val endless = EndlessStream()
        val staging = streaming(endless)

        assertEquals(
            StageResult.Skipped(SkipReason.TooLarge),
            stage(staging, FakeShareProvider.uri("1"), budget(staging)),
        )
        assertTrue(endless.bytesRead < 1_000L + 128 * 1024)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun providerContentOverThePerFileCap_isSkippedAsTooLarge() {
        val uri = register("1", bytes(1_500))
        val staging = staging()

        assertEquals(StageResult.Skipped(SkipReason.TooLarge), stage(staging, uri, budget(staging)))
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun shareBudget_isEnforcedOnCopiedBytes_andReportedAsTheShareLimit() {
        val uris = (1..3).map { id -> register("$id", bytes(900)) }
        val staging = staging()
        val budget = budget(staging)

        val results = uris.map { stage(staging, it, budget) }

        assertTrue(results[0] is StageResult.Staged)
        assertTrue(results[1] is StageResult.Staged)
        // 2 × 900 copied, 700 left of the 2,500 share budget.
        assertEquals(StageResult.Skipped(SkipReason.ShareLimit), results[2])
        assertEquals(2, stagedFiles().size)
    }

    @Test
    fun notEnoughFreeSpace_hasNoBudget() {
        free = 100
        assertNull(runBlocking { staging().newBudget() })
    }

    @Test
    fun folderCap_countsExistingStagedFiles() {
        stagingDir.mkdirs()
        File(stagingDir, UUID.randomUUID().toString()).writeBytes(ByteArray(10_000))

        assertNull(runBlocking { staging().newBudget() })
    }

    @Test
    fun runningOutOfSpaceWhileCopying_failsTheShare() {
        free = 600 // 500 usable after the safety margin, less than one 900 byte photo
        val uri = register("1", bytes(900))
        val staging = staging()

        assertEquals(StageResult.Failed, stage(staging, uri, budget(staging)))
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun unreadableItem_isSkipped() {
        FakeShareProvider.items["denied"] = FakeShareProvider.Item(openFailure = SecurityException("revoked"))
        val staging = staging()

        assertEquals(
            StageResult.Skipped(SkipReason.Unreadable),
            stage(staging, FakeShareProvider.uri("denied"), budget(staging)),
        )
        FakeShareProvider.items["missing"] = FakeShareProvider.Item(content = null)
        assertEquals(
            StageResult.Skipped(SkipReason.Unreadable),
            stage(staging, FakeShareProvider.uri("missing"), budget(staging)),
        )
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun readFailureMidStream_isSkipped_andLeavesNoPartialFile() {
        val broken =
            object : InputStream() {
                var count = 0

                override fun read(): Int = if (count++ < 10) 1 else throw IOException("broken pipe")
            }
        val staging = streaming(broken)

        assertEquals(
            StageResult.Skipped(SkipReason.Unreadable),
            stage(staging, FakeShareProvider.uri("1"), budget(staging)),
        )
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun writeFailure_failsTheShare() {
        val uri = register("1", bytes(10))
        val staging = staging()
        val budget = budget(staging)
        stagingDir.deleteRecursively()

        assertEquals(StageResult.Failed, stage(staging, uri, budget))
    }

    @Test
    fun cancellation_closesTheBlockedStream_andDeletesThePartialFile() {
        val blocking = BlockingStream()
        val staging = streaming(blocking)
        val budget = budget(staging)

        runBlocking {
            val copy =
                async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
                    staging.stage(resolver, FakeShareProvider.uri("1"), 0, budget)
                }
            assertTrue(blocking.readStarted.await(5, TimeUnit.SECONDS))
            copy.cancel()
            withTimeout(5_000) { copy.join() }
            assertTrue(copy.isCancelled)
        }

        awaitCondition { blocking.closed.get() && stagedFiles().isEmpty() }
    }

    @Test
    fun cancellationDuringABlockedOpen_cancelsTheProvidersSignal() {
        val (uri, gate) = gated("1", honoursCancel = true)
        val staging = staging()
        val budget = budget(staging)

        runBlocking {
            val copy = async(Dispatchers.IO) { staging.stage(resolver, uri, 0, budget) }
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            copy.cancel()
            withTimeout(5_000) { copy.join() }
        }

        assertTrue(gate.cancelled)
        awaitCondition { stagedFiles().isEmpty() }
    }

    @Test
    fun providerIgnoringTheSignal_doesNotBlockCancellation() {
        val (uri, gate) = gated("1", honoursCancel = false)
        val staging = staging()
        val budget = budget(staging)

        runBlocking {
            val copy = async(Dispatchers.IO) { staging.stage(resolver, uri, 0, budget) }
            assertTrue(gate.started.await(5, TimeUnit.SECONDS))
            copy.cancel()
            // Returns although the provider is still blocked in its open.
            withTimeout(2_000) { copy.join() }
        }

        assertTrue(gate.cancelled)
        // When the provider finally answers, the abandoned copy is discarded.
        gate.release()
        awaitCondition { stagedFiles().isEmpty() }
        Thread.sleep(100)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun providerNotAnsweringWithinTheItemTimeout_isSkippedAsUnreadable() {
        val (uri, gate) = gated("1", honoursCancel = false)
        val staging = staging(limits.copy(itemTimeoutMillis = 200))
        val budget = budget(staging)

        val started = System.currentTimeMillis()
        assertEquals(StageResult.Skipped(SkipReason.Unreadable), stage(staging, uri, budget))
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertTrue(gate.cancelled)

        gate.release()
        Thread.sleep(200)
        assertTrue(stagedFiles().isEmpty())
        assertEquals(2_500L, budget.shareRemaining)
    }

    @Test
    fun completedCopyWhoseResultRacesACancellation_leavesNoOrphan() {
        // The copy finishes, but the caller is cancelled before it can take the result.
        val staging = staging()
        val budget = budget(staging)
        val uri = register("1", bytes(10))

        val owned = java.util.concurrent.CopyOnWriteArrayList<String>()
        repeat(20) {
            runBlocking {
                // Like ShareViewModel: a returned name is recorded without a suspension point.
                val copy =
                    launch(Dispatchers.IO) {
                        (staging.stage(resolver, uri, 0, budget) as? StageResult.Staged)?.let { owned += it.name }
                    }
                Thread.sleep(it.toLong() % 3)
                copy.cancel()
                copy.join()
            }
        }
        // Every file is either owned by the caller or removed by the abandoned copy.
        owned.forEach { File(stagingDir, it).delete() }

        awaitCondition { stagedFiles().isEmpty() }
    }

    @Test
    fun delete_onlyDeletesValidStagedNames() {
        stagingDir.mkdirs()
        val staged = File(stagingDir, UUID.randomUUID().toString()).apply { writeText("x") }
        val outside = File(temp.root, "secret").apply { writeText("x") }

        staging().delete(listOf("../secret", "secret", staged.name))

        assertFalse(staged.exists())
        assertTrue(outside.exists())
        assertNull(resolveStagedFile(stagingDir, "../secret"))
        assertNull(resolveStagedFile(stagingDir, staged.name.uppercase()))
    }

    // ── sweep ────────────────────────────────────────────────────────────────

    private val eightDays = TimeUnit.DAYS.toMillis(8)

    private fun stagedFile(ageMillis: Long): File {
        stagingDir.mkdirs()
        return File(stagingDir, UUID.randomUUID().toString()).apply {
            writeText("photo")
            setLastModified(now - ageMillis)
        }
    }

    private fun queueJson(vararg staged: String) =
        JSONObject()
            .put("folderName", "Trip")
            .put(
                "photos",
                JSONArray().apply {
                    staged.forEach {
                        put(
                            JSONObject()
                                .put("uri", "content://x/1")
                                .put("mimeType", "image/jpeg")
                                .put("displayName", "a.jpg")
                                .put("staged", it),
                        )
                    }
                },
            ).toString()

    private fun uploadQueue(vararg staged: String) =
        File(queueDir, "upload_queue_1_${UUID.randomUUID()}.json").apply { writeText(queueJson(*staged)) }

    private fun retryQueue(vararg staged: String) =
        File(queueDir, "retry_queue_${UUID.randomUUID()}.json").apply { writeText(queueJson(*staged)) }

    @Test
    fun sweep_deletesOldUnreferencedFiles_keepsYoungAndReferencedOnes() {
        val oldOrphan = stagedFile(eightDays)
        val young = stagedFile(TimeUnit.DAYS.toMillis(1))
        val oldQueued = stagedFile(eightDays)
        val queue = uploadQueue(oldQueued.name)

        staging().sweep()

        assertFalse(oldOrphan.exists())
        assertTrue(young.exists())
        assertTrue(oldQueued.exists())
        assertTrue(queue.exists())
    }

    @Test
    fun sweep_oldFileReferencedOnlyByARetryFile_isDeletedWithTheRetryFile() {
        val old = stagedFile(eightDays)
        val retry = retryQueue(old.name).apply { setLastModified(now - eightDays) }
        val youngRetried = stagedFile(TimeUnit.DAYS.toMillis(2))
        val youngRetry = retryQueue(youngRetried.name)

        staging().sweep()

        assertFalse(old.exists())
        assertFalse(retry.exists())
        assertTrue(youngRetried.exists())
        assertTrue(youngRetry.exists())
    }

    @Test
    fun sweep_oldStagedFileOfAYoungRetryFile_isKept() {
        // The staged copy is from the first upload (8 days ago), the retry file was written or
        // tapped recently: the retry window counts from the retry file.
        val old = stagedFile(eightDays)
        val retry = retryQueue(old.name).apply { setLastModified(now - TimeUnit.DAYS.toMillis(1)) }

        staging().sweep()

        assertTrue(old.exists())
        assertTrue(retry.exists())
    }

    @Test
    fun sweep_oldRetryFileWithUnfinishedOrUnknownRetryWork_isKept() {
        val running = stagedFile(eightDays)
        val runningRetry = retryQueue(running.name).apply { setLastModified(now - eightDays) }
        retryWork[runningRetry.name] = true
        val unknown = stagedFile(eightDays)
        val unknownRetry = retryQueue(unknown.name).apply { setLastModified(now - eightDays) }
        retryWork[unknownRetry.name] = null

        staging().sweep()

        assertTrue(running.exists() && runningRetry.exists())
        assertTrue(unknown.exists() && unknownRetry.exists())
    }

    @Test
    fun sweep_keepsAFileReferencedByAnUploadQueueEvenIfARetryFileAlsoReferencesIt() {
        val old = stagedFile(eightDays)
        uploadQueue(old.name)
        val retry = retryQueue(old.name)

        staging().sweep()

        assertTrue(old.exists())
        assertTrue(retry.exists())
    }

    @Test
    fun sweep_failsClosedWhenAQueueFileIsUnreadable() {
        val oldOrphan = stagedFile(eightDays)
        File(queueDir, "upload_queue_1_${UUID.randomUUID()}.json").writeText("{not json")

        staging().sweep()

        assertTrue(oldOrphan.exists())
    }

    @Test
    fun sweep_withoutQueueFolder_stillDeletesOldOrphans() {
        val oldOrphan = stagedFile(eightDays)
        queueDir.deleteRecursively()

        staging().sweep()

        assertFalse(oldOrphan.exists())
    }
}
