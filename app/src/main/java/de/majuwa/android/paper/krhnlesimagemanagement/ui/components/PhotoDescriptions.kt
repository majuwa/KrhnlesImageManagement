package de.majuwa.android.paper.krhnlesimagemanagement.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.majuwa.android.paper.krhnlesimagemanagement.R

/**
 * Screen-reader description of a photo by its 1-based [position] among [total] photos.
 * Never use a file name (`displayName`) as a description; it is meaningless when spoken.
 */
@Composable
fun photoPositionDescription(
    position: Int,
    total: Int,
): String = stringResource(R.string.cd_photo_position, position, total)
