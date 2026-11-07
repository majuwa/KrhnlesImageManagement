package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the TLS trust rules (design D10): the only custom trust manager / hostname verifier and the
 * only place that builds a server client live in `data/tls/`; nothing trusts every certificate or
 * skips host name verification; the single approved lint suppression stays on `PinAwareTrustManager`.
 *
 * Patterns are matched on comment-stripped source with all whitespace removed: Spotless formats
 * `OkHttpClient.Builder()` as `OkHttpClient\n    .Builder()`, so a literal match would miss real sites.
 */
class NoTrustAllRegressionTest {
    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile
    private val mainDir = File(moduleDir, "src/main")

    @Test
    fun `main sources respect the trust rules`() {
        assertTrue("Expected ${mainDir.path}", mainDir.isDirectory)
        val sources = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = TlsSourceScanner.scan(sources, mainDir)
        assertTrue("Trust rule violations: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the single approved suppression sits on PinAwareTrustManager only`() {
        val suppressing =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { TlsSourceScanner.suppressesCustomTrustManager(it.readText()) }
                .map { it.name }
                .toList()
        assertEquals(listOf("PinAwareTrustManager.kt"), suppressing)
    }

    @Test
    fun `lint configuration never mentions the trust manager checks`() {
        val ids = listOf("TrustAllX509TrustManager", "BadHostnameVerifier", "CustomX509TrustManager")
        val configs =
            listOf("build.gradle.kts", "lint.xml", "lint-baseline.xml").map { File(moduleDir, it) }
        val mentions =
            configs
                .filter { it.isFile }
                .flatMap { file -> ids.filter { it in file.readText() }.map { "${file.name}:$it" } }
        assertTrue("Lint config mentions $mentions", mentions.isEmpty())
        assertFalse(File(moduleDir, "lint-baseline.xml").isFile)
    }

    @Test
    fun `no network security config trusts user certificates or overrides pins`() {
        val offenders =
            File(mainDir, "res/xml")
                .walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .filter { "src=\"user\"" in it.readText() || "overridePins" in it.readText() }
                .map { it.name }
                .toList()
        assertTrue("Unsafe network security config: $offenders", offenders.isEmpty())
        val manifest = File(mainDir, "AndroidManifest.xml").readText()
        assertFalse("overridePins" in manifest)
    }

    @Test
    fun `scanner flags planted violations including multi line ones`() {
        val outside = "app/Other.kt"
        val samples =
            listOf(
                "val c = OkHttpClient\n    .Builder()\n    .build()",
                "val c = OkHttpClient()",
                "b\n    .sslSocketFactory(\n f, tm)",
                "b.hostnameVerifier(v)",
                "class T : X509TrustManager {}",
                "class T : X509ExtendedTrustManager() {}",
                "val v = HostnameVerifier { _, _ -> true }",
                "SSLContext.getInstance(\"TLS\")",
                "val v = org.apache.NoopHostnameVerifier()",
                "SSLSocketFactory.getInsecure()",
                "class TrustAllManager",
                "HttpsURLConnection.setDefaultHostnameVerifier(v)",
                "url.openConnection()",
                "override fun onReceivedSslError() {}",
                "CertificatePinner.Builder()",
                "DefaultHttpDataSource.Factory()",
                "OkHttpNetworkFetcherFactory()",
                "HostnameVerifier {\n _, _ ->\n true\n}",
            )
        samples.forEach { sample ->
            assertTrue("Not flagged: $sample", TlsSourceScanner.violations(sample, outside).isNotEmpty())
        }
    }

    @Test
    fun `scanner allows the factory inside data tls and ignores comments`() {
        val inside = "app/data/tls/ServerHttpClients.kt"
        val other = "app/Other.kt"
        val factory = "OkHttpClient\n.Builder().sslSocketFactory(f, t).hostnameVerifier(v)"
        assertTrue(TlsSourceScanner.violations(factory, inside).isEmpty())
        val comments = "// OkHttpClient.Builder() and TrustAll\n/* HttpsURLConnection */"
        assertTrue(TlsSourceScanner.violations(comments, other).isEmpty())
        val urlWithComment = "val url = \"https://example.org\" // OkHttpClient()"
        assertTrue(TlsSourceScanner.violations(urlWithComment, other).isEmpty())
        val fetcher = "OkHttpNetworkFetcherFactory(callFactory = { holder })"
        assertTrue(TlsSourceScanner.violations(fetcher, other).isEmpty())
    }

    @Test
    fun `suppression detector recognises the annotation variants`() {
        assertTrue(TlsSourceScanner.suppressesCustomTrustManager("@SuppressLint(\"CustomX509TrustManager\")\nclass A"))
        assertTrue(TlsSourceScanner.suppressesCustomTrustManager("@Suppress(\"CustomX509TrustManager\")"))
        assertTrue(TlsSourceScanner.suppressesCustomTrustManager("@SuppressLint(\n  \"CustomX509TrustManager\",\n)"))
        assertFalse(TlsSourceScanner.suppressesCustomTrustManager("class A"))
    }
}

internal object TlsSourceScanner {
    private const val TLS_DIR = "data/tls/"

    /** Forbidden anywhere, also inside `data/tls/`. */
    private val everywhere =
        listOf(
            "ALLOW_ALL",
            "NoopHostnameVerifier",
            "getInsecure",
            "TrustAll",
            "HttpsURLConnection",
            "HttpURLConnection",
            """\.openConnection\(""",
            "onReceivedSslError",
            "CertificatePinner",
            "DefaultHttpDataSource",
            """OkHttpNetworkFetcherFactory\(\)""",
            """HostnameVerifier\{[^}]*->true\}""",
        ).map(::Regex)

    /** Allowed only in `data/tls/`. */
    private val outsideTlsForbidden =
        listOf(
            """OkHttpClient\.Builder\(""",
            """OkHttpClient\(""",
            """\.sslSocketFactory\(""",
            """\.hostnameVerifier\(""",
            """SSLContext\.getInstance""",
            "X509ExtendedTrustManager",
            "X509TrustManager",
            "HostnameVerifier",
        ).map(::Regex)

    private val suppression = Regex("""@(?:\w+:)?(?:[\w.]+\.)?Suppress(?:Lint|Warnings)?\(([^)]*)\)""")

    fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?m)(?<!:)//.*$"""), "")

    fun normalize(source: String): String = stripComments(source).replace(Regex("""\s+"""), "")

    fun violations(
        source: String,
        relativePath: String,
    ): List<String> {
        val text = normalize(source)
        val inTls = TLS_DIR in relativePath.replace('\\', '/')
        val patterns = if (inTls) everywhere else everywhere + outsideTlsForbidden
        return patterns.filter { it.containsMatchIn(text) }.map { it.pattern }
    }

    fun scan(
        files: List<File>,
        root: File,
    ): List<String> =
        files.flatMap { file ->
            violations(file.readText(), file.relativeTo(root).path).map { "${file.name}: $it" }
        }

    fun suppressesCustomTrustManager(source: String): Boolean =
        suppression.findAll(stripComments(source)).any { "\"CustomX509TrustManager\"" in it.groupValues[1] }
}
