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
                    val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    offenders(file.readText(), relative)
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
            """label = "Unknown"""",
            """snackbarHostState.showSnackbar("Saved")""",
            """.addAction(0, "Cancel", intent)""",
            """NotificationChannel(ID, "Uploads", IMPORTANCE_LOW)""",
            """.bigText("A long text")""",
            """stateDescription = "Selected"""",
            """onClick(label = "Select") { true }""",
            """val c = x // see https://a.b
            |contentDescription = "Back"
            """.trimMargin(),
        ).forEach { source -> assertTrue("Not detected: $source", offenders(source, "x/Y.kt").isNotEmpty()) }
    }

    @Test
    fun `matcher detects sinks that span lines`() {
        val channel =
            """
            NotificationChannel(
                CHANNEL_ID,
                "Uploads",
                NotificationManager.IMPORTANCE_LOW,
            )
            """.trimIndent()
        assertTrue(offenders(channel, "x/Y.kt").isNotEmpty())
        val snackbar = "showSnackbar(\n    \"Saved\",\n)"
        assertTrue(offenders(snackbar, "x/Y.kt").isNotEmpty())
    }

    @Test
    fun `exception messages are rejected in ui worker download and reminder only`() {
        listOf("ui", "worker", "download", "reminder").forEach { dir ->
            assertTrue(offenders("""state.copy(error = it.message)""", "a/b/$dir/Y.kt").isNotEmpty())
            assertTrue(offenders("""val m = throwable.localizedMessage""", "a/b/$dir/Y.kt").isNotEmpty())
        }
        assertTrue(offenders("""state.copy(error = it.message)""", "a/b/data/Y.kt").isEmpty())
        assertTrue(offenders("""// it.message is never shown""", "a/b/ui/Y.kt").isEmpty())
        assertTrue(offenders("""val m = messageRes""", "a/b/ui/Y.kt").isEmpty())
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
            """val url = "https://example.com" // contentDescription = "Back" is only a comment""",
            """snackbarHostState.showSnackbar(message)""",
            """.addAction(0, getString(R.string.action_cancel), intent)""",
            """stateDescription = state""",
            """onClick(label = clickLabel) { true }""",
        ).forEach { source ->
            assertEquals("False positive: $source", emptyList<String>(), offenders(source, "x/Y.kt"))
        }
    }

    @Test
    fun `a double slash inside a string literal is not a comment`() {
        assertEquals("""val u = "https://a.b"""", stripLineComment("""val u = "https://a.b" // note""").trimEnd())
        assertEquals("""val u = "a\"//b"""", stripLineComment("""val u = "a\"//b" // note""").trimEnd())
        assertEquals("val x = 1", stripLineComment("val x = 1 // note").trimEnd())
        assertTrue(offenders("""title = "https://a.b"""", "x/Y.kt").isNotEmpty())
    }

    @Test
    fun `animation labels are allowed only for their file and only as a label`() {
        val (file, literal) = ALLOWED_LABELS.keys.first()
        assertTrue(offenders("""label = "$literal",""", file).isEmpty())
        assertTrue(offenders("""label = "$literal",""", "other/Y.kt").isNotEmpty())
        assertTrue(offenders("""text = "$literal"""", file).isNotEmpty())
        assertTrue(offenders("""contentDescription = "$literal"""", file).isNotEmpty())
        assertTrue(offenders("""label = "another label"""", file).isNotEmpty())
    }

    @Test
    fun `allow list entries are documented and exist in the sources`() {
        assertFalse(ALLOWED_LABELS.isEmpty())
        ALLOWED_LABELS.values.forEach { reason -> assertTrue(reason.isNotBlank()) }
        val sourceRoot = File(moduleDir, "src/main/java")
        ALLOWED_LABELS.keys.forEach { (file, literal) ->
            val source = sourceRoot.walkTopDown().firstOrNull { it.invariantSeparatorsPath.endsWith(file) }
            assertTrue("Allow-listed file $file does not exist", source != null)
            assertTrue("Allow-listed literal $literal not found in $file", source!!.readText().contains("\"$literal\""))
        }
    }

    private companion object {
        /** Compose animation labels (debug tooling only, never shown): (file suffix, literal) -> reason. */
        val ALLOWED_LABELS: Map<Pair<String, String>, String> =
            mapOf(
                ("ui/albums/BlurReviewScreen.kt" to "blur-content") to
                    "AnimatedContent label (animation inspector only)",
                ("ui/albums/DuplicateReviewScreen.kt" to "duplicates-content") to
                    "AnimatedContent label (animation inspector only)",
            )

        const val LITERAL = """"((?:[^"\\]|\\.)*)""""
        const val LABEL_PATTERN = """\blabel\s*=\s*$LITERAL"""

        val PATTERNS: List<Regex> =
            listOf(
                """\bcontentDescription\s*=\s*$LITERAL""",
                """\bText\(\s*$LITERAL""",
                """\btext\s*=\s*$LITERAL""",
                """\berror\s*=\s*$LITERAL""",
                LABEL_PATTERN,
                """\btitle\s*=\s*$LITERAL""",
                """\bmessage\s*=\s*$LITERAL""",
                """\bsetContentTitle\(\s*$LITERAL""",
                """\bsetContentText\(\s*$LITERAL""",
                """\bToast\.makeText\([^,]+,\s*$LITERAL""",
                """\.message\s*\?:\s*$LITERAL""",
                """\bshowSnackbar\(\s*$LITERAL""",
                """\baddAction\(\s*[^,()]+,\s*$LITERAL""",
                """\bNotificationChannel\(\s*[^,()]+,\s*$LITERAL""",
                """\bbigText\(\s*$LITERAL""",
                """\bstateDescription\s*=\s*$LITERAL""",
                """\bonClick\(\s*label\s*=\s*$LITERAL""",
            ).map { Regex(it) }

        val EXCEPTION_MESSAGE = Regex("""\.(message|localizedMessage)\b""")
        val MESSAGE_DIRS = Regex("""(^|/)(ui|worker|download|reminder)/""")

        /** Descriptions of every user-facing literal and exception message in [source] (comments ignored). */
        fun offenders(
            source: String,
            file: String,
        ): List<String> {
            val code = source.lines().joinToString("\n") { stripLineComment(it) }
            val result = mutableListOf<String>()

            fun lineOf(index: Int) = code.substring(0, index).count { it == '\n' } + 1
            for (pattern in PATTERNS) {
                for (match in pattern.findAll(code)) {
                    val literal = match.groupValues[1]
                    val isLabel = pattern.pattern == LABEL_PATTERN
                    if (isLabel && ALLOWED_LABELS.containsKey(fileSuffixKey(file, literal))) continue
                    if (hasWords(literal)) result += "$file:${lineOf(match.range.first)}: \"$literal\""
                }
            }
            if (MESSAGE_DIRS.containsMatchIn(file)) {
                for (match in EXCEPTION_MESSAGE.findAll(code)) {
                    result += "$file:${lineOf(match.range.first)}: exception text ${match.value} (typed error, rule 7)"
                }
            }
            return result
        }

        private fun fileSuffixKey(
            file: String,
            literal: String,
        ): Pair<String, String> =
            ALLOWED_LABELS.keys.firstOrNull { (allowedFile, allowedLiteral) ->
                allowedLiteral == literal && file.endsWith(allowedFile)
            } ?: (file to literal)

        /** Removes a trailing `//` comment, ignoring `//` inside string literals. */
        fun stripLineComment(line: String): String {
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inString && c == '\\' -> i++
                    c == '"' -> inString = !inString
                    !inString && c == '/' && line.startsWith("//", i) -> return line.substring(0, i)
                }
                i++
            }
            return line
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
