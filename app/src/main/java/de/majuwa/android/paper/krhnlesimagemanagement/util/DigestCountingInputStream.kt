package de.majuwa.android.paper.krhnlesimagemanagement.util

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Passes bytes through while computing their SHA-256 and counting them, so a file is hashed from
 * exactly the bytes that are sent (no second read of the local file). Not thread-safe; `mark` /
 * `reset` are unsupported because they would hash bytes twice.
 */
class DigestCountingInputStream(
    delegate: InputStream,
) : FilterInputStream(delegate) {
    private val digest = MessageDigest.getInstance("SHA-256")

    /** Bytes read so far. */
    var bytesRead: Long = 0L
        private set

    /** Lower-case hex SHA-256 of the bytes read so far; call once the stream is fully read. */
    fun sha256Hex(): String = digest.clone().let { (it as MessageDigest).digest() }.toHexString()

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            digest.update(b.toByte())
            bytesRead++
        }
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            digest.update(b, off, n)
            bytesRead += n
        }
        return n
    }

    override fun skip(n: Long): Long {
        // Skipped bytes were not sent through the digest; read them so the hash stays complete.
        var remaining = n
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            remaining -= read
        }
        return n - remaining
    }

    override fun markSupported(): Boolean = false

    override fun mark(readlimit: Int) = Unit

    override fun reset(): Unit = throw java.io.IOException("mark/reset not supported")

    private companion object {
        const val SKIP_BUFFER_SIZE = 8 * 1024
    }
}
