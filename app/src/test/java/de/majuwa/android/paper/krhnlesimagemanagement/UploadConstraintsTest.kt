package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.work.NetworkType
import de.majuwa.android.paper.krhnlesimagemanagement.worker.readWifiOnly
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadConstraints
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UploadConstraintsTest {
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
