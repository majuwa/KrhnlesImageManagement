package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentParserTest {
    private val contentResolver =
        ApplicationProvider.getApplicationContext<Application>().contentResolver

    // ── non-share intents ────────────────────────────────────────────────────

    @Test
    fun `parseSharedPhotos returns empty list for non-share intent`() {
        val intent = Intent(Intent.ACTION_VIEW)
        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSharedPhotos returns empty list for null action intent`() {
        val intent = Intent()
        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)
        assertTrue(result.isEmpty())
    }

    // ── ACTION_SEND ──────────────────────────────────────────────────────────

    @Test
    fun `parseSharedPhotos handles ACTION_SEND with single URI`() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals(1, result.size)
        assertEquals(uri, result[0].uri)
    }

    @Test
    fun `parseSharedPhotos uses intent type as mimeType fallback for ACTION_SEND`() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals("image/png", result[0].mimeType)
    }

    @Test
    fun `parseSharedPhotos returns empty for ACTION_SEND without EXTRA_STREAM`() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSharedPhotos assigns sequential id per URI for ACTION_SEND`() {
        val uri = Uri.parse("content://media/external/images/media/99")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals(0L, result[0].id)
    }

    // ── ACTION_SEND_MULTIPLE ─────────────────────────────────────────────────

    @Test
    fun `parseSharedPhotos handles ACTION_SEND_MULTIPLE with two URIs`() {
        val uri1 = Uri.parse("content://media/external/images/media/1")
        val uri2 = Uri.parse("content://media/external/images/media/2")
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals(2, result.size)
        assertEquals(uri1, result[0].uri)
        assertEquals(uri2, result[1].uri)
        assertEquals(0L, result[0].id)
        assertEquals(1L, result[1].id)
    }

    @Test
    fun `parseSharedPhotos returns empty for ACTION_SEND_MULTIPLE without EXTRA_STREAM`() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSharedPhotos assigns today's date to all shared photos`() {
        val uri = Uri.parse("content://media/external/images/media/7")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        val today = java.time.LocalDate.now()
        assertEquals(today, result[0].dateTaken)
    }

    @Test
    fun `parseSharedPhotos uses image wildcard mimeType when intent type is null`() {
        val uri = Uri.parse("content://media/external/images/media/3")
        // No type set on intent; ContentResolver will also return null for a test URI
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
            }

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals(1, result.size)
        assertEquals("image/*", result[0].mimeType)
    }

    // ── URI policy (file:// and own authorities) ─────────────────────────────

    private fun sendMultiple(vararg uris: String) =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { Uri.parse(it) }))
        }

    @Test
    fun `parseSharedPhotos rejects file URIs of app private files`() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/$OWN_PACKAGE/files/upload_queue_1_a.json"))
            }

        assertTrue(parseSharedPhotos(intent, contentResolver, OWN_PACKAGE).isEmpty())
    }

    @Test
    fun `parseSharedPhotos rejects own authorities and keeps foreign content URIs`() {
        val intent =
            sendMultiple(
                "content://$OWN_PACKAGE/x/1",
                "content://$OWN_PACKAGE.fileprovider/x/2",
                "content://10@$OWN_PACKAGE.fileprovider/x/3",
                "http://example.com/a.jpg",
                "content://media/external/images/media/4",
                "content://${OWN_PACKAGE}evil.provider/x/5",
            )

        val result = parseSharedPhotos(intent, contentResolver, OWN_PACKAGE)

        assertEquals(
            listOf("content://media/external/images/media/4", "content://${OWN_PACKAGE}evil.provider/x/5"),
            result.map { it.uri.toString() },
        )
        assertEquals(listOf(0L, 1L), result.map { it.id })
    }

    private companion object {
        const val OWN_PACKAGE = "de.majuwa.android.paper.krhnlesimagemanagement"
    }
}
