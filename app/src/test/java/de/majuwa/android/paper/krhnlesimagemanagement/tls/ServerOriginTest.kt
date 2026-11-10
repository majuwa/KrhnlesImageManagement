package de.majuwa.android.paper.krhnlesimagemanagement.tls

import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOriginTest {
    @Test
    fun `host case and default port are normalized`() {
        assertEquals(ServerOrigin("files.example", 443), ServerOrigin.of("https://Files.EXAMPLE/"))
        assertEquals(ServerOrigin("files.example", 8443), ServerOrigin.of("https://files.example:8443/x"))
    }

    @Test
    fun `an IDN host is stored in its ASCII form and matches both spellings`() {
        val origin = ServerOrigin.of("https://BÜCHER.example/")!!
        assertEquals("xn--bcher-kva.example", origin.host)
        assertTrue(origin.matches("xn--bcher-kva.example", 443))
        assertTrue(origin.matches("bücher.example", 443))
        assertEquals(origin, ServerOrigin.parse(origin.serialize()))
    }

    @Test
    fun `an IPv6 host has no brackets, serializes with brackets and round trips`() {
        val origin = ServerOrigin.of("https://[2001:DB8::1]:8443/")!!
        assertEquals("2001:db8::1", origin.host)
        assertEquals("[2001:db8::1]:8443", origin.serialize())
        assertEquals(origin, ServerOrigin.parse("[2001:db8::1]:8443"))
        assertTrue(origin.matches("2001:db8::1", 8443))
        assertFalse(origin.matches("2001:db8::1", 443))
    }

    @Test
    fun `parse rejects text that is not a normalized host and port`() {
        assertNull(ServerOrigin.parse("Files.example:443"))
        assertNull(ServerOrigin.parse("files.example"))
        assertNull(ServerOrigin.parse("files.example:0"))
        assertNull(ServerOrigin.parse("files.example:70000"))
        assertNull(ServerOrigin.of("http://files.example/"))
    }
}
