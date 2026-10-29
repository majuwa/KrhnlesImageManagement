package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Requested together: full image and video access, Android 14+ partial (user-selected) access and
 * the media location (without it the MediaProvider redacts the location of uploaded photos). Access
 * is any of the first three; the location is never an access permission.
 */
internal val MEDIA_PERMISSIONS =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

/** Which media permissions are granted right now. */
internal data class MediaAccess(
    val images: Boolean,
    val video: Boolean,
    val userSelected: Boolean,
    /** `ACCESS_MEDIA_LOCATION`: originals with their location can be read (not an access permission). */
    val location: Boolean = false,
) {
    /** Any media access: the grid can be shown. */
    val hasAccess: Boolean get() = images || video || userSelected

    /** Full image access without video access: only users upgrading from an images-only version. */
    val videoMissing: Boolean get() = images && !video

    /** Full media access without the media location: users upgrading from a version that never asked. */
    val locationMissing: Boolean get() = (images || video) && !location

    /** Only the media the user selected (Android 14+ partial access). */
    val isPartial: Boolean get() = userSelected && !images && !video

    companion object {
        fun of(context: Context): MediaAccess =
            MediaAccess(
                images = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES),
                video = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO),
                userSelected = context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                location = context.isGranted(Manifest.permission.ACCESS_MEDIA_LOCATION),
            )

        private fun Context.isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/** The non-blocking banner above the grid, if any. */
internal enum class MediaAccessBanner {
    /** Partial access: offer to select more photos and videos (re-launches the request). */
    Partial,

    /** Images-only access: videos are not shown; offer to grant (or open the app settings). */
    VideoMissing,
}

internal fun MediaAccess.banner(): MediaAccessBanner? =
    when {
        !hasAccess -> null
        isPartial -> MediaAccessBanner.Partial
        videoMissing -> MediaAccessBanner.VideoMissing
        else -> null
    }

/** Which flags an automatic upgrade request marks as asked (the request itself always asks for all). */
internal data class UpgradeRequest(
    val video: Boolean,
    val location: Boolean,
)

/**
 * The automatic one-time upgrade request, or null. Never before both stored flags are read (null);
 * images-only users are asked for video once, full-access users without the media location for it
 * once (persisted flag plus a per-session guard each); partial-access users never automatically.
 * One request covers both: a video upgrade also marks the location as asked when it is missing.
 */
internal fun upgradeRequest(
    access: MediaAccess,
    videoRequested: Boolean?,
    locationRequested: Boolean?,
    videoRequestedThisSession: Boolean,
    locationRequestedThisSession: Boolean,
): UpgradeRequest? {
    if (videoRequested == null || locationRequested == null) return null
    val askLocation = access.locationMissing && !locationRequested && !locationRequestedThisSession
    val askVideo = access.videoMissing && !videoRequested && !videoRequestedThisSession
    return when {
        askVideo -> UpgradeRequest(video = true, location = access.locationMissing && !locationRequested)
        askLocation -> UpgradeRequest(video = false, location = true)
        else -> null
    }
}
