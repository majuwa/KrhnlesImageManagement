package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Activity
import android.app.Application
import android.net.Uri
import de.majuwa.android.paper.krhnlesimagemanagement.data.MAX_TRASH_REQUEST_URIS
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidate
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidateChecker
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCheck
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeLocalMediaTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeRemovalChecker
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeVerifiedUploadsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.verifiedRecord
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.REMOVAL_CONFIRM_MAX_AGE_MS
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.RemovalOutcome
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.RemovalState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.UploadedRemovalViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UploadedRemovalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverUrl = "https://cloud.example.com/remote.php/dav/files/u/"
    private val verified = FakeVerifiedUploadsRepository()
    private val checker = FakeRemovalChecker()
    private val trasher = FakeLocalMediaTrasher()
    private var config = WebDavConfig(serverUrl, "u", "p", "")
    private var now = 1_000_000L
    private var credentials: FakeCredentialRepository? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(removalChecker: RemovalCandidateChecker = checker) =
        UploadedRemovalViewModel(
            application = RuntimeEnvironment.getApplication() as Application,
            verifiedUploads = verified,
            credentials = FakeCredentialRepository(config).also { credentials = it },
            checker = removalChecker,
            trasher = trasher,
            computeDispatcher = dispatcher,
            clock = { now },
        )

    private suspend fun seed(ids: Collection<Long>) = ids.forEach { verified.recordVerified(verifiedRecord(it)) }

    private suspend fun seed(vararg ids: Long) = seed(ids.toList())

    private fun uri(id: Long) = Uri.parse("content://media/external/images/media/$id")

    private fun TestScope.startAndConfirm(vm: UploadedRemovalViewModel) {
        vm.start()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
    }

    // ── check ───────────────────────────────────────────────────────────────

    @Test
    fun `without records nothing is checked and the explanation state is shown`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.NothingToRemove, vm.state.value)
            assertEquals(0, checker.checkCalls)
        }

    @Test
    fun `without a usable configuration nothing is checked`() =
        runTest(dispatcher) {
            config = WebDavConfig()
            seed(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.NotConfigured, vm.state.value)
            assertEquals(0, checker.checkCalls)
        }

    @Test
    fun `no candidate after the check shows the explanation and never a request`() =
        runTest(dispatcher) {
            seed(1, 2)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.NothingToRemove, vm.state.value)
            assertNull(vm.pendingRequest.value)
            assertTrue(trasher.requests.isEmpty())
        }

    @Test
    fun `a failing check keeps every photo`() =
        runTest(dispatcher) {
            seed(1)
            checker.failure = IOException("boom")
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.NothingToRemove, vm.state.value)
        }

    @Test
    fun `candidates lead to the confirmation with their count and the config is passed`() =
        runTest(dispatcher) {
            seed(1, 2, 3)
            checker.passing = setOf(1, 3)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.Confirm(2), vm.state.value)
            assertEquals(config, checker.lastConfig)
            assertTrue(trasher.requests.isEmpty())
        }

    @Test
    fun `a second start while checking is ignored and cancel returns to idle without removing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            checker.gate = CompletableDeferred()
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            assertTrue(vm.state.value is RemovalState.Checking)

            vm.start()
            advanceUntilIdle()
            assertEquals(1, checker.checkCalls)

            vm.cancel()
            advanceUntilIdle()
            assertEquals(RemovalState.Idle, vm.state.value)
            assertTrue(trasher.requests.isEmpty())

            // A late completion of the cancelled check cannot bring the progress state back.
            checker.gate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(RemovalState.Idle, vm.state.value)

            checker.gate = null
            vm.start()
            advanceUntilIdle()
            assertEquals(RemovalState.Confirm(1), vm.state.value)
        }

    @Test
    fun `declining the confirmation removes nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            vm.cancel()
            advanceUntilIdle()

            assertEquals(RemovalState.Idle, vm.state.value)
            assertTrue(trasher.requests.isEmpty())
            assertNull(vm.pendingRequest.value)
            // confirm() after a decline is a no-op.
            vm.confirm()
            advanceUntilIdle()
            assertTrue(trasher.requests.isEmpty())
        }

    // ── trash ───────────────────────────────────────────────────────────────

    @Test
    fun `confirm rechecks locally, requests the trash and reports the confirmed count`() =
        runTest(dispatcher) {
            seed(1, 2)
            checker.passing = setOf(1, 2)
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(1, checker.recheckCalls)
            assertEquals(RemovalState.Trashing, vm.state.value)
            assertNotNull(vm.pendingRequest.value)
            assertEquals(listOf(setOf(uri(1), uri(2))), trasher.requests.map { it.toSet() })

            vm.onRequestLaunched()
            assertNull(vm.pendingRequest.value)
            trasher.trashed += setOf(uri(1), uri(2))
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalState.Idle, vm.state.value)
            assertEquals(RemovalOutcome.Trashed(2), vm.outcome.value)
            assertTrue(verified.current.isEmpty())
            assertEquals(listOf(setOf(1L, 2L)), verified.forgotten)

            vm.consumeOutcome()
            assertNull(vm.outcome.value)
        }

    @Test
    fun `the reported count is the confirmed one, never the requested one`() =
        runTest(dispatcher) {
            seed(1, 2, 3)
            checker.passing = setOf(1, 2, 3)
            val vm = viewModel()
            startAndConfirm(vm)
            vm.onRequestLaunched()
            trasher.trashed += setOf(uri(1), uri(2))

            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalOutcome.Trashed(2), vm.outcome.value)
            // The photo that is not in the trash keeps its record.
            assertEquals(listOf(3L), verified.current.map { it.id })
        }

    @Test
    fun `more than 2000 photos are requested in chunks, one dialog after the other`() =
        runTest(dispatcher) {
            val ids = (1L..(MAX_TRASH_REQUEST_URIS + 1L)).toList()
            seed(ids)
            checker.passing = ids.toSet()
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(listOf(MAX_TRASH_REQUEST_URIS), trasher.requests.map { it.size })
            vm.onRequestLaunched()
            trasher.trashed += ids.map(::uri)
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalState.Trashing, vm.state.value)
            assertNotNull(vm.pendingRequest.value)
            assertEquals(listOf(MAX_TRASH_REQUEST_URIS, 1), trasher.requests.map { it.size })
            assertNull("no outcome before the last dialog", vm.outcome.value)

            vm.onRequestLaunched()
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalOutcome.Trashed(ids.size), vm.outcome.value)
            assertEquals(RemovalState.Idle, vm.state.value)
        }

    @Test
    fun `declining the system dialog stops the remaining chunks and reports a cancellation`() =
        runTest(dispatcher) {
            val ids = (1L..(MAX_TRASH_REQUEST_URIS + 1L)).toList()
            seed(ids)
            checker.passing = ids.toSet()
            val vm = viewModel()
            startAndConfirm(vm)
            vm.onRequestLaunched()

            vm.onTrashResult(Activity.RESULT_CANCELED)
            advanceUntilIdle()

            assertEquals(1, trasher.requests.size)
            assertNull(vm.pendingRequest.value)
            assertEquals(RemovalOutcome.Cancelled, vm.outcome.value)
            assertEquals(RemovalState.Idle, vm.state.value)
            assertEquals(ids.size, verified.current.size)
        }

    @Test
    fun `a decline after the first chunk still reports what was trashed`() =
        runTest(dispatcher) {
            val ids = (1L..(MAX_TRASH_REQUEST_URIS + 1L)).toList()
            seed(ids)
            checker.passing = ids.toSet()
            val vm = viewModel()
            startAndConfirm(vm)
            vm.onRequestLaunched()
            trasher.trashed += trasher.requests.single()
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()
            vm.onRequestLaunched()

            vm.onTrashResult(Activity.RESULT_CANCELED)
            advanceUntilIdle()

            assertEquals(2, trasher.requests.size)
            assertEquals(RemovalOutcome.Trashed(MAX_TRASH_REQUEST_URIS), vm.outcome.value)
        }

    @Test
    fun `a photo that changed while the dialog was open is not requested`() =
        runTest(dispatcher) {
            seed(1, 2)
            checker.passing = setOf(1, 2)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            checker.passingLocal = setOf(1)

            vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf(listOf(uri(1))), trasher.requests)
        }

    @Test
    fun `when nothing passes the local recheck no system dialog is shown`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            checker.passingLocal = emptySet()

            vm.confirm()
            advanceUntilIdle()

            assertEquals(RemovalState.NothingToRemove, vm.state.value)
            assertTrue(trasher.requests.isEmpty())
            assertNull(vm.pendingRequest.value)
        }

    @Test
    fun `a request that cannot be created reports a failure and removes nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            trasher.createFailure = IllegalStateException("no request")
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(RemovalOutcome.Failed, vm.outcome.value)
            assertEquals(RemovalState.Idle, vm.state.value)
            assertEquals(1, verified.current.size)
        }

    @Test
    fun `an unconfirmable result is reported as a failure and keeps the records`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            trasher.confirmFailure = SecurityException("no access")
            val vm = viewModel()
            startAndConfirm(vm)
            vm.onRequestLaunched()

            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalOutcome.Failed, vm.outcome.value)
            assertEquals(1, verified.current.size)
        }

    @Test
    fun `a dialog result without a running request is ignored`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()

            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()
            assertNull(vm.outcome.value)

            vm.start()
            advanceUntilIdle()
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()
            assertNull(vm.outcome.value)
            assertEquals(RemovalState.Confirm(1), vm.state.value)
        }

    @Test
    fun `confirm twice requests only once`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            vm.confirm()
            vm.confirm()
            advanceUntilIdle()

            assertEquals(1, trasher.requests.size)
            assertEquals(1, checker.recheckCalls)
        }

    // ── review findings 2, 4, 7a, 8, 9 ──────────────────────────────────────

    @Test
    fun `photos already in the trash before the request are not requested, counted or forgotten`() =
        runTest(dispatcher) {
            seed(1, 2)
            checker.passing = setOf(1, 2)
            trasher.trashed += uri(1)
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(listOf(listOf(uri(2))), trasher.requests)
            vm.onRequestLaunched()
            trasher.trashed += uri(2)
            vm.onTrashResult(Activity.RESULT_OK)
            advanceUntilIdle()

            assertEquals(RemovalOutcome.Trashed(1), vm.outcome.value)
            assertEquals(listOf(setOf(2L)), verified.forgotten)
        }

    @Test
    fun `a declined dialog with nothing newly trashed is a cancellation, also when others were trashed before`() =
        runTest(dispatcher) {
            seed(1, 2)
            checker.passing = setOf(1, 2)
            // Already in the trash (e.g. by the gallery) before the request: not this request's doing.
            trasher.trashed += uri(1)
            val vm = viewModel()
            startAndConfirm(vm)
            vm.onRequestLaunched()

            vm.onTrashResult(Activity.RESULT_CANCELED)
            advanceUntilIdle()

            assertEquals(RemovalOutcome.Cancelled, vm.outcome.value)
            assertTrue(verified.forgotten.isEmpty())
        }

    @Test
    fun `only already trashed candidates lead to the explanation and no request`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            trasher.trashed += uri(1)
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(RemovalState.NothingToRemove, vm.state.value)
            assertTrue(trasher.requests.isEmpty())
        }

    @Test
    fun `a record that changed or was forgotten while the dialog was open is not requested`() =
        runTest(dispatcher) {
            seed(1, 2, 3)
            checker.passing = setOf(1, 2, 3)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            // 1 re-uploaded (new record), 2 forgotten, 3 unchanged.
            verified.replaceAll(listOf(verifiedRecord(1).copy(dateModified = 5L), verifiedRecord(3)))

            vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf(listOf(uri(3))), trasher.requests)
        }

    @Test
    fun `a server change while the dialog was open requests nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            credentials!!.setConfig(config.copy(url = "https://other.example.com/remote.php/dav/files/u/"))

            vm.confirm()
            advanceUntilIdle()

            assertTrue(trasher.requests.isEmpty())
            assertEquals(RemovalState.NothingToRemove, vm.state.value)
        }

    @Test
    fun `a logout while the dialog was open requests nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            credentials!!.clear()

            vm.confirm()
            advanceUntilIdle()

            assertTrue(trasher.requests.isEmpty())
            assertEquals(RemovalState.NotConfigured, vm.state.value)
        }

    @Test
    fun `a fresh confirmation re-checks only locally`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            now += REMOVAL_CONFIRM_MAX_AGE_MS

            vm.confirm()
            advanceUntilIdle()

            assertEquals(1, checker.checkCalls)
            assertEquals(1, checker.recheckCalls)
            assertEquals(1, trasher.requests.size)
        }

    @Test
    fun `a confirmation older than ten minutes repeats the full check including the server`() =
        runTest(dispatcher) {
            seed(1, 2)
            checker.passing = setOf(1, 2)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            now += REMOVAL_CONFIRM_MAX_AGE_MS + 1
            // Photo 2 left the server meanwhile.
            checker.passing = setOf(1)

            vm.confirm()
            advanceUntilIdle()

            assertEquals(2, checker.checkCalls)
            assertEquals(setOf(1L, 2L), checker.lastRecords.map { it.id }.toSet())
            assertEquals(listOf(listOf(uri(1))), trasher.requests)
        }

    @Test
    fun `a stale confirmation whose server is unreachable requests nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()
            now += REMOVAL_CONFIRM_MAX_AGE_MS + 1
            checker.serverUnreachable = true

            vm.confirm()
            advanceUntilIdle()

            assertEquals(RemovalState.ServerUnreachable, vm.state.value)
            assertTrue(trasher.requests.isEmpty())
        }

    @Test
    fun `a failing local recheck reports a failure and requests nothing`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            checker.recheckFailure = IllegalStateException("provider crashed")
            val vm = viewModel()
            startAndConfirm(vm)

            assertEquals(RemovalOutcome.Failed, vm.outcome.value)
            assertEquals(RemovalState.Idle, vm.state.value)
            assertTrue(trasher.requests.isEmpty())
        }

    @Test
    fun `an unreachable server shows its own state and keeps every photo`() =
        runTest(dispatcher) {
            seed(1)
            checker.passing = setOf(1)
            checker.serverUnreachable = true
            val vm = viewModel()
            vm.start()
            advanceUntilIdle()

            assertEquals(RemovalState.ServerUnreachable, vm.state.value)
            vm.confirm()
            advanceUntilIdle()
            assertTrue(trasher.requests.isEmpty())

            vm.cancel()
            assertEquals(RemovalState.Idle, vm.state.value)
        }

    /** A check that finishes its cancellation only when released and keeps every progress callback. */
    private class LingeringChecker : RemovalCandidateChecker {
        val releases = mutableListOf<CompletableDeferred<Unit>>()
        val callbacks = mutableListOf<(Int, Int) -> Unit>()

        override suspend fun check(
            records: List<VerifiedUpload>,
            config: WebDavConfig,
            onProgress: (done: Int, total: Int) -> Unit,
        ): RemovalCheck {
            callbacks += onProgress
            val release = CompletableDeferred<Unit>().also { releases += it }
            // Like a blocking server call: cancellation is only noticed once it returns.
            withContext(NonCancellable) { release.await() }
            return RemovalCheck(emptyList())
        }

        override suspend fun recheckLocal(candidates: List<RemovalCandidate>) = candidates
    }

    @Test
    fun `cancel followed at once by start keeps the new check's state against the old job's late writes`() =
        runTest(dispatcher) {
            seed(1, 2)
            val lingering = LingeringChecker()
            val vm = viewModel(lingering)
            vm.start()
            advanceUntilIdle()

            vm.cancel()
            vm.start()
            advanceUntilIdle()
            assertEquals(RemovalState.Checking(0, 2), vm.state.value)

            // The old job finishes its cancellation and reports progress late.
            lingering.releases.first().complete(Unit)
            advanceUntilIdle()
            lingering.callbacks.first().invoke(2, 2)

            assertEquals(RemovalState.Checking(0, 2), vm.state.value)
            lingering.callbacks.last().invoke(1, 2)
            assertEquals(RemovalState.Checking(1, 2), vm.state.value)
        }
}
