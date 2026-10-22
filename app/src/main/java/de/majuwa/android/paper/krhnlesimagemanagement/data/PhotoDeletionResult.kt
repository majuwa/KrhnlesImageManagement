package de.majuwa.android.paper.krhnlesimagemanagement.data

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of [deletePhotosIndividually].
 *
 * @property deletedHrefs hrefs of the photos that were actually deleted on the server
 *   (including photos the server reported as already gone, see [AlbumsRepositoryContract.deletePhoto]).
 * @property failureCount number of photos whose deletion failed.
 */
internal data class PhotoDeletionResult(
    val deletedHrefs: Set<String>,
    val failureCount: Int,
) {
    companion object {
        /** Every photo in [photos] counts as failed and none was deleted. */
        fun allFailed(photos: List<RemotePhoto>): PhotoDeletionResult = PhotoDeletionResult(emptySet(), photos.size)
    }
}

/**
 * Deletes each of [photos] from the server, one request per photo, in order.
 *
 * A failing deletion — a failed [Result] or an exception thrown by [AlbumsRepositoryContract.deletePhoto] —
 * is counted and does not stop the remaining ones. Cancellation is never counted as a failure: it is
 * rethrown and stops the loop before the next photo. Shared by every photo-deletion entry point (album
 * selection, duplicate review, blur review — issues #28 / #47) and independent of any ViewModel, so it
 * can be reused when the albums ViewModel is split.
 */
internal suspend fun AlbumsRepositoryContract.deletePhotosIndividually(photos: List<RemotePhoto>): PhotoDeletionResult {
    val deletedHrefs = mutableSetOf<String>()
    var failureCount = 0
    photos.forEach { photo ->
        currentCoroutineContext().ensureActive()
        runCatching { deletePhoto(photo).getOrThrow() }
            .onSuccess { deletedHrefs += photo.href }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                failureCount++
            }
    }
    return PhotoDeletionResult(deletedHrefs, failureCount)
}
