package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.net.Uri
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UploadBatchTest {
    private fun photo(
        id: Long,
        dateTaken: LocalDate,
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "$id.jpg",
        dateTaken = dateTaken,
        size = 1_024,
        mimeType = "image/jpeg",
    )

    @Test
    fun `resolveAutoDateUploadBatches groups photos by month in ascending order`() {
        val batches =
            resolveAutoDateUploadBatches(
                listOf(
                    photo(3, LocalDate.of(2026, 5, 10)),
                    photo(1, LocalDate.of(2026, 4, 3)),
                    photo(2, LocalDate.of(2026, 4, 20)),
                ),
            )

        assertEquals(listOf("2026/04-April", "2026/05-May"), batches.map { it.folderName })
        assertEquals(listOf(1L, 2L), batches.first().photos.map { it.id })
        assertEquals(listOf(3L), batches.last().photos.map { it.id })
    }

    @Test
    fun `previewUploadPath prefixes base folder when present`() {
        assertEquals("Photos/2026/05-May/", previewUploadPath("Photos", "2026/05-May"))
        assertEquals("2026/05-May/", previewUploadPath("", "2026/05-May"))
    }
}
