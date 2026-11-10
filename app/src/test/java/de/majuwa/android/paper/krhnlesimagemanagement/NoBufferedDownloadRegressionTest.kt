package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A photo download streams the body to its destination and never follows a redirect: no
 * `body.bytes()` / `string()` / `readBytes()` (a hostile server could exhaust memory) and no
 * `followRedirects(true)` / `followSslRedirects(true)` (a redirect would carry the request, and
 * `Authorization` on a same-host scheme change, somewhere else). Scans the download path files and
 * the `download` function of `WebDavClient`.
 */
class NoBufferedDownloadRegressionTest {
    private val forbidden =
        listOf(
            Regex("""\.bytes\(\)"""),
            Regex("""\.string\(\)"""),
            Regex("""\breadBytes\(\)"""),
            Regex("""followRedirects\(\s*true"""),
            Regex("""followSslRedirects\(\s*true"""),
        )

    @Test
    fun `the download path buffers nothing and follows no redirect`() {
        val files = DownloadSourceScan.downloadPathFiles()
        assertTrue("Expected download sources, found ${files.map { it.name }}", files.isNotEmpty())
        val offenders =
            files.flatMap { file ->
                violations(DownloadSourceScan.codeLines(file).map { it.second }).map { "${file.name}: $it" }
            } + violations(webDavClientDownloadFunction())

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the scan finds forbidden calls in a fixture built by concatenation`() {
        val fixture =
            listOf(
                "val a = response.body." + "bytes()",
                "val b = response.body." + "string()",
                "val c = stream." + "readBytes()",
                "OkHttpClient.Builder().follow" + "Redirects(true)",
                "OkHttpClient.Builder().follow" + "SslRedirects( true)",
                "val ok = client.followRedirects(false)",
            )

        assertEquals(5, violations(fixture).size)
    }

    private fun violations(lines: List<String>): List<String> =
        lines.flatMap { line ->
            forbidden.filter { it.containsMatchIn(line) }.map { "${it.pattern} in `${line.trim()}`" }
        }

    private fun webDavClientDownloadFunction(): List<String> {
        val file = File(DownloadSourceScan.moduleDir, "${DownloadSourceScan.BASE}/data/WebDavClient.kt")
        val lines = DownloadSourceScan.codeLines(file).map { it.second }
        val start = lines.indexOfFirst { it.contains("suspend fun download(") }
        assertTrue("WebDavClient.download not found", start >= 0)
        val end = lines.drop(start).indexOfFirst { it.startsWith("        }.onFailure") }
        assertTrue("End of WebDavClient.download not found", end >= 0)
        return lines.subList(start, start + end + 1)
    }
}
