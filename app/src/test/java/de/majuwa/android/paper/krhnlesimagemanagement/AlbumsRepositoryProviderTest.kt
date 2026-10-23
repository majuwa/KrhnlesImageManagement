package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryUnavailableException.Reason
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeAlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeCredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cached, configuration-aware repository creation shared by the album ViewModels (issues #29 / #48). */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsRepositoryProviderTest {
    /** Reads the configuration immediately but only emits it once [gate] is completed (if set). */
    private class GatedCredentials(
        private val delegate: FakeCredentialRepository,
    ) : CredentialRepository by delegate {
        var gate: CompletableDeferred<Unit>? = null

        override val webDavConfig: Flow<WebDavConfig> =
            flow {
                val config = delegate.webDavConfig.first()
                gate?.await()
                emit(config)
            }
    }

    private val firstConfig = WebDavConfig(url = "https://one.example.com/dav/", username = "u", password = "p")
    private val credentials = FakeCredentialRepository(firstConfig)
    private val createdFor = mutableListOf<WebDavConfig>()
    private val factory: (WebDavConfig) -> AlbumsRepositoryContract = { config ->
        createdFor += config
        FakeAlbumsRepository()
    }
    private val provider = AlbumsRepositoryProvider(credentials, repoFactory = factory)

    private fun Result<*>.unavailableReason(): Reason? =
        (exceptionOrNull() as? AlbumsRepositoryUnavailableException)?.reason

    @Test
    fun `creates the repository lazily and caches it`() =
        runTest {
            assertNull(provider.current)

            val first = provider.get().getOrThrow()
            val second = provider.get().getOrThrow()

            assertSame(first, second)
            assertSame(first, provider.current)
            assertEquals(listOf(firstConfig), createdFor)
        }

    @Test
    fun `invalidate makes the next get build a new repository`() =
        runTest {
            val first = provider.get().getOrThrow()

            provider.invalidate()
            assertNull(provider.current)
            val second = provider.get().getOrThrow()

            assertNotSame(first, second)
            assertEquals(listOf(firstConfig, firstConfig), createdFor)
        }

    @Test
    fun `invalidate and close close the old repository`() =
        runTest {
            val first = provider.get().getOrThrow() as FakeAlbumsRepository
            provider.invalidate()
            assertEquals(1, first.closeCalls)

            val second = provider.get().getOrThrow() as FakeAlbumsRepository
            provider.close()
            assertEquals(1, second.closeCalls)
            assertEquals("not closed twice", 1, first.closeCalls)
        }

    @Test
    fun `a rebuild after a configuration change closes the old repository`() =
        runTest {
            val first = provider.get().getOrThrow() as FakeAlbumsRepository
            credentials.save("https://two.example.com/dav/", "u2", "p2")
            provider.get().getOrThrow()
            assertEquals(1, first.closeCalls)

            val second = provider.current as FakeAlbumsRepository
            credentials.clear()
            provider.get()
            assertEquals(1, second.closeCalls)
        }

    @Test
    fun `a changed configuration is picked up without invalidate`() =
        runTest {
            val first = provider.get().getOrThrow()
            credentials.save("https://two.example.com/dav/", "u2", "p2")

            val second = provider.get().getOrThrow()

            assertNotSame("never reuses the repository of the old server", first, second)
            assertEquals("https://two.example.com/dav/", createdFor.last().url)
        }

    @Test
    fun `after logout get fails as not configured and drops the old repository`() =
        runTest {
            provider.get().getOrThrow()
            credentials.clear()

            val result = provider.get()

            assertEquals(Reason.NOT_CONFIGURED, result.unavailableReason())
            assertNull("the old server's repository is no longer handed out", provider.current)
            assertEquals(1, createdFor.size)
        }

    @Test
    fun `a factory throwing IllegalArgumentException is a handled failure and caches nothing`() =
        runTest {
            val failing =
                AlbumsRepositoryProvider(credentials) { throw IllegalArgumentException("Invalid WebDAV URL") }

            val result = failing.get()

            assertEquals(Reason.INVALID_CONFIGURATION, result.unavailableReason())
            assertNull(failing.current)
        }

    @Test
    fun `concurrent gets share one repository`() =
        runTest {
            val gated = GatedCredentials(credentials).apply { gate = CompletableDeferred() }
            val concurrent = AlbumsRepositoryProvider(gated, repoFactory = factory)

            val a = async { concurrent.get().getOrThrow() }
            val b = async { concurrent.get().getOrThrow() }
            runCurrent()
            gated.gate!!.complete(Unit)

            assertSame(a.await(), b.await())
            assertEquals("built only once", 1, createdFor.size)
        }

    @Test
    fun `a repository built while invalidate runs is not cached`() =
        runTest {
            val gated = GatedCredentials(credentials).apply { gate = CompletableDeferred() }
            val racing = AlbumsRepositoryProvider(gated, repoFactory = factory)

            val inFlight = async { racing.get().getOrThrow() }
            runCurrent() // configuration read, build pending
            racing.invalidate()
            gated.gate!!.complete(Unit)
            val stale = inFlight.await()

            assertNull("not cached across the invalidate", racing.current)
            val fresh = racing.get().getOrThrow()
            assertNotSame(stale, fresh)
            assertEquals(2, createdFor.size)
        }

    @Test
    fun `a stalled configuration read fails with a timeout instead of holding the lock forever`() =
        runTest {
            val gated = GatedCredentials(credentials).apply { gate = CompletableDeferred() }
            val stalled = AlbumsRepositoryProvider(gated, lockTimeoutMs = 1_000, repoFactory = factory)

            val result = stalled.get()

            assertEquals(Reason.TIMEOUT, result.unavailableReason())
            assertTrue(createdFor.isEmpty())
            // The lock was released: once the configuration is readable, get() works again.
            gated.gate = null
            assertTrue(stalled.get().isSuccess)
        }
}
