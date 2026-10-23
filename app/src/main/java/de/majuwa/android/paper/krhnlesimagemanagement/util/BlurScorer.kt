package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.graphics.Bitmap

/**
 * Computes a sharpness score for a bitmap (higher = sharper; below
 * [BlurDetector.DEFAULT_THRESHOLD] counts as blurry). Implemented by [BlurAnalyzer];
 * an abstraction so blur scans can be tested without ML Kit.
 */
interface BlurScorer {
    /** Returns the sharpness score of [bitmap]; the caller owns and recycles [bitmap]. */
    suspend fun computeBlurScore(bitmap: Bitmap): Double

    /** Releases the scorer's resources. */
    fun close()
}
