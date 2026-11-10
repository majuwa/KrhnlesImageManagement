package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saving album photos only ever creates, publishes and removes items this app inserted itself
 * (`LocalMediaWriter`): the single ContentResolver `delete` of the download path is
 * `LocalMediaWriter.deleteItem`, reached only from `discard()` and `discardLeftover()` (which
 * refuses anything but a pending item owned by this package). No `createDeleteRequest`, and no
 * `MANAGE_MEDIA` / storage permission in the manifest.
 */
class DownloadOwnItemsOnlyRegressionTest {
    /**
     * Any `.delete(` with arguments (or an argument list that starts on the next line), whatever the
     * receiver is called (`resolver`, `contentResolver`, `cr`, a client, ...): `File.delete()` has none,
     * `ContentResolver.delete(uri, where, args)` has.
     */
    private val resolverDelete = Regex("""\.delete\(\s*(?:[^)\s]|$)""")

    @Test
    fun `the delete pattern matches a content resolver under any name but not a file`() {
        listOf(
            "resolver.delete(uri, null, null)",
            "context.contentResolver.delete(uri, null, null)",
            "cr.delete(u, null, null)",
            "getContentResolver().delete(",
            "client.delete(uri, null, null)",
            "x.delete( uri, null, null)",
        ).forEach { assertTrue(it, resolverDelete.containsMatchIn(it)) }
        listOf("file.delete()", "queueTempFile(queueFile).delete()", "it.delete( )", "deleteItem(uri)").forEach {
            assertTrue(it, !resolverDelete.containsMatchIn(it))
        }
    }

    @Test
    fun `the only resolver delete of the download path is the writer's deleteItem`() {
        val offenders = mutableListOf<String>()
        DownloadSourceScan.downloadPathFiles().forEach { file ->
            val deletes = DownloadSourceScan.codeLines(file).filter { resolverDelete.containsMatchIn(it.second) }
            if (file.name == "LocalMediaWriter.kt") {
                assertEquals("exactly one delete in the writer", 1, deletes.size)
                assertTrue(
                    "the delete is inside deleteItem",
                    enclosingFunction(file, deletes.single().first) == "deleteItem",
                )
            } else {
                offenders += deletes.map { "${file.name}:${it.first}" }
            }
        }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `deleteItem is called only by discard and discardLeftover`() {
        val writer = File(DownloadSourceScan.moduleDir, "${DownloadSourceScan.BASE}/data/LocalMediaWriter.kt")
        val callers =
            DownloadSourceScan
                .codeLines(writer)
                .filter { it.second.contains("deleteItem(") && !it.second.contains("fun deleteItem") }
                .map { enclosingFunction(writer, it.first) }
                .sorted()

        assertEquals(listOf("discard", "discardLeftover"), callers)
        DownloadSourceScan.downloadPathFiles().filter { it.name != "LocalMediaWriter.kt" }.forEach { file ->
            assertTrue(
                "${file.name} must not call deleteItem",
                DownloadSourceScan.codeLines(file).none {
                    "deleteItem(" in
                        it.second
                },
            )
        }
    }

    @Test
    fun `no delete request in any main source`() {
        val offenders =
            File(DownloadSourceScan.moduleDir, "src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file -> DownloadSourceScan.codeLines(file).any { "createDeleteRequest" in it.second } }
                .map { it.name }
                .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the manifest requests no storage permission or MANAGE_MEDIA`() {
        val manifest = File(DownloadSourceScan.moduleDir, "src/main/AndroidManifest.xml")
        assertTrue("Expected ${manifest.path}", manifest.isFile)
        val text = manifest.readText()

        listOf("MANAGE_MEDIA", "WRITE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE").forEach {
            assertTrue("$it must not be requested", it !in text)
        }
    }

    /** Name of the nearest `fun` declared above [line] (1-based) in [file]. */
    private fun enclosingFunction(
        file: File,
        line: Int,
    ): String {
        val funRegex = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\(""")
        return file
            .readLines()
            .take(line)
            .mapNotNull { funRegex.find(it)?.groupValues?.get(1) }
            .last()
    }
}
