package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Blur detection combining ML Kit Object Detection with Laplacian variance.
 *
 * Full-image Laplacian variance fails for real-world Nextcloud thumbnails because
 * the score is content-dependent: motion-blurred textured scenes (trains, foliage)
 * still produce high variance, while sharp but soft-lit subjects (portraits, food)
 * produce low variance. The two distributions overlap completely.
 *
 * This class solves the problem by restricting analysis to semantic subject regions:
 *
 * 1. Scale bitmap to [BlurDetector.MAX_ANALYSIS_SIZE] (single JNI call).
 * 2. Convert to grayscale in one pass.
 * 3. Run ML Kit Object Detection to find subject bounding boxes.
 * 4. Compute Laplacian variance inside each bounding box; keep the maximum.
 * 5. If no objects are detected, fall back to full-image variance.
 *
 * Expected behaviour:
 * - Motion-blurred train: subject bounding box (if any) is also blurry → low variance → flagged
 * - Bokeh portrait: subject face is sharp → high variance in face region → not flagged
 * - Sharp textured landscape: detected subject region is sharp → high variance → not flagged
 *
 * **Must be closed** via [close] when no longer needed to release the ML Kit detector.
 */
class BlurAnalyzer {
    private val detector =
        ObjectDetection.getClient(
            ObjectDetectorOptions
                .Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build(),
        )

    /**
     * Returns a sharpness score for [bitmap]. Higher = sharper.
     * The caller owns [bitmap] and must recycle it; this function does not recycle it.
     */
    suspend fun computeBlurScore(bitmap: Bitmap): Double {
        val working = BlurDetector.scaledBitmap(bitmap)
        try {
            val width = working.width
            val height = working.height
            val argb = IntArray(width * height)
            working.getPixels(argb, 0, width, 0, 0, width, height)
            val gray = IntArray(argb.size) { BlurDetector.luminance(argb[it]) }

            val detected = detector.process(InputImage.fromBitmap(working, 0)).await()
            return if (detected.isEmpty()) {
                varianceInRegion(gray, width, 0, 0, width, height)
            } else {
                detected.maxOf { obj -> boxVariance(gray, width, height, obj.boundingBox) }
            }
        } finally {
            if (working !== bitmap) working.recycle()
        }
    }

    /** Returns `true` when [bitmap] is considered blurry (score below [threshold]). */
    suspend fun isBlurry(
        bitmap: Bitmap,
        threshold: Double = BlurDetector.DEFAULT_THRESHOLD,
    ): Boolean = computeBlurScore(bitmap) < threshold

    /** Releases the underlying ML Kit detector. */
    fun close() = detector.close()

    private fun boxVariance(
        gray: IntArray,
        width: Int,
        height: Int,
        box: Rect,
    ): Double {
        val left = box.left.coerceIn(0, width - 1)
        val top = box.top.coerceIn(0, height - 1)
        val right = box.right.coerceIn(left + 1, width)
        val bottom = box.bottom.coerceIn(top + 1, height)
        if (right - left < 3 || bottom - top < 3) return 0.0
        return varianceInRegion(gray, width, left, top, right, bottom)
    }

    private fun varianceInRegion(
        gray: IntArray,
        fullWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        val innerLeft = left + 1
        val innerTop = top + 1
        val innerRight = right - 1
        val innerBottom = bottom - 1
        val count = (innerRight - innerLeft) * (innerBottom - innerTop)
        if (count <= 0) return 0.0
        var sum = 0L
        var sumSq = 0L
        for (y in innerTop until innerBottom) {
            for (x in innerLeft until innerRight) {
                val lap = BlurDetector.convolve(gray, fullWidth, x, y)
                sum += lap
                sumSq += lap.toLong() * lap
            }
        }
        val mean = sum.toDouble() / count
        return sumSq.toDouble() / count - mean * mean
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
