package de.majuwa.android.paper.krhnlesimagemanagement.model

import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector

/**
 * How aggressively the blur review flags photos. A photo is blurry when its score is strictly below
 * [threshold], so a higher sensitivity flags more photos. The values are unvalidated heuristics (scores
 * differ between Nextcloud Memories previews and downsampled originals); retune them here only.
 * Persisted by enum name, never by number.
 */
enum class BlurSensitivity(
    val threshold: Double,
) {
    LOW(1500.0),
    MEDIUM(BlurDetector.DEFAULT_THRESHOLD),
    HIGH(6000.0),
    ;

    fun isBlurry(score: Double): Boolean = score < threshold

    companion object {
        val DEFAULT = MEDIUM

        /** The level stored under [name]; [DEFAULT] for null, blank or unknown values. */
        fun fromStored(name: String?): BlurSensitivity = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
