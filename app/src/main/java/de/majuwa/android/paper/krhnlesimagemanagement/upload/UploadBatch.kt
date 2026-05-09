package de.majuwa.android.paper.krhnlesimagemanagement.upload

import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFolderFormatter = DateTimeFormatter.ofPattern("MM-MMM", Locale.ENGLISH)

data class UploadBatch(
    val folderName: String,
    val photos: List<Photo>,
)

fun resolveAutoDateUploadBatches(photos: List<Photo>): List<UploadBatch> =
    photos
        .groupBy { YearMonth.from(it.dateTaken) }
        .toSortedMap()
        .map { (month, monthPhotos) ->
            UploadBatch(
                folderName = "${month.year}/${month.format(monthFolderFormatter)}",
                photos = monthPhotos.sortedWith(compareBy<Photo>({ it.dateTaken }, { it.id })),
            )
        }

fun previewUploadPath(
    baseFolder: String,
    folderName: String,
): String {
    val normalizedBaseFolder = baseFolder.trim('/')
    val normalizedFolderName = folderName.trim('/')
    return if (normalizedBaseFolder.isBlank()) {
        "$normalizedFolderName/"
    } else {
        "$normalizedBaseFolder/$normalizedFolderName/"
    }
}
