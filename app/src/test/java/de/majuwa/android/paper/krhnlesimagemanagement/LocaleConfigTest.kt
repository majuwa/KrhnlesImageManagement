package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** `res/xml/locales_config.xml` lists exactly the locales the app has string resources for. */
class LocaleConfigTest {
    private val moduleDir: File = StringResourceScanner.moduleDir()

    @Test
    fun `locales config equals the default language plus the values language folders`() {
        val config = File(moduleDir, "src/main/res/xml/locales_config.xml")
        assertTrue("Missing ${config.path}: add it with one <locale> per supported language", config.isFile)
        val declared = locales(config)

        val folders =
            File(moduleDir, "src/main/res")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values-") }
                .filter { dir -> StringResourceScanner.parse(dir).allKeys.isNotEmpty() }
                .map { it.name.removePrefix("values-") }
                .map { qualifier -> toLanguageTag(qualifier) }
        val expected = (listOf("en") + folders).toSortedSet()

        assertEquals("locales_config.xml must list en plus every values-<lang> folder with strings", expected, declared)
    }

    @Test
    fun `manifest references the locales config`() {
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
        assertTrue("Missing ${manifest.path}", manifest.isFile)
        assertTrue(
            "<application> must set android:localeConfig=\"@xml/locales_config\"",
            manifest.readText().contains("android:localeConfig=\"@xml/locales_config\""),
        )
    }

    @Test
    fun `qualifier conversion handles language and region folders`() {
        assertEquals("de", toLanguageTag("de"))
        assertEquals("de-CH", toLanguageTag("de-rCH"))
    }

    private fun toLanguageTag(qualifier: String): String = qualifier.replace("-r", "-")

    private fun locales(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("locale")
        return (0 until nodes.length)
            .map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
            .toSortedSet()
    }
}
