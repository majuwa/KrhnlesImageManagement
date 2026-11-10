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
    fun `manifest application element references the locales config`() {
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
        assertTrue("Missing ${manifest.path}", manifest.isFile)
        assertEquals("@xml/locales_config", localeConfigAttribute(manifest.readText()))
    }

    @Test
    fun `attribute outside the application element is not accepted`() {
        val misplaced =
            """<manifest xmlns:android="$NS" android:localeConfig="@xml/locales_config"><application/></manifest>"""
        assertEquals("", localeConfigAttribute(misplaced))
        val inComment =
            """<manifest xmlns:android="$NS"><!-- android:localeConfig="@xml/locales_config" -->""" +
                "<application/></manifest>"
        assertEquals("", localeConfigAttribute(inComment))
        val wrongValue =
            """<manifest xmlns:android="$NS"><application android:localeConfig="@xml/other"/></manifest>"""
        assertEquals("@xml/other", localeConfigAttribute(wrongValue))
    }

    private fun localeConfigAttribute(manifestXml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifestXml.byteInputStream())
        val applications = doc.documentElement.getElementsByTagName("application")
        assertEquals("exactly one <application> element", 1, applications.length)
        return (applications.item(0) as org.w3c.dom.Element).getAttributeNS(NS, "localeConfig")
    }

    @Test
    fun `qualifier conversion handles language and region folders`() {
        assertEquals("de", toLanguageTag("de"))
        assertEquals("de-CH", toLanguageTag("de-rCH"))
    }

    private companion object {
        const val NS = "http://schemas.android.com/apk/res/android"
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
