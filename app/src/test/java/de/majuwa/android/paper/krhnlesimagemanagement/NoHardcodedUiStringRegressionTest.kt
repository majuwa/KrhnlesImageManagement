package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every text shown to the user comes from a string resource (so it can be translated, and raw
 * exception / library messages never reach the UI). Scans all main Kotlin sources, not only `ui/`:
 * notifications and toasts are built in `worker/`, `download/`, `reminder/` and `upload/`.
 */
class NoHardcodedUiStringRegressionTest {
    private val moduleDir: File =
        File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `main sources contain no hardcoded user-facing literals`() {
        val sourceRoot = File(moduleDir, "src/main/java")
        assertTrue(
            "Expected main sources at ${sourceRoot.path} (system property krhnles.moduleDir)",
            sourceRoot.isDirectory,
        )
        val offenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val relative = file.relativeTo(sourceRoot).path
                        offendingLiteral(line, relative)?.let { "$relative:${index + 1}: $it" }
                    }
                }.toList()

        assertTrue(
            "User-facing texts must come from string resources (values/strings.xml + values-de). Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `matcher detects hardcoded literals`() {
        listOf(
            """contentDescription = "Back"""",
            """error = "Server URL is required"""",
            """it.copy(error = throwable.message ?: "Failed to load photos")""",
            """Text("Hello")""",
            """Text(text = "Hello world")""",
            """.setContentText("Uploading")""",
            """.setContentTitle("Upload ${'$'}{count} done")""",
            """Toast.makeText(context, "Saved", Toast.LENGTH_SHORT)""",
            """title = "Settings"""",
        ).forEach { line -> assertTrue("Not detected: $line", offendingLiteral(line, "x/Y.kt") != null) }
    }

    @Test
    fun `matcher ignores language neutral literals and resources`() {
        listOf(
            """Text(text = "%.0f".format(score))""",
            """setContentText("${'$'}current / ${'$'}total")""",
            """text = "${'$'}{first} – ${'$'}{last}"""",
            """contentDescription = null""",
            """Text(stringResource(R.string.action_upload))""",
            """text = stringResource(error.messageRes)""",
            """val s = "plain literal that is not UI"""",
        ).forEach { line -> assertEquals("False positive: $line", null, offendingLiteral(line, "x/Y.kt")) }
    }

    @Test
    fun `allow list entries are documented`() {
        assertFalse(ALLOWED_LABELS.isEmpty())
        ALLOWED_LABELS.values.forEach { reason -> assertTrue(reason.isNotBlank()) }
    }

    private companion object {
        /** Compose animation labels (debug tooling only, never shown), with the reason per entry. */
        val ALLOWED_LABELS: Map<String, String> =
            mapOf(
                "blur-content" to "AnimatedContent label (animation inspector only)",
                "duplicates-content" to "AnimatedContent label (animation inspector only)",
            )

        const val LITERAL = """"((?:[^"\\]|\\.)*)""""

        val PATTERNS: List<Regex> =
            listOf(
                """\bcontentDescription\s*=\s*$LITERAL""",
                """\bText\(\s*$LITERAL""",
                """\btext\s*=\s*$LITERAL""",
                """\berror\s*=\s*$LITERAL""",
                """\blabel\s*=\s*$LITERAL""",
                """\btitle\s*=\s*$LITERAL""",
                """\bmessage\s*=\s*$LITERAL""",
                """\bsetContentTitle\(\s*$LITERAL""",
                """\bsetContentText\(\s*$LITERAL""",
                """\bToast\.makeText\([^,]+,\s*$LITERAL""",
                """\.message\s*\?:\s*$LITERAL""",
            ).map { Regex(it) }

        /** Returns a description when [line] sets a user-facing text to a literal that contains words. */
        fun offendingLiteral(
            line: String,
            file: String,
        ): String? {
            val code = line.substringBefore("//")
            for (pattern in PATTERNS) {
                for (match in pattern.findAll(code)) {
                    val literal = match.groupValues[1]
                    if (literal in ALLOWED_LABELS) continue
                    if (hasWords(literal)) return "\"$literal\" in $file"
                }
            }
            return null
        }

        /** True when the literal has a letter outside `${...}` / `$name` templates and `%` format specifiers. */
        fun hasWords(literal: String): Boolean {
            val stripped =
                literal
                    .replace(Regex("""\$\{[^}]*}"""), "")
                    .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "")
                    .replace(Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]"""), "")
            return stripped.any { it.isLetter() }
        }
    }
}
