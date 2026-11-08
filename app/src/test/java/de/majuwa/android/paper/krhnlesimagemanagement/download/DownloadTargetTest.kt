package de.majuwa.android.paper.krhnlesimagemanagement.download

import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadTargetTest {
    private val config =
        WebDavConfig(
            url = "https://cloud.example/remote.php/dav/files/user",
            username = "user",
            password = "secret",
            baseFolder = "Photos",
        )
    private val album = "/remote.php/dav/files/user/Photos/Summer%202026/"

    private fun resolve(
        photo: String,
        albumHref: String = album,
        cfg: WebDavConfig = config,
    ) = resolveDownloadTarget(cfg, albumHref, photo)?.toString()

    @Test
    fun `a percent encoded Nextcloud href resolves below its album`() {
        assertEquals(
            "https://cloud.example/remote.php/dav/files/user/Photos/Summer%202026/Caf%C3%A9.jpg",
            resolve("/remote.php/dav/files/user/Photos/Summer%202026/Caf%C3%A9.jpg"),
        )
    }

    @Test
    fun `an album href without trailing slash works`() {
        assertNotNull(
            resolve("/remote.php/dav/files/user/Photos/A/x.jpg", albumHref = "/remote.php/dav/files/user/Photos/A"),
        )
    }

    @Test
    fun `traversal and encoded separators are refused`() {
        listOf(
            "/remote.php/dav/files/user/Photos/Summer%202026/../x.jpg",
            "/remote.php/dav/files/user/Photos/Summer%202026/%2e%2e/x.jpg",
            "/remote.php/dav/files/user/Photos/Summer%202026/a%2Fb.jpg",
            "/remote.php/dav/files/user/Photos/Summer%202026/a%5Cb.jpg",
            "/remote.php/dav/files/user/Photos/Summer%202026/x.jpg?x=1",
        ).forEach { assertNull(it, resolve(it)) }
    }

    @Test
    fun `another origin is refused`() {
        assertNull(resolve("https://evil.example/remote.php/dav/files/user/Photos/Summer%202026/x.jpg"))
        assertNull(resolve("https://cloud.example:8443/remote.php/dav/files/user/Photos/Summer%202026/x.jpg"))
    }

    @Test
    fun `a photo outside its album or equal to it is refused`() {
        assertNull(resolve("/remote.php/dav/files/user/Photos/Other/x.jpg"))
        assertNull(resolve("/remote.php/dav/files/user/Photos/Summer%202026"))
        assertNull(resolve("/remote.php/dav/files/user/Photos/Summer%202026/"))
        assertNull(resolve("/remote.php/dav/files/user/Photos/x.jpg"))
        assertNull(resolve("/remote.php/dav/files/other/Photos/Summer%202026/x.jpg"))
    }

    @Test
    fun `a collection href is refused`() {
        assertNull(resolve("/remote.php/dav/files/user/Photos/Summer%202026/sub/"))
    }

    @Test
    fun `an album equal to or outside the base folder is refused`() {
        assertNull(resolve("/remote.php/dav/files/user/Photos/x.jpg", albumHref = "/remote.php/dav/files/user/Photos/"))
        assertNull(resolve("/remote.php/dav/files/user/Other/x.jpg", albumHref = "/remote.php/dav/files/user/Other/"))
        assertNull(resolve("/x.jpg", albumHref = "/remote.php/dav/files/user/Photos/../Secret/"))
    }

    @Test
    fun `a changed base folder refuses the old album`() {
        val changed = config.copy(baseFolder = "Other")
        assertNull(resolve("/remote.php/dav/files/user/Photos/Summer%202026/x.jpg", cfg = changed))
    }

    @Test
    fun `an invalid configured url yields null`() {
        assertNull(resolve("/x.jpg", cfg = config.copy(url = "not a url")))
    }
}
