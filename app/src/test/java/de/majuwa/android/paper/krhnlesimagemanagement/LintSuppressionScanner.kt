package de.majuwa.android.paper.krhnlesimagemanagement

import java.io.File

/**
 * Finds every mechanism that can silence a lint check with a given issue id.
 *
 * The issue id is always passed in as a parameter and never written literally in this
 * file, so the scanner (and its tests, which build their fixtures from the same parameter)
 * can live inside the scanned tree without flagging themselves.
 */
internal object LintSuppressionScanner {
    private val sourceExtensions = setOf("kt", "kts", "java")

    /** Annotation forms: `@Suppress`, `@SuppressLint`, `@SuppressWarnings`, any use-site target, qualified or not. */
    private val suppressAnnotation =
        Regex("""@(?:\w+:)?(?:[\w.]+\.)?Suppress(?:Lint|Warnings)?\s*\(([^)]*)\)""")

    /** Line-level forms such as `//noinspection <id>` or a suppression keyword on the same line. */
    private val suppressKeyword = Regex("""suppress|noinspection""", RegexOption.IGNORE_CASE)

    /** True if [source] (Kotlin or Java) suppresses the lint issue [issueId]. */
    fun sourceSuppresses(
        source: String,
        issueId: String,
    ): Boolean {
        val quotedId = "\"$issueId\""
        val viaAnnotation = suppressAnnotation.findAll(source).any { quotedId in it.groupValues[1] }
        val viaLine = source.lineSequence().any { issueId in it && suppressKeyword.containsMatchIn(it) }
        return viaAnnotation || viaLine
    }

    /**
     * True if a lint configuration file ([config] = contents of `build.gradle.kts`, `lint.xml`
     * or a lint baseline) mentions [issueId] at all. Any mention there (`disable`, `ignore`,
     * `checkOnly`, `severity`, a baseline `<issue id=...>`) changes how lint reports the issue.
     */
    fun configMentions(
        config: String,
        issueId: String,
    ): Boolean = issueId in config

    /**
     * Scans [moduleDir] (the `app/` module): all Kotlin/Java sources in every source set under
     * `src/`, plus the module build script, `lint*.xml` and any lint baseline (`*baseline*.xml`).
     * [projectDir] additionally contributes a root `lint.xml`, which lint also honours.
     * Returns offending paths relative to [moduleDir]'s parent.
     */
    fun findOffenders(
        moduleDir: File,
        projectDir: File,
        issueId: String,
    ): List<String> {
        val root = moduleDir.absoluteFile.parentFile ?: moduleDir.absoluteFile
        val sources =
            File(moduleDir, "src")
                .walkTopDown()
                .filter { it.isFile && it.extension in sourceExtensions }
                .filter { sourceSuppresses(it.readText(), issueId) }
        val configs =
            lintConfigFiles(moduleDir, projectDir).filter { configMentions(it.readText(), issueId) }
        return (sources + configs)
            .map { it.absoluteFile.relativeTo(root).path }
            .distinct()
            .sorted()
            .toList()
    }

    private fun lintConfigFiles(
        moduleDir: File,
        projectDir: File,
    ): Sequence<File> {
        val moduleFiles =
            moduleDir.listFiles().orEmpty().asSequence().filter { file ->
                file.isFile &&
                    (
                        file.name == "build.gradle.kts" ||
                            (file.name.startsWith("lint") && file.extension == "xml") ||
                            (file.name.contains("baseline") && file.extension == "xml")
                    )
            }
        val rootLintXml = sequenceOf(File(projectDir, "lint.xml")).filter { it.isFile }
        return moduleFiles + rootLintXml
    }
}
