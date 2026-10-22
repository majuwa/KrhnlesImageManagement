package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression test for GitHub issues #24 / #43.
 *
 * Silencing the navigation-compose back-stack-entry lint check hid unsafe lookups from lint
 * and reviewers. Lint fails the build on an unremembered lookup, but only while the check is
 * active — this test closes that loophole for every suppression mechanism (annotations in any
 * source set, noinspection comments, Gradle lint options, lint.xml, lint baselines).
 * Use `rememberParentEntry` instead.
 *
 * The issue id is assembled from two parts so this file never contains it literally and the
 * scan of `app/src` (which includes this test source set) does not flag its own fixtures.
 */
class NoLintSuppressionRegressionTest {
    private val moduleDir: File =
        File(System.getProperty(MODULE_DIR_PROPERTY) ?: ".").absoluteFile
    private val projectDir: File =
        System.getProperty(PROJECT_DIR_PROPERTY)?.let(::File) ?: moduleDir.parentFile ?: moduleDir

    @Test
    fun `app module does not suppress the back-stack-entry lint check`() {
        assertTrue(
            "Expected app module sources at ${File(moduleDir, "src").path} " +
                "(system property $MODULE_DIR_PROPERTY)",
            File(moduleDir, "src/main").isDirectory,
        )
        assertTrue(
            "Expected module build script in ${moduleDir.path}",
            File(moduleDir, "build.gradle.kts").isFile,
        )

        val offenders = LintSuppressionScanner.findOffenders(moduleDir, projectDir, ISSUE_ID)

        assertTrue(
            "$ISSUE_ID must not be suppressed; use rememberParentEntry " +
                "(keyed on the current NavBackStackEntry) instead. Offending files: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `source matcher detects every suppression variant`() {
        val variants =
            listOf(
                """@SuppressLint("$ISSUE_ID")""",
                """@Suppress("$ISSUE_ID")""",
                """@file:Suppress("$ISSUE_ID")""",
                """@file:SuppressLint("$ISSUE_ID")""",
                """@android.annotation.SuppressLint("$ISSUE_ID")""",
                """@SuppressLint("Other", "$ISSUE_ID")""",
                """@SuppressLint(value = ["$ISSUE_ID"])""",
                "@Suppress(\n    \"$ISSUE_ID\",\n)",
                """@SuppressWarnings("$ISSUE_ID")""",
                """@SuppressLint({"Other", "$ISSUE_ID"})""",
                "//noinspection $ISSUE_ID",
                "// noinspection $ISSUE_ID",
                "/* noinspection $ISSUE_ID */",
                """@get:SuppressLint("$ISSUE_ID")""",
            )
        variants.forEach { source ->
            assertTrue("Not detected: $source", LintSuppressionScanner.sourceSuppresses(source, ISSUE_ID))
        }
    }

    @Test
    fun `source matcher ignores unrelated code`() {
        val clean =
            listOf(
                """@SuppressLint("MissingPermission")""",
                """@Suppress("UNUSED_PARAMETER")""",
                "//noinspection SpellCheckingInspection",
                "val entry = remember(backStack) { navController.getBackStackEntry(ROUTE_ALBUMS) }",
                "// The $ISSUE_ID lint check guards against this.",
                "",
            )
        clean.forEach { source ->
            assertFalse("False positive: $source", LintSuppressionScanner.sourceSuppresses(source, ISSUE_ID))
        }
    }

    @Test
    fun `config matcher detects lint configuration mentions`() {
        val variants =
            listOf(
                """lint { disable += "$ISSUE_ID" }""",
                """lint { checkOnly += setOf("$ISSUE_ID") }""",
                """<issue id="$ISSUE_ID" severity="ignore" />""",
                """<issue id="$ISSUE_ID" message="..."><location file="KrhnlesApp.kt"/></issue>""",
            )
        variants.forEach { config ->
            assertTrue("Not detected: $config", LintSuppressionScanner.configMentions(config, ISSUE_ID))
        }
        assertFalse(
            LintSuppressionScanner.configMentions("""lint { warningsAsErrors = true }""", ISSUE_ID),
        )
    }

    @Test
    fun `scanner finds offenders in other source sets, Java sources and lint config files`() {
        val project = Files.createTempDirectory("lint-scan").toFile()
        try {
            val module = File(project, "app")
            writeFile(module, "build.gradle.kts", """lint { disable += "$ISSUE_ID" }""")
            writeFile(module, "lint.xml", """<lint><issue id="$ISSUE_ID" severity="ignore"/></lint>""")
            writeFile(module, "lint-baseline.xml", """<issues><issue id="$ISSUE_ID"/></issues>""")
            writeFile(module, "src/debug/java/A.kt", """@file:Suppress("$ISSUE_ID")""")
            writeFile(module, "src/main/java/B.java", """@SuppressLint("$ISSUE_ID") class B {}""")
            writeFile(module, "src/main/java/Clean.kt", "val x = 1")
            writeFile(project, "lint.xml", """<lint><issue id="$ISSUE_ID" severity="ignore"/></lint>""")

            val offenders = LintSuppressionScanner.findOffenders(module, project, ISSUE_ID)

            assertEquals(
                listOf(
                    "app/build.gradle.kts",
                    "app/lint-baseline.xml",
                    "app/lint.xml",
                    "app/src/debug/java/A.kt",
                    "app/src/main/java/B.java",
                    "lint.xml",
                ),
                offenders,
            )
        } finally {
            project.deleteRecursively()
        }
    }

    private fun writeFile(
        dir: File,
        path: String,
        content: String,
    ) {
        val file = File(dir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private companion object {
        const val ISSUE_ID = "Unremembered" + "GetBackStackEntry"
        const val MODULE_DIR_PROPERTY = "krhnles.moduleDir"
        const val PROJECT_DIR_PROPERTY = "krhnles.projectDir"
    }
}
