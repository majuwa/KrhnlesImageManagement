package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.PinnedCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeTrustedCertificateRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeUploadHistoryRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.OccasionSuggestionTimeouts
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.OccasionSuggestionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString.Companion.decodeHex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OccasionSuggestionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val config = WebDavConfig("https://cloud.example.com/remote.php/dav/files/user/", "user", "pass")
    private lateinit var credentials: FakeCredentialRepository
    private lateinit var history: FakeUploadHistoryRepository
    private lateinit var trusted: FakeTrustedCertificateRepository
    private var repos = mutableListOf<FakeAlbumsRepository>()
    private var now = 0L
    private var listHook: suspend () -> Unit = {}
    private var remoteAlbums = listOf("Remote")

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        credentials = FakeCredentialRepository(config)
        history = FakeUploadHistoryRepository(listOf(entry(1, "Recent", 100)))
        trusted = FakeTrustedCertificateRepository()
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun entry(
        id: Long,
        name: String,
        time: Long,
    ) = UploadHistoryEntry(id, name, time, 1, 0)

    private fun newFake() =
        FakeAlbumsRepository().also { fake ->
            fake.albums = Result.success(remoteAlbums.map { RemoteAlbum(it, "/$it/") })
            fake.onListAlbums = { listHook() }
            repos += fake
        }

    private fun TestScope.vm(): OccasionSuggestionsViewModel {
        val model =
            OccasionSuggestionsViewModel(
                application = RuntimeEnvironment.getApplication() as Application,
                credentialRepo = credentials,
                trustedCertificates = trusted,
                history = history,
                repoFactory = { _, _ -> newFake() },
                backgroundDispatcher = testDispatcher,
                clock = { now },
                timeouts = OccasionSuggestionTimeouts(),
            )
        runCurrent() // consumes the observer's initial configuration
        return model
    }

    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred()

    @Test
    fun `recent names are published before a suspended listing completes`() =
        runTest {
            val g = gate()
            listHook = { g.await() }
            val model = vm()
            model.load()
            runCurrent()
            assertEquals(listOf("Recent"), model.candidates.value)
            g.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("Recent", "Remote"), model.candidates.value)
        }

    @Test
    fun `remote names are appended after the listing`() =
        runTest {
            val model = vm()
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("Recent", "Remote"), model.candidates.value)
        }

    @Test
    fun `history read failure gives empty recent and does not crash`() =
        runTest {
            history.failure = IllegalStateException("boom")
            val model = vm()
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("Remote"), model.candidates.value)
        }

    @Test
    fun `history timeout gives empty recent`() =
        runTest {
            history.hang = true
            val model = vm()
            model.load()
            runCurrent()
            advanceTimeBy(3_001)
            advanceUntilIdle()
            assertEquals(listOf("Remote"), model.candidates.value)
        }

    @Test
    fun `not configured does not list`() =
        runTest {
            credentials = FakeCredentialRepository(WebDavConfig())
            val model = vm()
            model.load()
            advanceUntilIdle()
            assertTrue(repos.isEmpty())
            assertEquals(listOf("Recent"), model.candidates.value)
        }

    @Test
    fun `a failing listing yields recent only`() =
        runTest {
            val model = vm()
            val failing =
                OccasionSuggestionsViewModel(
                    application = RuntimeEnvironment.getApplication() as Application,
                    credentialRepo = credentials,
                    trustedCertificates = trusted,
                    history = history,
                    repoFactory = { _, _ ->
                        FakeAlbumsRepository().also { it.albums = Result.failure(WebDavHttpException(500, "x")) }
                    },
                    backgroundDispatcher = testDispatcher,
                    clock = { now },
                )
            runCurrent()
            failing.load()
            advanceUntilIdle()
            assertEquals(listOf("Recent"), failing.candidates.value)
            assertTrue(model.candidates.value.isEmpty())
        }

    @Test
    fun `a listing over the timeout is cancelled and the job ends`() =
        runTest {
            listHook = { awaitCancellation() }
            val model = vm()
            model.load()
            runCurrent()
            advanceTimeBy(10_001)
            advanceUntilIdle()
            assertEquals(listOf("Recent"), model.candidates.value)
            model.load() // not stuck: a new load runs (and lists again)
            runCurrent()
            assertEquals(2, repos.single().listAlbumsCalls)
        }

    @Test
    fun `a config change between loads clears old remote names before the new listing`() =
        runTest {
            val model = vm()
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("Recent", "Remote"), model.candidates.value)
            credentials.save("https://other.example.com/remote.php/dav/files/u/", "u", "p")
            runCurrent()
            assertEquals(listOf("Recent"), model.candidates.value)
            remoteAlbums = listOf("Other")
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("Recent", "Other"), model.candidates.value)
        }

    @Test
    fun `a config change with no load cancels a running load`() =
        runTest {
            val g = gate()
            listHook = { g.await() }
            val model = vm()
            model.load()
            runCurrent()
            credentials.saveBaseFolder("Other")
            runCurrent()
            g.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("Recent"), model.candidates.value)
            listHook = {}
            model.load()
            advanceUntilIdle()
            assertEquals(listOf("Recent", "Remote"), model.candidates.value)
        }

    @Test
    fun `a pin change re-lists on the next load`() =
        runTest {
            credentials.save("https://cloud.example:8443/remote.php/dav/files/u/", "u", "p")
            val model = vm()
            model.load()
            advanceUntilIdle()
            assertEquals(1, repos.size)
            val pin = PinnedCertificate(ServerOrigin("cloud.example", 8443), "ab".repeat(32).decodeHex())
            trusted.trust(ServerCertificate(pin.origin, pin.sha256, "CN=a", "CN=b", 0L, Long.MAX_VALUE / 2))
            model.load()
            advanceUntilIdle()
            assertEquals(2, repos.size)
            assertEquals(1, repos.last().listAlbumsCalls)
            assertEquals(1, repos.first().closeCalls)
        }

    @Test
    fun `a configuration change during the listing discards the result`() =
        runTest {
            val g = gate()
            listHook = { g.await() }
            val model = vm()
            model.load()
            runCurrent()
            credentials.save(config.url, config.username, "changed") // password: ignored by the observer
            runCurrent()
            g.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("Recent"), model.candidates.value)
        }

    @Test
    fun `re-entry while loading performs one listing`() =
        runTest {
            val model = vm()
            model.load()
            model.load()
            advanceUntilIdle()
            assertEquals(1, repos.single().listAlbumsCalls)
        }

    @Test
    fun `a fresh result is reused within 60 seconds and re-listed after`() =
        runTest {
            val model = vm()
            model.load()
            advanceUntilIdle()
            now += 59_000
            model.load()
            advanceUntilIdle()
            assertEquals(1, repos.single().listAlbumsCalls)
            now += 2_000
            model.load()
            advanceUntilIdle()
            assertEquals(2, repos.single().listAlbumsCalls)
        }

    @Test
    fun `onCleared closes the repository`() =
        runTest {
            val store = ViewModelStore()
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer {
                            OccasionSuggestionsViewModel(
                                application = RuntimeEnvironment.getApplication() as Application,
                                credentialRepo = credentials,
                                trustedCertificates = trusted,
                                history = history,
                                repoFactory = { _, _ -> newFake() },
                                backgroundDispatcher = testDispatcher,
                            )
                        }
                    },
                )[OccasionSuggestionsViewModel::class.java]
            model.load()
            advanceUntilIdle()
            store.clear()
            assertEquals(1, repos.single().closeCalls)
        }

    @Test
    fun `history is never written and failures log no folder names`() =
        runTest {
            remoteAlbums = listOf("SecretAlbum")
            ShadowLog.clear()
            val failing =
                OccasionSuggestionsViewModel(
                    application = RuntimeEnvironment.getApplication() as Application,
                    credentialRepo = credentials,
                    trustedCertificates = trusted,
                    history = history,
                    repoFactory = { _, _ ->
                        FakeAlbumsRepository().also {
                            it.albums = Result.failure(IllegalStateException("/SecretAlbum/ server text"))
                        }
                    },
                    backgroundDispatcher = testDispatcher,
                )
            runCurrent()
            failing.load()
            advanceUntilIdle()
            assertEquals(0, history.writes)
            assertFalse(ShadowLog.getLogs().any { it.msg.contains("SecretAlbum") || it.msg.contains("server text") })
        }
}
