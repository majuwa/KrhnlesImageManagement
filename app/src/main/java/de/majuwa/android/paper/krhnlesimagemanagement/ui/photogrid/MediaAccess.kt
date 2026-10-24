package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Requested together: full image and video access plus Android 14+ partial (user-selected) access. */
internal val MEDIA_PERMISSIONS =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

/** Which media permissions are granted right now. */
internal data class MediaAccess(
    val images: Boolean,
    val video: Boolean,
    val userSelected: Boolean,
) {
    /** Any media access: the grid can be shown. */
    val hasAccess: Boolean get() = images || video || userSelected

    /** Full image access without video access: only users upgrading from an images-only version. */
    val videoMissing: Boolean get() = images && !video

    /** Only the media the user selected (Android 14+ partial access). */
    val isPartial: Boolean get() = userSelected && !images && !video

    companion object {
        fun of(context: Context): MediaAccess =
            MediaAccess(
                images = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES),
                video = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO),
                userSelected = context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
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
