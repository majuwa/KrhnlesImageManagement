package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.LimitedInputStream
import de.majuwa.android.paper.krhnlesimagemanagement.data.MalformedMultistatusException
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindEntryLimits
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindEntryReader
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindItem
import de.majuwa.android.paper.krhnlesimagemanagement.data.PropfindResponseTooLargeException
import de.majuwa.android.paper.krhnlesimagemanagement.data.XmlTokenSizeLimitingInputStream
import de.majuwa.android.paper.krhnlesimagemanagement.data.parsePropfindXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.SocketTimeoutException
import java.util.Enumeration

/**
 * Streaming PROPFIND reader (issues #27 / #46): XXE rejection, malformed input, per-entry bounds
 * and large listings. Runs under Robolectric so the production `android.util.Xml` parser is used.
 */
@RunWith(RobolectricTestRunner::class)
class PropfindEntryReaderTest {
    private fun response(
        href: String,
        extra: String = "",
    ) = "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
        "<d:resourcetype/><d:getcontenttype>image/jpeg</d:getcontenttype>$extra</d:prop></d:propstat></d:response>"

    private fun multistatus(vararg responses: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">" +
            responses.joinToString("") +
            "</d:multistatus>"

    private fun readAll(
        xml: String,
        limits: PropfindEntryLimits = PropfindEntryLimits(),
    ): List<PropfindItem> {
        val reader = PropfindEntryReader(xml.byteInputStream(), limits)
        return generateSequence { reader.next() }.toList()
    }

    private fun assertMalformed(xml: String) {
        assertThrows(MalformedMultistatusException::class.java) { parsePropfindXml(xml) }
    }

    // ── XXE ────────────────────────────────────────────────────────────────

    @Test
    fun `internal entity declaration is rejected and not expanded`() =
        assertMalformed(
            """
            <?xml version="1.0"?>
            <!DOCTYPE d:multistatus [<!ENTITY secret "expanded">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/a/&secret;.jpg</d:href></d:response></d:multistatus>
            """.trimIndent(),
        )

    @Test
    fun `billion laughs payload is rejected`() {
        val entities =
            buildString {
                append("<!ENTITY lol0 \"lol\">")
                for (i in 1..9) append("<!ENTITY lol$i \"" + "&lol${i - 1};".repeat(10) + "\">")
            }
        assertMalformed(
            "<?xml version=\"1.0\"?><!DOCTYPE d:multistatus [$entities]>" +
                "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&lol9;</d:href></d:response></d:multistatus>",
        )
    }

    @Test
    fun `external SYSTEM entity is rejected and never fetched`() =
        assertMalformed(
            """
            <?xml version="1.0"?>
            <!DOCTYPE d:multistatus [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>
            """.trimIndent(),
        )

    @Test
    fun `external DTD reference is rejected`() =
        assertMalformed(
            "<?xml version=\"1.0\"?><!DOCTYPE d:multistatus SYSTEM \"http://attacker.example/x.dtd\">" +
                "<d:multistatus xmlns:d=\"DAV:\"/>",
        )

    @Test
    fun `DOCTYPE after the root start tag is rejected and not expanded`() =
        assertMalformed(
            "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" +
                "<!DOCTYPE d:multistatus [<!ENTITY secret \"expanded\">]>" +
                response("/a/&secret;.jpg") + "</d:multistatus>",
        )

    @Test
    fun `DOCTYPE inside d response is rejected and not expanded`() =
        assertMalformed(
            multistatus(
                "<d:response><!DOCTYPE d:response [<!ENTITY secret \"expanded\">]>" +
                    "<d:href>/a/&secret;.jpg</d:href></d:response>",
            ),
        )

    @Test
    fun `undeclared entity reference in the body is rejected`() = assertMalformed(multistatus(response("/a/&foo;.jpg")))

    @Test
    fun `built-in entities and character references are accepted`() {
        val entries = parsePropfindXml(multistatus(response("/a/b&amp;c&#252;.jpg")))
        assertEquals("/a/b&cü.jpg", entries.single().href)
    }

    // ── Malformed input ────────────────────────────────────────────────────

    @Test
    fun `truncated XML is malformed`() = assertMalformed(multistatus(response("/a/1.jpg")).dropLast(30))

    @Test
    fun `mismatched tags are malformed`() =
        assertMalformed(multistatus("<d:response><d:href>/a/1.jpg</d:propstat></d:response>"))

    @Test
    fun `non-multistatus root is malformed`() =
        assertMalformed("<?xml version=\"1.0\"?><d:error xmlns:d=\"DAV:\"><d:response/></d:error>")

    @Test
    fun `multistatus in another namespace is malformed`() = assertMalformed("<multistatus xmlns=\"urn:other\"/>")

    @Test
    fun `empty body is malformed`() = assertMalformed("")

    @Test
    fun `non-XML body is malformed`() = assertMalformed("<html><body>Login</body></html>")

    @Test
    fun `unsupported declared encoding is malformed`() =
        assertMalformed("<?xml version=\"1.0\" encoding=\"bogus-encoding\"?><d:multistatus xmlns:d=\"DAV:\"/>")

    // ── Stream failures (KXml reads already while the parser starts) ──────

    private fun failingStream(failure: IOException): InputStream =
        object : InputStream() {
            override fun read(): Int = throw failure

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = throw failure
        }

    @Test
    fun `a timeout before the first byte is rethrown unchanged, not as malformed`() {
        val reader = PropfindEntryReader(failingStream(SocketTimeoutException("timeout")))
        assertThrows(SocketTimeoutException::class.java) { reader.next() }
    }

    @Test
    fun `a cancelled call before the first byte is rethrown unchanged`() {
        val canceled = IOException("Canceled")
        val reader = PropfindEntryReader(failingStream(canceled))
        val thrown = assertThrows(IOException::class.java) { reader.next() }
        assertSame(canceled, thrown)
    }

    @Test
    fun `the size cap hit while the parser starts is rethrown unchanged`() {
        val reader = PropfindEntryReader(LimitedInputStream(multistatus(response("/a/1.jpg")).byteInputStream(), 1))
        assertThrows(PropfindResponseTooLargeException::class.java) { reader.next() }
    }

    // ── Document bounds (parse-cost DoS, security review) ─────────────────

    private val hostileSize = 200_000

    @Test(timeout = 10_000)
    fun `deeply nested elements inside a response fail fast`() =
        assertMalformed(
            multistatus(response("/a/1.jpg", extra = "<x>".repeat(hostileSize) + "</x>".repeat(hostileSize))),
        )

    @Test(timeout = 10_000)
    fun `deeply nested elements outside a response fail fast`() =
        assertMalformed(multistatus("<x>".repeat(hostileSize) + "</x>".repeat(hostileSize), response("/a/1.jpg")))

    @Test
    fun `nesting just within the document depth is accepted`() {
        // multistatus (1) > response (2) > 30 levels = depth 32; the entry itself is skipped (entry depth).
        val items = readAll(multistatus(response("/a/1.jpg", extra = "<x>".repeat(28) + "</x>".repeat(28))))
        assertEquals(PropfindItem.Skipped, items.single())
    }

    @Test(timeout = 10_000)
    fun `an element with very many attributes fails fast`() =
        assertMalformed(
            multistatus("<d:response " + (0 until hostileSize).joinToString(" ") { "a$it=\"\"" } + "/>"),
        )

    @Test
    fun `an element with more attributes than allowed is malformed`() =
        assertMalformed(multistatus("<d:response " + (0 until 65).joinToString(" ") { "a$it=\"1\"" } + "/>"))

    @Test(timeout = 10_000)
    fun `an element with very many namespace declarations fails fast`() =
        assertMalformed(
            multistatus("<d:response " + (0 until hostileSize).joinToString(" ") { "xmlns:p$it=\"urn:x\"" } + "/>"),
        )

    @Test
    fun `an element with more namespace declarations than allowed is malformed`() =
        assertMalformed(
            multistatus("<d:response " + (0 until 65).joinToString(" ") { "xmlns:p$it=\"urn:x\"" } + "/>"),
        )

    @Test(timeout = 10_000)
    fun `a huge text node fails before it is buffered`() =
        assertMalformed(
            multistatus(response("/a/1.jpg", extra = "<d:getetag>" + "e".repeat(1_000_000) + "</d:getetag>")),
        )

    @Test(timeout = 10_000)
    fun `a huge CDATA text node containing markup characters fails`() =
        assertMalformed(
            multistatus(
                response("/a/1.jpg", extra = "<d:getetag><![CDATA[" + "<x>".repeat(400_000) + "]]></d:getetag>"),
            ),
        )

    @Test(timeout = 10_000)
    fun `text split by comments counts as one text node`() =
        assertMalformed(
            multistatus(response("/a/1.jpg", extra = "<d:getetag>" + "e<!-- <x> -->".repeat(100_000) + "</d:getetag>")),
        )

    @Test
    fun `a maximal multi-byte href still fits the token bound`() {
        val href = "/a/" + "€".repeat(8 * 1024 - 7) + ".jpg" // 3 bytes per character: ~24 KiB
        assertEquals(href, parsePropfindXml(multistatus(response(href))).single().href)
    }

    @Test
    fun `token limiter accepts sections with repeated terminator characters`() {
        // A missed terminator would keep the limiter inside the section, so the long text after <x> would overflow.
        val xml = "<r><!-- a --->b<![CDATA[ ]]]>c<?pi ??><x>" + "d".repeat(55) + "</x></r>"
        val bytes = XmlTokenSizeLimitingInputStream(xml.byteInputStream(), maxTokenBytes = 64).readBytes()
        assertEquals(xml, String(bytes))
    }

    @Test
    fun `token limiter resets on every tag`() {
        val xml = "<r>" + "<x>${"t".repeat(60)}</x>".repeat(1_000) + "</r>"
        assertEquals(xml.length, XmlTokenSizeLimitingInputStream(xml.byteInputStream(), 64).readBytes().size)
    }

    @Test
    fun `token limiter rejects one oversized tag`() {
        val xml = "<r " + "a".repeat(100) + "/>"
        assertThrows(MalformedMultistatusException::class.java) {
            XmlTokenSizeLimitingInputStream(xml.byteInputStream(), 64).readBytes()
        }
    }

    // ── Per-entry bounds ───────────────────────────────────────────────────

    @Test
    fun `entry with too many elements is skipped and the next entry is read`() {
        val noisy = response("/a/noisy.jpg", extra = "<x:y xmlns:x=\"urn:x\"/>".repeat(20))
        val items = readAll(multistatus(noisy, response("/a/ok.jpg")), PropfindEntryLimits(maxElementsPerEntry = 10))

        assertEquals(PropfindItem.Skipped, items[0])
        assertEquals("/a/ok.jpg", (items[1] as PropfindItem.Entry).entry.href)
    }

    @Test
    fun `entry nested too deeply is skipped`() {
        val deep = response("/a/deep.jpg", extra = "<d:x>".repeat(20) + "</d:x>".repeat(20))
        val items = readAll(multistatus(deep, response("/a/ok.jpg")), PropfindEntryLimits(maxEntryDepth = 8))

        assertEquals(PropfindItem.Skipped, items[0])
        assertTrue(items[1] is PropfindItem.Entry)
    }

    @Test
    fun `entry with an overlong text value is skipped`() {
        val items =
            readAll(
                multistatus(response("/a/" + "x".repeat(200) + ".jpg"), response("/a/ok.jpg")),
                PropfindEntryLimits(maxTextLength = 100),
            )

        assertEquals(PropfindItem.Skipped, items[0])
        assertEquals("/a/ok.jpg", (items[1] as PropfindItem.Entry).entry.href)
    }

    @Test
    fun `long text outside captured fields does not skip the entry`() {
        val items =
            readAll(
                multistatus(response("/a/ok.jpg", extra = "<d:getetag>" + "e".repeat(500) + "</d:getetag>")),
                PropfindEntryLimits(maxTextLength = 100),
            )

        assertTrue(items.single() is PropfindItem.Entry)
    }

    @Test
    fun `unknown elements between responses are ignored`() {
        val xml = multistatus("<d:sync-token>token</d:sync-token>", response("/a/1.jpg"))
        assertEquals(listOf("/a/1.jpg"), parsePropfindXml(xml).map { it.href })
    }

    @Test
    fun `reader returns null after the root element`() {
        val reader = PropfindEntryReader(multistatus(response("/a/1.jpg")).byteInputStream())
        assertTrue(reader.next() is PropfindItem.Entry)
        assertNull(reader.next())
        assertNull(reader.next())
    }

    // ── Large listings ─────────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun `a generated 10,000-entry stream parses incrementally`() {
        val count = 10_000
        val parts =
            sequenceOf("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">") +
                (0 until count).asSequence().map { response("/a/photo$it.jpg") } +
                sequenceOf("</d:multistatus>")
        val streams: Iterator<InputStream> = parts.map { ByteArrayInputStream(it.toByteArray()) }.iterator()
        // Generated lazily: the whole body never exists in memory at once.
        val enumeration =
            object : Enumeration<InputStream> {
                override fun hasMoreElements() = streams.hasNext()

                override fun nextElement() = streams.next()
            }
        val reader = PropfindEntryReader(SequenceInputStream(enumeration))

        var read = 0
        var last: String? = null
        while (true) {
            val item = reader.next() ?: break
            read++
            last = (item as PropfindItem.Entry).entry.href
        }

        assertEquals(count, read)
        assertEquals("/a/photo9999.jpg", last)
    }

    // ── LimitedInputStream ─────────────────────────────────────────────────

    @Test
    fun `limited stream accepts a body of exactly the cap`() {
        val stream = LimitedInputStream(ByteArray(10).inputStream(), maxBytes = 10)
        assertEquals(10, stream.readBytes().size)
    }

    @Test
    fun `limited stream throws as soon as the cap is exceeded`() {
        val stream = LimitedInputStream(ByteArray(11).inputStream(), maxBytes = 10)
        val buffer = ByteArray(64)
        assertEquals(10, stream.read(buffer, 0, buffer.size))
        assertThrows(PropfindResponseTooLargeException::class.java) { stream.read(buffer, 0, buffer.size) }
    }

    @Test
    fun `limited stream counts single-byte reads and skips`() {
        val stream = LimitedInputStream(ByteArray(20).inputStream(), maxBytes = 10)
        assertEquals(5L, stream.skip(5))
        repeat(5) { stream.read() }
        assertThrows(PropfindResponseTooLargeException::class.java) { stream.read() }
    }

    @Test
    fun `oversized body fails the reader with the size exception`() {
        val xml = multistatus(*Array(100) { response("/a/photo$it.jpg") })
        val reader = PropfindEntryReader(LimitedInputStream(xml.byteInputStream(), maxBytes = 2_000))

        assertThrows(PropfindResponseTooLargeException::class.java) {
            while (reader.next() != null) {
                // read until the cap is hit
            }
        }
    }
}
