package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumsRepositoryFolderNameTest {
    private fun folderNameOf(href: String) =
        AlbumsRepository(
            WebDavConfig("https://cloud.example.com/remote.php/dav/files/user/", "testuser", "testpass", ""),
            null,
        ).folderNameOf(RemoteAlbum("ignored", href))

    @Test
    fun `folderNameOf decodes the last href segment`() {
        assertEquals("Summer 2026", folderNameOf("/remote.php/dav/files/user/Summer%202026/"))
    }

    @Test
    fun `folderNameOf accepts an href without trailing slash`() {
        assertEquals("Trip", folderNameOf("/remote.php/dav/files/user/Trip"))
    }

    @Test
    fun `folderNameOf is null for an entry two levels deep`() {
        assertNull(folderNameOf("/remote.php/dav/files/user/Trip/Day1/"))
    }

    @Test
    fun `folderNameOf is null for foreign origin and traversal hrefs`() {
        assertNull(folderNameOf("https://evil.example/remote.php/dav/files/user/Trip/"))
        assertNull(folderNameOf("/remote.php/dav/files/user/../Trip/"))
        assertNull(folderNameOf("/remote.php/dav/files/user/a%2Fb/"))
    }

    @Test
    fun `folderNameOf is null for invisible, bidi, separator and undecodable names`() {
        listOf("%E2%80%8B", "%E2%80%AE", "%E2%81%A6", "%E2%81%A9", "%E2%80%A8", "%E2%80%A9", "%FF", "%EF%BF%BD")
            .forEach { encoded ->
                assertNull(encoded, folderNameOf("/remote.php/dav/files/user/Trip$encoded/"))
                assertNull(encoded, folderNameOf("/remote.php/dav/files/user/${encoded}Trip/"))
            }
        assertEquals("Café ☀", folderNameOf("/remote.php/dav/files/user/Caf%C3%A9%20%E2%98%80/"))
    }

    @Test
    fun `folderNameOf is null for a decoded backslash`() {
        assertNull(folderNameOf("/remote.php/dav/files/user/a%5Cb/"))
    }

    @Test
    fun `folderNameOf is null for leading or trailing whitespace`() {
        assertNull(folderNameOf("/remote.php/dav/files/user/%20Trip%20/"))
    }
}
