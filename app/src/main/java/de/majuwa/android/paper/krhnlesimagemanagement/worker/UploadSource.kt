package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileNotFoundException
import java.io.InputStream

private const val TAG = "UploadSource"

/**
 * An opened upload stream. Without `ACCESS_MEDIA_LOCATION` (or when the original cannot be opened)
 * the MediaProvider redacts the location of another app's photo (EXIF GPS, ISO-BMFF location boxes:
 * zeroed bytes, same size), so such a copy verifies but is not the original. Only an [Original]
 * may ever be verified and recorded.
 */
internal sealed interface UploadSource {
    val stream: InputStream

    /** Opened through `MediaStore.setRequireOriginal([mediaUri])`: not redacted. */
    class Original(
        override val stream: InputStream,
        val mediaUri: Uri,
    ) : UploadSource

    /** Opened as is (a staged copy, or a photo whose location may be redacted): never verified. */
    class Plain(
        override val stream: InputStream,
    ) : UploadSource
}

/** Whether the MediaProvider hands out originals (location not redacted). */
internal fun Context.hasMediaLocationAccess(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens the photo [uri] for upload: as the [UploadSource.Original] when [wantOriginal] (the caller
 * checked [hasMediaLocationAccess]), else, or if the original cannot be opened, as
 * [UploadSource.Plain] so the upload still happens (unverified). Null if it cannot be opened at all.
 */
internal fun Context.openForUpload(
    uri: Uri,
    wantOriginal: Boolean,
): UploadSource? {
    if (wantOriginal) openOriginal(uri)?.let { return it }
    return openStream(uri)?.let { UploadSource.Plain(it) }
}

/** The one place an [UploadSource.Original] is created: only from `setRequireOriginal`. */
private fun Context.openOriginal(uri: Uri): UploadSource.Original? {
    val original = MediaStore.setRequireOriginal(uri)
    return try {
        contentResolver.openInputStream(original)?.let { UploadSource.Original(it, uri) }
    } catch (e: UnsupportedOperationException) {
        logFallback(e)
    } catch (e: SecurityException) {
        logFallback(e)
    } catch (e: FileNotFoundException) {
        logFallback(e)
    } catch (e: IllegalArgumentException) {
        logFallback(e)
    }
}

private fun logFallback(e: Exception): UploadSource.Original? {
    Log.w(TAG, "Original not available, uploading unverified: ${e.javaClass.simpleName}")
    return null
}

private fun Context.openStream(uri: Uri): InputStream? =
    try {
        contentResolver.openInputStream(uri)
    } catch (e: SecurityException) {
        logUnopenable(e)
    } catch (e: FileNotFoundException) {
        logUnopenable(e)
    } catch (e: IllegalArgumentException) {
        logUnopenable(e)
    }

private fun logUnopenable(e: Exception): InputStream? {
    Log.w(TAG, "Photo could not be opened: ${e.javaClass.simpleName}")
    return null
}
