package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for GitHub issues #27 / #46: PROPFIND listings are stream-parsed
 * (`PropfindEntryReader` over a capped `byteStream()`), never buffered and DOM-parsed — a DOM of a
 * large album costs many times the response size and caused OOM / ANR. Fails if a DOM / SAX
 * document parser reappears on the production PROPFIND path.
 */
class NoDomPropfindRegressionTest {
    private val moduleDir: File =
        File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    private val propfindPath =
        listOf(
            "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/data/WebDavXmlParser.kt",
            "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/data/AlbumsRepository.kt",
        )

    private val forbidden =
        listOf("DocumentBuilderFactory", "DocumentBuilder", "SAXParserFactory", "org.w3c.dom", "XmlPullParserFactory")

    @Test
    fun `the PROPFIND path does not build a DOM`() {
        val offenders =
            propfindPath.flatMap { path ->
                val file = File(moduleDir, path)
                assertTrue("Expected source file ${file.path} (system property krhnles.moduleDir)", file.isFile)
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> forbidden.any { it in line } }
                    .map { (index, _) -> "$path:${index + 1}" }
            }

        assertTrue(
            "Stream-parse PROPFIND responses with PropfindEntryReader (android.util.Xml), " +
                "never with a DOM / SAX document parser. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the PROPFIND body is read through the size cap`() {
        val repository = File(moduleDir, propfindPath[1]).readText()
        assertTrue(
            "The PROPFIND body must be read via LimitedInputStream over byteStream()",
            "LimitedInputStream(response.body.byteStream()" in repository,
        )
    }
}
