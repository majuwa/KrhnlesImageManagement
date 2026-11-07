package de.majuwa.android.paper.krhnlesimagemanagement.model

/** Order of the album list. Persisted by enum name, never by number. */
enum class AlbumSort {
    NAME_ASC,
    NAME_DESC,
    MODIFIED_NEWEST,
    MODIFIED_OLDEST,
    ;

    companion object {
        val DEFAULT = NAME_ASC

        /** The sort stored under [name]; [DEFAULT] for null, blank or unknown values. */
        fun fromStored(name: String?): AlbumSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
