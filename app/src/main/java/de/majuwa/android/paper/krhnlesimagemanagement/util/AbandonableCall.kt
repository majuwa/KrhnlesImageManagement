package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the blocking provider call [block] on [dispatcher], detached from the caller's job, and
 * waits at most [timeoutMillis] for it.
 *
 * A sharing app's provider may ignore its [CancellationSignal] and block forever, and a blocking
 * call cannot be interrupted by coroutine cancellation. So on a timeout or a cancellation of the
 * caller the signal is cancelled and the call is abandoned: the caller returns at once (`null` on
 * timeout, the [kotlinx.coroutines.CancellationException] is rethrown), the call's thread returns
 * whenever the provider does. If an abandoned call still completes, [onAbandoned] receives its
 * result, so resources it holds (a staged file) are released and never orphaned; a result that
 * reaches the caller is never passed to [onAbandoned].
 *
 * [block] maps provider failures itself; an exception it throws is rethrown to the caller.
 */
internal suspend fun <T : Any> abandonableCall(
    dispatcher: CoroutineDispatcher,
    timeoutMillis: Long,
    onAbandoned: (T) -> Unit = {},
    block: (CancellationSignal) -> T,
): T? {
    val signal = CancellationSignal()
    val result = AtomicReference<T?>()
    // A throwaway scope: the caller never waits for (or is blocked by) the call itself.
    val call = CoroutineScope(dispatcher).async { block(signal).also(result::set) }
    var delivered = false
    try {
        return withTimeoutOrNull(timeoutMillis) { call.await() }.also { delivered = it != null }
    } finally {
        if (!delivered) {
            signal.cancel()
            // Runs at once if the call already completed (its result never reached the caller).
            call.invokeOnCompletion { cause -> if (cause == null) result.getAndSet(null)?.let(onAbandoned) }
        }
    }
}
