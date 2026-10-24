package de.majuwa.android.paper.krhnlesimagemanagement.upload

import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import java.time.LocalDate
import java.util.Locale

/**
 * One queued background upload. It has exactly one target shape: one [Occasion] folder for all
 * photos (occasion dialog, share target) or a folder [PerPhoto] (auto date folders).
 */
sealed interface UploadBatch {
    val photos: List<Photo>

    /** All [photos] go into [folderName] below the base folder (blank = the base folder itself). */
    data class Occasion(
        val folderName: String,
        override val photos: List<Photo>,
    ) : UploadBatch

    /**
     * `photos[i]` goes into `folders[i]` below the base folder; [label] is the upload-history label
     * (never used as a path).
     */
    data class PerPhoto(
        override val photos: List<Photo>,
        val folders: List<String>,
        val label: String,
    ) : UploadBatch {
        init {
            require(folders.size == photos.size) { "One folder per photo required" }
            require(folders.none { it.isBlank() }) { "Per-photo folders must not be blank" }
        }
    }
}

/** `YYYY/MM/DD` of [date], ASCII digits only (never the default locale's digits or month names). */
fun dayFolderName(date: LocalDate): String =
    String.format(Locale.ROOT, "%04d/%02d/%02d", date.year, date.monthValue, date.dayOfMonth)

/**
 * Resolves an auto date selection into **one** upload: photos ordered by capture day, then id,
 * each into its `YYYY/MM/DD` day folder. The label is the single day folder or
 * `"<first> – <last>"`.
 */
fun resolveAutoDateUploadBatch(photos: List<Photo>): UploadBatch.PerPhoto {
    val ordered = photos.sortedWith(compareBy<Photo>({ it.dateTaken }, { it.id }))
    val folders = ordered.map { dayFolderName(it.dateTaken) }
    val first = folders.firstOrNull().orEmpty()
    val last = folders.lastOrNull().orEmpty()
    val label = if (first == last) first else "$first – $last"
    return UploadBatch.PerPhoto(photos = ordered, folders = folders, label = label)
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
