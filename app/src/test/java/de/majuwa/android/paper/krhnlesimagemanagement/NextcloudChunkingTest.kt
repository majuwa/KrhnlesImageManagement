package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.chunkName
import de.majuwa.android.paper.krhnlesimagemanagement.data.isValidTransferId
import de.majuwa.android.paper.krhnlesimagemanagement.data.newTransferId
import de.majuwa.android.paper.krhnlesimagemanagement.data.nextcloudUploadsCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudChunkingTest {
    private fun collection(url: String) = nextcloudUploadsCollection(url)?.toString()

    @Test
    fun `login flow url maps to the uploads collection of the same user`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/uploads/alice",
            collection("https://cloud.example.com/remote.php/dav/files/alice/"),
        )
    }

    @Test
    fun `the folder below the user is ignored`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/uploads/alice",
            collection("https://cloud.example.com/remote.php/dav/files/alice/Photos/Trip"),
        )
    }

    @Test
    fun `sub path install keeps its prefix`() {
        assertEquals(
            "https://example.com/nextcloud/remote.php/dav/uploads/bob",
            collection("https://example.com/nextcloud/remote.php/dav/files/bob/"),
        )
        assertEquals(
            "https://example.com/a/b/remote.php/dav/uploads/bob",
            collection("https://example.com/a/b/remote.php/dav/files/bob"),
        )
    }

    @Test
    fun `port and scheme are preserved`() {
        assertEquals(
            "http://192.168.1.5:8080/remote.php/dav/uploads/alice",
            collection("http://192.168.1.5:8080/remote.php/dav/files/alice/"),
        )
        assertEquals(
            "https://cloud.example.com:8443/remote.php/dav/uploads/alice",
            collection("https://cloud.example.com:8443/remote.php/dav/files/alice/"),
        )
    }

    @Test
    fun `a user name with special characters stays one encoded segment`() {
        assertEquals(
            "https://c.example.com/remote.php/dav/uploads/a%20b@c",
            collection("https://c.example.com/remote.php/dav/files/a%20b@c/"),
        )
    }

    @Test
    fun `legacy webdav url and plain webdav are not chunked`() {
        assertNull(collection("https://cloud.example.com/remote.php/webdav/"))
        assertNull(collection("https://cloud.example.com/remote.php/webdav/Photos"))
        assertNull(collection("https://dav.example.com/"))
        assertNull(collection("https://dav.example.com/dav/files/alice/"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/uploads/alice/"))
    }

    @Test
    fun `unsafe or blank user segments are rejected`() {
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files//Photos"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/../x"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/%2e%2e/x"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/a%2Fb/x"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/a%5Cb/x"))
        assertNull(collection("https://cloud.example.com/remote.php/dav/files/a%0Ab/x"))
    }

    @Test
    fun `unparsable urls are not chunked`() {
        assertNull(collection(""))
        assertNull(collection("not a url"))
        assertNull(collection("ftp://cloud.example.com/remote.php/dav/files/alice/"))
    }

    @Test
    fun `new transfer ids are valid and unique`() {
        val a = newTransferId()
        val b = newTransferId()

        assertTrue(isValidTransferId(a))
        assertNotEquals(a, b)
        assertEquals("krhnles-".length + 32, a.length)
    }

    @Test
    fun `transfer ids accept only the app format`() {
        assertTrue(isValidTransferId("krhnles-0123456789abcdef0123456789abcdef"))
        assertFalse(isValidTransferId(""))
        assertFalse(isValidTransferId("krhnles-"))
        assertFalse(isValidTransferId("krhnles-0123456789ABCDEF0123456789ABCDEF"))
        assertFalse(isValidTransferId("krhnles-0123456789abcdef0123456789abcde"))
        assertFalse(isValidTransferId("krhnles-0123456789abcdef0123456789abcdef0"))
        assertFalse(isValidTransferId("other-0123456789abcdef0123456789abcdef"))
        assertFalse(isValidTransferId("krhnles-0123456789abcdef0123456789abcde/"))
        assertFalse(isValidTransferId("krhnles-../../0123456789abcdef0123456789"))
        assertFalse(isValidTransferId(" krhnles-0123456789abcdef0123456789abcdef"))
        assertFalse(isValidTransferId("krhnles-0123456789abcdef0123456789abcdef\n"))
    }

    @Test
    fun `chunk names are five digits and bounded`() {
        assertEquals("00001", chunkName(1))
        assertEquals("00042", chunkName(42))
        assertEquals("10000", chunkName(10_000))
        assertThrows(IllegalArgumentException::class.java) { chunkName(0) }
        assertThrows(IllegalArgumentException::class.java) { chunkName(-1) }
        assertThrows(IllegalArgumentException::class.java) { chunkName(10_001) }
    }

    @Test
    fun `chunk names sort numerically as strings`() {
        val names = (1..10_000).map(::chunkName)
        assertEquals(names, names.sorted())
    }
}
