package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.content.res.Resources
import de.majuwa.android.paper.krhnlesimagemanagement.R
import java.util.Locale

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Whole seconds of a known, positive duration (at least 1); null when unknown or zero (no badge). */
internal fun displayedDurationSeconds(durationMs: Long?): Long? =
    durationMs?.takeIf { it > 0L }?.let { maxOf(1L, it / MILLIS_PER_SECOND) }

/** Badge text `m:ss` or, from one hour, `h:mm:ss`; null when the duration is unknown or zero. */
internal fun formatDurationBadge(durationMs: Long?): String? {
    val total = displayedDurationSeconds(durationMs) ?: return null
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** Spoken duration, e.g. "1 minute 5 seconds" (zero parts are left out); null when unknown or zero. */
internal fun durationDescription(
    resources: Resources,
    durationMs: Long?,
): String? {
    val total = displayedDurationSeconds(durationMs) ?: return null
    val hours = (total / SECONDS_PER_HOUR).toInt()
    val minutes = ((total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
    val seconds = (total % SECONDS_PER_MINUTE).toInt()
    return buildList {
        if (hours > 0) add(resources.getQuantityString(R.plurals.duration_hours, hours, hours))
        if (minutes > 0) add(resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes))
        if (seconds > 0) add(resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds))
    }.joinToString(" ")
}
