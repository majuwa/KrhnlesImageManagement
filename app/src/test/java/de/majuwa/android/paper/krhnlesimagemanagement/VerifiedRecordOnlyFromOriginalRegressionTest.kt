package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review finding (location redaction): without `ACCESS_MEDIA_LOCATION`, or when a photo is not
 * opened through `MediaStore.setRequireOriginal`, the MediaProvider zeroes its location metadata
 * (same size). Such a copy verifies byte for byte, and removing the local photo would destroy the
 * only copy of its location. So a verified record may only ever describe a stream opened as the
 * original. Pinned in the sources:
 * - `UploadSource.Original` is created in exactly one function, and that function opens
 *   `MediaStore.setRequireOriginal(...)`;
 * - every `VerifiedUpload(` built in the production code outside the store's parser is built in a
 *   function that takes an `UploadSource.Original` parameter;
 * - nothing outside the store calls `recordVerified(` (records reach the store through those paths).
 */
class VerifiedRecordOnlyFromOriginalRegressionTest {
    private val moduleDir: File = File(System.getProperty(MODULE_DIR_PROPERTY) ?: ".").absoluteFile
    private val mainDir = File(moduleDir, "src/main/java")

    private fun mainSources(): Map<String, String> {
        assertTrue("Expected main sources at ${mainDir.path} ($MODULE_DIR_PROPERTY)", mainDir.isDirectory)
        return mainDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(mainDir).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun `an Original upload source is only created where setRequireOriginal is opened`() {
        val creations = mainSources().flatMap { (path, code) -> originalCreations(code).map { path to it } }
        assertEquals("exactly one place creates UploadSource.Original: $creations", 1, creations.size)
        val offenders = creations.filterNot { (_, function) -> REQUIRE_ORIGINAL in function }
        assertTrue("Original created without setRequireOriginal: $offenders", offenders.isEmpty())
    }

    @Test
    fun `a verified record is only built from an Original upload source`() {
        val sources = mainSources().filterKeys { !it.endsWith(STORE_FILE) }
        val builders = sources.flatMap { (path, code) -> recordBuilders(code).map { path to it } }
        assertTrue("the worker must build verified records", builders.isNotEmpty())
        val offenders =
            builders.filterNot { (_, function) -> ORIGINAL_PARAMETER.containsMatchIn(signatureOf(function)) }
        assertTrue("VerifiedUpload built without an UploadSource.Original parameter: $offenders", offenders.isEmpty())
    }

    @Test
    fun `records are not stored around the upload paths`() {
        val offenders =
            mainSources()
                .filterKeys { !it.endsWith(STORE_FILE) }
                .filterValues { code -> codeLines(code).any { ".recordVerified(" in it } }
                .keys
        assertTrue("recordVerified called outside the store: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the matchers find violations in synthetic sources`() {
        val bad =
            """
            private fun open(uri: Uri): UploadSource.Original? =
                resolver.openInputStream(uri)?.let { UploadSource.Original(it, uri) }

            private fun build(entry: Entry): VerifiedUpload =
                VerifiedUpload(id = entry.id)
            """.trimIndent()
        assertEquals(1, originalCreations(bad).size)
        assertTrue(REQUIRE_ORIGINAL !in originalCreations(bad).single())
        assertTrue(!ORIGINAL_PARAMETER.containsMatchIn(signatureOf(recordBuilders(bad).single())))

        val good =
            """
            internal sealed interface UploadSource {
                class Original(
                    override val stream: InputStream,
                ) : UploadSource
            }

            private fun open(uri: Uri): UploadSource.Original? {
                val original = MediaStore.setRequireOriginal(uri)
                // UploadSource.Original(fake) in a comment
                return resolver.openInputStream(original)?.let { UploadSource.Original(it, uri) }
            }

            private suspend fun verify(
                original: UploadSource.Original,
            ): VerifiedUpload =
                VerifiedUpload(id = 1)
            """.trimIndent()
        assertEquals(1, originalCreations(good).size)
        assertTrue(REQUIRE_ORIGINAL in originalCreations(good).single())
        assertTrue(ORIGINAL_PARAMETER.containsMatchIn(signatureOf(recordBuilders(good).single())))
    }

    private companion object {
        const val MODULE_DIR_PROPERTY = "krhnles.moduleDir"
        const val STORE_FILE = "data/VerifiedUploadsStore.kt"
        const val REQUIRE_ORIGINAL = "MediaStore.setRequireOriginal("
        val FUNCTION_START = Regex("""^\s*((private|internal|public|protected|override|suspend|inline)\s+)*fun\s""")
        val ORIGINAL_CREATION = Regex("""(?<!class )\bUploadSource\.Original\(|::Original\b|(?<![.\w])Original\(""")
        val ORIGINAL_PARAMETER = Regex(""":\s*UploadSource\.Original\b""")
        val RECORD_CREATION = Regex("""(?<![.\w])VerifiedUpload\(""")

        /** Code lines without comment-only lines. */
        fun codeLines(code: String): List<String> =
            code.lines().filterNot {
                val t = it.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }

        /** The code split into top-level and member functions (each from its `fun` to the next one). */
        fun functions(code: String): List<String> {
            val chunks = mutableListOf<MutableList<String>>()
            codeLines(code).forEach { line ->
                if (FUNCTION_START.containsMatchIn(line) || chunks.isEmpty()) chunks += mutableListOf<String>()
                chunks.last() += line
            }
            return chunks
                .map {
                    it.joinToString(
                        "\n",
                    )
                }.filter { FUNCTION_START.containsMatchIn(it.lineSequence().first()) }
        }

        /** Functions creating an `UploadSource.Original` (its declaration `class Original(` excluded). */
        fun originalCreations(code: String): List<String> =
            functions(code).filter { function ->
                function.lines().any { line -> "class Original(" !in line && ORIGINAL_CREATION.containsMatchIn(line) }
            }

        fun recordBuilders(code: String): List<String> =
            functions(code).filter { function ->
                function.lines().any { line ->
                    "class VerifiedUpload(" !in line && RECORD_CREATION.containsMatchIn(line)
                }
            }

        /** The function header up to its body (`{` or an expression body `=`). */
        fun signatureOf(function: String): String {
            val body = Regex("""\)\s*(:\s*[\w.<>?, ]+)?\s*(\{|=)""").find(function)
            return if (body == null) function else function.substring(0, body.range.last)
        }
    }
}
