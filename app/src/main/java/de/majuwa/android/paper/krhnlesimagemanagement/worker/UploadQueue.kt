package de.majuwa.android.paper.krhnlesimagemanagement.worker

import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidFolderPath
import org.json.JSONArray
import org.json.JSONObject

/** Upload-history labels are capped before they are recorded. */
internal const val MAX_HISTORY_LABEL_LENGTH = 200

private const val KEY_FOLDER_NAME = "folderName"
private const val KEY_LABEL = "label"
private const val KEY_PHOTOS = "photos"
private const val KEY_ID = "id"
private const val KEY_URI = "uri"
private const val KEY_MIME_TYPE = "mimeType"
private const val KEY_DISPLAY_NAME = "displayName"
private const val KEY_UPLOADED = "uploaded"

/** One photo of a queue file; [folderName] is set only in the per-photo format. */
internal data class UploadQueueEntry(
    /** -1 for entries that pre-date photo ids; such entries upload but are never marked. */
    val id: Long,
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val folderName: String?,
    /** Staged copy of a shared photo ([isValidStagedName], always with `id = -1`); read instead of [uri]. */
    val staged: String? = null,
    /**
     * Uploaded by an earlier, interrupted run of this queue (`"uploaded": true`; absent in files of
     * older versions = not uploaded). A re-run skips it but counts it in the batch result.
     */
    val uploaded: Boolean = false,
)

/**
 * A parsed queue (or retry) file. Exactly one of two formats, decided from the content:
 * - single folder: top-level [folderName] (blank = base folder), entries without folders;
 * - per photo: every entry has its own folder, no top-level [folderName], optional [label].
 */
internal data class UploadQueue(
    val folderName: String?,
    val label: String?,
    val entries: List<UploadQueueEntry>,
) {
    /** Upload-history label: the label, else the common folder (uncapped), else empty; only the label is capped. */
    val historyLabel: String
        get() = label?.take(MAX_HISTORY_LABEL_LENGTH) ?: folderName.orEmpty()

    /** The folder (relative to the base folder) photo [index] is uploaded into. */
    fun folderOf(index: Int): String = entries[index].folderName ?: folderName.orEmpty()

    /** True if every effective folder is usable: common folder blank or valid, entry folders valid. */
    fun hasValidFolders(): Boolean {
        val common = folderName
        val commonValid = common == null || common.isBlank() || isValidFolderPath(common)
        return commonValid && entries.all { entry -> entry.folderName?.let(::isValidFolderPath) ?: true }
    }

    /** Indices of the entries not uploaded yet (by this or an earlier run). */
    val pendingIndices: List<Int>
        get() = entries.indices.filter { !entries[it].uploaded }

    /** This queue with entry [index] marked uploaded. */
    fun withUploaded(index: Int): UploadQueue =
        copy(entries = entries.toMutableList().also { it[index] = it[index].copy(uploaded = true) })

    /** Retry queue JSON with only the entries at [failedIndices], each keeping its own folder. */
    fun toRetryJson(failedIndices: List<Int>): JSONObject =
        toJson(failedIndices.map { entries[it].copy(uploaded = false) })

    /** The whole queue in its own format, with an `uploaded` marker on every uploaded entry. */
    fun toProgressJson(): JSONObject = toJson(entries)

    private fun toJson(selected: List<UploadQueueEntry>): JSONObject =
        JSONObject().apply {
            folderName?.let { put(KEY_FOLDER_NAME, it) }
            label?.let { put(KEY_LABEL, it) }
            put(
                KEY_PHOTOS,
                JSONArray().also { arr ->
                    selected.forEach { entry ->
                        arr.put(
                            JSONObject().apply {
                                if (entry.id >= 0L) put(KEY_ID, entry.id)
                                put(KEY_URI, entry.uri)
                                put(KEY_MIME_TYPE, entry.mimeType)
                                put(KEY_DISPLAY_NAME, entry.displayName)
                                entry.folderName?.let { put(KEY_FOLDER_NAME, it) }
                                entry.staged?.let { put(KEY_STAGED, it) }
                                if (entry.uploaded) put(KEY_UPLOADED, true)
                            },
                        )
                    }
                },
            )
        }
}

/**
 * Parses a queue file's JSON; null if it is malformed. `folderName` and `label` are read strictly
 * (`opt` + `is String`): `org.json` would coerce numbers and `null` to strings otherwise.
 *
 * The file is per-photo if it has a `label` (of any type) or any entry has a `folderName` key;
 * then every entry needs a non-blank string `folderName` and a top-level `folderName` is not
 * allowed. Otherwise the top-level `folderName` must be a string. A non-string label is dropped.
 *
 * An optional entry `staged` must be a string staged name ([isValidStagedName]) on an entry
 * without an id (`id = -1`); otherwise the whole file is malformed. An optional entry `uploaded`
 * (written by [UploadQueue.toProgressJson]) marks an entry finished by an earlier run.
 *
 * @throws org.json.JSONException for unparseable JSON or missing / mistyped entry fields.
 */
internal fun parseUploadQueue(json: String): UploadQueue? {
    val queue = JSONObject(json)
    val photos = queue.getJSONArray(KEY_PHOTOS)
    val objects = List(photos.length()) { photos.getJSONObject(it) }
    val entryFolders = objects.map { it.opt(KEY_FOLDER_NAME) }
    val perPhoto = queue.has(KEY_LABEL) || objects.any { it.has(KEY_FOLDER_NAME) }
    val entries =
        objects.mapIndexed { i, obj ->
            UploadQueueEntry(
                id = obj.optLong(KEY_ID, -1L),
                uri = obj.getString(KEY_URI),
                mimeType = obj.getString(KEY_MIME_TYPE),
                displayName = obj.getString(KEY_DISPLAY_NAME),
                folderName = entryFolders[i] as? String,
                staged = obj.opt(KEY_STAGED) as? String,
                // Strict: only a JSON `true` marks an entry uploaded; anything else uploads it again.
                uploaded = obj.opt(KEY_UPLOADED) == true,
            )
        }
    val stagedValid = objects.indices.all { hasValidStaged(objects[it], entries[it]) }
    val commonFolder = queue.opt(KEY_FOLDER_NAME)
    return when {
        !stagedValid -> null
        perPhoto && (queue.has(KEY_FOLDER_NAME) || entryFolders.any { it !is String || it.isBlank() }) -> null
        perPhoto -> UploadQueue(folderName = null, label = queue.opt(KEY_LABEL) as? String, entries = entries)
        commonFolder is String -> UploadQueue(folderName = commonFolder, label = null, entries = entries)
        else -> null
    }
}

/** No `staged` key, or a valid staged name (a string) on an entry without a MediaStore id. */
private fun hasValidStaged(
    obj: JSONObject,
    entry: UploadQueueEntry,
): Boolean {
    if (!obj.has(KEY_STAGED)) return true
    val staged = entry.staged
    return staged != null && isValidStagedName(staged) && entry.id == -1L
}
