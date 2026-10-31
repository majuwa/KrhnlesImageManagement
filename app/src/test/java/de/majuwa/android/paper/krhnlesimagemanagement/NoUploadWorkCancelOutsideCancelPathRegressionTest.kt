package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards design D2 of `cancel-running-upload`: the worker treats a stop as the user's cancel only
 * when WorkManager reports `STOP_REASON_CANCELLED_BY_APP`, which is only sound while the single
 * `cancelRunningUpload()` in `worker/UploadCancellation.kt` is the only code that cancels work.
 * `ExistingWorkPolicy.REPLACE` / `APPEND_OR_REPLACE` also stop running work with that reason and
 * are therefore forbidden, as are the other WorkManager cancel APIs outside that file.
 */
class NoUploadWorkCancelOutsideCancelPathRegressionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `production sources cancel work only in the cancel path`() {
        val mainDir = File(moduleDir, "src/main")
        assertTrue("Expected app module sources at ${mainDir.path}", mainDir.isDirectory)

        assertEquals(emptyList<String>(), violations(mainDir))
    }

    @Test
    fun `the scan finds a cancel call outside the cancel path in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        val planted = File(copy, "java/de/majuwa/android/paper/krhnlesimagemanagement/Planted.kt")
        planted.writeText("fun f(m: androidx.work.WorkManager) { m.cancelAllWorkByTag(\"photo_upload\") }\n")

        assertEquals(listOf("java/de/majuwa/android/paper/krhnlesimagemanagement/Planted.kt:1"), violations(copy))
    }

    @Test
    fun `the scan finds a replace policy in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        File(copy, "java/Planted.kt").writeText("val p = ExistingWorkPolicy.REPLACE\n")
        File(copy, "java/Planted2.kt").writeText("val p = ExistingWorkPolicy.APPEND_OR_REPLACE\n")

        assertEquals(listOf("java/Planted.kt:1", "java/Planted2.kt:1"), violations(copy))
    }

    @Test
    fun `the scan allows the cancel path file and ignores comments`() {
        val copy = tmp.newFolder("main")
        val cancelPath = File(copy, "java/de/majuwa/android/paper/krhnlesimagemanagement/worker/UploadCancellation.kt")
        checkNotNull(cancelPath.parentFile).mkdirs()
        cancelPath.writeText("fun f(m: WorkManager) { m.cancelWorkById(id) }\n")
        File(copy, "java/Doc.kt").writeText("// cancelWorkById is only in the cancel path\n * cancelUniqueWork\n")

        assertEquals(emptyList<String>(), violations(copy))
    }

    private companion object {
        const val CANCEL_PATH_FILE = "worker/UploadCancellation.kt"

        val cancelApis = Regex("""\b(cancelWorkById|cancelAllWorkByTag|cancelUniqueWork|cancelAllWork)\b""")
        val replacePolicies = Regex("""ExistingWorkPolicy\.(REPLACE|APPEND_OR_REPLACE)\b""")

        fun violations(mainDir: File): List<String> =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val inCancelPath = file.invariantSeparatorsPath.endsWith("/$CANCEL_PATH_FILE")
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> isCode(line) }
                        .filter { (_, line) ->
                            val code = line.substringBefore("//")
                            replacePolicies.containsMatchIn(code) || (!inCancelPath && cancelApis.containsMatchIn(code))
                        }.map { (index, _) -> "${file.relativeTo(mainDir).invariantSeparatorsPath}:${index + 1}" }
                }.sorted()
                .toList()

        fun isCode(line: String): Boolean {
            val trimmed = line.trimStart()
            return !(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
        }
    }
}
