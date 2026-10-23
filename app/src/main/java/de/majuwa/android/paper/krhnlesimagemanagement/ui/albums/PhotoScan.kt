package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import android.graphics.Bitmap
import android.util.Log
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositoryContract
import de.majuwa.android.paper.krhnlesimagemanagement.data.AlbumsRepositorySource
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/** Computes one value per photo thumbnail during a [scanAlbumPhotos] run. */
internal interface PhotoAnalyzer<T> {
    /** Analyzes [bitmap] (implementations run the work off the main thread); the scan owns and recycles [bitmap]. */
    suspend fun analyze(bitmap: Bitmap): T

    /** Releases the analyzer's resources; called once when the scan ends. */
    fun close() {}
}

/** Result of [scanAlbumPhotos]. */
internal sealed interface PhotoScanOutcome<out T> {
    /** Results keyed by [RemotePhoto.href]; photos that could not be analyzed are missing. */
    data class Completed<T>(
        val results: Map<String, T>,
    ) : PhotoScanOutcome<T>

    data class Failed(
        val error: AlbumsError,
    ) : PhotoScanOutcome<Nothing>
}

/**
 * Shared scan loop of the duplicate and blur review. For each photo: verify the repository is
 * still the current one, resolve the thumbnail URL ([thumbnailUrls] or the repository's),
 * download it, [PhotoAnalyzer.analyze] it, always
 * recycle the bitmap, report [onProgress]. A photo that fails is skipped (logged without its
 * href); cancellation stops the scan before the next photo and is rethrown, never reported as a
 * failure.
 *
 * The repository comes from [repositories] and the analyzer from [analyzerFactory] (created after
 * the repository, inside the error handling, and closed only if it was created). A failure to
 * prepare either one is returned as [PhotoScanOutcome.Failed] with a typed [AlbumsError].
 *
 * Before every photo [repositories] is asked again: if it no longer yields the repository the
 * scan started with (logout, server or account change) the scan stops at once with
 * [AlbumsError.ConfigurationChanged] / the typed failure, so no further request is sent with
 * the old server or credentials.
 */
internal suspend fun <T> scanAlbumPhotos(
    photos: List<RemotePhoto>,
    thumbnailUrls: Map<String, String>,
    repositories: AlbumsRepositorySource,
    analyzerFactory: () -> PhotoAnalyzer<T>,
    onProgress: (processed: Int) -> Unit,
): PhotoScanOutcome<T> =
    try {
        val repository = repositories.get().getOrThrow()
        val analyzer = analyzerFactory()
        try {
            scanLoop(repository, photos, thumbnailUrls, repositories, analyzer, onProgress)
        } finally {
            analyzer.close()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Photo scan failed: ${e.javaClass.simpleName}")
        PhotoScanOutcome.Failed(e.toAlbumsError())
    }

private suspend fun <T> scanLoop(
    repository: AlbumsRepositoryContract,
    photos: List<RemotePhoto>,
    thumbnailUrls: Map<String, String>,
    repositories: AlbumsRepositorySource,
    analyzer: PhotoAnalyzer<T>,
    onProgress: (processed: Int) -> Unit,
): PhotoScanOutcome<T> {
    val results = mutableMapOf<String, T>()
    for ((index, photo) in photos.withIndex()) {
        // Logout / server change: stop before sending anything else to the old server.
        if (repositories.get().getOrThrow() !== repository) {
            return PhotoScanOutcome.Failed(AlbumsError.ConfigurationChanged)
        }
        analyzeOne(repository, photo, thumbnailUrls, analyzer)?.let { results[photo.href] = it }
        currentCoroutineContext().ensureActive()
        onProgress(index + 1)
    }
    return PhotoScanOutcome.Completed(results)
}

/** Downloads and analyzes one photo; `null` (logged without its href) if that fails, cancellation is rethrown. */
private suspend fun <T> analyzeOne(
    repository: AlbumsRepositoryContract,
    photo: RemotePhoto,
    thumbnailUrls: Map<String, String>,
    analyzer: PhotoAnalyzer<T>,
): T? =
    try {
        val url = thumbnailUrls[photo.href] ?: repository.thumbnailUrl(photo)
        val bitmap = repository.downloadBitmapForHash(url)
        try {
            analyzer.analyze(bitmap)
        } finally {
            bitmap.recycle()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Type only: hrefs and exception messages may contain server details.
        Log.w(TAG, "Skipping a photo that could not be analyzed: ${e.javaClass.simpleName}")
        null
    }

private const val TAG = "PhotoScan"
