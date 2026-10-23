package de.majuwa.android.paper.krhnlesimagemanagement.data

import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** Supplies the [AlbumsRepositoryContract] for the current WebDAV configuration. */
fun interface AlbumsRepositorySource {
    /**
     * The repository for the current configuration, or a failure — never throws except on
     * cancellation. A missing or invalid configuration fails with [AlbumsRepositoryUnavailableException].
     */
    suspend fun get(): Result<AlbumsRepositoryContract>
}

/** No repository could be prepared for the current configuration; see [reason]. */
class AlbumsRepositoryUnavailableException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("Albums repository unavailable: $reason", cause) {
    enum class Reason {
        /** No complete set of credentials is stored (e.g. after logout). */
        NOT_CONFIGURED,

        /** The stored configuration cannot be used (e.g. an invalid server URL). */
        INVALID_CONFIGURATION,

        /** Preparing the repository did not finish within the lock timeout. */
        TIMEOUT,
    }
}

/**
 * Creates an [AlbumsRepositoryContract] from the current WebDAV configuration on first use and
 * caches it.
 *
 * - Every [get] re-reads the configuration; when it differs from the one the cached repository
 *   was built for (logout, server or account change), a new repository is built (or
 *   [AlbumsRepositoryUnavailableException.Reason.NOT_CONFIGURED] is returned), so no request is ever
 *   sent with outdated credentials.
 * - [get] is serialized by a [Mutex] (acquired with a timeout, CLAUDE.md lock rule), so concurrent
 *   callers share one repository instead of building several.
 * - [invalidate] bumps a generation counter: a repository whose build overlapped an [invalidate]
 *   is returned to its caller but never cached.
 */
class AlbumsRepositoryProvider(
    private val credentialRepo: CredentialRepository,
    private val lockTimeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS,
    private val repoFactory: (WebDavConfig) -> AlbumsRepositoryContract,
) : AlbumsRepositorySource {
    private class Cached(
        val config: WebDavConfig,
        val generation: Long,
        val repository: AlbumsRepositoryContract,
    )

    private val mutex = Mutex()
    private val generation = AtomicLong(0)
    private val cached = AtomicReference<Cached?>(null)

    /** The cached repository, or `null` if none is cached (not created yet, or after [invalidate]). */
    val current: AlbumsRepositoryContract?
        get() = cached.get()?.takeIf { it.generation == generation.get() }?.repository

    override suspend fun get(): Result<AlbumsRepositoryContract> =
        withTimeoutOrNull(lockTimeoutMs) { mutex.withLock { getLocked() } }
            ?: Result.failure(AlbumsRepositoryUnavailableException(AlbumsRepositoryUnavailableException.Reason.TIMEOUT))

    private suspend fun getLocked(): Result<AlbumsRepositoryContract> {
        val buildGeneration = generation.get()
        val config =
            try {
                credentialRepo.webDavConfig.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
        cached.get()?.let { entry ->
            if (entry.generation == buildGeneration && entry.config == config) return Result.success(entry.repository)
        }
        cached.set(null) // outdated configuration: never hand out the old repository again
        if (!config.isValid) {
            return Result.failure(
                AlbumsRepositoryUnavailableException(AlbumsRepositoryUnavailableException.Reason.NOT_CONFIGURED),
            )
        }
        val repository =
            try {
                repoFactory(config)
            } catch (e: IllegalArgumentException) {
                return Result.failure(
                    AlbumsRepositoryUnavailableException(
                        AlbumsRepositoryUnavailableException.Reason.INVALID_CONFIGURATION,
                        e,
                    ),
                )
            }
        if (generation.get() == buildGeneration) cached.set(Cached(config, buildGeneration, repository))
        return Result.success(repository)
    }

    /** Drops the cached repository so the next [get] builds a new one. */
    fun invalidate() {
        generation.incrementAndGet()
        cached.set(null)
    }

    private companion object {
        /** Upper bound for waiting on the lock plus reading the configuration. */
        const val DEFAULT_LOCK_TIMEOUT_MS = 10_000L
    }
}
