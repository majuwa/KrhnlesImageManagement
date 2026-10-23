package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.res.Resources
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.albumsErrorMessage
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.toAlbumsError

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/** Why the remote upload folder could not be created; mapped to upload-specific strings. */
internal sealed interface FolderCreationError {
    /** The occasion / folder name is not a usable path (`..`, empty segment, backslash). */
    data object InvalidFolderName : FolderCreationError

    /** The server answered 403 / 404 / 409: parent folder missing or no permission to create. */
    data object FolderNotAvailable : FolderCreationError

    data object NotConfigured : FolderCreationError

    /** A connection-level failure (certificate, host, reachability, timeout, credentials). */
    data class Connection(
        val error: AlbumsError,
    ) : FolderCreationError

    data object Generic : FolderCreationError
}

internal fun Throwable.toFolderCreationError(): FolderCreationError =
    when {
        this is WebDavHttpException && code in setOf(HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_CONFLICT) -> {
            FolderCreationError.FolderNotAvailable
        }

        this is WebDavHttpException && code == HTTP_UNAUTHORIZED -> {
            FolderCreationError.Connection(AlbumsError.AuthFailed)
        }

        else -> {
            when (val error = toAlbumsError()) {
                AlbumsError.NotConfigured -> FolderCreationError.NotConfigured

                AlbumsError.CertificateError,
                AlbumsError.UnknownHost,
                AlbumsError.ServerUnreachable,
                AlbumsError.Timeout,
                AlbumsError.AuthFailed,
                AlbumsError.InvalidServerUrl,
                -> FolderCreationError.Connection(error)

                else -> FolderCreationError.Generic
            }
        }
    }

internal fun folderCreationErrorMessage(
    resources: Resources,
    error: FolderCreationError,
): String =
    when (error) {
        FolderCreationError.InvalidFolderName -> resources.getString(R.string.upload_error_invalid_folder_name)
        FolderCreationError.FolderNotAvailable -> resources.getString(R.string.upload_error_folder_unavailable)
        FolderCreationError.NotConfigured -> resources.getString(R.string.upload_error_not_configured)
        is FolderCreationError.Connection -> albumsErrorMessage(resources, error.error)
        FolderCreationError.Generic -> resources.getString(R.string.upload_error_generic)
    }
