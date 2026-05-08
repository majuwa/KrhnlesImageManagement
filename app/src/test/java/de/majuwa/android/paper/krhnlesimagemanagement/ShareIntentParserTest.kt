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
        val result = parseSharedPhotos(intent, contentResolver)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSharedPhotos returns empty list for null action intent`() {
        val intent = Intent()
        val result = parseSharedPhotos(intent, contentResolver)
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

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

        assertEquals("image/png", result[0].mimeType)
    }

    @Test
    fun `parseSharedPhotos returns empty for ACTION_SEND without EXTRA_STREAM`() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
            }

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

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

        val result = parseSharedPhotos(intent, contentResolver)

        assertEquals(1, result.size)
        assertEquals("image/*", result[0].mimeType)
    }
}
