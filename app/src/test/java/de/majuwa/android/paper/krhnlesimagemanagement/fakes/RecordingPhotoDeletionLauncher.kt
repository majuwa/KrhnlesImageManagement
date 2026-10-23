package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoDeletionLauncher

/**
 * [PhotoDeletionLauncher] that only records requests: [onStart] runs immediately (unless
 * [accept] is `false`), [onFinish] is kept in [pendingFinish] so the test decides when the
 * deletion "ends".
 */
class RecordingPhotoDeletionLauncher(
    var accept: Boolean = true,
) : PhotoDeletionLauncher {
    val requests = mutableListOf<List<RemotePhoto>>()
    var pendingFinish: (() -> Unit)? = null

    override fun launchPhotoDeletion(
        toDelete: List<RemotePhoto>,
        onStart: () -> Unit,
        onFinish: () -> Unit,
    ): Boolean {
        if (!accept) return false
        requests += toDelete
        onStart()
        pendingFinish = onFinish
        return true
    }
}
