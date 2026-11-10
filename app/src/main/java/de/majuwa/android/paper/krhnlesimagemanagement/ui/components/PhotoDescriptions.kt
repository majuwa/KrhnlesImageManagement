package de.majuwa.android.paper.krhnlesimagemanagement.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto

/**
 * Screen-reader description of a photo by its 1-based [position] among [total] photos.
 * Never use a file name (`displayName`) as a description; it is meaningless when spoken.
 */
@Composable
fun photoPositionDescription(
    position: Int,
    total: Int,
): String = stringResource(R.string.cd_photo_position, position, total)

/**
 * Screen-reader description of a remote album item: "Video N of M" for a video, else
 * [photoPositionDescription]. Remote items have no duration or date, so it is position only.
 */
@Composable
fun mediaPositionDescription(
    photo: RemotePhoto,
    position: Int,
    total: Int,
): String =
    if (photo.isVideo) {
        stringResource(R.string.cd_video_position, position, total)
    } else {
        stringResource(R.string.cd_photo_position, position, total)
    }
