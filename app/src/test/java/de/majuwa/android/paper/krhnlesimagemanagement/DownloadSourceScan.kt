package de.majuwa.android.paper.krhnlesimagemanagement

import java.io.File

/** The module directory (`krhnles.moduleDir`) and the files that make up the photo-download path. */
internal object DownloadSourceScan {
    val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile
    const val BASE = "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement"

    /** Source files of the download path: the `download/` package and the `Download*` / `LocalMediaWriter` files. */
    fun downloadPathFiles(): List<File> =
        listOf("download", "data", "worker")
            .map { File(moduleDir, "$BASE/$it") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file ->
                val inDownloadPackage = file.invariantSeparatorsPath.contains("/krhnlesimagemanagement/download/")
                inDownloadPackage || file.name.startsWith("Download") || file.name == "LocalMediaWriter.kt"
            }.sortedBy { it.path }

    /** Lines of [file] without comment-only lines and trailing `//` comments, 1-based. */
    fun codeLines(file: File): List<Pair<Int, String>> =
        file
            .readLines()
            .withIndex()
            .filterNot { (_, line) ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }.map { (i, line) -> (i + 1) to line.substringBefore("//") }
}
