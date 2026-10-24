package de.majuwa.android.paper.krhnlesimagemanagement.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes [call] blocking on the IO dispatcher and passes the response and the running job
 * to [handle]; closes the response afterwards.
 *
 * Coroutine cancellation does not interrupt a blocking socket read (and `invokeOnCompletion`
 * does not fire while the body is still blocked), so a sibling coroutine waits for
 * cancellation and then cancels the OkHttp [Call]: the read fails at once and the failure is
 * rethrown as the scope's [CancellationException], never reported as an error — whatever type the
 * failure has (a parser may wrap the `IOException`), so every non-cancellation exception of a
 * cancelled scope becomes its [CancellationException]. The sibling starts `UNDISPATCHED`, so its
 * `finally` is armed before the blocking read begins. No `callTimeout` (it would break large
 * uploads on the shared client); the read timeout applies. Shared by [AlbumsRepository] and
 * [WebDavClient.uploadFile].
 */
internal suspend fun <T> executeCancellable(
    call: Call,
    handle: (Response, Job) -> T,
): T =
    withContext(Dispatchers.IO) {
        val canceller =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            call.execute().use { response -> handle(response, coroutineContext.job) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive() // cancelled: the failure comes from Call.cancel()
            throw e
        } finally {
            canceller.cancel()
        }
    }
