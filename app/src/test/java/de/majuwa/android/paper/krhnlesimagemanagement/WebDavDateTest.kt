package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.parseWebDavDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class WebDavDateTest {
    private val expected = 784887151000L // Tue, 15 Nov 1994 08:12:31 GMT

    @Test
    fun `valid GMT date parses`() {
        assertEquals(expected, parseWebDavDate("Tue, 15 Nov 1994 08:12:31 GMT"))
    }

    @Test
    fun `numeric offset parses`() {
        assertEquals(expected, parseWebDavDate("Tue, 15 Nov 1994 10:12:31 +0200"))
    }

    @Test
    fun `missing weekday parses`() {
        assertEquals(expected, parseWebDavDate("15 Nov 1994 08:12:31 GMT"))
    }

    @Test
    fun `invalid values are unknown`() {
        listOf(
            "Mon, 15 Nov 1994 08:12:31 GMT", // weekday contradicts the date
            "Tue, 15 Nov 1994 08:12:31 UTC",
            "yesterday",
            "Tuesday, 15-Nov-94 08:12:31 GMT", // RFC 850
            "Tue Nov 15 08:12:31 1994", // asctime
            "",
            "   ",
        ).forEach { assertNull(it, parseWebDavDate(it)) }
        assertNull(parseWebDavDate(null))
    }

    @Test
    fun `overlong input is unknown`() {
        assertNull(parseWebDavDate("Tue, 15 Nov 1994 08:12:31 GMT" + " ".repeat(10) + "x".repeat(60)))
        assertNull(parseWebDavDate("x".repeat(65)))
    }

    @Test
    fun `result does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(expected, parseWebDavDate("Tue, 15 Nov 1994 08:12:31 GMT"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
