package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for GitHub issues #30 / #49.
 *
 * A raw file name (`IMG_2026….jpg`) is meaningless when read by TalkBack. No Kotlin source
 * under `ui/` may use a `displayName` as a `contentDescription`; use
 * `photoPositionDescription` or the localized grid descriptions instead.
 */
class NoFilenameContentDescriptionRegressionTest {
    private val moduleDir: File =
        File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `no ui source uses a display name as content description`() {
        val uiDir = File(moduleDir, "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/ui")
        assertTrue("Expected ui sources at ${uiDir.path}", uiDir.isDirectory)

        val sources = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No ui sources found", sources.size > 5)
        val offenders = sources.filter { findOffenders(it.readText()).isNotEmpty() }.map { it.name }

        assertTrue("displayName must not be a contentDescription: $offenders", offenders.isEmpty())
    }

    @Test
    fun `matcher detects display name or href anywhere in a description assignment`() {
        listOf(
            "contentDescription = photo.displayName,",
            "contentDescription=photo.displayName",
            "contentDescription =\n    album.photo.displayName",
            "contentDescription = displayName",
            "stateDescription = photo.displayName",
            "contentDescription = \"Photo \${photo.displayName}\"",
            "contentDescription = \"Photo \$displayName\"",
            "contentDescription = stringResource(R.string.cd_photo, photo.displayName),",
            "contentDescription = stringResource(R.string.cd_photo, photo.displayName.trim())",
            "contentDescription = photo.href",
            "stateDescription = \"\${state}: \${photo.href}\"",
            "contentDescription = if (a) photo.displayName else null,",
        ).forEach { assertEquals("Not detected: $it", 1, findOffenders(it).size) }
    }

    @Test
    fun `matcher ignores unrelated code`() {
        listOf(
            "contentDescription = null,",
            "contentDescription = photoPositionDescription(1, 2),",
            "text = album.displayName,",
            "contentDescription = stringResource(R.string.cd_back)",
            "contentDescription = stringResource(R.string.cd_back),\ntext = photo.displayName,",
            "contentDescription = \"Photo, 1\",\nlabel = photo.displayName",
            "stateDescription = state\n}\nval name = photo.displayName",
        ).forEach { assertFalse("False positive: $it", findOffenders(it).isNotEmpty()) }
    }

    /** Right-hand sides of `contentDescription` / `stateDescription` assignments mentioning a file name or href. */
    private fun findOffenders(source: String): List<String> =
        ASSIGNMENT
            .findAll(source)
            .map { expressionAfter(source, it.range.last + 1) }
            .filter { FILE_NAME.containsMatchIn(it) }
            .toList()

    /** The expression starting at [start]: up to a top-level `,`, newline, or closing bracket (strings are opaque). */
    private fun expressionAfter(
        source: String,
        start: Int,
    ): String {
        var i = start
        while (i < source.length && source[i].isWhitespace()) i++
        val begin = i
        var depth = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            if (c == '"') {
                inString = !inString
            } else if (!inString) {
                depth += bracketDelta(c)
                val topLevelEnd = depth == 0 && (c == ',' || c == '\n')
                if (depth < 0 || topLevelEnd) return source.substring(begin, i)
            }
            i++
        }
        return source.substring(begin)
    }

    private fun bracketDelta(c: Char): Int =
        when (c) {
            '(', '{', '[' -> 1
            ')', '}', ']' -> -1
            else -> 0
        }

    private companion object {
        val ASSIGNMENT = Regex("""\b(contentDescription|stateDescription)\s*=(?!=)""")
        val FILE_NAME = Regex("""\b(displayName|href)\b""")
    }
}
