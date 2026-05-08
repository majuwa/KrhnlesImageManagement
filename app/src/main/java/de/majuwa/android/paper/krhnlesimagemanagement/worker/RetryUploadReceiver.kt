package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

class RetryUploadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val queueFilePath = intent.getStringExtra(EXTRA_QUEUE_FILE)
        if (queueFilePath == null) {
            Log.w(TAG, "Retry action received without a queue file path — ignoring.")
            return
        }

        val filesDir = context.filesDir.canonicalPath
        val queueFile = File(queueFilePath)
        if (!queueFile.canonicalPath.startsWith("$filesDir/")) {
            Log.w(TAG, "Retry queue file path is outside expected directory — ignoring.")
            return
        }

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        val inputData = workDataOf(UploadWorker.KEY_QUEUE_FILE to queueFilePath)
        val uploadRequest =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .addTag("photo_upload")
                .build()
        WorkManager.getInstance(context).enqueue(uploadRequest)
    }

    companion object {
        const val EXTRA_QUEUE_FILE = "retry_queue_file"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TAG = "RetryUploadReceiver"
    }
}
