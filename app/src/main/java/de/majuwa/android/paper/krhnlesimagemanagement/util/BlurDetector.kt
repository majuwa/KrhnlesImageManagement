package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale

/**
 * Lightweight blur detection using Laplacian variance.
 *
 * The bitmap is analyzed at its natural size (capped at [MAX_ANALYSIS_SIZE] to bound memory).
 * Pixels are extracted in a single bulk call via [Bitmap.getPixels], converted to grayscale,
 * then convolved with a 3×3 Laplacian kernel. The variance of the convolution output measures
 * edge/detail content — blurry images produce low variance because motion or defocus spreads
 * edges over many pixels, reducing second-derivative magnitude.
 *
 * **Why not downscale to 200×200?**
 * Earlier testing showed that both a Nextcloud 512 px JPEG thumbnail and a 200×200
 * nearest-neighbour resize of the same thumbnail produce similar (and low) variance values
 * for both blurry *and* sharp textured images. Preserving the thumbnail at its natural
 * resolution keeps enough edge information for the metric to discriminate reliably.
 *
 * Typical Laplacian variance values (512 px JPEG thumbnail pipeline):
 * - Motion-blurred photo : ~600 – 2 000
 * - Sharp outdoor photo  : ~10 000 – 30 000+
 * Default threshold of [DEFAULT_THRESHOLD] = 3 000 sits safely between these bands.
 */
object BlurDetector {
    /** Images wider or taller than this are scaled down before analysis (memory guard). */
    internal const val MAX_ANALYSIS_SIZE = 512
    const val DEFAULT_THRESHOLD = 3000.0

    /**
     * Laplacian kernel (3×3). Approximates the second spatial derivative.
     *
     * ```
     *  0  1  0
     *  1 -4  1
     *  0  1  0
     * ```
     * The kernel sums to zero, so a perfectly uniform patch has Laplacian = 0.
     */
    internal val LAPLACIAN_KERNEL =
        intArrayOf(
            0,
            1,
            0,
            1,
            -4,
            1,
            0,
            1,
            0,
        )

    /**
     * Computes the Laplacian variance of [bitmap].
     *
     * A higher value indicates a sharper image; a lower value indicates blur.
     * The caller is responsible for recycling the original bitmap.
     */
    fun computeLaplacianVariance(bitmap: Bitmap): Double {
        val working = scaledBitmap(bitmap)
        val width = working.width
        val height = working.height

        // Bulk-extract all ARGB pixels in a single JNI call (much faster than getPixel() per pixel)
        val argb = IntArray(width * height)
        working.getPixels(argb, 0, width, 0, 0, width, height)
        if (working !== bitmap) working.recycle()

        // Convert to grayscale luminance
        val gray = IntArray(argb.size) { luminance(argb[it]) }

        // Apply 3×3 Laplacian convolution — border pixels are skipped
        val count = (width - 2) * (height - 2)
        if (count <= 0) return 0.0

        var sum = 0L
        var sumSq = 0L
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val lap = convolve(gray, width, x, y)
                sum += lap
                sumSq += lap.toLong() * lap
            }
        }

        val mean = sum.toDouble() / count
        return sumSq.toDouble() / count - mean * mean
    }

    /** Returns `true` when [bitmap] is considered blurry (variance below [threshold]). */
    fun isBlurry(
        bitmap: Bitmap,
        threshold: Double = DEFAULT_THRESHOLD,
    ): Boolean = computeLaplacianVariance(bitmap) < threshold

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns [bitmap] unchanged if it already fits within [MAX_ANALYSIS_SIZE],
     * otherwise returns a scaled-down copy (nearest-neighbour, caller must recycle).
     * Nearest-neighbour is used to avoid bilinear smoothing which would reduce variance.
     */
    internal fun scaledBitmap(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_ANALYSIS_SIZE) return bitmap
        val scale = MAX_ANALYSIS_SIZE.toFloat() / maxDim
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(w, h, filter = false)
    }

    /** Applies the 3×3 Laplacian kernel centred at ([cx], [cy]). */
    internal fun convolve(
        gray: IntArray,
        width: Int,
        cx: Int,
        cy: Int,
    ): Int {
        var value = 0
        for (ky in -1..1) {
            for (kx in -1..1) {
                val kernelVal = LAPLACIAN_KERNEL[(ky + 1) * 3 + (kx + 1)]
                value += kernelVal * gray[(cy + ky) * width + (cx + kx)]
            }
        }
        return value
    }

    internal fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }
}
