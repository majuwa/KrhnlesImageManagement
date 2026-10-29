package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The removal of uploaded photos only ever moves them to the system trash through a user-confirmed
 * system request (`createTrashRequest`, launched by an Activity): never a hard delete of a local
 * item, never `MANAGE_MEDIA` (the spike showed a worker cannot trash anyway). Fails if a permanent
 * delete or the permission appears on that path.
 */
class NoHardDeleteLocalMediaRegressionTest {
    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile
    private val base = "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement"

    private val removalSources =
        listOf(
            "$base/data/UploadedRemoval.kt",
            "$base/data/LocalMediaTrasher.kt",
            "$base/ui/photogrid/UploadedRemovalViewModel.kt",
            "$base/ui/photogrid/UploadedRemovalUi.kt",
        )

    private val forbidden =
        listOf(
            "createDeleteRequest",
            "createWriteRequest",
            ".delete(",
            "MANAGE_MEDIA",
            "canManageMedia",
            "MANAGE_EXTERNAL_STORAGE",
        )

    @Test
    fun `the removal path never hard deletes and requests no special access`() {
        val offenders =
            removalSources.flatMap { path ->
                val file = File(moduleDir, path)
                assertTrue("Expected ${file.path}", file.isFile)
                val code =
                    file.readLines().filterNot {
                        it.trim().startsWith("*") || it.trim().startsWith("//") ||
                            it.trim().startsWith("/**")
                    }
                forbidden.filter { token -> code.any { token in it } }.map { found -> "$path: $found" }
            }
        assertTrue("Forbidden on the removal path: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the trash request is created with the trash value true and launched through the system request only`() {
        val trasher = File(moduleDir, "$base/data/LocalMediaTrasher.kt").readText()
        assertEquals("one trash request", 1, TRASH_CALL.findAll(trasher).count())
        assertEquals(
            "createTrashRequest must pass the literal value true (trash, never untrash)",
            emptyList<String>(),
            trashCallsWithoutTrue(trasher),
        )
        val ui = File(moduleDir, "$base/ui/photogrid/UploadedRemovalUi.kt").readText()
        assertTrue("StartIntentSenderForResult must launch it", "StartIntentSenderForResult" in ui)
    }

    @Test
    fun `no main source creates a delete request or trashes with another value`() {
        val offenders =
            File(moduleDir, "src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val code = file.readText()
                    val name = file.relativeTo(moduleDir).invariantSeparatorsPath
                    listOfNotNull("$name: createDeleteRequest".takeIf { "createDeleteRequest" in code }) +
                        trashCallsWithoutTrue(code).map { "$name: $it" }
                }.toList()
        assertTrue("Forbidden: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the matcher accepts only the value true`() {
        assertEquals(emptyList<String>(), trashCallsWithoutTrue("MediaStore.createTrashRequest(resolver, uris, true)"))
        assertEquals(
            emptyList<String>(),
            trashCallsWithoutTrue("MediaStore.createTrashRequest(\n    resolver,\n    uris,\n    true,\n)"),
        )
        assertEquals(1, trashCallsWithoutTrue("MediaStore.createTrashRequest(resolver, uris, false)").size)
        assertEquals(1, trashCallsWithoutTrue("MediaStore.createTrashRequest(resolver, uris, trash)").size)
    }

    private companion object {
        val TRASH_CALL = Regex("""createTrashRequest\(([^()]*)\)""")

        /** Every `createTrashRequest(...)` call whose third argument is not the literal `true`. */
        fun trashCallsWithoutTrue(code: String): List<String> =
            TRASH_CALL
                .findAll(code)
                .filterNot { call ->
                    val args =
                        call.groupValues[1]
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    args.size == 3 && args[2] == "true"
                }.map { it.value }
                .toList()
    }

    @Test
    fun `the manifest does not declare MANAGE_MEDIA or all-files access`() {
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
        listOf("MANAGE_MEDIA", "MANAGE_EXTERNAL_STORAGE").forEach {
            assertTrue("$it must not be declared", it !in manifest)
        }
    }
}
