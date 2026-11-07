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
    fun `no source suppresses a trust check other than the approved one`() {
        val offenders =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    TlsSourceScanner
                        .forbiddenSuppressions(file.readText(), file.name)
                        .map { "${file.name}: $it" }
                }.toList()
        assertTrue("Forbidden suppressions: $offenders", offenders.isEmpty())
    }

    @Test
    fun `lint configuration never mentions the trust manager checks`() {
        val ids = listOf("TrustAllX509TrustManager", "BadHostnameVerifier", "CustomX509TrustManager")
        val rootDir = moduleDir.parentFile
        val names =
            listOf("build.gradle.kts", "lint.xml", "lint-baseline.xml", "gradle.properties", "settings.gradle.kts")
        val configs = listOf(moduleDir, rootDir).flatMap { dir -> names.map { File(dir, it) } }
        val mentions =
            configs
                .filter { it.isFile }
                .flatMap { file -> ids.filter { it in file.readText() }.map { "${file.path}:$it" } }
        assertTrue("Lint config mentions $mentions", mentions.isEmpty())
        assertFalse(File(moduleDir, "lint-baseline.xml").isFile)
        assertFalse(File(rootDir, "lint-baseline.xml").isFile)
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
                "DefaultDataSource.Factory(context)",
                "val f = DefaultMediaSourceFactory(context)",
                "val c = SimpleCache(dir, evictor, db)",
                "CacheDataSource.Factory().setCache(c)",
                "ExoPlayer.Builder(context).build()",
                "ExoPlayer\n    .Builder(context.applicationContext)\n    .build()",
                "OkHttpNetworkFetcherFactory()",
                "HostnameVerifier {\n _, _ ->\n true\n}",
            )
        samples.forEach { sample ->
            assertTrue("Not flagged: $sample", TlsSourceScanner.violations(sample, outside).isNotEmpty())
        }
    }

    @Test
    fun `scanner allows the two argument player builder over the OkHttp data source only in the player factory`() {
        val factory = "app/ui/viewer/Media3VideoPlayerFactory.kt"
        val allowed =
            "ExoPlayer\n    .Builder(application, mediaSourceFactory)\n    .build()\n" +
                "ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(callFactory), extractors)"
        assertTrue(TlsSourceScanner.violations(allowed, factory).isEmpty())
        val elsewhere = TlsSourceScanner.violations(allowed, "app/Other.kt")
        assertEquals(3, elsewhere.size)
        listOf(
            "ExoPlayer\n    .Builder(application, mediaSourceFactory)",
            "ProgressiveMediaSource.Factory(ds, extractors)",
            "OkHttpDataSource.Factory(callFactory)",
        ).forEach { sample ->
            assertTrue("Not flagged: $sample", TlsSourceScanner.violations(sample, "app/Other.kt").isNotEmpty())
        }
    }

    @Test
    fun `scanner flags other playback stacks and unrestricted player builders even in the player factory`() {
        val factory = "app/ui/viewer/Media3VideoPlayerFactory.kt"
        listOf(
            "val e = DefaultExtractorsFactory()",
            "ExoPlayer.Builder(application, DefaultRenderersFactory(application))",
            "ExoPlayer.Builder(application, renderersFactory)",
            "ExoPlayer.Builder(application, renderers, mediaSourceFactory)\n val r = DefaultRenderersFactory(c)",
            "VideoView(context)",
            "view.setVideoURI(uri)",
            "MediaPlayer().setDataSource(url)",
            "val p = MediaPlayer.create(context, uri)",
            "MediaMetadataRetriever().setDataSource(url, headers)",
        ).forEach { sample ->
            assertTrue("Not flagged: $sample", TlsSourceScanner.violations(sample, factory).isNotEmpty())
        }
        assertTrue(TlsSourceScanner.violations("val p: ExoPlayer; MediaPlayback()", factory).isEmpty())
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
    fun `scanner flags import aliases and type aliases of the client`() {
        val outside = "app/Other.kt"
        listOf(
            "import okhttp3.OkHttpClient as Http\nval c = Http()",
            "import okhttp3.OkHttpClient.Builder\nval b = Builder()",
            "import okhttp3.*\nval c = OkHttpClient.Builder()",
            "typealias Http = okhttp3.OkHttpClient",
            "typealias Http = OkHttpClient",
        ).forEach { sample ->
            assertTrue("Not flagged: $sample", TlsSourceScanner.violations(sample, outside).isNotEmpty())
        }
        assertTrue(TlsSourceScanner.violations("import okhttp3.OkHttpClient\nval c: OkHttpClient", outside).isEmpty())
    }

    @Test
    fun `forbidden suppressions are flagged anywhere but the approved class`() {
        fun flagged(
            source: String,
            file: String = "Other.kt",
        ) = TlsSourceScanner.forbiddenSuppressions(source, file).isNotEmpty()
        assertTrue(flagged("@SuppressLint(\"TrustAllX509TrustManager\")\nclass A"))
        assertTrue(flagged("@Suppress(\"BadHostnameVerifier\")\nclass A"))
        assertTrue(flagged("@SuppressLint(\"CustomX509TrustManager\")\nclass A"))
        assertTrue(flagged("@SuppressLint(\"TrustAllX509TrustManager\")", "PinAwareTrustManager.kt"))
        assertTrue(flagged("@file:Suppress(\"ALL\")"))
        assertTrue(flagged("@Suppress(\"unused\", \"all\")\nclass A"))
        assertTrue(flagged("@SuppressWarnings(value = [\"BadHostnameVerifier\"])"))
        assertFalse(flagged("@SuppressLint(\"CustomX509TrustManager\")\nclass A", "PinAwareTrustManager.kt"))
        assertFalse(flagged("@Suppress(\"unused\")\nclass A"))
        assertFalse(flagged("class A"))
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
            // Media3 playback: only ExoPlayer.Builder(application, ProgressiveMediaSource.Factory(...)) over the
            // OkHttp data source. These route a MediaItem through DefaultHttpDataSource (system trust only, no
            // credentials, platform redirects), keep every extractor or leave copies of the video on disk.
            "DefaultDataSource",
            "DefaultMediaSourceFactory",
            "SimpleCache",
            "CacheDataSource",
            """ExoPlayer\.Builder\((?:[^,()]|\([^()]*\))*\)""",
            // A two-argument builder is only the restricted one if its second argument is the media source factory
            // (a RenderersFactory there leaves the default media source factory, i.e. DefaultHttpDataSource).
            """ExoPlayer\.Builder\((?![^)]*[mM]ediaSourceFactory)""",
            "RenderersFactory",
            // Every extractor, or other playback stacks that open the URL themselves without the app's client.
            "DefaultExtractorsFactory",
            "VideoView",
            "setVideoURI",
            """setDataSource\(""",
            """(?<![A-Za-z])MediaPlayer(?![A-Za-z])""",
            """OkHttpNetworkFetcherFactory\(\)""",
            """HostnameVerifier\{[^}]*->true\}""",
        ).map(::Regex)

    /** Allowed only in [PLAYER_FACTORY_FILE]: the one place that wires the player to the app's OkHttp call factory. */
    private val playerFactoryOnly =
        listOf(
            """ExoPlayer\.Builder\(""",
            """ProgressiveMediaSource\.Factory\(""",
            """OkHttpDataSource\.Factory\(""",
        ).map(::Regex)

    private const val PLAYER_FACTORY_FILE = "Media3VideoPlayerFactory.kt"

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
            // Aliases would hide the other patterns.
            """importokhttp3\.OkHttpClient(?:as|\.Builder)""",
            """importokhttp3\.\*""",
            """typealias\w+=(?:okhttp3\.)?OkHttpClient""",
        ).map(::Regex)

    private val forbiddenIds = listOf("TrustAllX509TrustManager", "BadHostnameVerifier", "CustomX509TrustManager")

    private const val APPROVED_FILE = "PinAwareTrustManager.kt"

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
        val inPlayerFactory = relativePath.replace('\\', '/').substringAfterLast('/') == PLAYER_FACTORY_FILE
        val patterns =
            (if (inTls) everywhere else everywhere + outsideTlsForbidden) +
                (if (inPlayerFactory) emptyList() else playerFactoryOnly)
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

    /**
     * Suppressions that hide a trust check: the three trust lint ids anywhere except the single
     * `CustomX509TrustManager` on [APPROVED_FILE], and an `all` suppression anywhere.
     */
    fun forbiddenSuppressions(
        source: String,
        fileName: String,
    ): List<String> =
        suppression
            .findAll(stripComments(source))
            .map { it.groupValues[1] }
            .flatMap { args ->
                val ids = forbiddenIds.filter { "\"$it\"" in args }
                val allowed = fileName == APPROVED_FILE && ids == listOf("CustomX509TrustManager")
                val all = Regex("\"all\"", RegexOption.IGNORE_CASE).containsMatchIn(args)
                (if (allowed) emptyList() else ids) + listOfNotNull("all".takeIf { all })
            }.toList()
}
