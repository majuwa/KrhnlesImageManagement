package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for GitHub issues #22 / #41.
 *
 * `flow.collect { ...; return@collect }` only leaves the lambda for the current emission — it does
 * not cancel the upstream flow, so the collecting coroutine stays suspended (leaks) until its scope
 * dies. One-shot reads must use `first()` (or `firstOrNull()`); skipping values belongs in `filter`.
 * This test fails if the early-return idiom reappears anywhere in the production sources.
 */
class NoCollectEarlyReturnRegressionTest {
    private val moduleDir: File =
        File(System.getProperty(MODULE_DIR_PROPERTY) ?: ".").absoluteFile

    @Test
    fun `production sources do not return early from collect`() {
        val mainDir = File(moduleDir, "src/main")
        assertTrue(
            "Expected app module sources at ${mainDir.path} (system property $MODULE_DIR_PROPERTY)",
            mainDir.isDirectory,
        )

        val offenders =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> lineReturnsFromCollect(line) }
                        .map { (index, _) -> "${file.relativeTo(moduleDir).invariantSeparatorsPath}:${index + 1}" }
                }.sorted()
                .toList()

        assertTrue(
            "Use first()/firstOrNull() for one-shot flow reads instead of $LABEL " +
                "(it does not cancel the flow and leaks the collector). Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `matcher detects the early-return idiom`() {
        listOf(
            "        $LABEL",
            "    if (!config.isValid) $LABEL",
            "x.collect { v -> if (v) $LABEL else use(v) }",
        ).forEach { assertTrue("Not detected: $it", lineReturnsFromCollect(it)) }
    }

    @Test
    fun `matcher ignores comments and unrelated code`() {
        listOf(
            "// never write $LABEL here",
            "     * $LABEL does not cancel the flow",
            "val config = credentialStore.webDavConfig.first()",
            "flow.collect { value -> update(value) }",
            "return@collectLatest",
            "return@launch",
        ).forEach { assertFalse("False positive: $it", lineReturnsFromCollect(it)) }
    }

    private companion object {
        const val MODULE_DIR_PROPERTY = "krhnles.moduleDir"
        const val LABEL = "return@" + "collect"

        private val pattern = Regex(Regex.escape(LABEL) + "(?![A-Za-z0-9_])")

        fun lineReturnsFromCollect(line: String): Boolean {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
            return pattern.containsMatchIn(line.substringBefore("//"))
        }
    }
}
