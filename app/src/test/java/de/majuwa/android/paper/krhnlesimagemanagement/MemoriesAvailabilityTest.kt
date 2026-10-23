package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.MemoriesAvailability
import de.majuwa.android.paper.krhnlesimagemanagement.data.MemoriesCheck
import de.majuwa.android.paper.krhnlesimagemanagement.data.memoriesCheckForStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** [MemoriesAvailability] with a fake `check` and a fake clock: no real time, no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesAvailabilityTest {
    private var nowMs = 0L
    private var calls = 0

    private fun TestScope.availability(
        isNextcloud: Boolean = true,
        checkTimeoutMs: Long = 1_000,
        backoffMs: Long = 100,
        maxBackoffMs: Long = 400,
        lockTimeoutMs: Long = checkTimeoutMs,
        check: suspend () -> MemoriesCheck,
    ) = MemoriesAvailability(
        isNextcloud = isNextcloud,
        checkTimeoutMs = checkTimeoutMs,
        retryBackoffMs = backoffMs,
        maxRetryBackoffMs = maxBackoffMs,
        lockTimeoutMs = lockTimeoutMs,
        nanoTime = { TimeUnit.MILLISECONDS.toNanos(nowMs) },
        scope = backgroundScope,
        check = {
            calls++
            check()
        },
    )

    @Test
    fun `definite answers are cached`() =
        runTest {
            val yes = availability { MemoriesCheck.AVAILABLE }
            repeat(10) { yes.ensureChecked() }
            assertEquals(true, yes.available)
            assertEquals(1, calls)

            calls = 0
            val no = availability { MemoriesCheck.UNAVAILABLE }
            repeat(10) { no.ensureChecked() }
            assertEquals(false, no.available)
            assertEquals(1, calls)
        }

    @Test
    fun `non Nextcloud decides without a check`() =
        runTest {
            val a = availability(isNextcloud = false) { MemoriesCheck.AVAILABLE }
            a.ensureChecked()
            assertEquals(false, a.available)
            assertEquals(0, calls)
        }

    @Test
    fun `status mapping treats 429 and 5xx as inconclusive`() {
        assertEquals(MemoriesCheck.AVAILABLE, memoriesCheckForStatus(200))
        for (code in listOf(429, 500, 502, 503, 599)) {
            assertEquals("$code", MemoriesCheck.INCONCLUSIVE, memoriesCheckForStatus(code))
        }
        for (code in listOf(204, 301, 401, 403, 404, 410)) {
            assertEquals("$code", MemoriesCheck.UNAVAILABLE, memoriesCheckForStatus(code))
        }
    }

    @Test
    fun `inconclusive is not cached, backs off and retries without blocking the caller`() =
        runTest {
            val retry = CompletableDeferred<MemoriesCheck>()
            var first = true
            val a =
                availability {
                    if (first) {
                        first = false
                        MemoriesCheck.INCONCLUSIVE
                    } else {
                        retry.await()
                    }
                }
            a.ensureChecked()
            assertNull(a.available)
            assertEquals(1, calls)

            repeat(20) { a.ensureChecked() } // within the backoff: no request
            assertEquals(1, calls)

            nowMs = 100
            val before = currentTime
            a.ensureChecked() // starts the retry in the background and returns at once
            assertEquals(before, currentTime)
            runCurrent()
            assertEquals(2, calls)
            assertNull(a.available)
            repeat(20) { a.ensureChecked() } // retry in flight: no second request
            runCurrent()
            assertEquals(2, calls)

            retry.complete(MemoriesCheck.AVAILABLE)
            runCurrent()
            assertEquals(true, a.available)
        }

    @Test
    fun `close cancels an in-flight retry and launches no further ones`() =
        runTest {
            var cancelled = false
            var first = true
            val a =
                availability {
                    if (first) {
                        first = false
                        MemoriesCheck.INCONCLUSIVE
                    } else {
                        try {
                            delay(Long.MAX_VALUE).let { MemoriesCheck.AVAILABLE }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelled = true
                            throw e
                        }
                    }
                }
            a.ensureChecked()
            nowMs = 100
            a.ensureChecked()
            runCurrent()
            assertEquals(2, calls)

            a.close()
            runCurrent()
            assertTrue("in-flight retry (and its HTTP call) is cancelled", cancelled)
            assertNull(a.available)

            nowMs = 1_000_000
            repeat(5) { a.ensureChecked() }
            runCurrent()
            assertEquals("no retry after close", 2, calls)
            a.close() // idempotent
        }

    @Test
    fun `close before the first retry launches none`() =
        runTest {
            val a = availability { MemoriesCheck.INCONCLUSIVE }
            a.ensureChecked()
            a.close()
            nowMs = 1_000
            a.ensureChecked()
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun `hanging retry never delays the caller`() =
        runTest {
            val a = availability(checkTimeoutMs = 1_000) { delay(Long.MAX_VALUE).let { MemoriesCheck.AVAILABLE } }
            a.ensureChecked() // first attempt is awaited up to the timeout
            assertEquals(1_000, currentTime)
            assertNull(a.available)

            nowMs = 100
            val before = currentTime
            a.ensureChecked()
            assertEquals(before, currentTime)
            runCurrent()
            assertEquals(2, calls) // the retry runs in the background and times out there
            advanceUntilIdle()
            assertNull(a.available)
        }

    @Test
    fun `backoff doubles per failure and is capped`() =
        runTest {
            val a = availability(backoffMs = 100, maxBackoffMs = 400) { MemoriesCheck.INCONCLUSIVE }
            a.ensureChecked() // failure 1 at t=0 -> next at 100
            val expectedGaps = listOf(200L, 400L, 400L)
            var at = 100L
            for (gap in expectedGaps) {
                nowMs = at - 1
                a.ensureChecked()
                runCurrent()
                val before = calls
                nowMs = at
                a.ensureChecked()
                runCurrent()
                assertEquals("retry due at $at", before + 1, calls)
                at += gap
            }
        }

    @Test
    fun `failing check is inconclusive and does not propagate`() =
        runTest {
            val a = availability { error("boom") }
            a.ensureChecked()
            assertNull(a.available)
            repeat(10) { a.ensureChecked() }
            assertEquals(1, calls)
        }

    @Test
    fun `timeout while waiting for the lock falls back without scheduling a retry`() =
        runTest {
            val release = CompletableDeferred<MemoriesCheck>()
            val a = availability(checkTimeoutMs = 10_000, lockTimeoutMs = 100) { release.await() }
            val holder = launch { a.ensureChecked() }
            runCurrent()
            val start = currentTime
            a.ensureChecked() // waits for the lock, gives up after lockTimeoutMs
            assertEquals(100, currentTime - start)
            assertEquals(1, calls)
            release.complete(MemoriesCheck.AVAILABLE)
            holder.join()
            assertEquals(true, a.available) // the waiter did not poison the state with a backoff
        }

    @Test
    fun `late lock holder still gets a full check timeout`() =
        runTest {
            var n = 0
            val a =
                availability(checkTimeoutMs = 1_000, lockTimeoutMs = 1_000, backoffMs = 0) {
                    if (++n == 1) {
                        delay(900)
                        MemoriesCheck.INCONCLUSIVE
                    } else {
                        delay(950) // would exceed a timeout shared with the 900 ms spent waiting
                        MemoriesCheck.AVAILABLE
                    }
                }
            val first = async { a.ensureChecked() }
            runCurrent()
            a.ensureChecked()
            first.await()
            assertEquals(true, a.available)
            assertEquals(2, calls)
        }

    @Test
    fun `cancellation mid check is not cached`() =
        runTest {
            var hang = true
            val a =
                availability {
                    if (hang) awaitCancellationCheck() else MemoriesCheck.AVAILABLE
                }
            val job = launch { a.ensureChecked() }
            runCurrent()
            assertEquals(1, calls)
            job.cancelAndJoin()
            assertNull(a.available)

            hang = false
            a.ensureChecked() // no backoff: re-checked immediately and awaited
            assertEquals(2, calls)
            assertTrue(a.available == true)
            assertFalse(a.available == false)
        }

    private suspend fun awaitCancellationCheck(): MemoriesCheck {
        delay(Long.MAX_VALUE)
        return MemoriesCheck.INCONCLUSIVE
    }
}
