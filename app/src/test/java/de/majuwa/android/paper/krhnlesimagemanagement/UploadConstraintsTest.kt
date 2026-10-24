package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UPLOAD_BACKOFF_DELAY_SECONDS
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.readWifiOnly
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadConstraints
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadWorkRequest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class UploadConstraintsTest {
    @Test
    fun uploadWorkRequestUsesExplicitExponentialBackoffForQueueFile() {
        val file = File("/data/upload_queue_1_a.json")

        val request = uploadWorkRequest(file, uploadConstraints(true))

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(UPLOAD_BACKOFF_DELAY_SECONDS), request.workSpec.backoffDelayDuration)
        assertEquals(file.absolutePath, request.workSpec.input.getString(UploadWorker.KEY_QUEUE_FILE))
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue("photo_upload" in request.tags)
    }

    @Test
    fun wifiOnlyRequiresUnmeteredNetwork() {
        assertEquals(NetworkType.UNMETERED, uploadConstraints(true).requiredNetworkType)
    }

    @Test
    fun wifiOnlyOffHasNoNetworkRequirement() {
        assertEquals(NetworkType.NOT_REQUIRED, uploadConstraints(false).requiredNetworkType)
    }

    @Test
    fun readWifiOnlyReturnsStoredValue() =
        runBlocking {
            assertFalse(readWifiOnly(flowOf(false)))
            assertTrue(readWifiOnly(flowOf(true)))
        }

    @Test
    fun readWifiOnlyFallsBackToUnmeteredOnTimeout() =
        runBlocking {
            val slow =
                flow {
                    delay(10_000)
                    emit(false)
                }
            assertTrue(readWifiOnly(slow, timeoutMs = 50))
        }

    @Test
    fun readWifiOnlyFallsBackToUnmeteredOnFailure() =
        runBlocking {
            val failing = flow<Boolean> { throw IllegalStateException("boom") }
            assertTrue(readWifiOnly(failing))
        }

    @Test
    fun readWifiOnlyRethrowsCancellation() {
        val cancelled = flow<Boolean> { throw CancellationException("cancelled") }
        val thrown = runCatching { runBlocking { readWifiOnly(cancelled) } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
