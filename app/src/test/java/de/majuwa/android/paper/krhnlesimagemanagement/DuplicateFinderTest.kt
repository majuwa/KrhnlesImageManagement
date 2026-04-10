package de.majuwa.android.paper.krhnlesimagemanagement

import android.graphics.Bitmap
import android.graphics.Color
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.util.DuplicateFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class DuplicateFinderPureTest {
    // ── hammingDistance ──────────────────────────────────────────────────────

    @Test
    fun `hammingDistance of identical hashes is zero`() {
        assertEquals(0, DuplicateFinder.hammingDistance(0L, 0L))
        assertEquals(0, DuplicateFinder.hammingDistance(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0, DuplicateFinder.hammingDistance(-1L, -1L))
    }

    @Test
    fun `hammingDistance of single bit difference is one`() {
        assertEquals(1, DuplicateFinder.hammingDistance(0b0, 0b1))
        assertEquals(1, DuplicateFinder.hammingDistance(0L, 1L shl 63))
    }

    @Test
    fun `hammingDistance of opposite hashes is 64`() {
        assertEquals(64, DuplicateFinder.hammingDistance(0L, -1L))
    }

    @Test
    fun `hammingDistance is symmetric`() {
        val a = 0x0F0F0F0FL
        val b = 0xF0F0F0F0L
        assertEquals(
            DuplicateFinder.hammingDistance(a, b),
            DuplicateFinder.hammingDistance(b, a),
        )
    }

    @Test
    fun `hammingDistance counts known pattern correctly`() {
        // 0xFF = 8 bits set
        assertEquals(8, DuplicateFinder.hammingDistance(0L, 0xFFL))
    }

    // ── groupDuplicates ─────────────────────────────────────────────────────

    private fun photo(href: String) =
        RemotePhoto(
            displayName = href.substringAfterLast('/'),
            href = href,
            fileId = null,
            contentType = "image/jpeg",
        )

    @Test
    fun `groupDuplicates returns empty for no photos`() {
        val groups = DuplicateFinder.groupDuplicates(emptyList(), emptyMap())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupDuplicates returns empty for single photo`() {
        val p = photo("/a.jpg")
        val groups = DuplicateFinder.groupDuplicates(listOf(p), mapOf("/a.jpg" to 0L))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupDuplicates groups identical hashes`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        val hashes = mapOf("/a.jpg" to 42L, "/b.jpg" to 42L)
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2), hashes)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `groupDuplicates groups within threshold`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        // Hashes differ by 5 bits — well within default threshold of 18
        val hashes = mapOf("/a.jpg" to 0L, "/b.jpg" to 0b11111L)
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2), hashes)
        assertEquals(1, groups.size)
    }

    @Test
    fun `groupDuplicates does not group beyond threshold`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        // Hashes differ by more than 18 bits (32 bits differ)
        val hashes = mapOf("/a.jpg" to 0L, "/b.jpg" to 0xFFFFFFFFL)
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2), hashes)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupDuplicates with custom threshold`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        // 3 bits differ
        val hashes = mapOf("/a.jpg" to 0L, "/b.jpg" to 0b111L)
        // With threshold=2 they should NOT be grouped
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2), hashes, threshold = 2)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupDuplicates forms transitive groups`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        val p3 = photo("/c.jpg")
        // a~b (distance 5), b~c (distance 5), a~c (distance 10) — all within threshold
        val hashes =
            mapOf(
                "/a.jpg" to 0L,
                "/b.jpg" to 0b11111L, // 5 bits from a
                "/c.jpg" to 0b1111111111L, // 10 bits from a, 5 from b approx — let's compute precisely
            )
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2, p3), hashes)
        // All three should be in one group via union-find transitivity
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun `groupDuplicates separates distinct clusters`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        val p3 = photo("/c.jpg")
        val p4 = photo("/d.jpg")
        // Cluster 1: a, b (identical hash 0)
        // Cluster 2: c, d (identical hash with 64 bits different from cluster 1)
        val hashes =
            mapOf(
                "/a.jpg" to 0L,
                "/b.jpg" to 0L,
                "/c.jpg" to -1L,
                "/d.jpg" to -1L,
            )
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2, p3, p4), hashes)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.size == 2 })
    }

    @Test
    fun `groupDuplicates skips photos without hashes`() {
        val p1 = photo("/a.jpg")
        val p2 = photo("/b.jpg")
        val p3 = photo("/c.jpg")
        // Only p1 and p3 have hashes; p2 is missing
        val hashes = mapOf("/a.jpg" to 0L, "/c.jpg" to 0L)
        val groups = DuplicateFinder.groupDuplicates(listOf(p1, p2, p3), hashes)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
        val hrefs = groups[0].map { it.href }.toSet()
        assertTrue(hrefs.contains("/a.jpg"))
        assertTrue(hrefs.contains("/c.jpg"))
    }
}

@RunWith(RobolectricTestRunner::class)
class DuplicateFinderDHashTest {
    /**
     * Creates a 9×8 bitmap (the exact dHash internal size) to bypass Robolectric's
     * limited createScaledBitmap implementation.
     */
    private fun create9x8Bitmap(pixelFn: (x: Int, y: Int) -> Int): Bitmap {
        val bitmap = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                bitmap.setPixel(x, y, pixelFn(x, y))
            }
        }
        return bitmap
    }

    @Test
    fun `computeDHash of uniform white bitmap is zero`() {
        val bitmap = create9x8Bitmap { _, _ -> Color.WHITE }
        val hash = DuplicateFinder.computeDHash(bitmap)
        assertEquals(0L, hash)
        bitmap.recycle()
    }

    @Test
    fun `computeDHash of uniform black bitmap is zero`() {
        val bitmap = create9x8Bitmap { _, _ -> Color.BLACK }
        val hash = DuplicateFinder.computeDHash(bitmap)
        assertEquals(0L, hash)
        bitmap.recycle()
    }

    @Test
    fun `computeDHash of uniform gray bitmap is zero`() {
        val bitmap = create9x8Bitmap { _, _ -> Color.GRAY }
        val hash = DuplicateFinder.computeDHash(bitmap)
        assertEquals(0L, hash)
        bitmap.recycle()
    }

    @Test
    fun `computeDHash is deterministic`() {
        val bitmap = create9x8Bitmap { _, _ -> Color.WHITE }
        val hash1 = DuplicateFinder.computeDHash(bitmap)
        val hash2 = DuplicateFinder.computeDHash(bitmap)
        assertEquals(hash1, hash2)
        bitmap.recycle()
    }

    // Note: Robolectric's Bitmap.createScaledBitmap does not perform real pixel
    // interpolation, so gradient/similarity tests that rely on pixel values after
    // scaling are not reliable under Robolectric. Pixel-accurate dHash tests should
    // be run as Android instrumented tests on a real device or emulator.
}
