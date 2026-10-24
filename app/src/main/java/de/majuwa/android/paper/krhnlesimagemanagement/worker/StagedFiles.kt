package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

private const val TAG = "StagedFiles"
private const val SHARE_STAGING_DIR = "share_staging"
private const val KEY_PHOTOS = "photos"
internal const val KEY_STAGED = "staged"
private val stagedNamePattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * App-private staging folder for shared photos (`noBackupFilesDir/share_staging`): excluded from
 * cloud backup and device transfer like the queue files that reference it.
 */
internal fun shareStagingDir(context: Context): File = File(context.noBackupFilesDir, SHARE_STAGING_DIR)

/** True if [name] is a staged file name the app writes: a lower-case UUID. */
internal fun isValidStagedName(name: String): Boolean = stagedNamePattern.matches(name)

/**
 * The staged file [name] directly inside [stagingDir], or null if the name is not a staged name or
 * does not resolve (canonically) below [stagingDir].
 */
internal fun resolveStagedFile(
    stagingDir: File,
    name: String,
): File? {
    if (!isValidStagedName(name)) return null
    return try {
        val file = File(stagingDir, name)
        file.takeIf { it.canonicalFile.parentFile == stagingDir.canonicalFile }
    } catch (e: IOException) {
        Log.w(TAG, "Cannot canonicalize staged file path: ${e.javaClass.simpleName}")
        null
    }
}

/** Opens a staged copy; null (a failed photo, never a fallback to the URI) if it is invalid or missing. */
internal fun openStagedFile(
    context: Context,
    name: String,
): InputStream? {
    val file = resolveStagedFile(shareStagingDir(context), name)
    if (file == null) {
        Log.w(TAG, "Staged photo rejected: invalid name or location")
        return null
    }
    return try {
        FileInputStream(file)
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "Staged photo could not be opened: ${e.javaClass.simpleName}")
        null
    }
}

/** Deletes the staged files [names] in [stagingDir]; invalid names are ignored. */
internal fun deleteStagedFiles(
    stagingDir: File,
    names: Collection<String>,
) {
    names.forEach { name ->
        val file = resolveStagedFile(stagingDir, name)
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Staged file could not be deleted")
        }
    }
}

/**
 * The valid staged names referenced by the queue JSON [json], read leniently (also from a queue
 * that is otherwise malformed).
 *
 * @throws JSONException if [json] is not a JSON object.
 */
internal fun stagedNamesOf(json: String): Set<String> {
    val photos = JSONObject(json).optJSONArray(KEY_PHOTOS) ?: return emptySet()
    return (0 until photos.length())
        .mapNotNull { photos.optJSONObject(it)?.opt(KEY_STAGED) as? String }
        .filter(::isValidStagedName)
        .toSet()
}

/**
 * The one way the worker deletes a queue file: first the staged files it references, except
 * [keep] (the staged files of a newly written retry file), then the queue file itself and a
 * leftover progress temp file ([queueTempFile]). An
 * unreadable queue file is still deleted; its staged files are left to the age sweep.
 */
internal fun deleteQueueFile(
    context: Context,
    queueFile: File,
    keep: Set<String> = emptySet(),
) {
    val staged =
        try {
            stagedNamesOf(queueFile.readText())
        } catch (e: IOException) {
            Log.w(TAG, "Queue file unreadable for cleanup: ${e.javaClass.simpleName}")
            emptySet()
        } catch (e: JSONException) {
            Log.w(TAG, "Queue file malformed for cleanup: ${e.javaClass.simpleName}")
            emptySet()
        }
    deleteStagedFiles(shareStagingDir(context), staged - keep)
    queueFile.delete()
    queueTempFile(queueFile).delete()
}
