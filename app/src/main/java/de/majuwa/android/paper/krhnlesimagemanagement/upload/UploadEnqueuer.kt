package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialRepository
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.queueFilesDir
import de.majuwa.android.paper.krhnlesimagemanagement.worker.readWifiOnly
import de.majuwa.android.paper.krhnlesimagemanagement.worker.uploadConstraints
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Process-scoped upload enqueuing. [enqueue] returns immediately and does all of its work in
 * [scope] (the application scope), so an upload is never lost when the calling activity is
 * destroyed (rotation, dismissed share dialog) while the Wi-Fi-only setting is still being read.
 *
 * Per batch it writes the queue file (WorkManager `Data` is limited to 10 KB) and enqueues one
 * [UploadWorker]; the enqueue is awaited so the request is persisted, then a toast is shown.
 */
class UploadEnqueuer(
    private val appContext: Context,
    private val credentialRepository: CredentialRepository,
    private val scope: CoroutineScope,
    private val workManager: () -> WorkManager = { WorkManager.getInstance(appContext) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    /** Starts enqueueing [batches] in the application scope; empty batches are ignored. */
    fun enqueue(batches: List<UploadBatch>): Job? {
        val nonEmpty = batches.filter { it.photos.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        return scope.launch {
            val started =
                try {
                    enqueueBatches(nonEmpty)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Upload could not be enqueued: ${e.javaClass.simpleName}")
                    false
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Upload could not be enqueued: ${e.javaClass.simpleName}")
                    false
                }
            if (started) showStartedToast(nonEmpty)
        }
    }

    private suspend fun enqueueBatches(batches: List<UploadBatch>) {
        // Bounded read; an unreadable setting falls back to unmetered (never mobile data by accident).
        val constraints = uploadConstraints(readWifiOnly(credentialRepository.wifiOnly))
        val manager = workManager()
        batches.forEach { batch ->
            val queueFile = writeQueueFile(batch)
            val request =
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFile.absolutePath))
                    .setConstraints(constraints)
                    .addTag(UPLOAD_TAG)
                    .build()
            manager.enqueue(request).await()
        }
    }

    /**
     * Writes the queue file for [batch]. An [UploadBatch.Occasion] writes the single top-level
     * `folderName` format; an [UploadBatch.PerPhoto] writes `label`, a `folderName` on every entry
     * and no top-level `folderName` (see `UploadWorker` for the reader).
     */
    internal suspend fun writeQueueFile(batch: UploadBatch): File =
        withContext(ioDispatcher) {
            val perPhotoFolders = (batch as? UploadBatch.PerPhoto)?.folders
            val queue =
                JSONObject().apply {
                    when (batch) {
                        is UploadBatch.Occasion -> put("folderName", batch.folderName.trim())
                        is UploadBatch.PerPhoto -> put("label", batch.label)
                    }
                    put(
                        "photos",
                        JSONArray().also { arr ->
                            batch.photos.forEachIndexed { index, photo ->
                                arr.put(
                                    JSONObject().apply {
                                        put("id", photo.id)
                                        put("uri", photo.uri.toString())
                                        put("mimeType", photo.mimeType)
                                        put("displayName", photo.displayName)
                                        perPhotoFolders?.let { put("folderName", it[index]) }
                                    },
                                )
                            }
                        },
                    )
                }
            File(queueFilesDir(appContext), "upload_queue_${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
                .also { it.writeText(queue.toString()) }
        }

    private suspend fun showStartedToast(batches: List<UploadBatch>) {
        val photoCount = batches.sumOf { it.photos.size }
        withContext(mainDispatcher) {
            Toast
                .makeText(
                    appContext,
                    appContext.resources.getQuantityString(
                        R.plurals.toast_upload_started,
                        batches.size,
                        batches.size,
                        photoCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private companion object {
        const val TAG = "UploadEnqueuer"
        const val UPLOAD_TAG = "photo_upload"
    }
}
