package de.majuwa.android.paper.krhnlesimagemanagement.model

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val baseFolder: String = "",
) {
    val isValid: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** Normalized base folder path with no leading/trailing slashes, empty if unset. */
    val normalizedBaseFolder: String
        get() = baseFolder.trim('/')

    /** Full path prefix for uploads: e.g. "Photos/KrohnSync" or "" if unset. */
    val uploadPathPrefix: String
        get() = normalizedBaseFolder.let { if (it.isBlank()) "" else "$it/" }
}
