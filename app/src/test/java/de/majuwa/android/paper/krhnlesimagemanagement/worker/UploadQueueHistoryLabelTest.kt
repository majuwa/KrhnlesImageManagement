package de.majuwa.android.paper.krhnlesimagemanagement.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadQueueHistoryLabelTest {
    private fun queue(
        folderName: String?,
        label: String?,
    ) = UploadQueue(folderName = folderName, label = label, entries = emptyList())

    @Test
    fun label_isCappedToMaximumLength() {
        val long = "x".repeat(MAX_HISTORY_LABEL_LENGTH + 50)

        assertEquals("x".repeat(MAX_HISTORY_LABEL_LENGTH), queue(null, long).historyLabel)
    }

    @Test
    fun folderName_longerThanLabelCapButWithinFolderCap_staysFull() {
        val long = "f".repeat(MAX_HISTORY_LABEL_LENGTH + 50)

        assertEquals(long, queue(long, null).historyLabel)
    }

    @Test
    fun folderName_isCappedToFolderMaximumLength() {
        val huge = "f".repeat(MAX_FOLDER_HISTORY_LABEL_LENGTH + 50)

        assertEquals("f".repeat(MAX_FOLDER_HISTORY_LABEL_LENGTH), queue(huge, null).historyLabel)
    }

    @Test
    fun withoutLabelOrFolder_isEmpty() {
        assertEquals("", queue(null, null).historyLabel)
    }
}
