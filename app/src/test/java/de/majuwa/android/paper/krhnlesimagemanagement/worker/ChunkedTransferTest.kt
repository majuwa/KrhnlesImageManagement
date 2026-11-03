package de.majuwa.android.paper.krhnlesimagemanagement.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB

class ChunkedTransferTest {
    private val policy = ChunkedUploadPolicy()

    @Test
    fun `defaults are 50 MiB threshold, 10 MiB chunks, 10000 chunks`() {
        assertEquals(50 * MIB, policy.thresholdBytes)
        assertEquals(10 * MIB, policy.chunkBytes)
        assertEquals(10_000, policy.maxChunks)
    }

    @Test
    fun `threshold decides chunking and unknown or empty files are never chunked`() {
        assertFalse(policy.isChunked(-1L))
        assertFalse(policy.isChunked(0L))
        assertFalse(policy.isChunked(50 * MIB - 1))
        assertTrue(policy.isChunked(50 * MIB))
        assertTrue(policy.isChunked(2 * GIB))
    }

    @Test
    fun `chunk size stays at 10 MiB up to the 10000 chunk boundary`() {
        assertEquals(10 * MIB, policy.chunkBytesFor(50 * MIB))
        assertEquals(10 * MIB, policy.chunkBytesFor(10_000 * 10 * MIB))
        assertEquals(10_000, chunkCountOf(10_000 * 10 * MIB, policy.chunkBytesFor(10_000 * 10 * MIB)))
    }

    @Test
    fun `one byte over the boundary grows the chunk to a whole MiB`() {
        val total = 10_000 * 10 * MIB + 1
        val chunk = policy.chunkBytesFor(total)

        assertEquals(11 * MIB, chunk)
        assertTrue(chunkCountOf(total, chunk) <= 10_000)
    }

    @Test
    fun `a 200 GiB file needs at most 10000 chunks of equal whole MiB size`() {
        val total = 200 * GIB
        val chunk = policy.chunkBytesFor(total)

        assertEquals(0L, chunk % MIB)
        assertEquals(21 * MIB, chunk)
        assertTrue(chunkCountOf(total, chunk) <= 10_000)
        // The next smaller whole MiB would not fit.
        assertTrue(chunkCountOf(total, chunk - MIB) > 10_000)
    }

    @Test
    fun `chunk counts round up`() {
        assertEquals(3, chunkCountOf(25 * MIB, 10 * MIB))
        assertEquals(2, chunkCountOf(20 * MIB, 10 * MIB))
        assertEquals(1, chunkCountOf(1L, 10 * MIB))
    }

    @Test
    fun `a tiny test policy keeps its chunk size`() {
        val tiny = ChunkedUploadPolicy(thresholdBytes = 20L, chunkBytes = 10L)

        assertEquals(10L, tiny.chunkBytesFor(25L))
        assertEquals(3, chunkCountOf(25L, tiny.chunkBytesFor(25L)))
    }

    @Test
    fun `confirmed bytes never exceed the total`() {
        val state = ChunkedTransferState(ID, 10L, 25L, 3, HASH, true, HASH)

        assertEquals(25L, state.confirmedBytes)
        assertEquals(20L, state.copy(confirmedChunks = 2).confirmedBytes)
        assertEquals(0L, state.copy(confirmedChunks = 0).confirmedBytes)
        assertEquals(3, state.chunkCount)
    }

    @Test
    fun `binding changes with server, user, folder and file name`() {
        val base =
            chunkedBinding(
                "https://c.example/remote.php/dav/uploads/alice",
                "https://c.example/remote.php/dav/files/alice/V/a.mp4",
            )

        assertEquals(64, base.length)
        assertEquals(
            base,
            chunkedBinding(
                "https://c.example/remote.php/dav/uploads/alice",
                "https://c.example/remote.php/dav/files/alice/V/a.mp4",
            ),
        )
        listOf(
            "https://other.example/remote.php/dav/uploads/alice" to
                "https://other.example/remote.php/dav/files/alice/V/a.mp4",
            "https://c.example/remote.php/dav/uploads/bob" to "https://c.example/remote.php/dav/files/alice/V/a.mp4",
            "https://c.example/remote.php/dav/uploads/alice" to "https://c.example/remote.php/dav/files/alice/W/a.mp4",
            "https://c.example/remote.php/dav/uploads/alice" to "https://c.example/remote.php/dav/files/alice/V/b.mp4",
        ).forEach { (collection, destination) ->
            assertNotEquals(base, chunkedBinding(collection, destination))
        }
    }

    @Test
    fun `binding does not confuse the url boundary`() {
        assertNotEquals(chunkedBinding("ab", "c"), chunkedBinding("a", "bc"))
    }

    private companion object {
        const val ID = "krhnles-0123456789abcdef0123456789abcdef"
        val HASH = "ab".repeat(32)
    }
}
