package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for the flaky duplicate review UI test (`PhotoDeletionOutcomeUiTest`).
 *
 * `DuplicatesViewModel` / `BlurViewModel` analyze thumbnails on their `analysisDispatcher`
 * (`Dispatchers.Default` in production) and resume on the main dispatcher. In a Robolectric
 * Compose test the main looper is paused: `waitForIdle()` drains it, `waitUntil {}` does not. A
 * result coming back from a real background thread after the last `waitForIdle()` is therefore
 * never resumed and the scan hangs — or passes, depending on timing (it passed alone and failed
 * in the full class). Compose UI tests must inject a deterministic `analysisDispatcher`.
 */
class RobolectricAnalysisDispatcherRegressionTest {
    private val moduleDir: File =
        File(System.getProperty(MODULE_DIR_PROPERTY) ?: ".").absoluteFile

    @Test
    fun `compose UI tests inject the analysis dispatcher of review ViewModels`() {
        val testDir = File(moduleDir, "src/test")
        assertTrue(
            "Expected app module test sources at ${testDir.path} (system property $MODULE_DIR_PROPERTY)",
            testDir.isDirectory,
        )

        val offenders =
            testDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != SELF }
                .flatMap { file ->
                    findOffenders(file.readText()).map { "${file.relativeTo(moduleDir).invariantSeparatorsPath}: $it" }
                }.sorted()
                .toList()

        assertTrue(
            "Compose UI tests must pass analysisDispatcher (e.g. Dispatchers.Unconfined) to review " +
                "ViewModels; Dispatchers.Default races with Robolectric's paused main looper. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `matcher detects review ViewModels without an injected dispatcher in compose tests`() {
        val source =
            """
            val rule = createAndroidComposeRule<ComponentActivity>()
            fun a() = DuplicatesViewModel()
            fun b() = BlurViewModel(blurScorerFactory = { scorer(listOf(1)) })
            """.trimIndent()
        assertEquals(
            listOf("DuplicatesViewModel()", "BlurViewModel(blurScorerFactory = { scorer(listOf(1)) })"),
            findOffenders(source),
        )
    }

    @Test
    fun `matcher accepts injected dispatchers and ignores non-compose tests and type references`() {
        val injected =
            """
            val rule = createComposeRule()
            private fun create(): DuplicatesViewModel = DuplicatesViewModel(analysisDispatcher = dispatcher)
            val blur = BlurViewModel(
                blurScorerFactory = { scorer() },
                analysisDispatcher = dispatcher,
            )
            // DuplicatesViewModel() in a comment
            /**
             * Construct DuplicatesViewModel() without a dispatcher and BlurViewModel() in docs.
             */
            /* BlurViewModel() */
            """.trimIndent()
        assertEquals(emptyList<String>(), findOffenders(injected))
        assertEquals(emptyList<String>(), findOffenders("fun a() = DuplicatesViewModel()"))
    }

    private companion object {
        const val MODULE_DIR_PROPERTY = "krhnles.moduleDir"
        const val SELF = "RobolectricAnalysisDispatcherRegressionTest.kt"

        private val composeRule = Regex("""\bcreate(Android)?ComposeRule\b""")
        private val constructorCall = Regex("""\b(DuplicatesViewModel|BlurViewModel)\(""")

        /** Constructor calls of review ViewModels without `analysisDispatcher` in a Compose UI test source. */
        fun findOffenders(source: String): List<String> {
            val code = source.lines().filterNot { it.isCommentLine() }.joinToString("\n")
            if (!composeRule.containsMatchIn(code)) return emptyList()
            return constructorCall
                .findAll(code)
                .map { match -> code.substring(match.range.first, closingParen(code, match.range.last) + 1) }
                .filterNot { "analysisDispatcher" in it }
                .toList()
        }

        /** Line comments, KDoc / block comment lines (`/*`, ` * `, `*/`). */
        private fun String.isCommentLine(): Boolean =
            trimStart().let { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }

        /** Index of the parenthesis closing the one at [open] (or the last index if unbalanced). */
        fun closingParen(
            code: String,
            open: Int,
        ): Int {
            var depth = 0
            for (i in open until code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                }
            }
            return code.length - 1
        }
    }
}
