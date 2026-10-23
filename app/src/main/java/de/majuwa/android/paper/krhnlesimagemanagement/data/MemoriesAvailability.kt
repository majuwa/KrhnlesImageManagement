package de.majuwa.android.paper.krhnlesimagemanagement.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MemoriesCheck { AVAILABLE, UNAVAILABLE, INCONCLUSIVE }

/**
 * Maps the HTTP status of the Memories `describe` call: 200 -> available; 429 and 5xx are transient
 * -> inconclusive (retried with backoff); any other status -> definitely unavailable.
 */
internal fun memoriesCheckForStatus(code: Int): MemoriesCheck =
    when {
        code == 200 -> MemoriesCheck.AVAILABLE
        code == 429 || code in 500..599 -> MemoriesCheck.INCONCLUSIVE
        else -> MemoriesCheck.UNAVAILABLE
    }

/**
 * Lazily checks whether Nextcloud Memories is available.
 *
 * - A definite answer ([MemoriesCheck.AVAILABLE] / [MemoriesCheck.UNAVAILABLE]) is cached in
 *   [available]; no further request is sent.
 * - Only the first attempt per instance is awaited by callers (bounded by [checkTimeoutMs]). A
 *   timeout, an I/O failure or any other non-cancellation failure of [check] is inconclusive: it is
 *   not cached, the caller falls back to the WebDAV URL, and the next attempt is not made before an
 *   exponential backoff ([retryBackoffMs], doubled per consecutive failure, capped at
 *   [maxRetryBackoffMs]) has passed.
 * - Retries run fire-and-forget in [scope] (bounded by [checkTimeoutMs]) so a hanging endpoint never
 *   delays a listing or URL resolution again; callers keep using the WebDAV fallback until a retry
 *   produced a definite answer.
 * - Cancellation is never cached: a cancelled first attempt is simply repeated by the next call.
 * - [close] cancels an in-flight retry (and with it the HTTP call) and prevents new ones; the owner
 *   calls it when the repository is dropped so no request is sent with outdated credentials.
 * - Locks are acquired with a timeout ([lockTimeoutMs]) and the check has its own [checkTimeoutMs],
 *   so a late lock holder still gets a full check.
 */
internal class MemoriesAvailability(
    private val isNextcloud: Boolean,
    private val checkTimeoutMs: Long,
    private val retryBackoffMs: Long,
    private val maxRetryBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val lockTimeoutMs: Long = checkTimeoutMs,
    private val nanoTime: () -> Long = System::nanoTime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val check: suspend () -> MemoriesCheck,
) {
    @Volatile var available: Boolean? = null
        private set

    /** Guarded by [mutex]. */
    private var failures = 0

    /** [nanoTime] before which an inconclusive check is not repeated; guarded by [mutex]. */
    @Volatile private var retryAtNanos: Long? = null
    private val mutex = Mutex()
    private val retryInFlight = AtomicBoolean(false)

    /** Parent of every retry, a child of [scope]'s job so cancelling it never cancels the caller's scope. */
    private val retryJob = SupervisorJob(scope.coroutineContext[Job])
    private val retryScope = CoroutineScope(scope.coroutineContext + retryJob)

    @Volatile private var closed = false

    /** Cancels an in-flight retry and launches no further ones. Idempotent; awaited checks are not affected. */
    fun close() {
        closed = true
        retryJob.cancel()
    }

    suspend fun ensureChecked() {
        if (available != null) return
        if (!isNextcloud) {
            available = false // not a Nextcloud server: decided without a request
            return
        }
        if (inBackoff()) return
        if (retryAtNanos != null) {
            retryInBackground()
        } else {
            checkAwaited()
        }
    }

    /** First attempt: awaited by the caller. Waiting for the lock never schedules a retry. */
    private suspend fun checkAwaited() = locked { if (available == null && !inBackoff()) runCheck() }

    /** Runs [block] holding [mutex], acquired within [lockTimeoutMs]; skipped (not failed) if that times out. */
    private suspend fun locked(block: suspend () -> Unit) {
        var held = false
        withTimeoutOrNull(lockTimeoutMs) {
            mutex.lock()
            held = true
        }
        if (!held) return
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /** Later attempts: never block the caller; at most one retry in flight. */
    private fun retryInBackground() {
        if (closed || !retryInFlight.compareAndSet(false, true)) return
        retryScope
            .launch { locked { if (available == null && !inBackoff()) runCheck() } }
            .invokeOnCompletion { retryInFlight.set(false) } // also when the scope is cancelled
    }

    /** Runs [check] under [checkTimeoutMs]; the caller holds [mutex]. */
    private suspend fun runCheck() {
        val result =
            runCatching { withTimeoutOrNull(checkTimeoutMs) { check() } ?: MemoriesCheck.INCONCLUSIVE }
                .getOrElse { if (it is CancellationException) throw it else MemoriesCheck.INCONCLUSIVE }
        when (result) {
            MemoriesCheck.AVAILABLE -> available = true
            MemoriesCheck.UNAVAILABLE -> available = false
            MemoriesCheck.INCONCLUSIVE -> scheduleRetry()
        }
    }

    private fun inBackoff(): Boolean = retryAtNanos?.let { nanoTime() - it < 0 } == true

    private fun scheduleRetry() {
        failures++
        val shift = (failures - 1).coerceAtMost(MAX_SHIFT)
        val backoff = (retryBackoffMs shl shift).coerceIn(0L, maxOf(retryBackoffMs, maxRetryBackoffMs))
        retryAtNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff)
    }

    companion object {
        const val DEFAULT_MAX_BACKOFF_MS = 10 * 60_000L
        private const val MAX_SHIFT = 20
    }
}
