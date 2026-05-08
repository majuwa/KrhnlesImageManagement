package de.majuwa.android.paper.krhnlesimagemanagement.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class RetryUploadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val queueFilePath = intent.getStringExtra(EXTRA_QUEUE_FILE) ?: return
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
    }
}
