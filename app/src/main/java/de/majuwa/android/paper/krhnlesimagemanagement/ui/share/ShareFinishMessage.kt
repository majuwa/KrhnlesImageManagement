package de.majuwa.android.paper.krhnlesimagemanagement.ui.share

import android.content.res.Resources
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.upload.MAX_SHARE_MIB
import de.majuwa.android.paper.krhnlesimagemanagement.upload.MAX_STAGED_FILE_MIB

/**
 * The toast shown when the share activity finishes with [finish], or null for none: the error, or
 * the notices for photos skipped while copying (unreadable / too large, and over the share cap).
 */
fun shareFinishMessage(
    resources: Resources,
    finish: ShareFinish,
): String? =
    when (finish.error) {
        ShareError.NoPhotos -> {
            resources.getString(R.string.share_no_photos)
        }

        ShareError.NothingCopied -> {
            resources.getString(R.string.share_nothing_copied)
        }

        ShareError.CopyFailed -> {
            resources.getString(R.string.share_copy_failed)
        }

        null -> {
            buildList {
                if (finish.skipped > 0) {
                    add(
                        resources.getQuantityString(
                            R.plurals.share_copy_skipped,
                            finish.skipped,
                            finish.skipped,
                            MAX_STAGED_FILE_MIB,
                        ),
                    )
                }
                if (finish.overShareLimit > 0) {
                    add(
                        resources.getQuantityString(
                            R.plurals.share_over_share_limit,
                            finish.overShareLimit,
                            finish.overShareLimit,
                            MAX_SHARE_MIB,
                        ),
                    )
                }
            }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }
