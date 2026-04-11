package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.buildNextcloudBase
import de.majuwa.android.paper.krhnlesimagemanagement.data.buildOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.parsePropfindXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlParserTest {
    // ── parsePropfindXml ────────────────────────────────────────────────────

    @Test
    fun `parses typical Nextcloud PROPFIND response with directories and files`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Photos</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getcontenttype/>
                  </d:prop>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/Summer25/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Summer25</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getcontenttype/>
                  </d:prop>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/img001.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>img001.jpg</d:displayname>
                    <d:resourcetype/>
                    <d:getcontenttype>image/jpeg</d:getcontenttype>
                    <oc:fileid>12345</oc:fileid>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals(3, entries.size)

        // First entry: root directory
        val root = entries[0]
        assertEquals("/remote.php/dav/files/user/Photos/", root.href)
        assertEquals("Photos", root.displayName)
        assertTrue(root.isDirectory)
        assertNull(root.fileId)

        // Second entry: subdirectory
        val subDir = entries[1]
        assertEquals("/remote.php/dav/files/user/Photos/Summer25/", subDir.href)
        assertEquals("Summer25", subDir.displayName)
        assertTrue(subDir.isDirectory)

        // Third entry: image file
        val file = entries[2]
        assertEquals("/remote.php/dav/files/user/Photos/img001.jpg", file.href)
        assertEquals("img001.jpg", file.displayName)
        assertFalse(file.isDirectory)
        assertEquals("image/jpeg", file.contentType)
        assertEquals("12345", file.fileId)
    }

    @Test
    fun `parses empty PROPFIND response`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `falls back to href-derived name when displayname is missing`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/beach.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontenttype>image/jpeg</d:getcontenttype>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals(1, entries.size)
        assertEquals("beach.jpg", entries[0].displayName)
    }

    @Test
    fun `falls back to href-derived name when displayname is blank`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/Folder/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>   </d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals("Folder", entries[0].displayName)
    }

    @Test
    fun `handles response without href gracefully`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:propstat>
                  <d:prop>
                    <d:displayname>orphan</d:displayname>
                    <d:resourcetype/>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `handles non-Nextcloud server without oc fileid`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/webdav/photo.png</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>photo.png</d:displayname>
                    <d:resourcetype/>
                    <d:getcontenttype>image/png</d:getcontenttype>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals(1, entries.size)
        assertNull(entries[0].fileId)
        assertEquals("image/png", entries[0].contentType)
    }

    @Test
    fun `contentType defaults to empty string when missing`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/webdav/folder/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>folder</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals("", entries[0].contentType)
    }

    @Test
    fun `parses multiple image files in an album`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/Summer25/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Summer25</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/Summer25/a.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>a.jpg</d:displayname>
                    <d:resourcetype/>
                    <d:getcontenttype>image/jpeg</d:getcontenttype>
                    <oc:fileid>100</oc:fileid>
                  </d:prop>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/Summer25/b.png</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>b.png</d:displayname>
                    <d:resourcetype/>
                    <d:getcontenttype>image/png</d:getcontenttype>
                    <oc:fileid>101</oc:fileid>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals(3, entries.size)
        // Skip first (directory), check the two image files
        val images = entries.drop(1).filter { it.contentType.startsWith("image/") }
        assertEquals(2, images.size)
        assertEquals("100", images[0].fileId)
        assertEquals("101", images[1].fileId)
    }

    // ── buildOrigin ─────────────────────────────────────────────────────────

    @Test
    fun `buildOrigin extracts scheme and host`() {
        assertEquals(
            "https://cloud.example.com",
            buildOrigin("https://cloud.example.com/remote.php/dav/files/user/"),
        )
    }

    @Test
    fun `buildOrigin includes port when present`() {
        assertEquals(
            "https://cloud.example.com:8443",
            buildOrigin("https://cloud.example.com:8443/remote.php/dav/files/user/"),
        )
    }

    @Test
    fun `buildOrigin handles http scheme`() {
        assertEquals(
            "http://localhost",
            buildOrigin("http://localhost/webdav/"),
        )
    }

    @Test
    fun `buildOrigin returns empty for invalid URL`() {
        assertEquals("", buildOrigin("not a url"))
    }

    @Test
    fun `buildOrigin returns empty for empty string`() {
        assertEquals("", buildOrigin(""))
    }

    // ── buildNextcloudBase ──────────────────────────────────────────────────

    @Test
    fun `buildNextcloudBase extracts base from Nextcloud URL`() {
        assertEquals(
            "https://cloud.example.com",
            buildNextcloudBase("https://cloud.example.com/remote.php/dav/files/user/"),
        )
    }

    @Test
    fun `buildNextcloudBase returns null for non-Nextcloud URL`() {
        assertNull(buildNextcloudBase("https://webdav.example.com/files/"))
    }

    @Test
    fun `buildNextcloudBase returns null for empty string`() {
        assertNull(buildNextcloudBase(""))
    }

    @Test
    fun `buildNextcloudBase handles port in URL`() {
        assertEquals(
            "https://cloud.example.com:8080",
            buildNextcloudBase("https://cloud.example.com:8080/remote.php/dav/files/user/"),
        )
    }

    // ── Security: XXE injection prevention ──────────────────────────────────

    @Test
    fun `blocks external entity resolution to prevent XXE`() {
        val xxeXml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>&xxe;</d:href>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        // External entities are disabled so the reference is either rejected or produces
        // an empty/unexpanded value — never actual file contents.
        val entries =
            try {
                parsePropfindXml(xxeXml)
            } catch (_: Exception) {
                emptyList()
            }
        assertTrue(
            "XXE payload must not produce entries with filesystem paths",
            entries.none { it.href.contains("/etc/") || it.href.contains("root:") },
        )
    }

    @Test
    fun `parses response with benign DOCTYPE declaration`() {
        // Some WebDAV servers include a DOCTYPE in their response.
        // The parser must accept it as long as no external entities are referenced.
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE multistatus>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/user/Photos/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Photos</d:displayname>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()

        val entries = parsePropfindXml(xml)
        assertEquals(1, entries.size)
        assertEquals("Photos", entries[0].displayName)
    }
}
