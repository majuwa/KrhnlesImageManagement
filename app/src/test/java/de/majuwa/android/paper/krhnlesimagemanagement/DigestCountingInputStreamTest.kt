package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.util.DigestCountingInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class DigestCountingInputStreamTest {
    private fun expected(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val content = ByteArray(100_000) { (it * 31).toByte() }

    @Test
    fun `digest and count equal MessageDigest over the same bytes for bulk reads`() {
        val stream = DigestCountingInputStream(ByteArrayInputStream(content))
        val buffer = ByteArray(4096)
        while (stream.read(buffer) != -1) {
            // drain
        }
        assertEquals(expected(content), stream.sha256Hex())
        assertEquals(content.size.toLong(), stream.bytesRead)
    }

    @Test
    fun `single byte reads and skip are included in the digest`() {
        val stream = DigestCountingInputStream(ByteArrayInputStream(content))
        assertEquals(content[0].toInt() and 0xff, stream.read())
        assertEquals(10L, stream.skip(10))
        val rest = stream.readBytes()
        assertEquals(content.size - 11, rest.size)
        assertEquals(expected(content), stream.sha256Hex())
        assertEquals(content.size.toLong(), stream.bytesRead)
    }

    @Test
    fun `digest of an empty stream`() {
        val stream = DigestCountingInputStream(ByteArrayInputStream(ByteArray(0)))
        assertEquals(-1, stream.read())
        assertEquals(expected(ByteArray(0)), stream.sha256Hex())
        assertEquals(0L, stream.bytesRead)
    }

    @Test
    fun `hex can be read again without disturbing the digest`() {
        val stream = DigestCountingInputStream(ByteArrayInputStream(content))
        stream.readBytes()
        assertEquals(stream.sha256Hex(), stream.sha256Hex())
    }

    @Test
    fun `close closes the wrapped stream`() {
        var closed = false
        val delegate =
            object : InputStream() {
                override fun read(): Int = -1

                override fun close() {
                    closed = true
                }
            }
        DigestCountingInputStream(delegate).close()
        assertTrue(closed)
    }

    @Test
    fun `mark is not supported`() {
        assertFalse(DigestCountingInputStream(ByteArrayInputStream(content)).markSupported())
    }
}
