package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurDetectorTest {
    // ── Laplacian kernel tests ───────────────────────────────────────────────

    @Test
    fun `laplacian kernel has correct size and sums to zero`() {
        assertEquals(9, BlurDetector.LAPLACIAN_KERNEL.size)
        assertEquals(0, BlurDetector.LAPLACIAN_KERNEL.sum())
    }

    @Test
    fun `laplacian kernel centre value is negative four`() {
        assertEquals(-4, BlurDetector.LAPLACIAN_KERNEL[4])
    }

    // ── Convolution tests ───────────────────────────────────────────────────

    @Test
    fun `convolve on uniform gray returns zero`() {
        val width = 5
        val gray = IntArray(width * 5) { 128 }
        assertEquals(0, BlurDetector.convolve(gray, width, 2, 2))
    }

    @Test
    fun `convolve detects single bright pixel`() {
        val width = 3
        val gray =
            intArrayOf(
                0,
                0,
                0,
                0,
                100,
                0,
                0,
                0,
                0,
            )
        // 0 + 0 + 0 + 0 + (-4)*100 + 0 + 0 + 0 + 0 = -400
        assertEquals(-400, BlurDetector.convolve(gray, width, 1, 1))
    }

    @Test
    fun `convolve with checkerboard pattern produces nonzero`() {
        val width = 3
        val gray =
            intArrayOf(
                0,
                255,
                0,
                255,
                0,
                255,
                0,
                255,
                0,
            )
        // 0 + 255 + 0 + 255 + 0 + 255 + 0 + 255 + 0 = 1020
        assertEquals(1020, BlurDetector.convolve(gray, width, 1, 1))
    }

    @Test
    fun `convolve at edge of larger uniform image returns zero`() {
        val width = 7
        val gray = IntArray(width * 7) { 50 }
        assertEquals(0, BlurDetector.convolve(gray, width, 1, 1))
        assertEquals(0, BlurDetector.convolve(gray, width, 5, 5))
    }

    // ── Variance logic tests ────────────────────────────────────────────────

    @Test
    fun `uniform gray array produces zero variance`() {
        val width = 10
        val gray = IntArray(width * width) { 128 }
        var sum = 0L
        var sumSq = 0L
        val count = (width - 2) * (width - 2)
        for (y in 1 until width - 1) {
            for (x in 1 until width - 1) {
                val lap = BlurDetector.convolve(gray, width, x, y)
                sum += lap
                sumSq += lap.toLong() * lap
            }
        }
        val mean = sum.toDouble() / count
        val variance = sumSq.toDouble() / count - mean * mean
        assertEquals(0.0, variance, 0.001)
    }

    @Test
    fun `checkerboard array produces higher variance than uniform`() {
        val width = 10
        val checkerGray =
            IntArray(width * width) { idx ->
                val x = idx % width
                val y = idx / width
                if ((x + y) % 2 == 0) 255 else 0
            }
        val uniformGray = IntArray(width * width) { 128 }

        fun lapVariance(gray: IntArray): Double {
            var sum = 0L
            var sumSq = 0L
            val count = (width - 2) * (width - 2)
            for (y in 1 until width - 1) {
                for (x in 1 until width - 1) {
                    val lap = BlurDetector.convolve(gray, width, x, y)
                    sum += lap
                    sumSq += lap.toLong() * lap
                }
            }
            val mean = sum.toDouble() / count
            return sumSq.toDouble() / count - mean * mean
        }

        assertTrue(lapVariance(checkerGray) > lapVariance(uniformGray))
    }

    // ── Configuration tests ─────────────────────────────────────────────────

    @Test
    fun `default threshold separates sharp from blurry bands`() {
        // Calibrated against real 512 px JPEG thumbnails:
        // motion-blurred photos score ~600–2000; sharp outdoor photos score ~10000–30000+
        assertTrue("threshold must be above typical blurry band (2000)", BlurDetector.DEFAULT_THRESHOLD > 2000)
        assertTrue("threshold must be below typical sharp band (10000)", BlurDetector.DEFAULT_THRESHOLD < 10000)
    }

    @Test
    fun `max analysis size caps at reasonable value for mobile`() {
        assertTrue(BlurDetector.MAX_ANALYSIS_SIZE in 256..1024)
    }
}
